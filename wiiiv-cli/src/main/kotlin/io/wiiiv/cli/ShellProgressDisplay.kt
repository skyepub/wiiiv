package io.wiiiv.cli

import io.wiiiv.cli.client.ProgressEventDto
import io.wiiiv.cli.model.CliProgressPhase
import java.util.Timer
import java.util.TimerTask

/**
 * Shell 진행 표시 — 스피너 애니메이션
 *
 * 서버에서 수신한 SSE progress 이벤트를 표시한다.
 * verboseLevel: 0=quiet(스피너 없음), 1=normal, 2=detailed, 3=debug(줄바꿈 로그)
 */
class ShellProgressDisplay(
    var verboseLevel: Int = 1
) {
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

    // 터미널 너비 캐시 (Windows/Linux 모두 지원)
    private val termWidth: Int by lazy { detectTerminalWidth() }

    private fun detectTerminalWidth(): Int {
        // 1. 환경변수 COLUMNS
        System.getenv("COLUMNS")?.toIntOrNull()?.let { return it }

        // 2. tput cols (Linux/macOS)
        try {
            val proc = ProcessBuilder("tput", "cols").redirectErrorStream(true).start()
            val result = proc.inputStream.bufferedReader().readLine()?.trim()?.toIntOrNull()
            proc.waitFor()
            if (result != null && result > 0) return result
        } catch (_: Exception) {}

        // 3. mode con (Windows)
        try {
            val proc = ProcessBuilder("cmd", "/c", "mode", "con").redirectErrorStream(true).start()
            val output = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            val match = Regex("""Columns:\s*(\d+)""").find(output)
            match?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        } catch (_: Exception) {}

        return 80
    }

    // ANSI: 현재 줄 전체 지우기 + 커서를 1열로
    private val CLEAR_LINE = "\u001b[2K\u001b[1G"

    /**
     * 서버에서 수신한 progress 이벤트 처리
     */
    fun onServerProgress(event: ProgressEventDto) {
        val phase = CliProgressPhase.fromString(event.phase)
        val stepInfo = if (event.totalSteps != null) " (${event.totalSteps} steps)" else ""
        val detail = event.detail?.let { " — $it" } ?: ""

        val (color, label) = when (phase) {
            CliProgressPhase.LLM_THINKING -> c.BRIGHT_CYAN to "Thinking"
            CliProgressPhase.DACS_EVALUATING -> c.YELLOW to "DACS Evaluating"
            CliProgressPhase.BLUEPRINT_CREATING -> c.BRIGHT_BLUE to "Blueprint Creating"
            CliProgressPhase.EXECUTING -> c.GREEN to "Executing"
            CliProgressPhase.COMMAND_RUNNING -> c.DIM to "Command Running"
            CliProgressPhase.IMAGE_ANALYZING -> c.BRIGHT_CYAN to "Image Analyzing"
            CliProgressPhase.DONE -> c.BRIGHT_GREEN to "Done"
        }

        if (phase == CliProgressPhase.DONE) {
            stopSpinner()
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val timeStr = String.format("%.1fs", elapsed)
            if (verboseLevel >= 2) {
                println("  ${c.BRIGHT_GREEN}> Done${stepInfo}${detail}  [${timeStr}]${c.RESET}")
            } else {
                print("${CLEAR_LINE}${c.BRIGHT_GREEN}  > ${c.RESET}${c.DIM}Done${stepInfo}${detail}  [${timeStr}]${c.RESET}")
                println()
            }
            hasActiveProgress = false
            return
        }

        // 현재 상태 업데이트
        currentColor = color
        currentLabel = label
        currentStepInfo = stepInfo
        currentDetail = detail
        hasActiveProgress = true

        if (verboseLevel >= 2) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            val timeStr = String.format("%.1fs", elapsed)
            println("  ${color}> ${label}${stepInfo}${detail}  [${timeStr}]${c.RESET}")
        } else {
            startSpinner()
            renderFrame()
        }
    }

    private fun renderFrame() {
        val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
        val timeStr = String.format("%.1fs", elapsed)
        val frame = spinnerFrames[spinnerIndex % spinnerFrames.size]

        val prefix = "  $frame "
        val body = "${currentLabel}${currentStepInfo}${currentDetail}  [${timeStr}]"
        val maxBody = termWidth - prefix.length - 2
        val truncated = if (body.length > maxBody && maxBody > 3) body.take(maxBody - 3) + "..." else body

        print("${CLEAR_LINE}${currentColor}${prefix}${c.RESET}${c.DIM}${truncated}${c.RESET}")
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
            }, 100, 100)
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
