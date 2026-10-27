package com.lumen.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lumen.player.LumenApp
import com.lumen.player.ui.browse.BrowseScreen
import com.lumen.player.ui.browse.LocalFolderScreen
import com.lumen.player.ui.browse.RemoteBrowserScreen
import com.lumen.player.ui.components.MiniPlayer
import com.lumen.player.ui.components.ResumeVideoButton
import com.lumen.player.ui.library.LibraryScreen
import com.lumen.player.ui.onboarding.OnboardingScreen
import com.lumen.player.ui.playlists.PlaylistDetailScreen
import com.lumen.player.ui.playlists.PlaylistsScreen
import com.lumen.player.ui.settings.AiSettingsScreen
import com.lumen.player.ui.settings.OnlineSubtitleSettingsScreen
import com.lumen.player.ui.settings.SettingsScreen
import com.lumen.player.ui.settings.SubtitleStyleScreen
import com.lumen.player.ui.theme.Lumen
import kotlinx.coroutines.launch

object Routes {
    const val LIBRARY = "library"
    const val BROWSE = "browse?add={add}"
    const val PLAYLISTS = "playlists"
    const val SETTINGS = "settings"
    const val REMOTE = "remote/{serverId}?path={path}&title={title}"
    const val FOLDER = "folder/{name}"
    const val PLAYLIST = "playlist/{id}"
    const val SUBTITLE_STYLE = "settings/subtitle"
    const val AI = "settings/ai"
    const val ONLINE = "settings/online"

    fun browse(add: Boolean = false) = "browse?add=$add"
    fun remote(serverId: Long, path: String, title: String) =
        "remote/$serverId?path=${Uri.encode(path)}&title=${Uri.encode(title)}"
    fun folder(name: String) = "folder/${Uri.encode(name)}"
    fun playlist(id: Long) = "playlist/$id"
}

private data class Tab(val route: String, val navigate: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.LIBRARY, Routes.LIBRARY, "라이브러리", Icons.Filled.GridView),
    Tab(Routes.BROWSE, Routes.browse(), "탐색", Icons.Filled.Folder),
    Tab(Routes.PLAYLISTS, Routes.PLAYLISTS, "재생목록", Icons.AutoMirrored.Filled.QueueMusic),
    Tab(Routes.SETTINGS, Routes.SETTINGS, "설정", Icons.Filled.Settings),
)

@UnstableApi
@Composable
fun LumenRoot() {
    val container = LumenApp.container
    val settings by container.settings.settings.collectAsState()
    val scope = rememberCoroutineScope()
    val nav = rememberNavController()
    var openAddServer by rememberSaveable { mutableStateOf(false) }

    if (!settings.onboardingDone) {
        OnboardingScreen(
            onGranted = { scope.launch { container.settings.update { it.copy(onboardingDone = true) } } },
            onAddServer = {
                openAddServer = true
                scope.launch { container.settings.update { it.copy(onboardingDone = true) } }
            },
        )
        return
    }
    // NavHost가 그래프를 만든 뒤에 이동해야 한다
    LaunchedEffect(openAddServer) {
        if (openAddServer) {
            nav.navigate(Routes.browse(add = true))
            openAddServer = false
        }
    }

    val backStack by nav.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { ResumeVideoButton() },
        bottomBar = {
            Column {
                MiniPlayer()
                NavigationBar(containerColor = Lumen.NavBar) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = { nav.navigateTab(tab.navigate) },
                            icon = { Icon(tab.icon, null) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Lumen.OnPrimaryContainer,
                                selectedTextColor = Lumen.OnPrimaryContainer,
                                indicatorColor = Lumen.PrimaryContainer,
                                unselectedIconColor = Lumen.TextMuted,
                                unselectedTextColor = Lumen.TextMuted,
                            ),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            NavHost(nav, startDestination = Routes.LIBRARY) {
                composable(Routes.LIBRARY) { LibraryScreen(nav) }
                composable(
                    Routes.BROWSE,
                    arguments = listOf(navArgument("add") { type = NavType.BoolType; defaultValue = false }),
                ) { BrowseScreen(nav, openAdd = it.arguments?.getBoolean("add") == true) }
                composable(Routes.PLAYLISTS) { PlaylistsScreen(nav) }
                composable(Routes.SETTINGS) { SettingsScreen(nav) }
                composable(
                    Routes.REMOTE,
                    arguments = listOf(
                        navArgument("serverId") { type = NavType.LongType },
                        navArgument("path") { type = NavType.StringType; defaultValue = "/" },
                        navArgument("title") { type = NavType.StringType; defaultValue = "" },
                    ),
                ) {
                    RemoteBrowserScreen(
                        nav,
                        serverId = it.arguments!!.getLong("serverId"),
                        path = it.arguments!!.getString("path") ?: "/",
                        title = it.arguments!!.getString("title").orEmpty(),
                    )
                }
                composable(Routes.FOLDER, arguments = listOf(navArgument("name") { type = NavType.StringType })) {
                    LocalFolderScreen(nav, it.arguments!!.getString("name").orEmpty())
                }
                composable(Routes.PLAYLIST, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                    PlaylistDetailScreen(nav, it.arguments!!.getLong("id"))
                }
                composable(Routes.SUBTITLE_STYLE) { SubtitleStyleScreen(nav) }
                composable(Routes.AI) { AiSettingsScreen(nav) }
                composable(Routes.ONLINE) { OnlineSubtitleSettingsScreen(nav) }
            }
        }
    }
}

private fun NavHostController.navigateTab(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}
