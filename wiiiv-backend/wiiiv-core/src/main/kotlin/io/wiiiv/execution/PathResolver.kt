package io.wiiiv.execution

import java.io.File

/**
 * 플랫폼별 경로 해석 유틸리티
 *
 * WSL 환경에서 Windows 경로(C:\Users\...)가 들어오면
 * /mnt/c/Users/... 로 자동 변환한다.
 */
object PathResolver {

    private val IS_WSL: Boolean by lazy {
        try {
            File("/proc/version").readText().contains("microsoft", ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 경로를 현재 플랫폼에 맞게 해석한다.
     *
     * - WSL에서 Windows 경로 → /mnt/드라이브/... 변환
     * - 원본이 존재하면 원본 그대로 반환
     */
    fun resolve(path: String): String {
        if (!IS_WSL) return path

        // 원본이 이미 존재하면 그대로
        if (File(path).exists()) return path

        // Windows 절대 경로: C:\Users\... 또는 C:/Users/...
        val winRegex = "^([a-zA-Z]):[/\\\\](.*)".toRegex()
        val winMatch = winRegex.matchEntire(path)
        if (winMatch != null) {
            val drive = winMatch.groupValues[1].lowercase()
            val rest = winMatch.groupValues[2].replace('\\', '/')
            return "/mnt/$drive/$rest"
        }

        // Git Bash 스타일: /c/Users/... → /mnt/c/Users/...
        val msysRegex = "^/([a-zA-Z])/(.*)".toRegex()
        val msysMatch = msysRegex.matchEntire(path)
        if (msysMatch != null) {
            val drive = msysMatch.groupValues[1].lowercase()
            val rest = msysMatch.groupValues[2]
            return "/mnt/$drive/$rest"
        }

        return path
    }
}
