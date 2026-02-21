package io.wiiiv.plugins.webfetch

import java.net.InetAddress
import java.net.URI

/**
 * SSRF Guard — Server-Side Request Forgery 기본 방어
 *
 * webhook 플러그인에서 복사 (D-2 범위, core 이동은 D-3)
 *
 * @property allowPrivateIp private IP 허용 여부 (기본 false)
 * @property allowLocalhost localhost/127.0.0.1 허용 여부 (기본 true — 테스트 편의)
 */
class SsrfGuard(
    private val allowPrivateIp: Boolean = false,
    private val allowLocalhost: Boolean = true
) {
    private val blockedSchemes = setOf("file", "ftp", "gopher", "jar", "data", "dict")
    private val metadataIps = setOf("169.254.169.254", "fd00:ec2::254", "metadata.google.internal")

    fun validate(url: String): String? {
        val parsed = try {
            URI(url)
        } catch (_: Exception) {
            return "INVALID_URL"
        }

        val scheme = parsed.scheme?.lowercase()
        if (scheme in blockedSchemes) return "BLOCKED_SCHEME: $scheme"
        if (scheme != "http" && scheme != "https") return "UNSUPPORTED_SCHEME: $scheme"

        val host = parsed.host ?: return "MISSING_HOST"

        if (host in metadataIps) return "BLOCKED_METADATA_IP"

        if (isLocalhost(host)) {
            return if (allowLocalhost) null else "BLOCKED_LOCALHOST"
        }

        if (!allowPrivateIp && isPrivateIp(host)) {
            return "BLOCKED_PRIVATE_IP"
        }

        return null
    }

    private fun isLocalhost(host: String): Boolean {
        return host == "localhost" ||
                host == "127.0.0.1" ||
                host == "::1" ||
                host == "[::1]"
    }

    private fun isPrivateIp(host: String): Boolean {
        return try {
            val addr = InetAddress.getByName(host)
            addr.isSiteLocalAddress || addr.isLoopbackAddress || addr.isLinkLocalAddress
        } catch (_: Exception) {
            false
        }
    }
}
