package io.wiiiv.server.config

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import java.util.*

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
    val roles: List<String>
) : Principal

fun Application.configureAuthentication() {
    install(Authentication) {
        jwt("auth-jwt") {
            verifier(JwtConfig.verifier)
            validate { credential ->
                val userId = credential.payload.getClaim("userId").asString()
                val roles = credential.payload.getClaim("roles").asList(String::class.java) ?: listOf("user")
                if (userId != null) {
                    UserPrincipal(userId, roles)
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
    }
}
