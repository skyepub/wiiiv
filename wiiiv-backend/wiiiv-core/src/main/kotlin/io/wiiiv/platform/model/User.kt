package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class User(
    val userId: Long = 0,
    val email: String,
    val displayName: String,
    val passwordHash: String,
    val isActive: Boolean = true,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String? = null
)
