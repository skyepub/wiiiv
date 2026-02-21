package io.wiiiv.plugins.webhook

import java.net.InetAddress
import java.net.URI

/**
 * SSRF Guard — Server-Side Request Forgery 기본 방어
 *
 * D-1 범위:
 * - blocked scheme (file, ftp, gopher, jar)
 * - cloud metadata IP 차단 (169.254.169.254 등)
 * - private IP 차단 (설정 가능)
 * - localhost 허용 옵션 (개발/테스트 편의)
 *
 * DNS 기반 private IP 탐지는 D-1에서는 host가 IP인 경우만 판정.
 * (DNS resolve 기반 판정은 후속 Phase에서 추가)
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

    /**
     * URL 검증. 통과 시 null, 차단 시 오류 코드 문자열 반환.
     */
    fun validate(url: String): String? {
        val parsed = try {
            URI(url)
        } catch (_: Exception) {
            return "INVALID_URL"
        }

        // scheme 차단
        val scheme = parsed.scheme?.lowercase()
        if (scheme in blockedSchemes) return "BLOCKED_SCHEME: $scheme"
        if (scheme != "http" && scheme != "https") return "UNSUPPORTED_SCHEME: $scheme"

        val host = parsed.host ?: return "MISSING_HOST"

        // cloud metadata IP 차단
        if (host in metadataIps) return "BLOCKED_METADATA_IP"

        // localhost 체크
        if (isLocalhost(host)) {
            return if (allowLocalhost) null else "BLOCKED_LOCALHOST"
        }

        // private IP 체크 (host가 IP인 경우만 판정)
        if (!allowPrivateIp && isPrivateIp(host)) {
            return "BLOCKED_PRIVATE_IP"
        }

        return null // 통과
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
            false // DNS인 경우 D-1에서는 통과 (후속에서 resolve 추가)
        }
    }
}
