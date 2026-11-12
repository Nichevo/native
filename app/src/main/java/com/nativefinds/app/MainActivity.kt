package com.nativefinds.app

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nativefinds.app.ui.detail.FindDetailScreen
import com.nativefinds.app.ui.finds.FindsScreen
import com.nativefinds.app.ui.identify.IdentifyScreen
import com.nativefinds.app.ui.library.LibraryScreen
import com.nativefinds.app.ui.library.SpeciesScreen
import com.nativefinds.app.ui.map.MapScreen
import com.nativefinds.app.ui.settings.SettingsScreen
import com.nativefinds.app.ui.theme.NativeFindsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as NativeFindsApp).container
        setContent {
            NativeFindsTheme { AppNav(container) }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("finds", "Finds", Icons.Filled.PhotoLibrary),
    Tab("library", "Species", Icons.Filled.LocalFlorist),
    Tab("map", "Map", Icons.Filled.Map),
    Tab("settings", "Settings", Icons.Filled.Settings),
)

@Composable
fun AppNav(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    Scaffold(
        bottomBar = {
            if (tabs.any { it.route == route }) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = route == tab.route,
                            onClick = {
                                nav.navigate(tab.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = "finds", modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable("finds") {
                FindsScreen(
                    container = container,
                    onNewFind = { nav.navigate("identify") },
                    onOpen = { id -> nav.navigate("find/$id") },
                )
            }
            composable("library") {
                LibraryScreen(container = container, onOpenSpecies = { name -> nav.navigate("species/" + Uri.encode(name)) })
            }
            composable("species/{name}") { entry ->
                SpeciesScreen(
                    container = container,
                    scientificName = entry.arguments?.getString("name").orEmpty(),
                    onBack = { nav.popBackStack() },
                    onOpenFind = { id -> nav.navigate("find/$id") },
                )
            }
            composable("map") {
                MapScreen(container = container, onOpen = { id -> nav.navigate("find/$id") })
            }
            composable("settings") {
                SettingsScreen(container = container)
            }
            composable("identify") {
                IdentifyScreen(
                    container = container,
                    onBack = { nav.popBackStack() },
                    onSaved = { id ->
                        nav.navigate("find/$id") { popUpTo("finds") }
                    },
                )
            }
            composable("edit/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                IdentifyScreen(
                    container = container,
                    editFindId = entry.arguments?.getLong("id"),
                    onBack = { nav.popBackStack() },
                    onSaved = { nav.popBackStack() },
                )
            }
            composable("find/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { entry ->
                FindDetailScreen(
                    container = container,
                    findId = entry.arguments?.getLong("id") ?: 0L,
                    onBack = { nav.popBackStack() },
                    onEdit = { id -> nav.navigate("edit/$id") },
                )
            }
        }
    }
}
