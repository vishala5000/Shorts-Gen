package com.shortsgen.app.model

/** One rendered clip. */
data class GeneratedVideo(
    val index: Int,
    val line: String,
    val path: String,
    val sizeBytes: Long,
    val durationMs: Long
) {
    val fileName: String get() = path.substringAfterLast('/')
}

/** Everything the UI needs to render the current state of a run. */
sealed interface GenState {

    data object Idle : GenState

    data class Working(
        val step: String,
        val currentIndex: Int,
        val total: Int,
        val progress: Float
    ) : GenState

    data class Done(
        val zipPath: String,
        val videos: List<GeneratedVideo>,
        val totalMs: Long
    ) : GenState

    data class Failed(val message: String) : GenState
}
