package io.wiiiv.platform.store

import io.wiiiv.platform.model.*

/**
 * Platform Store — User, Project, Membership, API Key 관리
 *
 * JdbcAuditStore 패턴을 따름:
 * - DbConnectionProvider 주입
 * - init{} 블록에서 DDL 자동 생성
 * - PreparedStatement 사용
 */
interface PlatformStore {

    // ── User ──
    fun createUser(email: String, displayName: String, passwordHash: String): User
    fun findUserById(userId: Long): User?
    fun findUserByEmail(email: String): User?
    fun updateUser(userId: Long, displayName: String? = null, isActive: Boolean? = null): Boolean
    fun listUsers(): List<User>

    // ── Project ──
    fun createProject(name: String, description: String?, ownerUserId: Long, defaultModel: String): Project
    fun findProjectById(projectId: Long): Project?
    fun updateProject(projectId: Long, name: String? = null, description: String? = null, defaultModel: String? = null): Boolean
    fun deleteProject(projectId: Long, requestUserId: Long): Boolean
    fun listProjectsForUser(userId: Long): List<Project>

    // ── Membership ──
    fun addMember(projectId: Long, userId: Long, role: ProjectRole): ProjectMember
    fun removeMember(projectId: Long, userId: Long): Boolean
    fun updateMemberRole(projectId: Long, userId: Long, role: ProjectRole): Boolean
    fun findMember(projectId: Long, userId: Long): ProjectMember?
    fun listMembers(projectId: Long): List<ProjectMember>
    fun isMember(projectId: Long, userId: Long): Boolean

    // ── API Key ──
    fun createApiKey(userId: Long, projectId: Long, label: String?, expiresAt: String?): Pair<String, ApiKeyRecord>
    fun findByApiKeyHash(apiKeyHash: String): ApiKeyRecord?
    fun listApiKeys(projectId: Long): List<ApiKeyRecord>
    fun revokeApiKey(keyId: Long): Boolean

    // ── Policy (F-4 준비, 스키마만) ──
    fun getPolicy(projectId: Long): ProjectPolicy?
    fun upsertPolicy(policy: ProjectPolicy): Boolean

    // ── Memory ──
    fun getMemory(userId: Long, projectId: Long?): UserMemory?
    fun upsertMemory(userId: Long, projectId: Long?, content: String): Boolean

    // ── RAG Documents ──
    fun saveRagDocument(doc: io.wiiiv.rag.RagDocument): Boolean
    fun getRagDocument(documentId: String): io.wiiiv.rag.RagDocument?
    fun listRagDocuments(scope: String? = null): List<io.wiiiv.rag.RagDocument>
    fun deleteRagDocument(documentId: String): Boolean
}
