package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProjectMember(
    val projectId: Long,
    val userId: Long,
    val role: ProjectRole,
    val joinedAt: String = Instant.now().toString()
)
