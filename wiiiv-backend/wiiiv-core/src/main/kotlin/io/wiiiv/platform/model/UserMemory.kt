package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * UserMemory - 사용자별/프로젝트별 기억 저장소
 *
 * Governor가 대화 시 참조하는 사용자 컨텍스트.
 * projectId가 null이면 사용자 전역 메모리, 있으면 프로젝트 스코프 메모리.
 */
@Serializable
data class UserMemory(
    val userId: Long,
    val projectId: Long?,      // null이면 사용자 전역 메모리
    val content: String,       // Markdown
    val updatedAt: String = Instant.now().toString()
)
