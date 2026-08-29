package com.cedervs.worlddiscovery

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.cedervs.worlddiscovery.ui.WorldDiscoveryApp
import com.cedervs.worlddiscovery.ui.theme.WorldDiscoveryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorldDiscoveryTheme {
                WorldDiscoveryApp()
            }
        }
    }
}
