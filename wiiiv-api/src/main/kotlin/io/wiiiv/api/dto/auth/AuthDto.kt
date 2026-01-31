package io.wiiiv.api.dto.auth

import kotlinx.serialization.Serializable

@Serializable
data class LoginRequest(
    val username: String,
    val password: String
)

@Serializable
data class LoginResponse(
    val accessToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long = 86400
)

@Serializable
data class UserInfo(
    val userId: String,
    val username: String,
    val roles: List<String>
)
