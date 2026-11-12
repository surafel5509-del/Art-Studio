package com.artforge.mobile.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.artforge.mobile.ui.assets.AssetLibraryScreen
import com.artforge.mobile.ui.common.HelpScreen
import com.artforge.mobile.ui.home.HomeScreen
import com.artforge.mobile.ui.home.HomeViewModel
import com.artforge.mobile.ui.home.RecoveryScreen
import com.artforge.mobile.ui.settings.SettingsScreen
import com.artforge.mobile.ui.studio.StudioScreen
import com.artforge.mobile.ui.theme.ArtForgeTheme

private object Routes {
    const val Home = "home"
    const val Studio = "studio/{projectId}"
    const val Settings = "settings"
    const val Assets = "assets"
    const val Help = "help"
    const val Recovery = "recovery"

    fun studio(id: String) = "studio/$id"
}

/** Root navigation graph. */
@Composable
fun AppNavigation(startProjectId: String? = null) {
    val navController = rememberNavController()
    val homeVm: HomeViewModel = viewModel()
    val recents by homeVm.recents.collectAsStateWithLifecycle()
    val recoveries by homeVm.recoveries.collectAsStateWithLifecycle()
    val settingsVm: com.artforge.mobile.ui.settings.SettingsViewModel = viewModel()
    val settings by settingsVm.settings.collectAsStateWithLifecycle()

    var pendingProject by remember { mutableStateOf(startProjectId) }

    LaunchedEffect(pendingProject) {
        pendingProject?.let { id ->
            navController.navigate(Routes.studio(id))
            pendingProject = null
        }
    }

    ArtForgeTheme(
        themeMode = settings.theme,
        highContrast = settings.highContrast,
        largeTargets = settings.largeTouchTargets,
    ) {
        NavHost(navController = navController, startDestination = Routes.Home) {
            composable(Routes.Home) {
                HomeScreen(
                    onOpenProject = { id -> navController.navigate(Routes.studio(id)) },
                    onNewProject = { kind, title, w, h, fps, frames, transparent, bg ->
                        homeVm.createProject(kind, title, w, h, fps, frames, transparent, bg) { id ->
                            navController.navigate(Routes.studio(id))
                        }
                    },
                    onOpenSettings = { navController.navigate(Routes.Settings) },
                    onOpenAssets = { navController.navigate(Routes.Assets) },
                    onOpenHelp = { navController.navigate(Routes.Help) },
                    onOpenRecovery = { navController.navigate(Routes.Recovery) },
                    onImportProject = { uri ->
                        homeVm.importProject(uri) { id ->
                            if (id != null) navController.navigate(Routes.studio(id))
                        }
                    },
                    recents = recents,
                    onDeleteProject = homeVm::deleteProject,
                    onDuplicateProject = homeVm::duplicateProject,
                    onToggleFavorite = homeVm::toggleFavorite,
                )
            }

            composable(
                Routes.Studio,
                arguments = listOf(navArgument("projectId") { type = NavType.StringType }),
            ) { entry ->
                val projectId = entry.arguments?.getString("projectId").orEmpty()
                StudioScreen(projectId = projectId, onBack = { navController.popBackStack() })
            }

            composable(Routes.Settings) {
                SettingsScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.Assets) {
                AssetLibraryScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.Help) {
                HelpScreen(onBack = { navController.popBackStack() })
            }

            composable(Routes.Recovery) {
                RecoveryScreen(
                    recoveries = recoveries,
                    onBack = { navController.popBackStack() },
                    onRestore = { entry ->
                        homeVm.restoreRecovery(entry) { id -> navController.navigate(Routes.studio(id)) }
                    },
                    onDiscard = homeVm::discardRecovery,
                )
            }
        }
    }
}
