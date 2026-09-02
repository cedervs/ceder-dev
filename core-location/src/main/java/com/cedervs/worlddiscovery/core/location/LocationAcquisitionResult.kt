package com.cedervs.worlddiscovery.core.location

/** Outcome of a single foreground location acquisition attempt. [Success] can only ever wrap
 * a [LocationObservation] whose coordinate is already validated — [Coordinate]'s own constructor
 * rejects invalid latitude/longitude, so no invalid coordinate can ever reach
 * [SubmitCurrentLocationUseCase]. */
sealed interface LocationAcquisitionResult {
    data class Success(val observation: LocationObservation) : LocationAcquisitionResult
    data object PermissionDenied : LocationAcquisitionResult
    data object LocationServicesDisabled : LocationAcquisitionResult
    data object LocationUnavailable : LocationAcquisitionResult
    data class Error(val reason: String?) : LocationAcquisitionResult
}
