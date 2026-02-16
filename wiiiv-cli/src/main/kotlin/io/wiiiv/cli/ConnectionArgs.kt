package io.wiiiv.cli

/**
 * SSH 스타일 접속 인자 파서.
 *
 * 파싱 규칙:
 * - (빈 인자)            → auto-login, localhost:8235
 * - admin                → username=admin, localhost:8235
 * - admin@192.168.1.10   → username=admin, host=192.168.1.10, port=8235
 * - admin@192.168.1.10:9000 → username=admin, host=192.168.1.10, port=9000
 */
data class ConnectionArgs(
    val username: String? = null,
    val host: String = "localhost",
    val port: Int = 8235,
    val isAutoLogin: Boolean = true
) {
    fun toServerUrl(): String = "http://$host:$port"
    /**
     * credential 저장 키 — 같은 서버면 IP가 달라도 동일 키를 사용한다.
     * localhost, 127.0.0.1, WSL IP 등은 모두 "localhost:포트"로 정규화.
     */
    fun credentialKey(): String {
        val normalized = if (host == "127.0.0.1" || isPrivateOrLocal(host)) "localhost" else host
        return "$normalized:$port"
    }

    private fun isPrivateOrLocal(h: String): Boolean {
        // WSL IP(172.x), 내부망(10.x, 192.168.x) → 동일 머신으로 간주
        return h.startsWith("172.") || h.startsWith("10.") || h.startsWith("192.168.")
    }

    companion object {
        fun parse(args: Array<String>): ConnectionArgs {
            if (args.isEmpty()) return ConnectionArgs()

            val target = args[0]

            // user@host 또는 user@host:port
            if ('@' in target) {
                val (user, hostPart) = target.split('@', limit = 2)
                return if (':' in hostPart) {
                    val (h, p) = hostPart.split(':', limit = 2)
                    val port = p.toIntOrNull() ?: 8235
                    ConnectionArgs(username = user, host = h, port = port, isAutoLogin = false)
                } else {
                    ConnectionArgs(username = user, host = hostPart, isAutoLogin = false)
                }
            }

            // username만 (localhost)
            return ConnectionArgs(username = target, isAutoLogin = false)
        }
    }
}
