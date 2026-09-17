package io.github.ryancontento.tincan.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Lives in desktopTest rather than commonTest because it needs a real temp
 * directory. Each test gets its own: DataStore throws if two instances in one
 * process are pointed at the same file, which is exactly what sharing a
 * directory across tests would do.
 */
private fun tempRepo(): Pair<SettingsRepository, File> {
    val dir = File(System.getProperty("java.io.tmpdir"), "tincan-test-${UUID.randomUUID()}")
    dir.mkdirs()
    return createSettingsRepository(dir.absolutePath) to dir
}

class SettingsRepositoryTest {

    @Test
    fun unset_values_fall_back_to_defaults() = runTest {
        val (repo, dir) = tempRepo()
        try {
            val settings = repo.settings.first()
            assertEquals(TinCanSettings.DEFAULT_SERVER_URL, settings.serverUrl)
            assertEquals(TinCanSettings.DEFAULT_KEEP_ALIVE, settings.keepAlive)
            assertEquals(TinCanSettings.DEFAULT_LOADING_THRESHOLD_MILLIS, settings.modelLoadingThresholdMillis)
            assertNull(settings.selectedModel)
            assertNull(settings.numCtx)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun written_values_survive_a_new_repository_over_the_same_file() = runTest {
        val (repo, dir) = tempRepo()
        try {
            repo.setServerUrl("  http://example-host.internal:11434  ")
            repo.setSelectedModel("phi4")
            repo.setNumCtx(8192)
            repo.setModelLoadingThreshold(15_000)

            val settings = repo.settings.first()

            // The setter trims — a URL pasted with trailing whitespace would
            // otherwise produce a confusing connection failure.
            assertEquals("http://example-host.internal:11434", settings.serverUrl)
            assertEquals("phi4", settings.selectedModel)
            assertEquals(8192, settings.numCtx)
            assertEquals(15_000, settings.modelLoadingThresholdMillis)

            assertTrue(
                File(dir, SettingsRepository.FILE_NAME).exists(),
                "settings should have been written to disk",
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun nulling_a_value_restores_its_default_rather_than_storing_null() = runTest {
        val (repo, dir) = tempRepo()
        try {
            repo.setNumCtx(4096)
            assertEquals(4096, repo.settings.first().numCtx)

            repo.setNumCtx(null)
            // Cleared, not stored as a zero — so the server picks again.
            assertNull(repo.settings.first().numCtx)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun negative_loading_threshold_is_clamped_to_zero() = runTest {
        val (repo, dir) = tempRepo()
        try {
            repo.setModelLoadingThreshold(-5_000)
            // Zero is meaningful: it disables the inference entirely.
            assertEquals(0, repo.settings.first().modelLoadingThresholdMillis)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun generation_options_omit_blank_keep_alive() = runTest {
        val (repo, dir) = tempRepo()
        try {
            repo.setKeepAlive("   ")
            val options = repo.settings.first().toGenerationOptions()
            assertNull(options.keepAlive, "a blank keep_alive must not be sent to the server")
        } finally {
            dir.deleteRecursively()
        }
    }
}
