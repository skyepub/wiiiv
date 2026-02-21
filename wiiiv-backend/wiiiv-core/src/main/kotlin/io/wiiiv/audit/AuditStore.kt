package io.wiiiv.audit

import java.time.Instant

/**
 * Audit Store - 감사 레코드 영구 저장소 인터페이스
 *
 * INSERT-only 설계: 레코드는 한번 저장되면 수정/삭제 불가
 */
interface AuditStore {

    /** 감사 레코드 저장 */
    fun insert(record: AuditRecord)

    /** ID로 조회 */
    fun findById(auditId: String): AuditRecord?

    /** 필터 기반 목록 조회 */
    fun findAll(filter: AuditFilter = AuditFilter()): List<AuditRecord>

    /** 통계 */
    fun stats(): AuditStats

    /** 프로젝트별 요청 횟수 (특정 시점 이후) — F5-F6 */
    fun countByProject(projectId: Long, from: Instant): Long
}

/**
 * 감사 레코드 필터
 */
data class AuditFilter(
    val userId: String? = null,
    val role: String? = null,
    val status: String? = null,
    val executionPath: String? = null,
    val sessionId: String? = null,
    val workflowId: String? = null,
    val projectId: Long? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Int = 50,
    val offset: Int = 0
)

/**
 * 감사 통계
 */
data class AuditStats(
    val totalRecords: Long,
    val completedCount: Long,
    val failedCount: Long,
    val pathCounts: Map<String, Long>
)
