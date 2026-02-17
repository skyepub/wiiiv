package io.wiiiv.cli

import io.wiiiv.cli.client.ExecutionSummaryDto
import io.wiiiv.cli.client.ServerInfoDto
import io.wiiiv.cli.client.WiiivApiClient

/**
 * Shell 레벨 설정 — 서버 측 변경 불가, CLI 로컬 설정
 */
data class ShellSettings(
    var autoContinue: Boolean = true,
    var maxContinue: Int = 10,
    var verbose: Int = 1,
    var color: Boolean = true,
    var workspace: String? = null
) {
    companion object {
        val VERBOSE_NAMES = arrayOf("quiet", "normal", "detailed", "debug")

        fun verboseName(level: Int): String =
            VERBOSE_NAMES.getOrElse(level) { "unknown" }

        fun verboseFromName(name: String): Int? =
            VERBOSE_NAMES.indexOfFirst { it == name.lowercase() }.takeIf { it >= 0 }
    }
}

/**
 * 슬래시 명령 핸들러에 전달할 컨텍스트
 *
 * core 타입 대신 WiiivApiClient를 사용한다.
 */
data class ShellContext(
    val client: WiiivApiClient,
    val sessionId: String,
    val inputReader: ShellInputReader,
    val settings: ShellSettings,
    val serverInfo: ServerInfoDto,
    var lastExecutionSummary: ExecutionSummaryDto? = null
) {
    /**
     * y/N 확인 프롬프트 — `/cancel all` 등에서 사용
     */
    fun confirm(prompt: String): Boolean {
        val answer = inputReader.readSimpleLine("$prompt (y/N) ")?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }
}
