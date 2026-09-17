package io.github.ryancontento.tincan

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ryancontento.tincan.chat.ChatScreen
import io.github.ryancontento.tincan.settings.SettingsScreen
import io.github.ryancontento.tincan.ui.TinCanTheme
import kotlinx.serialization.Serializable

@Serializable
object ChatRoute

@Serializable
object SettingsRoute

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
    TinCanTheme {
        val navController = rememberNavController()
        NavHost(navController = navController, startDestination = ChatRoute) {
            composable<ChatRoute> {
                ChatScreen(onOpenSettings = { navController.navigate(SettingsRoute) })
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
