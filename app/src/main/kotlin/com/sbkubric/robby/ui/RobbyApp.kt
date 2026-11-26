package com.sbkubric.robby.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.sbkubric.robby.R
import com.sbkubric.robby.ui.editor.EditorScreen
import com.sbkubric.robby.ui.scenarios.ScenariosScreen
import com.sbkubric.robby.ui.settings.ActiveAppletsScreen
import com.sbkubric.robby.ui.settings.ActivityLogScreen
import com.sbkubric.robby.ui.settings.SettingsScreen
import com.sbkubric.robby.ui.telegram.TelegramSetupScreen
import com.sbkubric.robby.ui.theme.RobbyTheme
import kotlinx.serialization.Serializable

/** Screens; navigation-compose scopes each one's ViewModel to its back stack entry. */
@Serializable private data object HomeRoute
@Serializable private data class EditorRoute(val scenarioId: String? = null)
@Serializable private data object ActiveAppletsRoute
@Serializable private data object TelegramSetupRoute
@Serializable private data object ActivityLogRoute

@Composable
fun RobbyApp() {
    RobbyTheme {
        val nav = rememberNavController()
        // Guarded so that a double tap (e.g. on «Сохранить») cannot pop the home screen itself.
        val back: () -> Unit = { if (nav.previousBackStackEntry != null) nav.popBackStack() }
        NavHost(nav, startDestination = HomeRoute) {
            composable<HomeRoute> {
                HomeScreen(
                    onOpenScenario = { nav.navigate(EditorRoute(it)) },
                    onOpenApplets = { nav.navigate(ActiveAppletsRoute) },
                    onOpenLog = { nav.navigate(ActivityLogRoute) },
                )
            }
            composable<EditorRoute> { entry -> EditorScreen(entry.toRoute<EditorRoute>().scenarioId, onBack = back) }
            composable<ActiveAppletsRoute> {
                ActiveAppletsScreen(onBack = back, onSetupTelegram = { nav.navigate(TelegramSetupRoute) })
            }
            composable<TelegramSetupRoute> { TelegramSetupScreen(onBack = back) }
            composable<ActivityLogRoute> { ActivityLogScreen(onBack = back) }
        }
    }
}

private enum class Tab(@StringRes val label: Int, val icon: ImageVector) {
    Scenarios(R.string.tab_scenarios, Icons.AutoMirrored.Filled.List),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
private fun HomeScreen(onOpenScenario: (String?) -> Unit, onOpenApplets: () -> Unit, onOpenLog: () -> Unit) {
    var current by rememberSaveable { mutableStateOf(Tab.Scenarios) }
    Scaffold(
        floatingActionButton = {
            if (current == Tab.Scenarios) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenScenario(null) },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.scenarios_add)) },
                )
            }
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { tab ->
                    NavigationBarItem(
                        selected = tab == current,
                        onClick = { current = tab },
                        icon = { Icon(tab.icon, contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (current) {
            Tab.Scenarios -> ScenariosScreen(onOpen = { onOpenScenario(it) }, modifier = modifier)
            Tab.Settings -> SettingsScreen(onOpenApplets, onOpenLog, modifier)
        }
    }
}
