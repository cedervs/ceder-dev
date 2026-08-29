package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate

/** Outcome of a single foreground location acquisition attempt. [Success] can only ever wrap
 * an already-validated [Coordinate] — its constructor rejects invalid latitude/longitude, so
 * no invalid coordinate can ever reach [SubmitCurrentLocationUseCase]. */
sealed interface LocationAcquisitionResult {
    data class Success(val coordinate: Coordinate) : LocationAcquisitionResult
    data object PermissionDenied : LocationAcquisitionResult
    data object LocationServicesDisabled : LocationAcquisitionResult
    data object LocationUnavailable : LocationAcquisitionResult
    data class Error(val reason: String?) : LocationAcquisitionResult
}
