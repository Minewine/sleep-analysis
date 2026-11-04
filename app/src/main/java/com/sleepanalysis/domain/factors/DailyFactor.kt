package com.sleepanalysis.domain.factors

/**
 * Self-logged daily lifestyle factors (docs/DATA_MODEL.md §3.6). These are USER-entered
 * context for the nightly report — deliberately NOT measurements: no tier, no confidence,
 * no Measurement rows, and they never feed scores. Phase 4's analysis engine may correlate
 * them with measurements later, with explicit "user-reported" framing.
 */
enum class DailyFactor(val key: String, val label: String, val kind: Kind) {
    ALCOHOL("ALCOHOL", "Alcohol", Kind.BOOLEAN_WITH_UNITS),
    LATE_MEAL("LATE_MEAL", "Late meal", Kind.BOOLEAN),
    CAFFEINE_AFTER_14("CAFFEINE_AFTER_14", "Caffeine after 14:00", Kind.BOOLEAN),
    EXERCISE("EXERCISE", "Exercise", Kind.BOOLEAN),
    MEDICATION_OR_DEVICE("MEDICATION_OR_DEVICE", "Medication / breathing device used", Kind.BOOLEAN),
    STRESS("STRESS", "Stress level", Kind.SCALE_1_5),
    NOTES("NOTES", "Notes", Kind.FREE_TEXT);

    enum class Kind { BOOLEAN, BOOLEAN_WITH_UNITS, SCALE_1_5, FREE_TEXT }

    companion object {
        fun fromKey(key: String): DailyFactor? = entries.firstOrNull { it.key == key }
    }
}
