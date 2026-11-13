// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vantaview.app.data.Prefs
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.feature.live.LiveScreen
import com.vantaview.app.feature.player.PlayerScreen
import com.vantaview.app.feature.playlist.PlaylistsScreen
import com.vantaview.app.feature.terms.TermsScreen
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: Prefs
    @Inject lateinit var providers: ProviderRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VantaTheme {
                AppNav(prefs, providers, onExit = { finish() })
            }
        }
    }
}

@Composable
private fun AppNav(prefs: Prefs, providers: ProviderRepository, onExit: () -> Unit) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<String?>(null) }

    // First launch order: terms, then add a playlist, then Live TV.
    LaunchedEffect(Unit) {
        start = when {
            !prefs.termsAccepted -> "terms"
            providers.count() == 0 -> "playlists"
            else -> "live"
        }
    }

    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(Vanta.Bg))
        return
    }

    NavHost(navController = nav, startDestination = startRoute) {
        composable("terms") {
            TermsScreen(
                onAgree = {
                    prefs.termsAccepted = true
                    scope.launch {
                        val next = if (providers.count() == 0) "playlists" else "live"
                        nav.navigate(next) { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                },
                onDisagree = onExit,
            )
        }
        composable("playlists") {
            PlaylistsScreen(
                onOpenLive = { nav.navigate("live") { popUpTo(nav.graph.id) { inclusive = true } } },
            )
        }
        composable("live") {
            LiveScreen(
                onPlay = { id -> nav.navigate("player/$id") },
                onPlaylists = { nav.navigate("playlists") },
            )
        }
        composable(
            route = "player/{channelId}",
            arguments = listOf(navArgument("channelId") { type = NavType.LongType }),
        ) {
            PlayerScreen(onBack = { nav.popBackStack() })
        }
    }
}
