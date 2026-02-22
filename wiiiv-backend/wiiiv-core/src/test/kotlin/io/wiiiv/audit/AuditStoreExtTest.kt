package io.wiiiv.audit

import io.wiiiv.execution.impl.SimpleConnectionProvider
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.*

/**
 * AuditStoreExtTest — Audit 확장 기능 단위 테스트
 *
 * countByProject, projectId 필터, AuditRecordFactory 검증
 * H2 인메모리, 외부 의존 제로
 */
class AuditStoreExtTest {

    private fun freshAuditStore(): JdbcAuditStore {
        val db = SimpleConnectionProvider.h2InMemory("audit-${UUID.randomUUID()}")
        return JdbcAuditStore(db)
    }

    private fun makeRecord(
        projectId: Long? = null,
        timestamp: Instant = Instant.now(),
        status: String = "COMPLETED",
        executionPath: ExecutionPath = ExecutionPath.DIRECT_HLX_API
    ): AuditRecord = AuditRecord(
        auditId = UUID.randomUUID().toString(),
        timestamp = timestamp,
        executionPath = executionPath,
        status = status,
        projectId = projectId,
        userId = "user-1",
        role = "OPERATOR"
    )

    // ════════════════════════════════════════════
    //  1. countByProject
    // ════════════════════════════════════════════

    @Test
    fun `countByProject — returns 0 for empty project`() {
        val store = freshAuditStore()
        val count = store.countByProject(1L, Instant.now().minus(1, ChronoUnit.DAYS))
        assertEquals(0L, count)
    }

    @Test
    fun `countByProject — counts only matching projectId`() {
        val store = freshAuditStore()
        val from = Instant.now().minus(1, ChronoUnit.HOURS)

        store.insert(makeRecord(projectId = 1L))
        store.insert(makeRecord(projectId = 1L))
        store.insert(makeRecord(projectId = 2L))

        assertEquals(2L, store.countByProject(1L, from))
        assertEquals(1L, store.countByProject(2L, from))
    }

    @Test
    fun `countByProject — respects from timestamp`() {
        val store = freshAuditStore()
        val now = Instant.now()
        val hourAgo = now.minus(1, ChronoUnit.HOURS)
        val twoHoursAgo = now.minus(2, ChronoUnit.HOURS)

        store.insert(makeRecord(projectId = 1L, timestamp = twoHoursAgo))
        store.insert(makeRecord(projectId = 1L, timestamp = now))

        // From 90 min ago → only the recent one
        val from90min = now.minus(90, ChronoUnit.MINUTES)
        assertEquals(1L, store.countByProject(1L, from90min))
    }

    @Test
    fun `countByProject — excludes records before from`() {
        val store = freshAuditStore()
        val now = Instant.now()
        val yesterday = now.minus(1, ChronoUnit.DAYS)

        store.insert(makeRecord(projectId = 1L, timestamp = yesterday))

        // From now → nothing
        assertEquals(0L, store.countByProject(1L, now))
    }

    @Test
    fun `countByProject — handles null projectId records`() {
        val store = freshAuditStore()
        val from = Instant.now().minus(1, ChronoUnit.HOURS)

        store.insert(makeRecord(projectId = null))
        store.insert(makeRecord(projectId = 1L))

        // null projectId records are not counted for project 1
        assertEquals(1L, store.countByProject(1L, from))
    }

    // ════════════════════════════════════════════
    //  2. AuditFilter.projectId
    // ════════════════════════════════════════════

    @Test
    fun `findAll with projectId filter — returns only matching`() {
        val store = freshAuditStore()

        store.insert(makeRecord(projectId = 10L))
        store.insert(makeRecord(projectId = 10L))
        store.insert(makeRecord(projectId = 20L))
        store.insert(makeRecord(projectId = null))

        val filtered = store.findAll(AuditFilter(projectId = 10L))
        assertEquals(2, filtered.size)
        assertTrue(filtered.all { it.projectId == 10L })
    }

    @Test
    fun `findAll without projectId — returns all`() {
        val store = freshAuditStore()

        store.insert(makeRecord(projectId = 10L))
        store.insert(makeRecord(projectId = 20L))
        store.insert(makeRecord(projectId = null))

        val all = store.findAll(AuditFilter())
        assertEquals(3, all.size)
    }

    @Test
    fun `findAll with projectId + status filter — AND condition`() {
        val store = freshAuditStore()

        store.insert(makeRecord(projectId = 10L, status = "COMPLETED"))
        store.insert(makeRecord(projectId = 10L, status = "FAILED"))
        store.insert(makeRecord(projectId = 20L, status = "COMPLETED"))

        val filtered = store.findAll(AuditFilter(projectId = 10L, status = "COMPLETED"))
        assertEquals(1, filtered.size)
        assertEquals("COMPLETED", filtered.first().status)
        assertEquals(10L, filtered.first().projectId)
    }

    @Test
    fun `findAll with projectId + time range — compound filter`() {
        val store = freshAuditStore()
        val now = Instant.now()
        val hourAgo = now.minus(1, ChronoUnit.HOURS)
        val twoHoursAgo = now.minus(2, ChronoUnit.HOURS)

        store.insert(makeRecord(projectId = 10L, timestamp = twoHoursAgo))
        store.insert(makeRecord(projectId = 10L, timestamp = now))

        val filtered = store.findAll(AuditFilter(
            projectId = 10L,
            from = now.minus(90, ChronoUnit.MINUTES)
        ))
        assertEquals(1, filtered.size)
    }

    // ════════════════════════════════════════════
    //  3. AuditRecord insert & find
    // ════════════════════════════════════════════

    @Test
    fun `insert and findById — roundtrip preserves all core fields`() {
        val store = freshAuditStore()
        val record = AuditRecord(
            executionPath = ExecutionPath.DB_QUERY_HLX,
            sessionId = "sess-1",
            userId = "user-1",
            role = "ADMIN",
            workflowId = "wf-1",
            workflowName = "Test Workflow",
            intent = "query database",
            taskType = "DB_QUERY",
            status = "COMPLETED",
            durationMs = 150,
            nodeCount = 3,
            governanceApproved = true,
            riskLevel = "LOW",
            projectId = 42L
        )
        store.insert(record)

        val found = store.findById(record.auditId)
        assertNotNull(found)
        assertEquals(record.auditId, found.auditId)
        assertEquals(ExecutionPath.DB_QUERY_HLX, found.executionPath)
        assertEquals("sess-1", found.sessionId)
        assertEquals("user-1", found.userId)
        assertEquals("ADMIN", found.role)
        assertEquals("wf-1", found.workflowId)
        assertEquals("Test Workflow", found.workflowName)
        assertEquals("query database", found.intent)
        assertEquals("DB_QUERY", found.taskType)
        assertEquals("COMPLETED", found.status)
        assertEquals(150L, found.durationMs)
        assertEquals(3, found.nodeCount)
        assertTrue(found.governanceApproved)
        assertEquals("LOW", found.riskLevel)
        assertEquals(42L, found.projectId)
    }

    @Test
    fun `insert with null projectId — projectId remains null on read`() {
        val store = freshAuditStore()
        val record = makeRecord(projectId = null)
        store.insert(record)

        val found = store.findById(record.auditId)
        assertNotNull(found)
        assertNull(found.projectId)
    }

    // ════════════════════════════════════════════
    //  4. Stats & Integrity
    // ════════════════════════════════════════════

    @Test
    fun `stats — counts total, per-status, per-path`() {
        val store = freshAuditStore()

        store.insert(makeRecord(status = "COMPLETED", executionPath = ExecutionPath.DIRECT_HLX_API))
        store.insert(makeRecord(status = "COMPLETED", executionPath = ExecutionPath.DIRECT_HLX_API))
        store.insert(makeRecord(status = "FAILED", executionPath = ExecutionPath.DIRECT_BLUEPRINT))

        val stats = store.stats()
        assertEquals(3L, stats.totalRecords)
        assertEquals(2L, stats.completedCount)
        assertEquals(1L, stats.failedCount)
        assertEquals(2L, stats.pathCounts["DIRECT_HLX_API"])
        assertEquals(1L, stats.pathCounts["DIRECT_BLUEPRINT"])
    }

    @Test
    fun `insert same auditId twice — throws on duplicate`() {
        val store = freshAuditStore()
        val record = makeRecord()
        store.insert(record)

        assertFailsWith<Exception> {
            store.insert(record.copy()) // same auditId
        }
    }

    @Test
    fun `large batch 100 records — countByProject performance`() {
        val store = freshAuditStore()
        val from = Instant.now().minus(1, ChronoUnit.HOURS)

        repeat(100) {
            store.insert(makeRecord(projectId = if (it % 2 == 0) 1L else 2L))
        }

        assertEquals(50L, store.countByProject(1L, from))
        assertEquals(50L, store.countByProject(2L, from))
    }
}
