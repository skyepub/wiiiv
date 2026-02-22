package io.wiiiv.hlx.store

import io.wiiiv.execution.impl.DbConnectionProvider
import java.sql.ResultSet
import java.sql.Timestamp

/**
 * JDBC Workflow Store - JDBC 기반 워크플로우 영구 저장소
 *
 * audit_log와 동일한 DbConnectionProvider를 사용한다.
 * 워크플로우 JSON 전체를 TEXT 컬럼에 저장하여 재실행 시 파싱한다.
 */
class JdbcWorkflowStore(
    private val connectionProvider: DbConnectionProvider
) : WorkflowStore {

    init {
        createTableIfNotExists()
    }

    private fun createTableIfNotExists() {
        val ddl = """
            CREATE TABLE IF NOT EXISTS hlx_workflows (
                workflow_id   VARCHAR(128) PRIMARY KEY,
                name          VARCHAR(256) NOT NULL,
                description   VARCHAR(1024),
                workflow_json TEXT         NOT NULL,
                session_id    VARCHAR(64),
                user_id       VARCHAR(128),
                project_id    BIGINT,
                created_at    TIMESTAMP    NOT NULL,
                updated_at    TIMESTAMP    NOT NULL
            )
        """.trimIndent()

        val indexes = listOf(
            "CREATE INDEX IF NOT EXISTS idx_wf_name ON hlx_workflows(name)",
            "CREATE INDEX IF NOT EXISTS idx_wf_project ON hlx_workflows(project_id)",
            "CREATE INDEX IF NOT EXISTS idx_wf_session ON hlx_workflows(session_id)"
        )

        connectionProvider.getConnection("wf-init").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(ddl)
                indexes.forEach { stmt.execute(it) }
            }
        }
        println("[WORKFLOW-STORE] Table hlx_workflows initialized")
    }

    override fun save(record: WorkflowRecord) {
        // H2/MySQL 호환 upsert: DELETE + INSERT
        connectionProvider.getConnection("wf-save").use { conn ->
            // 같은 이름+projectId의 기존 레코드 삭제 (이름 기반 덮어쓰기)
            conn.prepareStatement(
                "DELETE FROM hlx_workflows WHERE name = ? AND (project_id = ? OR (project_id IS NULL AND ? IS NULL))"
            ).use { ps ->
                ps.setString(1, record.name)
                if (record.projectId != null) {
                    ps.setLong(2, record.projectId)
                    ps.setLong(3, record.projectId)
                } else {
                    ps.setNull(2, java.sql.Types.BIGINT)
                    ps.setNull(3, java.sql.Types.BIGINT)
                }
                ps.executeUpdate()
            }

            // INSERT
            conn.prepareStatement("""
                INSERT INTO hlx_workflows (
                    workflow_id, name, description, workflow_json,
                    session_id, user_id, project_id, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent()).use { ps ->
                ps.setString(1, record.workflowId)
                ps.setString(2, record.name)
                ps.setString(3, record.description)
                ps.setString(4, record.workflowJson)
                ps.setString(5, record.sessionId)
                ps.setString(6, record.userId)
                if (record.projectId != null) {
                    ps.setLong(7, record.projectId)
                } else {
                    ps.setNull(7, java.sql.Types.BIGINT)
                }
                ps.setTimestamp(8, Timestamp(record.createdAt))
                ps.setTimestamp(9, Timestamp(record.updatedAt))
                ps.executeUpdate()
            }
        }
    }

    override fun findById(workflowId: String): WorkflowRecord? {
        connectionProvider.getConnection("wf-find-id").use { conn ->
            conn.prepareStatement("SELECT * FROM hlx_workflows WHERE workflow_id = ?").use { ps ->
                ps.setString(1, workflowId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun findByName(name: String, projectId: Long?): WorkflowRecord? {
        val sql = if (projectId != null) {
            "SELECT * FROM hlx_workflows WHERE name = ? AND project_id = ? ORDER BY updated_at DESC"
        } else {
            "SELECT * FROM hlx_workflows WHERE name = ? ORDER BY updated_at DESC"
        }

        connectionProvider.getConnection("wf-find-name").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, name)
                if (projectId != null) ps.setLong(2, projectId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun listByProject(projectId: Long?, limit: Int): List<WorkflowRecord> {
        val sql = if (projectId != null) {
            "SELECT * FROM hlx_workflows WHERE project_id = ? ORDER BY updated_at DESC LIMIT ?"
        } else {
            "SELECT * FROM hlx_workflows ORDER BY updated_at DESC LIMIT ?"
        }

        connectionProvider.getConnection("wf-list").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                if (projectId != null) ps.setLong(idx++, projectId)
                ps.setInt(idx, limit)
                ps.executeQuery().use { rs ->
                    val records = mutableListOf<WorkflowRecord>()
                    while (rs.next()) records.add(mapRow(rs))
                    return records
                }
            }
        }
    }

    override fun findBySession(sessionId: String): WorkflowRecord? {
        connectionProvider.getConnection("wf-find-session").use { conn ->
            conn.prepareStatement(
                "SELECT * FROM hlx_workflows WHERE session_id = ? ORDER BY updated_at DESC"
            ).use { ps ->
                ps.setString(1, sessionId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun delete(workflowId: String): Boolean {
        connectionProvider.getConnection("wf-delete").use { conn ->
            conn.prepareStatement("DELETE FROM hlx_workflows WHERE workflow_id = ?").use { ps ->
                ps.setString(1, workflowId)
                return ps.executeUpdate() > 0
            }
        }
    }

    private fun mapRow(rs: ResultSet): WorkflowRecord = WorkflowRecord(
        workflowId = rs.getString("workflow_id"),
        name = rs.getString("name"),
        description = rs.getString("description"),
        workflowJson = rs.getString("workflow_json"),
        sessionId = rs.getString("session_id"),
        userId = rs.getString("user_id"),
        projectId = rs.getLong("project_id").let { if (rs.wasNull()) null else it },
        createdAt = rs.getTimestamp("created_at").time,
        updatedAt = rs.getTimestamp("updated_at").time
    )
}
