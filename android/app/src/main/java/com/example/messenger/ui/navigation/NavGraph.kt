package com.example.messenger.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.viewModelFactory as vmFactory
import androidx.lifecycle.viewmodel.initializer
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.navigation.NavType
import com.example.messenger.MessengerApp
import com.example.messenger.ui.auth.AuthScreen
import com.example.messenger.ui.auth.AuthViewModel
import com.example.messenger.ui.chat.ChatScreen
import com.example.messenger.ui.chat.ChatViewModel
import com.example.messenger.ui.chatlist.ChatListScreen
import com.example.messenger.ui.chatlist.ChatListViewModel
import com.example.messenger.ui.settings.SettingsScreen
import com.example.messenger.ui.settings.SettingsViewModel
import com.example.messenger.ui.splash.SplashScreen
import java.net.URLDecoder
import java.net.URLEncoder

// Заголовок dm-чата резолвится сервером в никнейм собеседника (может отличаться от email),
// поэтому Signal-сессии (см. ChatViewModel.peerEmail) нужен настоящий email отдельным аргументом.
private const val NO_PEER = "-"

private object Routes {
    const val SPLASH = "splash"
    const val AUTH = "auth"
    const val CHAT_LIST = "chat_list"
    const val SETTINGS = "settings"
    const val CHAT = "chat/{chatId}/{title}/{chatType}/{peerEmail}"
    fun chat(chatId: String, title: String, chatType: String, peerEmail: String?) =
        "chat/${URLEncoder.encode(chatId, "UTF-8")}/${URLEncoder.encode(title, "UTF-8")}/" +
            "${URLEncoder.encode(chatType, "UTF-8")}/${URLEncoder.encode(peerEmail ?: NO_PEER, "UTF-8")}"
}

@Composable
fun MessengerNavGraph(app: MessengerApp) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = Routes.SPLASH) {

        composable(Routes.SPLASH) {
            LaunchedEffect(Unit) {
                val destination = if (app.sessionManager.currentToken() != null) Routes.CHAT_LIST else Routes.AUTH
                navController.navigate(destination) {
                    popUpTo(Routes.SPLASH) { inclusive = true }
                }
            }
            SplashScreen()
        }

        composable(Routes.AUTH) {
            val vm = viewModel<AuthViewModel>(factory = vmFactory {
                initializer { AuthViewModel(app.api, app.sessionManager, app.signalRepository) }
            })
            AuthScreen(
                viewModel = vm,
                onLoggedIn = {
                    navController.navigate(Routes.CHAT_LIST) {
                        popUpTo(Routes.AUTH) { inclusive = true }
                    }
                }
            )
        }

        composable(Routes.CHAT_LIST) {
            val vm = viewModel<ChatListViewModel>(factory = vmFactory {
                initializer { ChatListViewModel(app.api, app.sessionManager) }
            })
            ChatListScreen(
                viewModel = vm,
                onOpenChat = { chatId, title, chatType, peerEmail ->
                    navController.navigate(Routes.chat(chatId, title, chatType, peerEmail))
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS)
                }
            )
        }

        composable(Routes.SETTINGS) {
            val vm = viewModel<SettingsViewModel>(factory = vmFactory {
                initializer {
                    SettingsViewModel(app.api, app.sessionManager, app.preferencesManager, app.notificationHelper)
                }
            })
            SettingsScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onLoggedOut = {
                    navController.navigate(Routes.AUTH) {
                        popUpTo(Routes.CHAT_LIST) { inclusive = true }
                    }
                }
            )
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument("chatId") { type = NavType.StringType },
                navArgument("title") { type = NavType.StringType },
                navArgument("chatType") { type = NavType.StringType },
                navArgument("peerEmail") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val chatId = URLDecoder.decode(backStackEntry.arguments?.getString("chatId").orEmpty(), "UTF-8")
            val title = URLDecoder.decode(backStackEntry.arguments?.getString("title").orEmpty(), "UTF-8")
            val chatType = URLDecoder.decode(backStackEntry.arguments?.getString("chatType").orEmpty(), "UTF-8")
            val peerEmailArg = URLDecoder.decode(backStackEntry.arguments?.getString("peerEmail").orEmpty(), "UTF-8")
            val peerEmail = peerEmailArg.takeUnless { it == NO_PEER }
            val vm = viewModel<ChatViewModel>(factory = vmFactory {
                initializer {
                    ChatViewModel(
                        chatId, title, chatType, peerEmail,
                        app.api, app.sessionManager, app.webSocketClient, app.signalRepository,
                        app.notificationHelper, app.preferencesManager
                    )
                }
            })
            ChatScreen(
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onLeftGroup = { navController.popBackStack(Routes.CHAT_LIST, inclusive = false) }
            )
        }
    }
}