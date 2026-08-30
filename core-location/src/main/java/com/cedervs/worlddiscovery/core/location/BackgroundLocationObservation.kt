package com.cedervs.worlddiscovery.core.location

import com.cedervs.worlddiscovery.core.discovery.Coordinate
import java.time.Instant

/**
 * One usable location extracted from a background-location broadcast, paired with that specific
 * fix's own timestamp ([observedAt] — from the `Location`'s own `time`, never the time the
 * broadcast happened to be processed). See [extractBackgroundLocationObservations].
 */
data class BackgroundLocationObservation(val coordinate: Coordinate, val observedAt: Instant)
