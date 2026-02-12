package io.wiiiv.server.dto.common

import kotlinx.serialization.Serializable

@Serializable
data class ApiResponse<T>(
    val success: Boolean,
    val data: T? = null,
    val error: ApiError? = null
) {
    companion object {
        fun <T> success(data: T): ApiResponse<T> = ApiResponse(
            success = true,
            data = data,
            error = null
        )

        fun <T> error(error: ApiError): ApiResponse<T> = ApiResponse(
            success = false,
            data = null,
            error = error
        )
    }
}

@Serializable
data class ApiError(
    val code: String,
    val message: String,
    val details: Map<String, String>? = null
)
