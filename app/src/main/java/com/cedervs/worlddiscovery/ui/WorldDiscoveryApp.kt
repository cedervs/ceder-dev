package com.cedervs.worlddiscovery.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.cedervs.worlddiscovery.feature.journey.JourneyScreen
import com.cedervs.worlddiscovery.feature.map.MapScreen
import com.cedervs.worlddiscovery.feature.profile.ProfileScreen
import com.cedervs.worlddiscovery.feature.progress.ProgressScreen
import com.cedervs.worlddiscovery.navigation.TopLevelDestination

@Composable
fun WorldDiscoveryApp() {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val currentRoute = navController.currentBackStackEntryAsState().value?.destination?.route
            NavigationBar {
                TopLevelDestination.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(stringResource(destination.labelRes)) },
                    )
                }
            }
        },
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = TopLevelDestination.MAP.route,
            modifier = Modifier.padding(innerPadding),
        ) {
            composable(TopLevelDestination.MAP.route) { MapScreen() }
            composable(TopLevelDestination.JOURNEY.route) { JourneyScreen() }
            composable(TopLevelDestination.PROGRESS.route) { ProgressScreen() }
            composable(TopLevelDestination.PROFILE.route) { ProfileScreen() }
        }
    }
}
