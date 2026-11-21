package com.belugachat.app.ui

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.belugachat.app.ui.chat.ChatScreen
import com.belugachat.app.ui.chat.ChatViewModel
import com.belugachat.app.ui.components.UpdateDialog
import com.belugachat.app.ui.settings.AddProviderScreen
import com.belugachat.app.ui.settings.AnalyticsScreen
import com.belugachat.app.ui.settings.ManageChatsScreen
import com.belugachat.app.ui.settings.ManageViewModel
import com.belugachat.app.ui.settings.MarketplaceScreen
import com.belugachat.app.ui.settings.McpScreen
import com.belugachat.app.ui.settings.PromptScreen
import com.belugachat.app.ui.settings.ProviderScreen
import com.belugachat.app.ui.settings.ProvidersScreen
import com.belugachat.app.ui.settings.SettingsHome
import com.belugachat.app.ui.settings.SkillDetailScreen
import com.belugachat.app.ui.settings.SkillsScreen
import com.belugachat.app.ui.settings.ToolsScreen

@Composable
fun AppNav(chat: ChatViewModel, manage: ManageViewModel) {
    val nav = rememberNavController()
    val back: () -> Unit = { nav.popBackStack() }
    val updateState by manage.updateState.collectAsState()

    NavHost(
        navController = nav,
        startDestination = "chat",
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        enterTransition = { slideInHorizontally { it / 6 } + fadeIn() },
        exitTransition = { fadeOut() },
        popEnterTransition = { fadeIn() },
        popExitTransition = { slideOutHorizontally { it / 6 } + fadeOut() },
    ) {
        composable("chat") {
            ChatScreen(
                chat,
                onOpenSettings = { nav.navigate("settings") },
                onOpenProviders = { nav.navigate("providers") },
                onConnectDeepSeek = { nav.navigate("provider/new?preset=deepseek") },
                onAddProvider = { nav.navigate("providers/add") },
                onOpenMarketplace = { nav.navigate("marketplace") },
            )
        }
        composable("settings") { SettingsHome(manage, back) { nav.navigate(it) } }
        composable("providers") {
            ProvidersScreen(manage, back, onOpen = { nav.navigate("provider/$it") }, onAdd = { nav.navigate("providers/add") })
        }
        composable("providers/add") { AddProviderScreen(back) { nav.navigate("provider/new?preset=$it") } }
        composable("provider/new?preset={preset}") { entry ->
            val preset = entry.arguments?.getString("preset") ?: "custom"
            ProviderScreen(manage, null, preset, back) { id ->
                val popTarget = if (runCatching { nav.getBackStackEntry("providers") }.isSuccess) "providers" else "chat"
                nav.navigate("provider/$id") { popUpTo(popTarget) { inclusive = false } }
            }
        }
        composable("provider/{id}") { entry ->
            val id = entry.arguments?.getString("id")!!
            ProviderScreen(manage, id, "custom", back) { }
        }
        composable("prompt") { PromptScreen(manage, back) }
        composable("tools") { ToolsScreen(manage, back) }
        composable("mcp") { McpScreen(manage, back) { nav.navigate("marketplace") } }
        composable("skills") { SkillsScreen(manage, back, onOpen = { nav.navigate("skill/$it") }) { nav.navigate("marketplace") } }
        composable("skill/{id}") { entry -> SkillDetailScreen(manage, entry.arguments?.getString("id")!!, back) }
        composable("marketplace") { MarketplaceScreen(manage, back) }
        composable("chats") { ManageChatsScreen(manage, back) }
        composable("analytics") { AnalyticsScreen(manage, back) }
    }

    UpdateDialog(
        state = updateState,
        canInstallPackages = manage::canInstallUpdates,
        onDownload = manage::downloadUpdate,
        onCancelDownload = manage::cancelUpdateDownload,
        onRequestInstallPermission = manage::openInstallPermissionSettings,
        onInstall = manage::installUpdate,
        onDismiss = manage::dismissUpdate,
    )
}
