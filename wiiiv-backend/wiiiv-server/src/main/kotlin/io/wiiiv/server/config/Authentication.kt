package io.wiiiv.server.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.wiiiv.config.AuthType
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import java.security.MessageDigest
import java.time.Instant
import java.util.*

const val AUTH_JWT = "auth-jwt"
const val AUTH_APIKEY = "auth-apikey"

object JwtConfig {
    private val secret = System.getenv("JWT_SECRET") ?: "wiiiv-dev-secret-key-change-in-production"
    private val issuer = System.getenv("JWT_ISSUER") ?: "wiiiv"
    private val audience = System.getenv("JWT_AUDIENCE") ?: "wiiiv-api"
    private val algorithm = Algorithm.HMAC256(secret)
    private val validityMs = 3600_000L * 24 // 24 hours

    val verifier = JWT.require(algorithm)
        .withIssuer(issuer)
        .withAudience(audience)
        .build()

    fun generateToken(userId: String, roles: List<String> = listOf("user")): String {
        return JWT.create()
            .withIssuer(issuer)
            .withAudience(audience)
            .withClaim("userId", userId)
            .withClaim("roles", roles)
            .withExpiresAt(Date(System.currentTimeMillis() + validityMs))
            .sign(algorithm)
    }
}

data class UserPrincipal(
    val userId: String,
    val roles: List<String>,
    val projectId: Long? = null,
    val authType: AuthType = AuthType.JWT
) : Principal

fun Application.configureAuthentication() {
    install(Authentication) {
        jwt(AUTH_JWT) {
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                val roles = credential.payload.getClaim("roles").asList(String::class.java) ?: listOf("user")
                if (userId != null) {
                    UserPrincipal(userId, roles, authType = AuthType.JWT)
                } else {
                    null
                }
            }
            challenge { _, _ ->
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>(
                        ApiError(
                            code = "UNAUTHORIZED",
                            message = "Token is not valid or has expired"
                        )
                    )
                )
            }
        }

        // API Key authentication
        register(ApiKeyAuthProvider(ApiKeyAuthProvider.Config(AUTH_APIKEY)))
    }
}

/**
 * X-API-Key 헤더 기반 인증 프로바이더.
 *
 * 흐름:
 * 1. X-API-Key 헤더 추출 (없으면 skip → 다음 프로바이더에 위임)
 * 2. SHA-256 해시 → api_keys 테이블 조회
 * 3. 만료/비활성 체크 (PlatformStore 내부에서 처리)
 * 4. project_members에서 role 조회
 * 5. UserPrincipal(userId, roles, projectId, "API_KEY") 생성
 */
class ApiKeyAuthProvider(config: Config) : AuthenticationProvider(config) {

    class Config(name: String?) : AuthenticationProvider.Config(name)

    override suspend fun onAuthenticate(context: AuthenticationContext) {
        val call = context.call
        val apiKey = call.request.headers["X-API-Key"] ?: return // 헤더 없으면 skip

        val store = WiiivRegistry.platformStore
        if (store == null) {
            context.challenge("ApiKey", AuthenticationFailedCause.Error("Platform store unavailable")) { challenge, _ ->
                context.call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<Unit>(
                        ApiError(code = "PLATFORM_UNAVAILABLE", message = "Platform store not initialized")
                    )
                )
                challenge.complete()
            }
            return
        }

        val hash = sha256Hex(apiKey)
        val record = store.findByApiKeyHash(hash)

        if (record == null) {
            context.challenge("ApiKey", AuthenticationFailedCause.InvalidCredentials) { challenge, _ ->
                context.call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>(
                        ApiError(code = "INVALID_API_KEY", message = "API key is invalid, expired, or revoked")
                    )
                )
                challenge.complete()
            }
            return
        }

        // role 조회 (멤버가 아닌 경우 기본 MEMBER)
        val member = store.findMember(record.projectId, record.userId)
        val roles = if (member != null) listOf(member.role.name) else listOf("MEMBER")

        context.principal(
            UserPrincipal(
                userId = record.userId.toString(),
                roles = roles,
                projectId = record.projectId,
                authType = AuthType.API_KEY
            )
        )
    }

    companion object {
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
