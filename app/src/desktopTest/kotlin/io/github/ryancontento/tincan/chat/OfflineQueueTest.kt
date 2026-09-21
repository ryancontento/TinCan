package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.createChatRepository
import io.github.ryancontento.tincan.data.createSettingsRepository
import io.github.ryancontento.tincan.data.db.MessageStatus
import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.GenerationStats
import io.github.ryancontento.tincan.llm.LlmBackend
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.stop
import kotlinx.coroutines.Dispatchers
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A backend whose reachability the test controls.
 *
 * This is what extracting LlmBackendProvider bought. The offline → queue →
 * reconnect → deliver cycle is the whole point of this milestone, and against a
 * concrete factory it could only have been exercised by genuinely unplugging a
 * network mid-test.
 */
private class FakeBackend(
    @Volatile var reachable: Boolean = true,
    private val reply: String = "hello there",
) : LlmBackendProvider, LlmBackend {

    override val id = BackendId("fake")

    @Volatile
    var chatCalls = 0
        private set

    override fun create(baseUrl: String, id: BackendId, modelLoadingThresholdMillis: Long): LlmBackend = this

    override suspend fun probe(): BackendHealth =
        if (reachable) BackendHealth.Available(1) else BackendHealth.Unavailable(LlmError.Unreachable)

    override suspend fun listModels(): Result<List<ModelInfo>> =
        if (reachable) Result.success(listOf(ModelInfo("phi4"))) else Result.failure(RuntimeException("down"))

    override fun chat(request: ChatRequest): Flow<ChatEvent> = flow {
        chatCalls++
        if (!reachable) {
            emit(ChatEvent.Failed(LlmError.Unreachable))
            return@flow
        }
        emit(ChatEvent.Token(reply))
        emit(ChatEvent.Completed(GenerationStats(completionTokens = 2, evalDurationNanos = 1_000_000_000)))
    }
}

/**
 * Real dispatchers and a real clock, deliberately.
 *
 * The obvious approach — runTest's virtual clock with advanceUntilIdle — does
 * not work here, and produced five tests that all saw the untouched default
 * state. DataStore and Room perform genuine file IO on their own dispatchers,
 * and advancing the test scheduler does not wait for work it does not own. This
 * is an integration test over real SQLite, so it waits on real conditions with
 * real timeouts rather than pretending otherwise.
 *
 * The reconnect poll is injected at 50ms so the suite stays fast.
 */
class OfflineQueueTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "tincan-vm-${UUID.randomUUID()}")
        .also { it.mkdirs() }
    private val chat: ChatRepository = createChatRepository(dir.absolutePath)
    private val settings: SettingsRepository = createSettingsRepository(dir.absolutePath)
    private val running = mutableListOf<ChatViewModel>()

    // viewModelScope runs on Dispatchers.Main.immediate, which does not exist in
    // a plain JVM test — without a Main dispatcher nothing in init ever runs.
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

    private suspend fun viewModel(backend: FakeBackend): ChatViewModel {
        settings.setSelectedModel("phi4")
        return ChatViewModel(settings, chat, backend, reconnectPollMillis = POLL_MILLIS)
            .also { running += it }
    }

    /** Waits for the view model to reach a state, rather than guessing at timing. */
    private suspend fun ChatViewModel.await(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MILLIS) { state.first(predicate) }

    @Test
    fun a_message_sent_while_the_server_is_down_is_queued_rather_than_lost() = runBlocking {
        val backend = FakeBackend(reachable = false)
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.OFFLINE }

        vm.send("are you awake")
        val state = vm.await { it.activeConversationId != null && it.queuedCount > 0 }

        val queued = chat.oldestPendingMessage(state.activeConversationId!!)
        assertNotNull(queued, "the message must survive as queued")
        assertEquals("are you awake", queued.content)

        // Already known to be down, so no pointless request was attempted.
        assertEquals(0, backend.chatCalls, "should not have tried to send while offline")
    }

    @Test
    fun the_queued_message_goes_out_by_itself_once_the_server_returns() = runBlocking {
        val backend = FakeBackend(reachable = false)
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.OFFLINE }

        vm.send("are you awake")
        val queuedState = vm.await { it.activeConversationId != null && it.queuedCount > 0 }
        val conversationId = queuedState.activeConversationId!!

        // The MacBook wakes up. Nothing prods the view model — the reconnect
        // watch is meant to notice on its own.
        backend.reachable = true
        vm.await { it.connection == ConnectionState.ONLINE && it.queuedCount == 0 }

        assertNull(
            chat.oldestPendingMessage(conversationId),
            "the queue should have drained without the user doing anything",
        )
        val messages = chat.observeMessages(conversationId).first()
        assertEquals(2, messages.size, "the queued question and its answer")
        assertEquals("hello there", messages.last().content)
        assertEquals(MessageStatus.COMPLETE, messages.last().status)
    }

    @Test
    fun a_send_that_fails_mid_flight_requeues_the_question_and_leaves_no_empty_reply() = runBlocking {
        val backend = FakeBackend(reachable = true)
        val vm = viewModel(backend)
        vm.await { it.connection == ConnectionState.ONLINE }

        // Believed up, so the send is attempted — and the server dies during it.
        backend.reachable = false
        vm.send("still there?")
        val state = vm.await { it.connection == ConnectionState.OFFLINE && it.queuedCount > 0 }

        val messages = chat.observeMessages(state.activeConversationId!!).first()
        assertEquals(1, messages.size, "a reply that produced nothing must leave no bubble")
        assertEquals(MessageStatus.PENDING, messages.single().status)
    }

    @Test
    fun the_failure_notice_offers_a_way_forward() = runBlocking {
        val backend = FakeBackend(reachable = false)
        val vm = viewModel(backend)
        val state = vm.await { it.notice != null }

        val notice = assertNotNull(state.notice)
        assertEquals(Notice.Severity.ERROR, notice.severity)
        assertEquals(NoticeAction.RETRY, notice.action)
        assertTrue(notice.text.contains("asleep"), "the message should name the likely cause")
    }

    @Test
    fun coming_back_online_clears_the_error_rather_than_leaving_it_on_screen() = runBlocking {
        val backend = FakeBackend(reachable = false)
        val vm = viewModel(backend)
        vm.await { it.notice != null }

        backend.reachable = true

        // A stale error must not outlive the condition that caused it.
        vm.await { it.connection == ConnectionState.ONLINE && it.notice == null }
        Unit
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
