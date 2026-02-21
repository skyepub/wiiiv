package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ApiKeyRecord(
    val keyId: Long = 0,
    val apiKeyHash: String,
    val apiKeyPrefix: String,
    val userId: Long,
    val projectId: Long,
    val label: String? = null,
    val expiresAt: String? = null,
    val isActive: Boolean = true,
    val createdAt: String = Instant.now().toString()
)
