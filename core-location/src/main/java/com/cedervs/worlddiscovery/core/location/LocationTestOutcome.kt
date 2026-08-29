package com.cedervs.worlddiscovery.core.location

/** Result shown to the user after [SubmitCurrentLocationUseCase] runs — deliberately generic,
 * never carries a coordinate or H3 cell (nothing location-derived is safe to display/log). */
sealed interface LocationTestOutcome {
    data object Success : LocationTestOutcome
    data object PermissionDenied : LocationTestOutcome
    data object LocationServicesDisabled : LocationTestOutcome
    data object LocationUnavailable : LocationTestOutcome
    data object SubmissionError : LocationTestOutcome
}
