package io.wiiiv.platform.store

import io.wiiiv.execution.impl.DbConnectionProvider
import io.wiiiv.platform.model.*
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.util.Base64

/**
 * JDBC 기반 Platform Store — H2/MySQL 양립
 *
 * JdbcAuditStore 패턴:
 * - init{} 블록에서 DDL 자동 생성
 * - PreparedStatement 사용
 * - DbConnectionProvider 주입
 */
class JdbcPlatformStore(
    private val db: DbConnectionProvider
) : PlatformStore {

    init {
        createTables()
    }

    private fun createTables() {
        val conn = db.getConnection(null)
        try {
            conn.createStatement().use { stmt ->
                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS platform_users (
                        user_id       BIGINT AUTO_INCREMENT PRIMARY KEY,
                        email         VARCHAR(255) NOT NULL UNIQUE,
                        display_name  VARCHAR(100) NOT NULL,
                        password_hash VARCHAR(255) NOT NULL,
                        is_active     BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at    TIMESTAMP NULL
                    )
                """)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS projects (
                        project_id    BIGINT AUTO_INCREMENT PRIMARY KEY,
                        name          VARCHAR(150) NOT NULL,
                        description   VARCHAR(2000) NULL,
                        owner_user_id BIGINT NOT NULL,
                        default_model VARCHAR(100) NOT NULL DEFAULT 'gpt-4o-mini',
                        created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at    TIMESTAMP NULL,
                        CONSTRAINT fk_project_owner FOREIGN KEY (owner_user_id)
                            REFERENCES platform_users(user_id)
                    )
                """)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS project_members (
                        project_id BIGINT NOT NULL,
                        user_id    BIGINT NOT NULL,
                        role       VARCHAR(10) NOT NULL,
                        joined_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        PRIMARY KEY (project_id, user_id),
                        CONSTRAINT fk_pm_project FOREIGN KEY (project_id)
                            REFERENCES projects(project_id) ON DELETE CASCADE,
                        CONSTRAINT fk_pm_user FOREIGN KEY (user_id)
                            REFERENCES platform_users(user_id) ON DELETE CASCADE
                    )
                """)

                // user_id 기반 조회 인덱스
                try {
                    stmt.execute("CREATE INDEX IF NOT EXISTS idx_pm_user ON project_members(user_id)")
                } catch (_: Exception) { /* 이미 존재 */ }

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS api_keys (
                        key_id        BIGINT AUTO_INCREMENT PRIMARY KEY,
                        api_key_hash  CHAR(64) NOT NULL UNIQUE,
                        api_key_prefix VARCHAR(12) NOT NULL,
                        user_id       BIGINT NOT NULL,
                        project_id    BIGINT NOT NULL,
                        label         VARCHAR(100) NULL,
                        expires_at    TIMESTAMP NULL,
                        is_active     BOOLEAN NOT NULL DEFAULT TRUE,
                        created_at    TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_api_user FOREIGN KEY (user_id)
                            REFERENCES platform_users(user_id) ON DELETE CASCADE,
                        CONSTRAINT fk_api_project FOREIGN KEY (project_id)
                            REFERENCES projects(project_id) ON DELETE CASCADE
                    )
                """)

                stmt.execute("""
                    CREATE TABLE IF NOT EXISTS project_policies (
                        project_id           BIGINT PRIMARY KEY,
                        allowed_step_types   VARCHAR(2000) NOT NULL DEFAULT '[]',
                        allowed_plugins      VARCHAR(2000) NOT NULL DEFAULT '[]',
                        max_requests_per_day INT NULL,
                        created_at           TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at           TIMESTAMP NULL,
                        CONSTRAINT fk_policy_project FOREIGN KEY (project_id)
                            REFERENCES projects(project_id) ON DELETE CASCADE
                    )
                """)
            }
        } finally {
            db.releaseConnection(conn)
        }
    }

    // ════════════════════════════════════════════
    //  User
    // ════════════════════════════════════════════

    override fun createUser(email: String, displayName: String, passwordHash: String): User {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "INSERT INTO platform_users (email, display_name, password_hash) VALUES (?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            )
            ps.setString(1, email.lowercase().trim())
            ps.setString(2, displayName.trim())
            ps.setString(3, passwordHash)
            ps.executeUpdate()

            val rs = ps.generatedKeys
            rs.next()
            val userId = rs.getLong(1)
            ps.close()

            return findUserById(userId)!!
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun findUserById(userId: Long): User? {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("SELECT * FROM platform_users WHERE user_id = ?")
            ps.setLong(1, userId)
            val rs = ps.executeQuery()
            val user = if (rs.next()) mapUser(rs) else null
            ps.close()
            return user
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun findUserByEmail(email: String): User? {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("SELECT * FROM platform_users WHERE email = ?")
            ps.setString(1, email.lowercase().trim())
            val rs = ps.executeQuery()
            val user = if (rs.next()) mapUser(rs) else null
            ps.close()
            return user
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun updateUser(userId: Long, displayName: String?, isActive: Boolean?): Boolean {
        if (displayName == null && isActive == null) return false
        val sets = mutableListOf<String>()
        if (displayName != null) sets.add("display_name = ?")
        if (isActive != null) sets.add("is_active = ?")
        sets.add("updated_at = ?")

        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("UPDATE platform_users SET ${sets.joinToString()} WHERE user_id = ?")
            var idx = 1
            if (displayName != null) ps.setString(idx++, displayName.trim())
            if (isActive != null) ps.setBoolean(idx++, isActive)
            ps.setTimestamp(idx++, java.sql.Timestamp.from(Instant.now()))
            ps.setLong(idx, userId)
            val updated = ps.executeUpdate()
            ps.close()
            return updated > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun listUsers(): List<User> {
        val conn = db.getConnection(null)
        try {
            val rs = conn.createStatement().executeQuery("SELECT * FROM platform_users ORDER BY user_id")
            val users = mutableListOf<User>()
            while (rs.next()) users.add(mapUser(rs))
            return users
        } finally {
            db.releaseConnection(conn)
        }
    }

    private fun mapUser(rs: ResultSet): User = User(
        userId = rs.getLong("user_id"),
        email = rs.getString("email"),
        displayName = rs.getString("display_name"),
        passwordHash = rs.getString("password_hash"),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant()?.toString()
    )

    // ════════════════════════════════════════════
    //  Project
    // ════════════════════════════════════════════

    override fun createProject(name: String, description: String?, ownerUserId: Long, defaultModel: String): Project {
        val conn = db.getConnection(null)
        try {
            conn.autoCommit = false

            // 1. 프로젝트 생성
            val ps = conn.prepareStatement(
                "INSERT INTO projects (name, description, owner_user_id, default_model) VALUES (?, ?, ?, ?)",
                Statement.RETURN_GENERATED_KEYS
            )
            ps.setString(1, name.trim())
            ps.setString(2, description?.trim())
            ps.setLong(3, ownerUserId)
            ps.setString(4, defaultModel)
            ps.executeUpdate()

            val rs = ps.generatedKeys
            rs.next()
            val projectId = rs.getLong(1)
            ps.close()

            // 2. owner를 자동으로 OWNER 멤버로 추가
            val mps = conn.prepareStatement(
                "INSERT INTO project_members (project_id, user_id, role) VALUES (?, ?, ?)"
            )
            mps.setLong(1, projectId)
            mps.setLong(2, ownerUserId)
            mps.setString(3, ProjectRole.OWNER.name)
            mps.executeUpdate()
            mps.close()

            // 3. 기본 정책 생성
            val pps = conn.prepareStatement(
                "INSERT INTO project_policies (project_id) VALUES (?)"
            )
            pps.setLong(1, projectId)
            pps.executeUpdate()
            pps.close()

            conn.commit()
            conn.autoCommit = true

            return findProjectById(projectId)!!
        } catch (e: Exception) {
            conn.rollback()
            conn.autoCommit = true
            throw e
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun findProjectById(projectId: Long): Project? {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("SELECT * FROM projects WHERE project_id = ?")
            ps.setLong(1, projectId)
            val rs = ps.executeQuery()
            val project = if (rs.next()) mapProject(rs) else null
            ps.close()
            return project
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun updateProject(projectId: Long, name: String?, description: String?, defaultModel: String?): Boolean {
        if (name == null && description == null && defaultModel == null) return false
        val sets = mutableListOf<String>()
        if (name != null) sets.add("name = ?")
        if (description != null) sets.add("description = ?")
        if (defaultModel != null) sets.add("default_model = ?")
        sets.add("updated_at = ?")

        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("UPDATE projects SET ${sets.joinToString()} WHERE project_id = ?")
            var idx = 1
            if (name != null) ps.setString(idx++, name.trim())
            if (description != null) ps.setString(idx++, description.trim())
            if (defaultModel != null) ps.setString(idx++, defaultModel)
            ps.setTimestamp(idx++, java.sql.Timestamp.from(Instant.now()))
            ps.setLong(idx, projectId)
            val updated = ps.executeUpdate()
            ps.close()
            return updated > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun deleteProject(projectId: Long, requestUserId: Long): Boolean {
        val project = findProjectById(projectId) ?: return false
        if (project.ownerUserId != requestUserId) return false

        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("DELETE FROM projects WHERE project_id = ?")
            ps.setLong(1, projectId)
            val deleted = ps.executeUpdate()
            ps.close()
            return deleted > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun listProjectsForUser(userId: Long): List<Project> {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("""
                SELECT p.* FROM projects p
                INNER JOIN project_members pm ON p.project_id = pm.project_id
                WHERE pm.user_id = ?
                ORDER BY p.project_id
            """)
            ps.setLong(1, userId)
            val rs = ps.executeQuery()
            val projects = mutableListOf<Project>()
            while (rs.next()) projects.add(mapProject(rs))
            ps.close()
            return projects
        } finally {
            db.releaseConnection(conn)
        }
    }

    private fun mapProject(rs: ResultSet): Project = Project(
        projectId = rs.getLong("project_id"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        ownerUserId = rs.getLong("owner_user_id"),
        defaultModel = rs.getString("default_model"),
        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant()?.toString()
    )

    // ════════════════════════════════════════════
    //  Membership
    // ════════════════════════════════════════════

    override fun addMember(projectId: Long, userId: Long, role: ProjectRole): ProjectMember {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "INSERT INTO project_members (project_id, user_id, role) VALUES (?, ?, ?)"
            )
            ps.setLong(1, projectId)
            ps.setLong(2, userId)
            ps.setString(3, role.name)
            ps.executeUpdate()
            ps.close()
            return findMember(projectId, userId)!!
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun removeMember(projectId: Long, userId: Long): Boolean {
        // owner_user_id는 제거 불가
        val project = findProjectById(projectId) ?: return false
        if (project.ownerUserId == userId) return false

        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "DELETE FROM project_members WHERE project_id = ? AND user_id = ?"
            )
            ps.setLong(1, projectId)
            ps.setLong(2, userId)
            val deleted = ps.executeUpdate()
            ps.close()
            return deleted > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun updateMemberRole(projectId: Long, userId: Long, role: ProjectRole): Boolean {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "UPDATE project_members SET role = ? WHERE project_id = ? AND user_id = ?"
            )
            ps.setString(1, role.name)
            ps.setLong(2, projectId)
            ps.setLong(3, userId)
            val updated = ps.executeUpdate()
            ps.close()
            return updated > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun findMember(projectId: Long, userId: Long): ProjectMember? {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "SELECT * FROM project_members WHERE project_id = ? AND user_id = ?"
            )
            ps.setLong(1, projectId)
            ps.setLong(2, userId)
            val rs = ps.executeQuery()
            val member = if (rs.next()) mapMember(rs) else null
            ps.close()
            return member
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun listMembers(projectId: Long): List<ProjectMember> {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "SELECT * FROM project_members WHERE project_id = ? ORDER BY joined_at"
            )
            ps.setLong(1, projectId)
            val rs = ps.executeQuery()
            val members = mutableListOf<ProjectMember>()
            while (rs.next()) members.add(mapMember(rs))
            ps.close()
            return members
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun isMember(projectId: Long, userId: Long): Boolean =
        findMember(projectId, userId) != null

    private fun mapMember(rs: ResultSet): ProjectMember = ProjectMember(
        projectId = rs.getLong("project_id"),
        userId = rs.getLong("user_id"),
        role = ProjectRole.valueOf(rs.getString("role")),
        joinedAt = rs.getTimestamp("joined_at").toInstant().toString()
    )

    // ════════════════════════════════════════════
    //  API Key
    // ════════════════════════════════════════════

    override fun createApiKey(userId: Long, projectId: Long, label: String?, expiresAt: String?): Pair<String, ApiKeyRecord> {
        // 멤버십 검증
        require(isMember(projectId, userId)) { "User is not a member of this project" }

        // secure random → Base64 URL-safe
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        val rawKey = "wiiiv_" + Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes)
        val keyHash = sha256Hex(rawKey)
        val prefix = rawKey.take(12)

        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                """INSERT INTO api_keys (api_key_hash, api_key_prefix, user_id, project_id, label, expires_at)
                   VALUES (?, ?, ?, ?, ?, ?)""",
                Statement.RETURN_GENERATED_KEYS
            )
            ps.setString(1, keyHash)
            ps.setString(2, prefix)
            ps.setLong(3, userId)
            ps.setLong(4, projectId)
            ps.setString(5, label)
            if (expiresAt != null) {
                ps.setTimestamp(6, java.sql.Timestamp.from(Instant.parse(expiresAt)))
            } else {
                ps.setNull(6, java.sql.Types.TIMESTAMP)
            }
            ps.executeUpdate()

            val rs = ps.generatedKeys
            rs.next()
            val keyId = rs.getLong(1)
            ps.close()

            val record = ApiKeyRecord(
                keyId = keyId,
                apiKeyHash = keyHash,
                apiKeyPrefix = prefix,
                userId = userId,
                projectId = projectId,
                label = label,
                expiresAt = expiresAt
            )

            // rawKey는 이 순간에만 반환 (다시 조회 불가)
            return Pair(rawKey, record)
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun findByApiKeyHash(apiKeyHash: String): ApiKeyRecord? {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "SELECT * FROM api_keys WHERE api_key_hash = ? AND is_active = TRUE"
            )
            ps.setString(1, apiKeyHash)
            val rs = ps.executeQuery()
            val record = if (rs.next()) mapApiKey(rs) else null
            ps.close()

            // 만료 체크
            if (record?.expiresAt != null) {
                val expires = Instant.parse(record.expiresAt)
                if (Instant.now().isAfter(expires)) return null
            }

            return record
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun listApiKeys(projectId: Long): List<ApiKeyRecord> {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement(
                "SELECT * FROM api_keys WHERE project_id = ? AND is_active = TRUE ORDER BY created_at DESC"
            )
            ps.setLong(1, projectId)
            val rs = ps.executeQuery()
            val keys = mutableListOf<ApiKeyRecord>()
            while (rs.next()) keys.add(mapApiKey(rs))
            ps.close()
            return keys
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun revokeApiKey(keyId: Long): Boolean {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("UPDATE api_keys SET is_active = FALSE WHERE key_id = ?")
            ps.setLong(1, keyId)
            val updated = ps.executeUpdate()
            ps.close()
            return updated > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    private fun mapApiKey(rs: ResultSet): ApiKeyRecord = ApiKeyRecord(
        keyId = rs.getLong("key_id"),
        apiKeyHash = rs.getString("api_key_hash"),
        apiKeyPrefix = rs.getString("api_key_prefix"),
        userId = rs.getLong("user_id"),
        projectId = rs.getLong("project_id"),
        label = rs.getString("label"),
        expiresAt = rs.getTimestamp("expires_at")?.toInstant()?.toString(),
        isActive = rs.getBoolean("is_active"),
        createdAt = rs.getTimestamp("created_at").toInstant().toString()
    )

    // ════════════════════════════════════════════
    //  Policy (F-4 준비)
    // ════════════════════════════════════════════

    override fun getPolicy(projectId: Long): ProjectPolicy? {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("SELECT * FROM project_policies WHERE project_id = ?")
            ps.setLong(1, projectId)
            val rs = ps.executeQuery()
            val policy = if (rs.next()) mapPolicy(rs) else null
            ps.close()
            return policy
        } finally {
            db.releaseConnection(conn)
        }
    }

    override fun upsertPolicy(policy: ProjectPolicy): Boolean {
        val conn = db.getConnection(null)
        try {
            val ps = conn.prepareStatement("""
                MERGE INTO project_policies (project_id, allowed_step_types, allowed_plugins, max_requests_per_day, updated_at)
                KEY (project_id)
                VALUES (?, ?, ?, ?, ?)
            """)
            ps.setLong(1, policy.projectId)
            ps.setString(2, policy.allowedStepTypes)
            ps.setString(3, policy.allowedPlugins)
            if (policy.maxRequestsPerDay != null) {
                ps.setInt(4, policy.maxRequestsPerDay)
            } else {
                ps.setNull(4, java.sql.Types.INTEGER)
            }
            ps.setTimestamp(5, java.sql.Timestamp.from(Instant.now()))
            val updated = ps.executeUpdate()
            ps.close()
            return updated > 0
        } finally {
            db.releaseConnection(conn)
        }
    }

    private fun mapPolicy(rs: ResultSet): ProjectPolicy = ProjectPolicy(
        projectId = rs.getLong("project_id"),
        allowedStepTypes = rs.getString("allowed_step_types"),
        allowedPlugins = rs.getString("allowed_plugins"),
        maxRequestsPerDay = rs.getObject("max_requests_per_day") as? Int,
        createdAt = rs.getTimestamp("created_at").toInstant().toString(),
        updatedAt = rs.getTimestamp("updated_at")?.toInstant()?.toString()
    )

    // ════════════════════════════════════════════
    //  Utility
    // ════════════════════════════════════════════

    companion object {
        fun sha256Hex(input: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
