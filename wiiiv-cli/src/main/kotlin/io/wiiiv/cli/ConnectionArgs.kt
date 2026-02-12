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
    fun credentialKey(): String = "$host:$port"

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
