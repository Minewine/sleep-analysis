package com.sleepanalysis.domain.labels

/**
 * The five user labels for acoustic events, applied from clip playback
 * (docs/DATA_MODEL.md §3.5). This is the personal labeled dataset Phase 2 model improvement
 * trains against — user ground truth, never a measurement, never altering Phase 1 scores.
 */
enum class EventUserLabel(val label: String) {
    SNORE("Snore"),
    COUGH("Cough"),
    SPEECH("Speech"),
    MOVEMENT("Movement"),
    OTHER_NOT_SNORE("Other / Not snore");

    companion object {
        fun fromKey(key: String?): EventUserLabel? = entries.firstOrNull { it.name == key }
    }
}
