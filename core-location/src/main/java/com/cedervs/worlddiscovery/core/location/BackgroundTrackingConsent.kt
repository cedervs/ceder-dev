package com.cedervs.worlddiscovery.core.location

import kotlinx.coroutines.flow.Flow

/**
 * Explicit, persisted, off-by-default user consent for background location tracking.
 *
 * This is a product-level opt-in, entirely separate from the Android OS permission — a `true`
 * here only ever means "the user asked for this feature," never "the OS has granted it."
 * [BackgroundLocationController] is the only place these two are combined, and it always
 * re-verifies the actual permission itself (via [FusedBackgroundLocationRegistrar]) rather than
 * trusting this flag as a proxy for it.
 */
interface BackgroundTrackingConsent {
    val isEnabled: Flow<Boolean>
    suspend fun setEnabled(enabled: Boolean)
}
