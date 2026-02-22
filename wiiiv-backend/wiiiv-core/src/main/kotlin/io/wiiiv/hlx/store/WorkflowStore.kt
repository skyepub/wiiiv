package io.wiiiv.hlx.store

/**
 * Workflow Store - HLX 워크플로우 영구 저장소 인터페이스
 *
 * 워크플로우를 이름으로 저장하고, 이름/ID로 검색하여 재실행할 수 있다.
 */
interface WorkflowStore {

    /** 워크플로우 저장 (upsert: 같은 이름+projectId면 덮어쓰기) */
    fun save(record: WorkflowRecord)

    /** ID로 조회 */
    fun findById(workflowId: String): WorkflowRecord?

    /** 이름으로 조회 (프로젝트 스코핑) */
    fun findByName(name: String, projectId: Long? = null): WorkflowRecord?

    /** 프로젝트별 목록 */
    fun listByProject(projectId: Long? = null, limit: Int = 50): List<WorkflowRecord>

    /** 세션에서 마지막으로 실행된 워크플로우 */
    fun findBySession(sessionId: String): WorkflowRecord?

    /** 삭제 */
    fun delete(workflowId: String): Boolean
}

/**
 * 영구 저장되는 워크플로우 레코드
 */
data class WorkflowRecord(
    val workflowId: String,
    val name: String,
    val description: String? = null,
    val workflowJson: String,
    val sessionId: String? = null,
    val userId: String? = null,
    val projectId: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
