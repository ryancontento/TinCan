package io.github.ryancontento.tincan.chat

import io.github.ryancontento.tincan.data.ChatRepository
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.createChatRepository
import io.github.ryancontento.tincan.data.createSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
import kotlin.test.assertNotEquals

/**
 * Starting a second conversation, which used to be undone by a race.
 *
 * Finishing a reply bumps the conversation's updatedAt, so the conversation
 * list re-emits shortly afterwards. The restore-on-launch branch fired on every
 * emission, and any that landed after New conversation found no selection and
 * "restored" the previous thread — so the next message went to the old one.
 */
class NewConversationTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "tincan-new-${UUID.randomUUID()}")
        .also { it.mkdirs() }
    private val chat: ChatRepository = createChatRepository(dir.absolutePath)
    private val settings: SettingsRepository = createSettingsRepository(dir.absolutePath)

    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        Dispatchers.resetMain()
        chat.close()
        dir.deleteRecursively()
    }

    private suspend fun ChatViewModel.await(predicate: (ChatUiState) -> Boolean): ChatUiState =
        withTimeout(TIMEOUT_MILLIS) { state.first(predicate) }

    @Test
    fun a_second_conversation_keeps_its_own_messages() = runBlocking {
        val backend = RecordingBackend()
        settings.setSelectedModel("phi4")
        val vm = ChatViewModel(settings, chat, backend, reconnectPollMillis = POLL_MILLIS)
        vm.await { it.connection == ConnectionState.ONLINE }

        vm.send("first thread")
        val first = vm.await { it.messages.size == 2 && !it.isGenerating }

        vm.newConversation()
        vm.send("second thread")
        // Keyed on the new id, not just the message count: a stale emission from
        // the previous conversation also carries two messages.
        val second = vm.await {
            it.activeConversationId != null &&
                it.activeConversationId != first.activeConversationId &&
                it.messages.size == 2 && !it.isGenerating
        }

        assertNotEquals(first.activeConversationId, second.activeConversationId)
        assertEquals("second thread", second.messages.first().content)
        assertEquals(2, chat.observeMessages(first.activeConversationId!!).first().size)
    }

    /** On launch there is no selection to preserve, so the last thread reopens. */
    @Test
    fun the_last_conversation_still_reopens_on_launch() = runBlocking {
        val backend = RecordingBackend()
        settings.setSelectedModel("phi4")
        val first = ChatViewModel(settings, chat, backend, reconnectPollMillis = POLL_MILLIS)
        first.await { it.connection == ConnectionState.ONLINE }
        first.send("something worth resuming")
        val saved = first.await { it.messages.size == 2 && !it.isGenerating }

        val relaunched = ChatViewModel(settings, chat, backend, reconnectPollMillis = POLL_MILLIS)
        val restored = relaunched.await { it.activeConversationId != null && it.messages.isNotEmpty() }

        assertEquals(saved.activeConversationId, restored.activeConversationId)
    }

    private companion object {
        const val POLL_MILLIS = 50L
        const val TIMEOUT_MILLIS = 10_000L
    }
}
