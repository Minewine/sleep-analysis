package com.sleepanalysis.domain.policy

/**
 * The single gate between stored measurements and what the UI/exports may show
 * (docs/ARCHITECTURE.md §6.5). Keep in sync with docs/MEASUREMENT_RELIABILITY.md §2 —
 * ClaimPolicyTest fails the build on drift.
 */
object ClaimPolicy {

    fun tierFor(type: MeasurementType): MeasurementTier = type.tier

    /** Tier-3 measurements must never be rendered — and never exist. Defense in depth. */
    fun displayAllowed(type: MeasurementType): Boolean =
        tierFor(type) != MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS

    /** Tier-2 values render only behind the experimental toggle and always carry a badge. */
    fun displayAllowed(type: MeasurementType, experimentalToggleEnabled: Boolean): Boolean =
        when (tierFor(type)) {
            MeasurementTier.ESTABLISHED -> true
            MeasurementTier.EXPERIMENTAL -> experimentalToggleEnabled
            MeasurementTier.OUT_OF_SCOPE_FOR_CLAIMS -> false
        }
}
