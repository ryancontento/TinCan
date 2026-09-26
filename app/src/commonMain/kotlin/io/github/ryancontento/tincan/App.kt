package io.github.ryancontento.tincan

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.ryancontento.tincan.chat.ChatScreen
import io.github.ryancontento.tincan.data.SettingsRepository
import io.github.ryancontento.tincan.data.TinCanSettings
import io.github.ryancontento.tincan.models.ModelsScreen
import io.github.ryancontento.tincan.settings.SettingsScreen
import io.github.ryancontento.tincan.ui.GuardedUriHandler
import io.github.ryancontento.tincan.ui.LinkConfirmDialog
import io.github.ryancontento.tincan.ui.TinCanTheme
import io.github.ryancontento.tincan.ui.linkHost
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

@Serializable
object ChatRoute

@Serializable
object SettingsRoute

@Serializable
object ModelsRoute

/** The shared UI root. Koin must already be running: settings are read before a window exists. */
@Composable
fun App() {
    val settingsRepository: SettingsRepository = koinInject()
    val settings by settingsRepository.settings.collectAsState(initial = TinCanSettings())
    val scope = rememberCoroutineScope()

    var pendingLink by remember { mutableStateOf<String?>(null) }
    val trustedHosts by rememberUpdatedState(settings.trustedLinkHosts)
    val platformLinks = LocalUriHandler.current
    val links = remember(platformLinks) {
        GuardedUriHandler(platformLinks, trustedHosts = { trustedHosts }, confirm = { pendingLink = it })
    }

    CompositionLocalProvider(LocalUriHandler provides links) {
        TinCanTheme(settings.theme) {
            val navController = rememberNavController()
            NavHost(navController = navController, startDestination = ChatRoute) {
                composable<ChatRoute> {
                    ChatScreen(
                        onOpenSettings = { navController.navigate(SettingsRoute) },
                        onOpenModels = { navController.navigate(ModelsRoute) },
                    )
                }
                // popBackStack, not navigate: a second Chat on the stack breaks Android's back button.
                composable<ModelsRoute> { ModelsScreen(onBack = { navController.popBackStack() }) }
                composable<SettingsRoute> { SettingsScreen(onBack = { navController.popBackStack() }) }
            }

            pendingLink?.let { url ->
                LinkConfirmDialog(
                    url = url,
                    onOpen = { alwaysTrust ->
                        pendingLink = null
                        if (alwaysTrust) linkHost(url)?.let { scope.launch { settingsRepository.trustLinkHost(it) } }
                        links.open(url)
                    },
                    onDismiss = { pendingLink = null },
                )
            }
        }
    }
}
