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
        val appContainer = (application as WorldDiscoveryApplication).appContainer
        setContent {
            WorldDiscoveryTheme {
                WorldDiscoveryApp(
                    authRepository = appContainer.authRepository,
                    submitCurrentLocationUseCase = appContainer.submitCurrentLocationUseCase,
                    onLocationPermissionGranted = appContainer::retryLocationTrackingAfterPermissionGranted,
                    backgroundTrackingConsent = appContainer.backgroundTrackingConsent,
                    observeMapReadState = appContainer.observeMapReadState,
                    currentLocationObservation = appContainer.currentLocationObservation,
                )
            }
        }
    }
}
