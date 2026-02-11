package io.wiiiv.shell

import io.wiiiv.governor.GovernorProgressListener
import io.wiiiv.governor.ProgressEvent
import io.wiiiv.governor.ProgressPhase
import java.util.Timer
import java.util.TimerTask

/**
 * Shell 진행 표시 — 스피너 애니메이션
 *
 * Governor 실행 단계마다 실시간으로 진행 상황을 표시한다.
 * 이벤트 사이 구간에도 스피너가 돌아서 시스템이 살아있음을 보여준다.
 */
class ShellProgressDisplay : GovernorProgressListener {
    private var startTime = System.currentTimeMillis()
    private var hasActiveProgress = false
    private val c = ShellColors

    // 스피너 상태
    private val spinnerFrames = arrayOf("⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏")
    private var spinnerIndex = 0
    private var timer: Timer? = null

    // 현재 표시 중인 정보
    @Volatile private var currentColor = ""
    @Volatile private var currentLabel = ""
    @Volatile private var currentStepInfo = ""
    @Volatile private var currentDetail = ""

    override fun onProgress(event: ProgressEvent) {
        val stepInfo = if (event.totalSteps != null) " (${event.totalSteps} steps)" else ""
        val detail = event.detail?.let { " — $it" } ?: ""

        val (_, color, label) = when (event.phase) {
            ProgressPhase.LLM_THINKING -> Triple("", c.BRIGHT_CYAN, "LLM 판단 중")
            ProgressPhase.DACS_EVALUATING -> Triple("", c.YELLOW, "DACS 합의 평가")
            ProgressPhase.BLUEPRINT_CREATING -> Triple("", c.BRIGHT_BLUE, "Blueprint 생성")
            ProgressPhase.EXECUTING -> Triple("", c.GREEN, "실행 중")
            ProgressPhase.COMMAND_RUNNING -> Triple("", c.DIM, "명령 실행")
            ProgressPhase.IMAGE_ANALYZING -> Triple("", c.BRIGHT_CYAN, "이미지 분석")
            ProgressPhase.DONE -> Triple("", c.BRIGHT_GREEN, "완료")
        }

        if (event.phase == ProgressPhase.DONE) {
            stopSpinner()
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val timeStr = String.format("%.1fs", elapsed)
            print("\r${c.BRIGHT_GREEN}  > ${c.RESET}${c.DIM}완료${stepInfo}${detail}  [${timeStr}]${c.RESET}    ")
            println()
            hasActiveProgress = false
            return
        }

        // 현재 상태 업데이트
        currentColor = color
        currentLabel = label
        currentStepInfo = stepInfo
        currentDetail = detail
        hasActiveProgress = true

        // 스피너 시작 (아직 안 돌고 있으면)
        startSpinner()

        // 즉시 한번 표시
        renderFrame()
    }

    private fun renderFrame() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val timeStr = String.format("%.1fs", elapsed)
        val frame = spinnerFrames[spinnerIndex % spinnerFrames.size]

        print("\r${currentColor}  $frame ${c.RESET}${c.DIM}${currentLabel}${currentStepInfo}${currentDetail}  [${timeStr}]${c.RESET}    ")
        System.out.flush()
    }

    private fun startSpinner() {
        if (timer != null) return
        spinnerIndex = 0
        timer = Timer("progress-spinner", true).also { t ->
            t.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    if (hasActiveProgress) {
                        spinnerIndex++
                        renderFrame()
                    }
                }
            }, 80, 80)
        }
    }

    private fun stopSpinner() {
        timer?.cancel()
        timer = null
    }

    fun ensureNewline() {
        stopSpinner()
        if (hasActiveProgress) {
            println()
            hasActiveProgress = false
        }
    }

    fun reset() {
        stopSpinner()
        startTime = System.currentTimeMillis()
        hasActiveProgress = false
    }
}
