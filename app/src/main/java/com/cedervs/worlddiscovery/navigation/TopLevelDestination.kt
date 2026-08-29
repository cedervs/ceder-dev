package com.cedervs.worlddiscovery.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import com.cedervs.worlddiscovery.R

enum class TopLevelDestination(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
) {
    MAP(route = "map", labelRes = R.string.nav_map, icon = Icons.Filled.Map),
    JOURNEY(route = "journey", labelRes = R.string.nav_journey, icon = Icons.Filled.Explore),
    PROGRESS(route = "progress", labelRes = R.string.nav_progress, icon = Icons.AutoMirrored.Filled.TrendingUp),
    PROFILE(route = "profile", labelRes = R.string.nav_profile, icon = Icons.Filled.Person),
}
