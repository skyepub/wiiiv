package io.wiiiv.shell

import io.wiiiv.governor.GovernorProgressListener
import io.wiiiv.governor.ProgressEvent
import io.wiiiv.governor.ProgressPhase

/**
 * Shell 진행 표시 — >>> 스타일 애니메이션
 *
 * Governor 실행 단계마다 실시간으로 진행 상황을 표시한다.
 */
class ShellProgressDisplay : GovernorProgressListener {
    private var startTime = System.currentTimeMillis()
    private var hasActiveProgress = false
    private val c = ShellColors

    override fun onProgress(event: ProgressEvent) {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val timeStr = String.format("%.1fs", elapsed)

        val (icon, color, label) = when (event.phase) {
            ProgressPhase.LLM_THINKING -> Triple(">>>", c.BRIGHT_CYAN, "LLM 판단 중")
            ProgressPhase.DACS_EVALUATING -> Triple(">>>", c.YELLOW, "DACS 합의 평가")
            ProgressPhase.BLUEPRINT_CREATING -> Triple(">>>", c.BRIGHT_BLUE, "Blueprint 생성")
            ProgressPhase.EXECUTING -> Triple(">>>", c.GREEN, "실행 중")
            ProgressPhase.COMMAND_RUNNING -> Triple(" >>", c.DIM, "명령 실행")
            ProgressPhase.DONE -> Triple("  >", c.BRIGHT_GREEN, "완료")
        }

        val stepInfo = if (event.stepIndex != null && event.totalSteps != null) {
            " (${event.totalSteps} steps)"
        } else ""

        val detail = event.detail?.let { " — $it" } ?: ""

        print("\r${color}${icon}${c.RESET} ${label}${stepInfo}${detail}  ${c.DIM}[${timeStr}]${c.RESET}    ")
        System.out.flush()
        hasActiveProgress = true

        if (event.phase == ProgressPhase.DONE) {
            println()
            hasActiveProgress = false
        }
    }

    /**
     * progress 출력 후 줄바꿈 보장 — chat() 반환 후 wiiiv>가 새 줄에서 시작하도록
     */
    fun ensureNewline() {
        if (hasActiveProgress) {
            println()
            hasActiveProgress = false
        }
    }

    fun reset() {
        startTime = System.currentTimeMillis()
        hasActiveProgress = false
    }
}
