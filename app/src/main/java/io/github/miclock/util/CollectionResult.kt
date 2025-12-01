package io.github.miclock.util

import android.content.Intent

/**
 * Represents the result of a debug log collection operation.
 * Provides detailed information about success, partial success, or failure scenarios.
 */
sealed class CollectionResult {
    /**
     * All diagnostic data was collected successfully.
     * @param shareIntent Intent configured to share the diagnostic package
     * @param warnings Optional list of non-critical warnings that occurred during collection
     */
    data class Success(
        val shareIntent: Intent,
        val warnings: List<String> = emptyList(),
    ) : CollectionResult()

    /**
     * Some diagnostic data was collected, but some components failed.
     * @param shareIntent Intent configured to share the partial diagnostic package
     * @param failures List of components that failed during collection
     */
    data class PartialSuccess(
        val shareIntent: Intent,
        val failures: List<CollectionFailure>,
    ) : CollectionResult()

    /**
     * Critical failure occurred and no viable diagnostic data could be collected.
     * @param error Human-readable error message describing the failure
     * @param failures List of all components that failed during collection
     */
    data class Failure(
        val error: String,
        val failures: List<CollectionFailure>,
    ) : CollectionResult()
}

/**
 * Represents a failure that occurred during diagnostic data collection.
 * @param component Name of the component that failed (e.g., "dumpsys audio", "logcat")
 * @param error Human-readable error message
 * @param isCritical Whether this failure prevents sharing any diagnostic data
 * @param timestamp Unix timestamp (milliseconds) when the failure occurred
 */
data class CollectionFailure(
    val component: String,
    val error: String,
    val isCritical: Boolean,
    val timestamp: Long = System.currentTimeMillis(),
)
