package io.wiiiv.audit

import io.wiiiv.execution.impl.DbConnectionProvider
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

/**
 * JDBC Audit Store - JDBC 기반 감사 레코드 저장소
 *
 * INSERT-only 테이블. init 블록에서 CREATE TABLE IF NOT EXISTS 실행.
 * H2 파일 모드(기본) 또는 MySQL 전환 가능.
 */
class JdbcAuditStore(
    private val connectionProvider: DbConnectionProvider
) : AuditStore {

    init {
        createTableIfNotExists()
    }

    private fun createTableIfNotExists() {
        val ddl = """
            CREATE TABLE IF NOT EXISTS audit_log (
                audit_id         VARCHAR(64)  PRIMARY KEY,
                timestamp        TIMESTAMP    NOT NULL,
                execution_path   VARCHAR(32)  NOT NULL,
                session_id       VARCHAR(64),
                user_id          VARCHAR(128),
                role             VARCHAR(32),
                workflow_id      VARCHAR(128),
                workflow_name    VARCHAR(256),
                intent           VARCHAR(1024),
                task_type        VARCHAR(64),
                status           VARCHAR(32)  NOT NULL,
                duration_ms      BIGINT       DEFAULT 0,
                node_count       INT          DEFAULT 0,
                error            VARCHAR(2048),
                governance_approved BOOLEAN   DEFAULT TRUE,
                risk_level       VARCHAR(16),
                gates_passed     VARCHAR(512),
                denied_by        VARCHAR(128),
                node_records_json TEXT,
                gate_trace_json  TEXT
            )
        """.trimIndent()

        val indexes = listOf(
            "CREATE INDEX IF NOT EXISTS idx_audit_timestamp ON audit_log(timestamp)",
            "CREATE INDEX IF NOT EXISTS idx_audit_user_id ON audit_log(user_id)",
            "CREATE INDEX IF NOT EXISTS idx_audit_status ON audit_log(status)",
            "CREATE INDEX IF NOT EXISTS idx_audit_session_id ON audit_log(session_id)",
            "CREATE INDEX IF NOT EXISTS idx_audit_workflow_id ON audit_log(workflow_id)"
        )

        connectionProvider.getConnection("audit-init").use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(ddl)
                indexes.forEach { stmt.execute(it) }
            }
        }
        println("[AUDIT] Table audit_log initialized")
    }

    override fun insert(record: AuditRecord) {
        val sql = """
            INSERT INTO audit_log (
                audit_id, timestamp, execution_path, session_id,
                user_id, role, workflow_id, workflow_name, intent, task_type,
                status, duration_ms, node_count, error,
                governance_approved, risk_level, gates_passed, denied_by,
                node_records_json, gate_trace_json
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        connectionProvider.getConnection("audit-insert").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, record.auditId)
                ps.setTimestamp(2, Timestamp.from(record.timestamp))
                ps.setString(3, record.executionPath.name)
                ps.setString(4, record.sessionId)
                ps.setString(5, record.userId)
                ps.setString(6, record.role)
                ps.setString(7, record.workflowId)
                ps.setString(8, record.workflowName)
                ps.setString(9, record.intent?.take(1024))
                ps.setString(10, record.taskType)
                ps.setString(11, record.status)
                ps.setLong(12, record.durationMs)
                ps.setInt(13, record.nodeCount)
                ps.setString(14, record.error?.take(2048))
                ps.setBoolean(15, record.governanceApproved)
                ps.setString(16, record.riskLevel)
                ps.setString(17, record.gatesPassed)
                ps.setString(18, record.deniedBy)
                ps.setString(19, record.nodeRecordsJson)
                ps.setString(20, record.gateTraceJson)
                ps.executeUpdate()
            }
        }
    }

    override fun findById(auditId: String): AuditRecord? {
        val sql = "SELECT * FROM audit_log WHERE audit_id = ?"
        connectionProvider.getConnection("audit-find").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, auditId)
                ps.executeQuery().use { rs ->
                    return if (rs.next()) mapRow(rs) else null
                }
            }
        }
    }

    override fun findAll(filter: AuditFilter): List<AuditRecord> {
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()

        filter.userId?.let { conditions.add("user_id = ?"); params.add(it) }
        filter.role?.let { conditions.add("role = ?"); params.add(it) }
        filter.status?.let { conditions.add("status = ?"); params.add(it) }
        filter.executionPath?.let { conditions.add("execution_path = ?"); params.add(it) }
        filter.sessionId?.let { conditions.add("session_id = ?"); params.add(it) }
        filter.workflowId?.let { conditions.add("workflow_id = ?"); params.add(it) }
        filter.from?.let { conditions.add("timestamp >= ?"); params.add(Timestamp.from(it)) }
        filter.to?.let { conditions.add("timestamp <= ?"); params.add(Timestamp.from(it)) }

        val where = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = "SELECT * FROM audit_log $where ORDER BY timestamp DESC LIMIT ? OFFSET ?"

        connectionProvider.getConnection("audit-list").use { conn ->
            conn.prepareStatement(sql).use { ps ->
                var idx = 1
                params.forEach { param ->
                    when (param) {
                        is String -> ps.setString(idx++, param)
                        is Timestamp -> ps.setTimestamp(idx++, param)
                        else -> ps.setObject(idx++, param)
                    }
                }
                ps.setInt(idx++, filter.limit)
                ps.setInt(idx, filter.offset)

                ps.executeQuery().use { rs ->
                    val records = mutableListOf<AuditRecord>()
                    while (rs.next()) {
                        records.add(mapRow(rs))
                    }
                    return records
                }
            }
        }
    }

    override fun stats(): AuditStats {
        connectionProvider.getConnection("audit-stats").use { conn ->
            val total: Long
            val completed: Long
            val failed: Long

            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM audit_log").use { rs ->
                    rs.next(); total = rs.getLong(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM audit_log WHERE status = 'COMPLETED'").use { rs ->
                    rs.next(); completed = rs.getLong(1)
                }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM audit_log WHERE status = 'FAILED'").use { rs ->
                    rs.next(); failed = rs.getLong(1)
                }
            }

            val pathCounts = mutableMapOf<String, Long>()
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT execution_path, COUNT(*) as cnt FROM audit_log GROUP BY execution_path").use { rs ->
                    while (rs.next()) {
                        pathCounts[rs.getString("execution_path")] = rs.getLong("cnt")
                    }
                }
            }

            return AuditStats(
                totalRecords = total,
                completedCount = completed,
                failedCount = failed,
                pathCounts = pathCounts
            )
        }
    }

    private fun mapRow(rs: ResultSet): AuditRecord = AuditRecord(
        auditId = rs.getString("audit_id"),
        timestamp = rs.getTimestamp("timestamp").toInstant(),
        executionPath = ExecutionPath.valueOf(rs.getString("execution_path")),
        sessionId = rs.getString("session_id"),
        userId = rs.getString("user_id"),
        role = rs.getString("role"),
        workflowId = rs.getString("workflow_id"),
        workflowName = rs.getString("workflow_name"),
        intent = rs.getString("intent"),
        taskType = rs.getString("task_type"),
        status = rs.getString("status"),
        durationMs = rs.getLong("duration_ms"),
        nodeCount = rs.getInt("node_count"),
        error = rs.getString("error"),
        governanceApproved = rs.getBoolean("governance_approved"),
        riskLevel = rs.getString("risk_level"),
        gatesPassed = rs.getString("gates_passed"),
        deniedBy = rs.getString("denied_by"),
        nodeRecordsJson = rs.getString("node_records_json"),
        gateTraceJson = rs.getString("gate_trace_json")
    )
}
