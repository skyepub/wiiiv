package io.wiiiv.shell.commands

/**
 * 경로 해석 유틸리티 — ~, Git Bash 스타일(/c/...) 처리
 *
 * ControlCommands, RagCommands 등에서 공유한다.
 */
fun resolvePath(path: String): String {
    var resolved = path

    // ~ → 홈 디렉토리
    if (resolved.startsWith("~")) {
        resolved = resolved.replaceFirst("~", System.getProperty("user.home"))
    }

    // Git Bash 스타일: /c/Users/... → C:/Users/...
    val msysRegex = "^/([a-zA-Z])/(.*)".toRegex()
    val msysMatch = msysRegex.matchEntire(resolved)
    if (msysMatch != null) {
        resolved = "${msysMatch.groupValues[1].uppercase()}:/${msysMatch.groupValues[2]}"
    }

    return resolved
}
