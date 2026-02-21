package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProjectPolicy(
    val projectId: Long,
    val allowedStepTypes: String = "[]",
    val allowedPlugins: String = "[]",
    val maxRequestsPerDay: Int? = null,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String? = null
)
