package io.github.ryancontento.tincan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ryancontento.tincan.chat.ChatScreen
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.models.ModelsScreen
import io.github.ryancontento.tincan.settings.SettingsScreen
import io.github.ryancontento.tincan.ui.TinCanTheme
import io.github.ryancontento.tincan.ui.WebOnlyUriHandler
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object ChatRoute

@Serializable
object SettingsRoute

@Serializable
object ModelsRoute

/**
 * The whole app above the platform line. v2's MainActivity calls exactly this,
 * which is the point of keeping the window and the Activity down to an entry
 * point that does nothing but host it.
 *
 * Koin is expected to have been started already, by main() on desktop and by
 * the Application class on Android. It is not started here because settings
 * must be readable before a window exists, and a second graph would mean a
 * second DataStore over the same file — which DataStore rejects.
 */
@Composable
fun App() {
    // The theme is read here rather than through a view model: it wraps the
    // navigation host, so it has to resolve before any screen composes.
    val settingsRepository: SettingsRepository = koinInject()
    val settings by settingsRepository.settings.collectAsState(initial = TinCanSettings())

    val uriHandler = LocalUriHandler.current
    val webOnly = remember(uriHandler) { WebOnlyUriHandler(uriHandler) }

    CompositionLocalProvider(LocalUriHandler provides webOnly) {
        TinCanTheme(settings.theme) {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = ChatRoute) {
                composable<ChatRoute> {
                    ChatScreen(
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenModels = { navController.navigate(ModelsRoute) },
                    )
                }
                composable<ModelsRoute> {
                    ModelsScreen(onBack = { navController.popBackStack() })
                }
                composable<SettingsRoute> {
                    // popBackStack rather than navigate() so returning to chat does
                    // not stack a second copy of it on the back stack — which is
                    // what makes Android's back button behave in v2.
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}
