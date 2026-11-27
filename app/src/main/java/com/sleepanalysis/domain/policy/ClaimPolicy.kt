package com.sleepanalysis.domain.policy

/**
 * The single gate between stored measurements and what the UI/exports may show
 * (docs/ARCHITECTURE.md §6.5). Keep in sync with docs/MEASUREMENT_RELIABILITY.md §2 —
 * ClaimPolicyTest fails the build on drift.
 */
object ClaimPolicy {

    fun tierFor(type: MeasurementType): MeasurementTier = type.tier
}
