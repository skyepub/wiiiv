package io.wiiiv.cli

/**
 * ANSI 색상 상수
 *
 * `enabled` 플래그로 `/set color off` 시 즉시 비활성화.
 * 모든 색상 값은 get() 프로퍼티이므로 enabled 변경 즉시 반영된다.
 */
object ShellColors {
    var enabled: Boolean = true

    val CYAN get() = if (enabled) "\u001B[36m" else ""
    val BRIGHT_CYAN get() = if (enabled) "\u001B[96m" else ""
    val WHITE get() = if (enabled) "\u001B[97m" else ""
    val DIM get() = if (enabled) "\u001B[2m" else ""
    val RESET get() = if (enabled) "\u001B[0m" else ""
    val BOLD get() = if (enabled) "\u001B[1m" else ""
    val YELLOW get() = if (enabled) "\u001B[33m" else ""
    val RED get() = if (enabled) "\u001B[31m" else ""
    val GREEN get() = if (enabled) "\u001B[32m" else ""
    val BLUE get() = if (enabled) "\u001B[34m" else ""
    val BRIGHT_BLUE get() = if (enabled) "\u001B[94m" else ""
    val MAGENTA get() = if (enabled) "\u001B[35m" else ""
    val BRIGHT_MAGENTA get() = if (enabled) "\u001B[95m" else ""
    val BRIGHT_YELLOW get() = if (enabled) "\u001B[93m" else ""
    val BRIGHT_GREEN get() = if (enabled) "\u001B[92m" else ""
}
