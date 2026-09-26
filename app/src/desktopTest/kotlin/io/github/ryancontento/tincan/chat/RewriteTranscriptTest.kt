package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.attach.PickedFile
import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.createChatRepository
import io.github.ryancontento.tincan.data.createSettingsRepository
import io.github.ryancontento.tincan.data.db.MessageRole
import io.github.ryancontento.tincan.export.ExportDocument
import io.github.ryancontento.tincan.export.ExportFormat
import io.github.ryancontento.tincan.export.FileSaver
import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmBackend
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.Role
import io.github.ryancontento.tincan.stop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3)

/**
 * Records what each request actually carried, which is the only way to tell a
 * regenerate from a continuation or to see which system prompt was sent.
 */
internal class RecordingBackend : LlmBackendProvider, LlmBackend {
    override val id = BackendId("fake")

    @Volatile
    var reachable = true

    @Volatile
    var replies = 0
        private set

    val requests = mutableListOf<ChatRequest>()

    override fun create(baseUrl: String, id: BackendId, modelLoadingThresholdMillis: Long): LlmBackend = this

    override suspend fun probe(): BackendHealth =
        if (reachable) BackendHealth.Available(1) else BackendHealth.Unavailable(LlmError.Unreachable)

    @Volatile
    var models = listOf(ModelInfo("phi4"), ModelInfo("qwen3:8b"))

    override suspend fun listModels(): Result<List<ModelInfo>> = Result.success(models)

    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        synchronized(requests) { requests += request }
        if (!reachable) {
            emit(ChatEvent.Failed(LlmError.Unreachable))
            return@flow
        }
        replies++
        emit(ChatEvent.Token("reply $replies"))
        emit(ChatEvent.Completed(GenerationStats(completionTokens = 2, evalDurationNanos = 1_000_000_000)))
    }

    fun lastRequest(): ChatRequest = synchronized(requests) { requests.last() }
}

/**
 * Real dispatchers and real SQLite, for the same reason OfflineQueueTest uses
 * them: Room and DataStore do genuine file IO on dispatchers a virtual clock
 * does not own, so advancing a scheduler proves nothing.
 */
class RewriteTranscriptTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "tincan-rewrite-${UUID.randomUUID()}")
        .also { it.mkdirs() }
    private val chat: ChatRepository = createChatRepository(dir.absolutePath)
    private val settings: SettingsRepository = createSettingsRepository(dir.absolutePath)
    private val running = mutableListOf<ChatViewModel>()

    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        // Before resetMain, or their collectors reach for a dispatcher that is
        // no longer there and fail whichever test is running by then.
        running.forEach { it.stop() }
        Dispatchers.resetMain()
        chat.close()
        dir.deleteRecursively()
    }

    private suspend fun viewModel(backend: RecordingBackend): ChatViewModel {
        settings.setSelectedModel("phi4")
        return ChatViewModel(settings, chat, backend, reconnectPollMillis = POLL_MILLIS)
            .also { running += it }
    }

    private suspend fun ChatViewModel.await(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MILLIS) { state.first(predicate) }

    @Test
    fun regenerating_replaces_the_reply_instead_of_adding_another() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("first question")
        val state = vm.await { it.messages.size == 2 && !it.isGenerating }
        val conversationId = state.activeConversationId!!

        vm.regenerateLastReply()
        vm.await { !it.isGenerating && it.messages.lastOrNull()?.content == "reply 2" }

        val messages = chat.observeMessages(conversationId).first()
        assertEquals(2, messages.size, "the question and exactly one reply")
        assertEquals("reply 2", messages.last().content)

        // The discarded answer must not be in the history the model was shown,
        // or this would be a continuation rather than a retry.
        val sent = backend.lastRequest().messages
        assertEquals(1, sent.size)
        assertEquals(Role.USER, sent.single().role)
    }

    /**
     * A retry that produces nothing must not also destroy the answer it was
     * retrying. The old reply has to be deleted before the request so the model
     * is not shown it, which puts it at risk for the length of the call.
     */
    @Test
    fun a_regenerate_that_fails_leaves_the_previous_reply_in_place() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("a question")
        val state = vm.await { it.messages.size == 2 && !it.isGenerating }
        val conversationId = state.activeConversationId!!

        backend.reachable = false
        vm.regenerateLastReply()
        // Keyed on the request actually being attempted: waiting on the state
        // alone matches the starting state and proves nothing.
        withTimeout(TIMEOUT_MILLIS) {
            while (backend.requests.size < 2) delay(20)
        }
        // Not just !isGenerating: the reply is put back in a finally block that
        // runs after generation has already reported itself finished, so the
        // wait has to be for the restored row, not for the flag.
        vm.await { !it.isGenerating && it.messages.size == 2 }

        val messages = chat.observeMessages(conversationId).first()
        assertEquals(2, messages.size, "the question and its original answer")
        assertEquals("reply 1", messages.last().content, "the answer must survive a failed retry")
        assertEquals(MessageRole.ASSISTANT, messages.last().role)
    }

    @Test
    fun editing_a_question_discards_the_answers_that_followed_it() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("frist question")
        val afterFirst = vm.await { it.messages.size == 2 && !it.isGenerating }
        val conversationId = afterFirst.activeConversationId!!
        val questionId = afterFirst.messages.first().id

        vm.editAndResend(questionId, "first question")
        vm.await { !it.isGenerating && it.messages.size == 2 && it.messages.first().content == "first question" }

        val messages = chat.observeMessages(conversationId).first()
        assertEquals(2, messages.size, "the corrected question and one new reply")
        assertEquals("first question", messages.first().content)
        assertEquals(MessageRole.USER, messages.first().role)
        assertEquals("reply 2", messages.last().content)

        val sent = backend.lastRequest().messages
        assertEquals(1, sent.size, "the replaced turns must not be sent")
        assertEquals("first question", sent.single().content)
    }

    /**
     * The bug this whole change exists for: generation used to read the live
     * setting, so changing the default rewrote conversations already under way.
     */
    @Test
    fun changing_the_default_prompt_does_not_reach_into_an_existing_conversation() = runBlocking {
        val backend = RecordingBackend()
        settings.setSystemPrompt("You are terse.")
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("first question")
        vm.await { it.messages.size == 2 && !it.isGenerating }
        assertEquals("You are terse.", backend.lastRequest().systemPrompt)

        settings.setSystemPrompt("You are a pirate.")
        vm.await { it.settings.systemPrompt == "You are a pirate." }

        vm.send("second question")
        vm.await { it.messages.size == 4 && !it.isGenerating }

        assertEquals(
            "You are terse.",
            backend.lastRequest().systemPrompt,
            "the conversation must keep the prompt it was created with",
        )
    }

    @Test
    fun a_per_conversation_prompt_overrides_the_default() = runBlocking {
        val backend = RecordingBackend()
        settings.setSystemPrompt("You are terse.")
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("first question")
        val state = vm.await { it.messages.size == 2 && !it.isGenerating }

        // Joined, not fired and forgotten: the prompt is written by a coroutine,
        // and a send that overtakes it carries the old prompt.
        vm.setConversationSystemPrompt(state.activeConversationId!!, "Answer only in haiku.").join()
        vm.send("second question")
        vm.await { it.messages.size == 4 && !it.isGenerating }

        assertEquals("Answer only in haiku.", backend.lastRequest().systemPrompt)
    }

    @Test
    fun a_renamed_conversation_keeps_the_name_it_was_given() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("a question that would become the title")
        val state = vm.await { it.messages.size == 2 && !it.isGenerating }

        vm.renameConversation(state.activeConversationId!!, "Bandwidth notes")
        val renamed = vm.await { it.activeConversation?.title == "Bandwidth notes" }

        assertEquals("Bandwidth notes", renamed.activeConversation?.title)
    }

    @Test
    fun opening_a_search_hit_switches_conversation_and_points_at_the_message() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("memory bandwidth question")
        val first = vm.await { it.messages.size == 2 && !it.isGenerating }
        vm.newConversation()
        vm.send("something else entirely")
        // Keyed on the new id: a stale emission from the previous conversation
        // also carries two messages.
        val second = vm.await {
            it.activeConversationId != null &&
                it.activeConversationId != first.activeConversationId &&
                it.messages.size == 2 && !it.isGenerating
        }

        vm.search("bandwidth")
        val searched = vm.await { it.search.hits.isNotEmpty() }
        val hit = searched.search.hits.single()
        assertTrue(hit.conversationId != second.activeConversationId)

        vm.openSearchHit(hit)
        val opened = vm.await { it.activeConversationId == hit.conversationId }
        assertEquals(hit.messageId, opened.scrollToMessageId)
    }

    @Test
    fun a_conversations_own_temperature_and_context_reach_the_request() = runBlocking {
        val backend = RecordingBackend()
        settings.setTemperature(0.9f)
        settings.setNumCtx(4096)
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("first question")
        val state = vm.await { it.messages.size == 2 && !it.isGenerating }
        assertEquals(0.9f, backend.lastRequest().options.temperature)

        vm.setConversationOptions(state.activeConversationId!!, temperature = "0.2", numCtx = "").join()
        vm.send("second question")
        vm.await { it.messages.size == 4 && !it.isGenerating }

        assertEquals(0.2f, backend.lastRequest().options.temperature)
        assertEquals(4096, backend.lastRequest().options.numCtx, "blank follows the global setting")
    }

    @Test
    fun pinning_moves_a_conversation_to_the_top_without_touching_its_date() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("older")
        val first = vm.await { it.messages.size == 2 && !it.isGenerating }
        vm.newConversation()
        vm.send("newer")
        vm.await { it.conversations.size == 2 && !it.isGenerating }

        val olderId = first.activeConversationId!!
        val before = chat.conversation(olderId)!!.updatedAt
        vm.setPinned(olderId, true).join()
        val pinned = vm.await { it.conversations.firstOrNull()?.id == olderId }

        assertTrue(pinned.conversations.first().pinned)
        assertEquals(before, chat.conversation(olderId)!!.updatedAt)
    }

    @Test
    fun an_attached_image_travels_with_the_question_and_stays_in_the_history() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        assertTrue(vm.attach(listOf(PickedFile("cat.png", PNG))).isEmpty(), "an image is not text to insert")
        assertEquals(1, vm.state.value.draftImages.size)

        assertTrue(vm.send(""), "an image alone is a question")
        vm.await { it.messages.size == 2 && !it.isGenerating }
        assertTrue(vm.state.value.draftImages.isEmpty())
        assertEquals(1, backend.lastRequest().messages.single().images.size)

        // Ollama keeps no state, so the image has to ride along with every later turn.
        vm.send("and what colour is it?")
        vm.await { it.messages.size == 4 && !it.isGenerating }
        assertEquals(listOf(1, 0, 0), backend.lastRequest().messages.map { it.images.size })
    }

    @Test
    fun a_model_that_cannot_see_keeps_the_draft_and_explains() = runBlocking {
        val backend = RecordingBackend().apply { models = listOf(ModelInfo("phi4", capabilities = setOf("completion"))) }
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE && it.availableModels.isNotEmpty() }

        vm.attach(listOf(PickedFile("cat.png", PNG)))
        assertEquals(false, vm.send("what is this?"))

        assertEquals(1, vm.state.value.draftImages.size, "nothing is thrown away")
        assertTrue(vm.state.value.notice!!.text.contains("can't read images"))
        assertTrue(backend.requests.isEmpty())
    }

    @Test
    fun editing_a_question_keeps_its_image() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.attach(listOf(PickedFile("cat.png", PNG)))
        vm.send("wat is this")
        val sent = vm.await { it.messages.size == 2 && !it.isGenerating && it.attachments.isNotEmpty() }

        vm.editAndResend(sent.messages.first().id, "what is this")
        vm.await { !it.isGenerating && it.messages.firstOrNull()?.content == "what is this" && it.messages.size == 2 }

        assertEquals(1, backend.lastRequest().messages.single().images.size)
    }

    @Test
    fun a_text_file_comes_back_as_a_block_for_the_composer() = runBlocking {
        val vm = viewModel(RecordingBackend())
        val blocks = vm.attach(listOf(PickedFile("notes.txt", "remember the milk".encodeToByteArray())))

        assertEquals(listOf("`notes.txt`\n```\nremember the milk\n```"), blocks)
        assertTrue(vm.state.value.draftImages.isEmpty())
    }

    @Test
    fun an_export_marks_images_in_markdown_and_carries_them_in_json() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.attach(listOf(PickedFile("cat.png", PNG)))
        vm.send("what is this?")
        vm.await { it.messages.size == 2 && !it.isGenerating }

        assertTrue(vm.buildExport(ExportFormat.MARKDOWN)!!.content.contains("_(1 image attached)_"))
        assertTrue(vm.buildExport(ExportFormat.JSON)!!.content.contains("\"mimeType\": \"image/png\""))
    }

    @Test
    fun switching_to_a_saved_server_changes_where_requests_go() = runBlocking {
        val backend = RecordingBackend()
        settings.saveServer("MacBook", "http://macbook:11434")
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.selectServer("http://macbook:11434")
        val switched = vm.await { it.settings.serverUrl == "http://macbook:11434" }

        assertEquals("MacBook", switched.settings.activeServerName)
    }

    @Test
    fun an_export_carries_the_whole_conversation() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("why does bandwidth matter")
        vm.await { it.messages.size == 2 && !it.isGenerating }

        val document = assertNotNull(vm.buildExport(ExportFormat.MARKDOWN))
        assertTrue(document.content.contains("why does bandwidth matter"))
        assertTrue(document.content.contains("reply 1"))
        assertTrue(document.fileName.endsWith(".md"))
    }

    @Test
    fun an_export_that_cannot_be_written_reports_an_error_instead_of_crashing() = runBlocking {
        val backend = RecordingBackend()
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("a question")
        vm.await { it.messages.size == 2 && !it.isGenerating }

        val failingSaver = object : FileSaver {
            override suspend fun save(document: ExportDocument): String? =
                throw java.io.IOException("Access is denied")
        }
        vm.export(ExportFormat.MARKDOWN, failingSaver).join()

        val notice = assertNotNull(vm.state.value.notice)
        assertEquals(Notice.Severity.ERROR, notice.severity)
        assertTrue(notice.text.contains("Access is denied"))
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
