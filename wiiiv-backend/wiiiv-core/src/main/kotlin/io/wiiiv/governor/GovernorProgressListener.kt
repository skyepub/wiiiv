package io.wiiiv.governor

/**
 * Governor 실행 진행 리스너
 *
 * Shell 등 UI에서 구현하여 실시간 피드백 제공
 */
fun interface GovernorProgressListener {
    fun onProgress(event: ProgressEvent)
}

data class ProgressEvent(
    val phase: ProgressPhase,
    val detail: String? = null,
    val stepIndex: Int? = null,
    val totalSteps: Int? = null
)

enum class ProgressPhase {
    LLM_THINKING,
    DACS_EVALUATING,
    BLUEPRINT_CREATING,
    EXECUTING,
    COMMAND_RUNNING,
    IMAGE_ANALYZING,
    DONE
}
