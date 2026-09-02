package com.cedervs.worlddiscovery.feature.map

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import com.cedervs.worlddiscovery.core.discovery.DiscoveredCellGeometry
import com.cedervs.worlddiscovery.core.discovery.GeographicAreaVisitedStatus
import com.cedervs.worlddiscovery.core.discovery.MapReadState
import com.cedervs.worlddiscovery.core.location.LocationObservation
import com.cedervs.worlddiscovery.core.location.LocationPermissions
import com.cedervs.worlddiscovery.core.location.LocationTestOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

private val EMPTY_MAP_READ_STATE = MapReadState(
    geometries = emptyList<DiscoveredCellGeometry>(),
    franceVisitedStatus = GeographicAreaVisitedStatus.franceNotVisitedPlaceholder,
    franceComponents = emptyList(),
)

@Composable
fun MapScreen(
    submitCurrentLocation: suspend () -> LocationTestOutcome,
    onLocationPermissionGranted: () -> Unit,
    mapReadState: Flow<MapReadState>,
    currentLocationObservation: Flow<LocationObservation?>,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    // A single collected snapshot -- geometries and the France visited status below always come
    // from the exact same MapReadState emission (see ObserveMapReadState's doc comment for why
    // this is one Flow, not two independently-collected ones -- collecting two derived .map{}
    // flows separately would each re-trigger the underlying cold repository.observeAll(), silently
    // reintroducing the same "one write, two SELECT *" issue this shape exists to avoid).
    val readState by mapReadState.collectAsState(initial = EMPTY_MAP_READ_STATE)
    val geometries = readState.geometries
    val franceAreaId = readState.franceVisitedStatus.area.id
    // Highlighting follows real per-component presence, never the whole area's own VISITED status
    // -- see CountryOverlayRendering.kt's applyCountryOverlay doc comment. Only the components that
    // are themselves actually visited are passed through to rendering/navigation.
    val visitedFranceComponents = readState.franceComponents
        .filter { componentStatus -> componentStatus.visited }
        .map { componentStatus -> componentStatus.component }
    val currentPosition by currentLocationObservation.collectAsState(initial = null)

    var isBusy by remember { mutableStateOf(false) }
    var lastOutcome by remember { mutableStateOf<LocationTestOutcome?>(null) }
    // UI-only nuance, not part of the domain outcome: whether the permission looks permanently
    // denied ("don't ask again"), so we can hint at Settings without a new domain concept.
    var permissionPermanentlyDenied by remember { mutableStateOf(false) }
    var permissionRequestedBefore by remember { mutableStateOf(false) }

    fun runTest() {
        isBusy = true
        permissionPermanentlyDenied = false
        coroutineScope.launch {
            lastOutcome = submitCurrentLocation()
            isBusy = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) {
            runTest()
            // The automatic foreground tracking session may have already terminated as
            // PermissionDenied before the user granted permission here (see AppContainer's
            // retryLocationTrackingAfterPermissionGranted doc) — nudge it to retry now, in the
            // same foreground period, rather than waiting for the next app foreground/background
            // cycle.
            onLocationPermissionGranted()
        } else {
            val activity = context.findActivity()
            val canStillAskAgain = activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                )
            // If we've already asked once before and the system now refuses to show a rationale,
            // the user most likely picked "don't ask again" — best-effort detection only.
            permissionPermanentlyDenied = permissionRequestedBefore && !canStillAskAgain
            lastOutcome = LocationTestOutcome.PermissionDenied
        }
        permissionRequestedBefore = true
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            DiscoveryMapView(
                geometries = geometries,
                franceAreaId = franceAreaId,
                visitedFranceComponents = visitedFranceComponents,
                currentPosition = currentPosition,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            Column(
                modifier = Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.map_placeholder_title),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(Modifier.height(24.dp))
                Button(
                    enabled = !isBusy,
                    onClick = {
                        lastOutcome = null
                        if (LocationPermissions.hasAnyLocationPermission(context)) {
                            runTest()
                        } else {
                            permissionLauncher.launch(LocationPermissions.REQUIRED_PERMISSIONS)
                        }
                    },
                ) {
                    Text(stringResource(R.string.map_test_location_button))
                }
                if (isBusy) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator()
                }
                lastOutcome?.let { outcome ->
                    Spacer(Modifier.height(16.dp))
                    val textRes = outcome.toDisplayStringRes(permissionPermanentlyDenied)
                    Text(
                        text = stringResource(textRes),
                        color = if (outcome == LocationTestOutcome.Success) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }
        }
    }
}

private fun LocationTestOutcome.toDisplayStringRes(permissionPermanentlyDenied: Boolean): Int = when (this) {
    LocationTestOutcome.Success -> R.string.map_test_location_result_success
    LocationTestOutcome.PermissionDenied -> if (permissionPermanentlyDenied) {
        R.string.map_test_location_result_permission_denied_permanently
    } else {
        R.string.map_test_location_result_permission_denied
    }
    LocationTestOutcome.LocationServicesDisabled -> R.string.map_test_location_result_services_disabled
    LocationTestOutcome.LocationUnavailable -> R.string.map_test_location_result_unavailable
    LocationTestOutcome.SubmissionError -> R.string.map_test_location_result_error
}

private fun Context.findActivity(): Activity? {
    var current = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
