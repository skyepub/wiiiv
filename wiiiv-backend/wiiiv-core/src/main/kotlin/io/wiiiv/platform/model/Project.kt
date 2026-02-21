package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class Project(
    val projectId: Long = 0,
    val name: String,
    val description: String? = null,
    val ownerUserId: Long,
    val defaultModel: String = "gpt-4o-mini",
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String? = null
)
