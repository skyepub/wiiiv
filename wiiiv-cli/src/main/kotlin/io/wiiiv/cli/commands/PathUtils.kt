package io.wiiiv.cli.commands

import java.io.File

/**
 * WSL 환경 감지 (한 번만 체크)
 */
private val IS_WSL: Boolean by lazy {
    try {
        File("/proc/version").readText().contains("microsoft", ignoreCase = true)
    } catch (_: Exception) {
        false
    }
}

/**
 * 경로 해석 유틸리티 — ~, Git Bash 스타일(/c/...), WSL 자동 변환
 *
 * ControlCommands, RagCommands, ImageInputParser 등에서 공유한다.
 *
 * WSL 환경에서는 Windows 경로(C:\Users\...)가 들어오면
 * 먼저 원본을 시도하고, 파일이 없으면 /mnt/c/... 로 변환하여 재시도한다.
 */
fun resolvePath(path: String): String {
    var resolved = path.trim()

    // ~ → 홈 디렉토리
    if (resolved.startsWith("~")) {
        resolved = resolved.replaceFirst("~", System.getProperty("user.home"))
    }

    // Git Bash 스타일: /c/Users/... → C:/Users/...  (Git Bash에서만 의미 있음)
    if (!IS_WSL) {
        val msysRegex = "^/([a-zA-Z])/(.*)".toRegex()
        val msysMatch = msysRegex.matchEntire(resolved)
        if (msysMatch != null) {
            resolved = "${msysMatch.groupValues[1].uppercase()}:/${msysMatch.groupValues[2]}"
        }
    }

    // WSL: Windows 경로 → /mnt/ 경로 변환 시도
    if (IS_WSL) {
        resolved = wslResolvePath(resolved)
    }

    return resolved
}

/**
 * WSL 환경에서 Windows 경로를 /mnt/ 경로로 변환
 *
 * 1) 원본 경로가 존재하면 그대로 반환
 * 2) Windows 절대 경로(C:\... 또는 C:/...)면 /mnt/c/... 로 변환
 * 3) Git Bash 스타일(/c/...)이면 /mnt/c/... 로 변환
 * 4) 변환된 경로가 존재하면 변환 경로 반환, 아니면 변환 경로를 기본으로 반환
 *    (아직 만들어질 파일일 수 있으므로)
 */
private fun wslResolvePath(path: String): String {
    // 원본이 이미 존재하면 그대로
    if (File(path).exists()) return path

    // Windows 절대 경로: C:\Users\... 또는 C:/Users/...
    val winRegex = "^([a-zA-Z]):[/\\\\](.*)".toRegex()
    val winMatch = winRegex.matchEntire(path)
    if (winMatch != null) {
        val drive = winMatch.groupValues[1].lowercase()
        val rest = winMatch.groupValues[2].replace('\\', '/')
        val wslPath = "/mnt/$drive/$rest"
        return if (File(wslPath).exists()) wslPath else wslPath
    }

    // Git Bash 스타일: /c/Users/... → /mnt/c/Users/...
    val msysRegex = "^/([a-zA-Z])/(.*)".toRegex()
    val msysMatch = msysRegex.matchEntire(path)
    if (msysMatch != null) {
        val drive = msysMatch.groupValues[1].lowercase()
        val rest = msysMatch.groupValues[2]
        val wslPath = "/mnt/$drive/$rest"
        return if (File(wslPath).exists()) wslPath else wslPath
    }

    return path
}
