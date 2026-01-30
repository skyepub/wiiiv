package io.wiiiv.gate

import kotlin.test.*

/**
 * Gate Tests
 *
 * Gate 통제 계층 검증
 *
 * Canonical: Gate 최소 스펙 정의서 v1.0
 */
class GateTest {

    // ==================== DACS Gate Tests ====================

    @Test
    fun `DACS Gate should ALLOW when consensus is YES`() {
        // Given
        val gate = DACSGate.INSTANCE
        val context = GateContext.forDacs("YES")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isAllow)
        assertEquals("gate-dacs", result.gateId)
    }

    @Test
    fun `DACS Gate should DENY when consensus is NO`() {
        // Given
        val gate = DACSGate.INSTANCE
        val context = GateContext.forDacs("NO")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("CONSENSUS_NO", (result as GateResult.Deny).code)
    }

    @Test
    fun `DACS Gate should DENY when consensus is REVISION`() {
        // Given
        val gate = DACSGate.INSTANCE
        val context = GateContext.forDacs("REVISION")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("CONSENSUS_REVISION", (result as GateResult.Deny).code)
    }

    @Test
    fun `DACS Gate should DENY when consensus is null`() {
        // Given
        val gate = DACSGate.INSTANCE
        val context = GateContext()

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("NO_CONSENSUS", (result as GateResult.Deny).code)
    }

    @Test
    fun `DACS Gate should DENY for unknown consensus value`() {
        // Given
        val gate = DACSGate.INSTANCE
        val context = GateContext.forDacs("MAYBE")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("INVALID_CONSENSUS", (result as GateResult.Deny).code)
    }

    // ==================== User Approval Gate Tests ====================

    @Test
    fun `User Approval Gate should ALLOW when approved is true`() {
        // Given
        val gate = UserApprovalGate.INSTANCE
        val context = GateContext.forUserApproval(true)

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isAllow)
    }

    @Test
    fun `User Approval Gate should DENY when approved is false`() {
        // Given
        val gate = UserApprovalGate.INSTANCE
        val context = GateContext.forUserApproval(false)

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("NOT_APPROVED", (result as GateResult.Deny).code)
    }

    @Test
    fun `User Approval Gate should DENY when approval is null`() {
        // Given
        val gate = UserApprovalGate.INSTANCE
        val context = GateContext()

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("NO_APPROVAL_INFO", (result as GateResult.Deny).code)
    }

    // ==================== Execution Permission Gate Tests ====================

    @Test
    fun `Permission Gate should ALLOW when executor has permission`() {
        // Given
        val permissions = mapOf(
            "executor-file" to setOf("READ", "WRITE"),
            "executor-cmd" to setOf("EXECUTE")
        )
        val gate = ExecutionPermissionGate.withPermissions(permissions)
        val context = GateContext.forPermission("executor-file", "READ")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isAllow)
    }

    @Test
    fun `Permission Gate should DENY when executor lacks permission`() {
        // Given
        val permissions = mapOf(
            "executor-file" to setOf("READ")
        )
        val gate = ExecutionPermissionGate.withPermissions(permissions)
        val context = GateContext.forPermission("executor-file", "DELETE")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("NOT_PERMITTED", (result as GateResult.Deny).code)
    }

    @Test
    fun `Permission Gate should DENY when executor not found`() {
        // Given
        val permissions = mapOf(
            "executor-file" to setOf("READ")
        )
        val gate = ExecutionPermissionGate.withPermissions(permissions)
        val context = GateContext.forPermission("unknown-executor", "READ")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("NOT_PERMITTED", (result as GateResult.Deny).code)
    }

    @Test
    fun `Permission Gate should ALLOW all when permissions is empty`() {
        // Given
        val gate = ExecutionPermissionGate.PERMISSIVE
        val context = GateContext.forPermission("any-executor", "ANY_ACTION")

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isAllow)
    }

    @Test
    fun `Permission Gate should DENY when info is missing`() {
        // Given
        val gate = ExecutionPermissionGate.withPermissions(mapOf("x" to setOf("y")))
        val context = GateContext() // No executor or action

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("MISSING_INFO", (result as GateResult.Deny).code)
    }

    // ==================== Cost Gate Tests ====================

    @Test
    fun `Cost Gate should ALLOW when cost is within limit`() {
        // Given
        val gate = CostGate.withLimit(100.0)
        val context = GateContext.forCost(50.0, 100.0)

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isAllow)
    }

    @Test
    fun `Cost Gate should ALLOW when cost equals limit`() {
        // Given
        val gate = CostGate.UNLIMITED
        val context = GateContext.forCost(100.0, 100.0)

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isAllow)
    }

    @Test
    fun `Cost Gate should DENY when cost exceeds limit`() {
        // Given
        val gate = CostGate.UNLIMITED
        val context = GateContext.forCost(150.0, 100.0)

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("COST_EXCEEDED", (result as GateResult.Deny).code)
    }

    @Test
    fun `Cost Gate should use default limit when not specified`() {
        // Given
        val gate = CostGate.withLimit(50.0)
        val context = GateContext(estimatedCost = 100.0) // No costLimit in context

        // When
        val result = gate.check(context)

        // Then
        assertTrue(result.isDeny) // 100 > 50 (default)
    }

    // ==================== Gate Chain Tests ====================

    @Test
    fun `Gate Chain should ALLOW when all gates ALLOW`() {
        // Given
        val chain = GateChain.builder()
            .add(AlwaysAllowGate("gate-1"))
            .add(AlwaysAllowGate("gate-2"))
            .add(AlwaysAllowGate("gate-3"))
            .build()

        val context = GateContext()

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isAllow)
        assertEquals(3, result.passedCount)
        assertNull(result.stoppedAt)
    }

    @Test
    fun `Gate Chain should DENY on first DENY and stop`() {
        // Given
        val chain = GateChain.builder()
            .add(AlwaysAllowGate("gate-1"))
            .add(AlwaysDenyGate("gate-2", "DENIED_AT_2"))
            .add(AlwaysAllowGate("gate-3")) // Should not be checked
            .build()

        val context = GateContext()

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("gate-2", result.stoppedAt)
        assertEquals(2, result.allResults.size) // Only 2 gates checked
        assertEquals(1, result.passedCount)
    }

    @Test
    fun `Gate Chain standard should follow canonical order`() {
        // Given - Standard chain with YES consensus and approval
        val logger = InMemoryGateLogger()
        val chain = GateChain.standard(logger)

        val context = GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "test",
            action = "test"
        )

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isAllow)
        assertEquals(4, result.allResults.size)
        assertEquals(4, logger.size)

        // Verify order in logs
        val entries = logger.getAllEntries()
        assertEquals("DACS Gate", entries[0].gateName)
        assertEquals("User Approval Gate", entries[1].gateName)
        assertEquals("Execution Permission Gate", entries[2].gateName)
        assertEquals("Cost Gate", entries[3].gateName)
    }

    @Test
    fun `Gate Chain should stop at DACS if consensus is not YES`() {
        // Given
        val chain = GateChain.standard()
        val context = GateContext(
            dacsConsensus = "NO",
            userApproved = true // Would pass, but never checked
        )

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("gate-dacs", result.stoppedAt)
        assertEquals(1, result.allResults.size) // Only DACS checked
    }

    @Test
    fun `Gate Chain minimal should only check DACS`() {
        // Given
        val chain = GateChain.minimal()
        val context = GateContext(dacsConsensus = "YES")

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isAllow)
        assertEquals(1, result.allResults.size)
    }

    // ==================== Gate Logging Tests ====================

    @Test
    fun `Gate Chain should log all checks`() {
        // Given
        val logger = InMemoryGateLogger()
        val chain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .withLogger(logger)
            .build()

        val context = GateContext(
            requestId = "req-123",
            dacsConsensus = "YES",
            userApproved = false // Will DENY at second gate
        )

        // When
        chain.check(context)

        // Then
        assertEquals(2, logger.size)

        val entries = logger.getEntries("req-123")
        assertEquals(2, entries.size)

        // First gate: ALLOW
        assertEquals("DACS Gate", entries[0].gateName)
        assertEquals("ALLOW", entries[0].result)
        assertNull(entries[0].denyCode)

        // Second gate: DENY
        assertEquals("User Approval Gate", entries[1].gateName)
        assertEquals("DENY", entries[1].result)
        assertEquals("NOT_APPROVED", entries[1].denyCode)
    }

    @Test
    fun `Gate log entries should have timestamps`() {
        // Given
        val logger = InMemoryGateLogger()
        val chain = GateChain.builder()
            .add(AlwaysAllowGate())
            .withLogger(logger)
            .build()

        // When
        chain.check(GateContext())

        // Then
        val entry = logger.getAllEntries().first()
        assertNotNull(entry.timestamp)
        assertNotNull(entry.logId)
    }

    // ==================== Integration Scenario Tests ====================

    @Test
    fun `Complete gate check scenario - all pass`() {
        // Given
        val permissions = mapOf(
            "file-executor" to setOf("READ", "WRITE", "DELETE")
        )

        val chain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.withPermissions(permissions))
            .add(CostGate.withLimit(1000.0))
            .build()

        val context = GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "WRITE",
            estimatedCost = 50.0,
            costLimit = 100.0
        )

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isAllow)
        assertEquals(4, result.passedCount)
    }

    @Test
    fun `Complete gate check scenario - permission denied`() {
        // Given
        val permissions = mapOf(
            "file-executor" to setOf("READ") // No WRITE permission
        )

        val chain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.withPermissions(permissions))
            .add(CostGate.withLimit(1000.0))
            .build()

        val context = GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "WRITE", // Not permitted
            estimatedCost = 50.0
        )

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("gate-permission", result.stoppedAt)
        assertEquals(2, result.passedCount) // DACS and UserApproval passed
    }

    @Test
    fun `Complete gate check scenario - cost exceeded`() {
        // Given
        val chain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.PERMISSIVE)
            .add(CostGate.UNLIMITED)
            .build()

        val context = GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "any",
            action = "any",
            estimatedCost = 500.0,
            costLimit = 100.0 // Exceeded!
        )

        // When
        val result = chain.check(context)

        // Then
        assertTrue(result.isDeny)
        assertEquals("gate-cost", result.stoppedAt)
        assertEquals(3, result.passedCount)
    }

    // ==================== Stateless Tests ====================

    @Test
    fun `Gates should be stateless - same input same output`() {
        // Given
        val gate = DACSGate.INSTANCE
        val context = GateContext.forDacs("YES")

        // When - Multiple checks
        val result1 = gate.check(context)
        val result2 = gate.check(context)
        val result3 = gate.check(context)

        // Then - All same
        assertTrue(result1.isAllow)
        assertTrue(result2.isAllow)
        assertTrue(result3.isAllow)
    }

    @Test
    fun `Gates should be stateless - different inputs different outputs`() {
        // Given
        val gate = DACSGate.INSTANCE

        // When
        val allowResult = gate.check(GateContext.forDacs("YES"))
        val denyResult = gate.check(GateContext.forDacs("NO"))
        val allowAgain = gate.check(GateContext.forDacs("YES"))

        // Then
        assertTrue(allowResult.isAllow)
        assertTrue(denyResult.isDeny)
        assertTrue(allowAgain.isAllow)
    }
}
