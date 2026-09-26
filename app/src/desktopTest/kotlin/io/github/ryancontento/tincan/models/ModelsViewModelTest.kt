package io.github.ryancontento.tincan.models

import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.createSettingsRepository
import io.github.ryancontento.tincan.llm.BackendHealth
import io.github.ryancontento.tincan.llm.BackendId
import io.github.ryancontento.tincan.llm.ChatEvent
import io.github.ryancontento.tincan.llm.ChatRequest
import io.github.ryancontento.tincan.llm.LlmBackend
import io.github.ryancontento.tincan.llm.LlmBackendProvider
import io.github.ryancontento.tincan.llm.LlmError
import io.github.ryancontento.tincan.llm.LoadedModel
import io.github.ryancontento.tincan.llm.ModelInfo
import io.github.ryancontento.tincan.llm.PullEvent
import io.github.ryancontento.tincan.llm.ollama.OllamaException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** A server whose model list changes as the view model acts on it. */
private class FakeModelServer : LlmBackendProvider, LlmBackend {
    override val id = BackendId("fake")

    val installed = mutableListOf(ModelInfo("llama3.2:1b"), ModelInfo("qwen3:8b"))
    val loaded = mutableListOf(LoadedModel("llama3.2:1b", 1_500_000_000, 1_500_000_000, null, 4096))
    var pullScript: List<PullEvent> = listOf(PullEvent.Done)

    override fun create(baseUrl: String, id: BackendId, modelLoadingThresholdMillis: Long): LlmBackend = this
    override suspend fun probe(): BackendHealth = BackendHealth.Available(1)
    override fun chat(request: ChatRequest): Flow<ChatEvent> = emptyFlow()

    override suspend fun listModels() = Result.success(synchronized(installed) { installed.toList() })
    override suspend fun loadedModels() = Result.success(synchronized(loaded) { loaded.toList() })

    override suspend fun unloadModel(model: String): Result<Unit> {
        synchronized(loaded) { loaded.removeAll { it.id == model } }
        return Result.success(Unit)
    }

    override suspend fun deleteModel(model: String): Result<Unit> {
        val removed = synchronized(installed) { installed.removeAll { it.id == model } }
        return if (removed) Result.success(Unit) else Result.failure(OllamaException(LlmError.Rejected("model '$model' not found")))
    }

    override fun pullModel(model: String): Flow<PullEvent> = flow {
        pullScript.forEach { event ->
            if (event == PullEvent.Done) synchronized(installed) { installed += ModelInfo(model) }
            emit(event)
        }
    }
}

class ModelsViewModelTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "tincan-models-${UUID.randomUUID()}").also { it.mkdirs() }
    private val settings: SettingsRepository = createSettingsRepository(dir.absolutePath)
    private val server = FakeModelServer()
    private val running = mutableListOf<ModelsViewModel>()

    @BeforeTest
    fun installMainDispatcher() = Dispatchers.setMain(Dispatchers.Default)

    @AfterTest
    fun cleanUp() {
        running.clear()
        Dispatchers.resetMain()
        runBlocking { settings.close() }
        dir.deleteRecursively()
    }

    private fun viewModel() = ModelsViewModel(settings, server).also { running += it }

    private suspend fun ModelsViewModel.await(predicate: (ModelsUiState) -> Boolean): ModelsUiState =
        withTimeout(10_000) { state.first(predicate) }

    @Test
    fun opening_lists_installed_and_loaded_models() = runBlocking {
        val state = viewModel().await { it.installed.size == 2 && it.loaded.size == 1 }

        assertEquals("llama3.2:1b", state.loaded.single().id)
        assertTrue(state.loadedFor("llama3.2:1b") != null)
        assertNull(state.loadedFor("qwen3:8b"))
    }

    @Test
    fun a_finished_pull_adds_the_model_to_the_list() = runBlocking {
        server.pullScript = listOf(
            PullEvent.Progress("pulling manifest", null, null),
            PullEvent.Progress("pulling 74701a8c35f6", 500, 1000),
            PullEvent.Done,
        )
        val vm = viewModel()
        vm.await { it.installed.size == 2 }

        vm.pull("  phi4  ")
        val done = vm.await { it.pull?.running == false && it.installed.any { m -> m.id == "phi4" } }

        assertEquals("phi4", done.pull?.model)
        assertNull(done.pull?.error)
    }

    @Test
    fun a_failed_pull_shows_the_servers_reason() = runBlocking {
        server.pullScript = listOf(PullEvent.Failed(LlmError.Rejected("pull model manifest: file does not exist")))
        val vm = viewModel()
        vm.await { it.installed.size == 2 }

        vm.pull("no-such-model")
        val failed = vm.await { it.pull?.running == false }

        assertTrue(failed.pull?.error!!.contains("file does not exist"))
    }

    @Test
    fun deleting_waits_for_confirmation() = runBlocking {
        val vm = viewModel()
        vm.await { it.installed.size == 2 }

        vm.requestDelete("qwen3:8b")
        assertEquals("qwen3:8b", vm.state.value.confirmDelete)
        assertEquals(2, server.installed.size, "nothing deleted before confirming")

        vm.confirmDelete().join()
        val after = vm.await { it.installed.size == 1 }
        assertNull(after.confirmDelete)
    }

    @Test
    fun unloading_takes_it_out_of_memory() = runBlocking {
        val vm = viewModel()
        vm.await { it.loaded.size == 1 }

        vm.unload("llama3.2:1b").join()

        assertTrue(vm.await { it.loaded.isEmpty() }.loaded.isEmpty())
    }
}

class ModelFormattingTest {

    @Test
    fun memory_says_when_a_model_spills_off_the_gpu() {
        assertEquals("1.5 GB on GPU", memoryDescription(LoadedModel("m", 1_500_000_000, 1_500_000_000, null, null)))
        assertEquals("2.0 GB, 50% on GPU", memoryDescription(LoadedModel("m", 2_000_000_000, 1_000_000_000, null, null)))
        assertEquals("2.0 GB on CPU", memoryDescription(LoadedModel("m", 2_000_000_000, 0, null, null)))
    }

    @OptIn(ExperimentalTime::class)
    @Test
    fun expiry_reads_as_time_remaining() {
        val now = Instant.parse("2026-09-26T17:30:00Z")
        // Ollama's own format, with a local offset and seven fractional digits.
        assertEquals("unloads in 6 min", unloadsIn("2026-09-26T13:36:53.4650789-04:00", now))
        assertEquals("stays loaded", unloadsIn("2318-01-01T00:00:00Z", now))
        assertNull(unloadsIn("not a time", now))
    }

    @Test
    fun sizes_read_in_the_largest_whole_unit() {
        assertEquals("1.3 GB", formatBytes(1_321_098_329))
        assertEquals("485 B", formatBytes(485))
        assertEquals("7 KB", formatBytes(7_711))
    }
}
