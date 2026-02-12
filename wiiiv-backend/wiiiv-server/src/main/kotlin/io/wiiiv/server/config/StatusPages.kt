package io.wiiiv.server.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse

fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<BadRequestException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "BAD_REQUEST",
                        message = cause.message ?: "Invalid request body"
                    )
                )
            )
        }

        exception<IllegalArgumentException> { call, cause ->
            call.respond(
                HttpStatusCode.BadRequest,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "BAD_REQUEST",
                        message = cause.message ?: "Invalid request"
                    )
                )
            )
        }

        exception<IllegalStateException> { call, cause ->
            call.respond(
                HttpStatusCode.Conflict,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "CONFLICT",
                        message = cause.message ?: "Resource conflict"
                    )
                )
            )
        }

        exception<NoSuchElementException> { call, cause ->
            call.respond(
                HttpStatusCode.NotFound,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "NOT_FOUND",
                        message = cause.message ?: "Resource not found"
                    )
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled exception", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "INTERNAL_ERROR",
                        message = "An internal error occurred"
                    )
                )
            )
        }

        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "UNAUTHORIZED",
                        message = "Authentication required"
                    )
                )
            )
        }

        status(HttpStatusCode.Forbidden) { call, _ ->
            call.respond(
                HttpStatusCode.Forbidden,
                ApiResponse.error<Unit>(
                    ApiError(
                        code = "FORBIDDEN",
                        message = "Access denied"
                    )
                )
            )
        }
    }
}
