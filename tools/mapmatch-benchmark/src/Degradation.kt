package worlddiscovery.benchmark

/**
 * Deterministic degradation generator -- protocol §D.5 / correction §16. Implements the minimum
 * required now: [DegradationSelectionPolicy.FIRST_AT_OR_AFTER_TARGET] downsampling to a fixed
 * target cadence (7s/15s/27s/45s are the standard variants, but any positive cadence works).
 *
 * Selection rule (matches the correction's recommended rule verbatim): for each target timestamp
 * `t0 + k*targetCadenceMs` (k = 0, 1, 2, ...), select the first observation at or after that target
 * timestamp that has not already been selected. This is a pure downsampling of real observations --
 * it never synthesizes a point that doesn't exist in the reference trace (protocol §D.5 point 2).
 *
 * Handles, by construction rather than by special-casing:
 * - irregular source intervals: the search advances by scanning forward from the last selected
 *   index, regardless of how unevenly the source points are spaced;
 * - stops (near-duplicate/identical timestamps in the source): they don't cause a duplicate
 *   selection because the search always starts from `lastSelectedIndex + 1`;
 * - gaps larger than the target cadence: the loop simply lands on whatever real point is first
 *   at-or-after the next target instant, however far that is -- no synthetic point is inserted to
 *   fill the gap;
 * - no duplicate selected point: guaranteed structurally (search floor is monotonically
 *   increasing), not just checked defensively;
 * - final point policy: the reference trace's last observation is always included, even if it
 *   doesn't land exactly on a `t0 + k*targetCadenceMs` boundary -- otherwise a trip's true endpoint
 *   could be silently dropped purely because of cadence-boundary rounding.
 *
 * Random perturbations (accuracy/coordinate noise/outlier injection) described in [DegradationSpec]
 * are NOT implemented here (protocol correction §16: "do not implement unless needed now"; would
 * persist [DegradationSpec.randomSeed] if/when added).
 */
object Degradation {

    fun generate(
        reference: ReferenceTrace,
        spec: DegradationSpec,
        variantLabel: String,
        mode: TransportMode,
    ): DegradedObservationSet {
        require(spec.selectionPolicy == DegradationSelectionPolicy.FIRST_AT_OR_AFTER_TARGET) {
            "only FIRST_AT_OR_AFTER_TARGET is implemented this round -- got ${spec.selectionPolicy}"
        }
        require(spec.targetCadenceSeconds > 0.0) { "targetCadenceSeconds must be > 0" }
        val points = reference.rawObservations
        require(points.isNotEmpty()) { "reference trace ${reference.id} has no raw observations" }

        val stepMs = (spec.targetCadenceSeconds * 1000.0).toLong()
        require(stepMs > 0) { "targetCadenceSeconds too small to produce a positive millisecond step" }

        val startMs = points.first().timestampEpochMs
        val endMs = points.last().timestampEpochMs

        val selectedIndices = mutableListOf<Int>()
        var searchFrom = 0
        var targetMs = startMs
        while (targetMs <= endMs) {
            var idx = searchFrom
            while (idx < points.size && points[idx].timestampEpochMs < targetMs) idx++
            if (idx >= points.size) break
            selectedIndices.add(idx)
            searchFrom = idx + 1
            targetMs += stepMs
        }
        if (selectedIndices.isEmpty() || selectedIndices.last() != points.size - 1) {
            selectedIndices.add(points.size - 1)
        }

        val observations = selectedIndices.mapIndexed { i, idx ->
            val p = points[idx]
            DegradedObservation(
                index = i,
                lat = p.lat,
                lon = p.lon,
                timestampEpochMs = p.timestampEpochMs,
                accuracyMeters = p.accuracyMeters,
                bearingDegrees = p.bearingDegrees,
                speedMps = p.speedMps,
                intentLabel = "NORMAL",
            )
        }

        return DegradedObservationSet(
            referenceTraceId = reference.id,
            variant = variantLabel,
            mode = mode,
            spec = spec,
            observations = observations,
        )
    }
}
