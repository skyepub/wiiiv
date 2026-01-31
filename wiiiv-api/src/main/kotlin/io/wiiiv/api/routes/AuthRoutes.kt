package io.wiiiv.api.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.api.config.JwtConfig
import io.wiiiv.api.config.UserPrincipal
import io.wiiiv.api.dto.auth.LoginRequest
import io.wiiiv.api.dto.auth.LoginResponse
import io.wiiiv.api.dto.auth.UserInfo
import io.wiiiv.api.dto.common.ApiResponse

fun Route.authRoutes() {
    route("/auth") {
        // Auto-login for dev mode
        get("/auto-login") {
            val devMode = System.getenv("WIIIV_ENV") != "production"
            if (!devMode) {
                call.respond(
                    HttpStatusCode.Forbidden,
                    ApiResponse.error<LoginResponse>(
                        io.wiiiv.api.dto.common.ApiError(
                            code = "DEV_ONLY",
                            message = "Auto-login is only available in dev mode"
                        )
                    )
                )
                return@get
            }

            val token = JwtConfig.generateToken("dev-user", listOf("admin"))
            call.respond(
                ApiResponse.success(
                    LoginResponse(accessToken = token)
                )
            )
        }

        // Manual login
        post("/login") {
            val request = call.receive<LoginRequest>()

            // TODO: Implement actual user validation
            // For now, simple check for demo
            if (request.username == "admin" && request.password == "admin123") {
                val token = JwtConfig.generateToken(request.username, listOf("admin"))
                call.respond(
                    ApiResponse.success(
                        LoginResponse(accessToken = token)
                    )
                )
            } else {
                call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<LoginResponse>(
                        io.wiiiv.api.dto.common.ApiError(
                            code = "INVALID_CREDENTIALS",
                            message = "Invalid username or password"
                        )
                    )
                )
            }
        }

        // Get current user info (authenticated)
        authenticate("auth-jwt") {
            get("/me") {
                val principal = call.principal<UserPrincipal>()!!
                call.respond(
                    ApiResponse.success(
                        UserInfo(
                            userId = principal.userId,
                            username = principal.userId,
                            roles = principal.roles
                        )
                    )
                )
            }
        }
    }
}
