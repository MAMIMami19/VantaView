// SPDX-License-Identifier: GPL-3.0-or-later
package com.vantaview.app

import android.net.Uri
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
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vantaview.app.data.Prefs
import com.vantaview.app.data.addon.AddonRepository
import com.vantaview.app.data.repo.ProviderRepository
import com.vantaview.app.feature.live.LiveScreen
import com.vantaview.app.feature.player.PlayerScreen
import com.vantaview.app.feature.player.VodPlayerScreen
import com.vantaview.app.feature.sources.SourcesScreen
import com.vantaview.app.feature.sources.SourcesTab
import com.vantaview.app.feature.terms.TermsScreen
import com.vantaview.app.feature.vod.CatalogScreen
import com.vantaview.app.feature.vod.DetailsScreen
import com.vantaview.app.ui.AppShell
import com.vantaview.app.ui.Section
import com.vantaview.app.ui.Vanta
import com.vantaview.app.ui.VantaTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var prefs: Prefs
    @Inject lateinit var providers: ProviderRepository
    @Inject lateinit var addons: AddonRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VantaTheme {
                AppNav(prefs, providers, addons, onExit = { finish() })
            }
        }
    }
}

/** Where to land: Live TV if there are playlists, Movies if there are only add-ons, else Live TV's empty state. */
private suspend fun landing(providers: ProviderRepository, addons: AddonRepository): String = when {
    providers.count() > 0 -> Section.LIVE.route
    addons.count() > 0 -> Section.MOVIES.route
    else -> Section.LIVE.route
}

/** Switches section. [restore] brings back the section as it was left (off when opening a specific tab). */
private fun NavHostController.goTo(route: String, restore: Boolean = true) = navigate(route) {
    popUpTo(graph.id) { saveState = restore }
    launchSingleTop = true
    restoreState = restore
}

@Composable
private fun AppNav(prefs: Prefs, providers: ProviderRepository, addons: AddonRepository, onExit: () -> Unit) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<String?>(null) }

    // First launch: terms, then straight into the app (no forced setup).
    LaunchedEffect(Unit) { start = if (!prefs.termsAccepted) "terms" else landing(providers, addons) }

    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(Vanta.Bg))
        return
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val section = when {
        route == null -> null
        route == "live" -> Section.LIVE
        route.startsWith("catalog/") -> if (entry?.arguments?.getString("type") == "series") Section.SERIES else Section.MOVIES
        route.startsWith("sources") -> Section.SOURCES
        else -> null
    }

    AppShell(current = section, onSelect = { nav.goTo(it.route) }) {
        NavHost(navController = nav, startDestination = startRoute) {
            composable("terms") {
                TermsScreen(
                    onAgree = {
                        prefs.termsAccepted = true
                        scope.launch {
                            val next = landing(providers, addons)
                            nav.navigate(next) { popUpTo(nav.graph.id) { inclusive = true } }
                        }
                    },
                    onDisagree = onExit,
                )
            }
            composable("live") {
                LiveScreen(
                    onPlay = { id -> nav.navigate("player/$id") },
                    onAddPlaylist = { nav.goTo("sources?tab=playlists", restore = false) },
                    onAddAddon = { nav.goTo("sources?tab=addons", restore = false) },
                )
            }
            composable("catalog/{type}", arguments = listOf(navArgument("type") { type = NavType.StringType })) { e ->
                val isSeries = e.arguments?.getString("type") == "series"
                CatalogScreen(
                    title = if (isSeries) "TV Series" else "Movies",
                    onOpen = { m ->
                        nav.navigate(
                            "details/${Uri.encode(m.type)}?id=${Uri.encode(m.id)}&name=${Uri.encode(m.name)}&poster=${Uri.encode(m.poster.orEmpty())}"
                        )
                    },
                    onAddAddon = { nav.goTo("sources?tab=addons", restore = false) },
                )
            }
            composable(
                "sources?tab={tab}",
                arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "playlists" }),
            ) { e ->
                SourcesScreen(if (e.arguments?.getString("tab") == "addons") SourcesTab.ADDONS else SourcesTab.PLAYLISTS)
            }
            composable(
                "details/{type}?id={id}&name={name}&poster={poster}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                DetailsScreen(onPlay = { nav.navigate("vod") }, onBack = { nav.popBackStack() })
            }
            composable(
                route = "player/{channelId}",
                arguments = listOf(navArgument("channelId") { type = NavType.LongType }),
            ) {
                PlayerScreen(onBack = { nav.popBackStack() })
            }
            composable("vod") {
                VodPlayerScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
