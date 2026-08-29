package com.cedervs.worlddiscovery.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview

@Preview(name = "Light", showBackground = true)
@Composable
private fun ThemePreviewLight() {
    WorldDiscoveryTheme(themeMode = ThemeMode.LIGHT) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text("World Discovery", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Preview(name = "Dark", showBackground = true)
@Composable
private fun ThemePreviewDark() {
    WorldDiscoveryTheme(themeMode = ThemeMode.DARK) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text("World Discovery", style = MaterialTheme.typography.headlineMedium)
        }
    }
}

@Preview(name = "System", showBackground = true)
@Composable
private fun ThemePreviewSystem() {
    WorldDiscoveryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Text("World Discovery", style = MaterialTheme.typography.headlineMedium)
        }
    }
}
