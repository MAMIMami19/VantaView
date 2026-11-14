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
import androidx.compose.runtime.collectAsState
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
import com.vantaview.app.feature.home.HomeScreen
import com.vantaview.app.feature.live.LiveScreen
import com.vantaview.app.feature.player.PlayerScreen
import com.vantaview.app.feature.profiles.ChooseProfileScreen
import com.vantaview.app.feature.profiles.ProfileEditScreen
import com.vantaview.app.data.profile.ProfileRepository
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
    @Inject lateinit var profiles: ProfileRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VantaTheme {
                AppNav(prefs, providers, addons, profiles, onExit = { finish() })
            }
        }
    }
}

/** Where to land: Live TV if there are playlists, Movies if there are only add-ons, else Live TV's empty state. */
@Suppress("UNUSED_PARAMETER")
private suspend fun landing(providers: ProviderRepository, addons: AddonRepository): String = Section.HOME.route

/** Switches section. [restore] brings back the section as it was left (off when opening a specific tab). */
/**
 * After the terms: make the first profile, pick one when there are several, or go straight in with one.
 */
private suspend fun afterTerms(profiles: ProfileRepository, providers: ProviderRepository, addons: AddonRepository): String {
    val all = profiles.all()
    return when {
        all.isEmpty() -> "profile/setup"
        all.size > 1 -> "profiles"
        else -> { profiles.select(all.first().id); landing(providers, addons) }
    }
}

private fun NavHostController.goTo(route: String, restore: Boolean = true) {
    // Home sits at the bottom of the page history, so Back from any section returns to Home.
    val homeInHistory = runCatching { getBackStackEntry(Section.HOME.route) }.isSuccess
    if (!homeInHistory) {
        navigate(Section.HOME.route) { popUpTo(graph.id) { inclusive = true } }
    }
    navigate(route) {
        popUpTo(Section.HOME.route) { saveState = restore }
        launchSingleTop = true
        restoreState = restore
    }
}

@Composable
private fun AppNav(
    prefs: Prefs,
    providers: ProviderRepository,
    addons: AddonRepository,
    profiles: ProfileRepository,
    onExit: () -> Unit,
) {
    val nav = rememberNavController()
    val scope = rememberCoroutineScope()
    var start by remember { mutableStateOf<String?>(null) }

    // First launch: terms, then "Who's watching?" (create a profile), then Sources. No playlist is required.
    LaunchedEffect(Unit) { start = if (!prefs.termsAccepted) "terms" else afterTerms(profiles, providers, addons) }
    val profile by profiles.observeCurrent().collectAsState(initial = null)

    /** Clears the back stack and opens the right place for the profile now in use. */
    fun enterApp() = scope.launch {
        val next = landing(providers, addons)
        nav.navigate(next) { popUpTo(nav.graph.id) { inclusive = true } }
    }

    val startRoute = start
    if (startRoute == null) {
        Box(Modifier.fillMaxSize().background(Vanta.Bg))
        return
    }

    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val section = when {
        route == null -> null
        route == "home" -> Section.HOME
        route == "live" -> Section.LIVE
        route == "movies" -> Section.MOVIES
        route == "series" -> Section.SERIES
        route.startsWith("sources") -> Section.SOURCES
        else -> null
    }

    AppShell(
        current = section,
        profile = profile,
        onSelect = { nav.goTo(it.route) },
        onProfiles = { nav.navigate("profiles") },
    ) {
        NavHost(navController = nav, startDestination = startRoute) {
            composable("terms") {
                TermsScreen(
                    onAgree = {
                        prefs.termsAccepted = true
                        scope.launch {
                            val next = afterTerms(profiles, providers, addons)
                            nav.navigate(next) { popUpTo(nav.graph.id) { inclusive = true } }
                        }
                    },
                    onDisagree = onExit,
                )
            }
            composable("profile/setup") {
                ProfileEditScreen(
                    profileId = 0L,
                    first = true,
                    onDone = { id ->
                        profiles.select(id)
                        nav.goTo("sources?tab=playlists", restore = false)
                    },
                    onBack = null,
                )
            }
            composable("profiles") {
                ChooseProfileScreen(
                    onChosen = { enterApp() },
                    onEdit = { id -> nav.navigate("profile/edit/$id") },
                    onAdd = { nav.navigate("profile/edit/0") },
                )
            }
            composable("profile/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                ProfileEditScreen(
                    profileId = e.arguments?.getLong("id") ?: 0L,
                    first = false,
                    onDone = { nav.popBackStack() },
                    onBack = { nav.popBackStack() },
                )
            }
            composable("home") {
                // Back on Home: press twice to leave the app, so a stray press doesn't close it.
                var exitArmed by remember { mutableStateOf(false) }
                val context = androidx.compose.ui.platform.LocalContext.current
                LaunchedEffect(exitArmed) { if (exitArmed) { kotlinx.coroutines.delay(2_000); exitArmed = false } }
                androidx.activity.compose.BackHandler(enabled = !exitArmed) {
                    exitArmed = true
                    android.widget.Toast.makeText(context, "Press Back again to exit", android.widget.Toast.LENGTH_SHORT).show()
                }
                HomeScreen(
                    onSection = { route -> nav.goTo(route) },
                    onOpenTitle = { type, id, name, poster, play ->
                        nav.navigate(
                            "details/${Uri.encode(type)}?id=${Uri.encode(id)}&name=${Uri.encode(name)}" +
                                "&poster=${Uri.encode(poster.orEmpty())}&play=${Uri.encode(play.orEmpty())}"
                        )
                    },
                    onPlayChannel = { id -> nav.navigate("player/$id") },
                )
            }
            composable("live") {
                LiveScreen(
                    onPlay = { id -> nav.navigate("player/$id") },
                    onAddPlaylist = { nav.goTo("sources?tab=playlists", restore = false) },
                    onAddAddon = { nav.goTo("sources?tab=addons", restore = false) },
                )
            }
            // Movies and TV Series are separate destinations so each keeps its own page and state.
            listOf("movies" to "movie", "series" to "series").forEach { (route, kind) ->
                composable(route, arguments = listOf(navArgument("type") { type = NavType.StringType; defaultValue = kind })) {
                    CatalogScreen(
                        title = if (kind == "series") "TV Series" else "Movies",
                        onOpen = { m ->
                            nav.navigate(
                                "details/${Uri.encode(m.type)}?id=${Uri.encode(m.id)}&name=${Uri.encode(m.name)}&poster=${Uri.encode(m.poster.orEmpty())}"
                            )
                        },
                        onAddAddon = { nav.goTo("sources?tab=addons", restore = false) },
                    )
                }
            }
            composable(
                "sources?tab={tab}",
                arguments = listOf(navArgument("tab") { type = NavType.StringType; defaultValue = "playlists" }),
            ) { e ->
                SourcesScreen(
                    when (e.arguments?.getString("tab")) {
                        "addons" -> SourcesTab.ADDONS
                        "debrid" -> SourcesTab.DEBRID
                        else -> SourcesTab.PLAYLISTS
                    }
                )
            }
            composable(
                "details/{type}?id={id}&name={name}&poster={poster}&play={play}",
                arguments = listOf(
                    navArgument("type") { type = NavType.StringType },
                    navArgument("id") { type = NavType.StringType; defaultValue = "" },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("poster") { type = NavType.StringType; defaultValue = "" },
                    navArgument("play") { type = NavType.StringType; defaultValue = "" },
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
        if (profile != null) com.vantaview.app.feature.country.CountryChooserHost()
    }
}
