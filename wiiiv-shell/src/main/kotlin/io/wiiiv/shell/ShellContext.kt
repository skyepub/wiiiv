package io.wiiiv.shell

import io.wiiiv.governor.ConversationSession
import io.wiiiv.governor.ConversationalGovernor
import java.io.BufferedReader

/**
 * Shell 레벨 설정 — Governor/DACS 내부는 변경 불가
 */
data class ShellSettings(
    var autoContinue: Boolean = true,
    var maxContinue: Int = 10,
    var verbose: Boolean = false,
    var color: Boolean = true
)

/**
 * 슬래시 명령 핸들러에 전달할 컨텍스트
 *
 * Governor의 private 필드(model, dacs 타입)는 Main.kt에서 캡처한 값을 사용한다.
 */
data class ShellContext(
    val governor: ConversationalGovernor,
    val sessionId: String,
    val session: ConversationSession,
    val reader: BufferedReader,
    val modelName: String?,
    val dacsTypeName: String,
    val llmProviderPresent: Boolean,
    val settings: ShellSettings
) {
    /**
     * y/N 확인 프롬프트 — `/cancel all` 등에서 사용
     */
    fun confirm(prompt: String): Boolean {
        print("$prompt (y/N) ")
        System.out.flush()
        val answer = reader.readLine()?.trim()?.lowercase()
        return answer == "y" || answer == "yes"
    }
}
