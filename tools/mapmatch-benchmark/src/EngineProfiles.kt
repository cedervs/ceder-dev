package worlddiscovery.benchmark

/**
 * Single source of truth mapping [TransportMode] to each engine's profile/costing identifier --
 * protocol round 2B-4 §8/§16 ("implement it generically, not as hard-coded Scenario 3 behavior").
 * Used by both [GenerateEngineRequests] (to build the actual request) and [AnalyzeEngineResults]
 * (to record the correct profile in [EngineConfigIdentity] rather than a stale hardcoded value) --
 * a single mapping means a new [TransportMode] only needs to be added in one place.
 */
object EngineProfiles {
    fun osrmProfile(mode: TransportMode): String = when (mode) {
        TransportMode.CAR -> "car"
        TransportMode.WALK -> "foot"
        TransportMode.BIKE -> "bike"
    }

    fun valhallaCosting(mode: TransportMode): String = when (mode) {
        TransportMode.CAR -> "auto"
        TransportMode.WALK -> "pedestrian"
        TransportMode.BIKE -> "bicycle"
    }

    fun graphHopperProfile(mode: TransportMode): String = when (mode) {
        TransportMode.CAR -> "car"
        TransportMode.WALK -> "foot"
        TransportMode.BIKE -> "bike"
    }
}
