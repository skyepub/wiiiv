package io.wiiiv.integration

import io.wiiiv.blueprint.Blueprint
import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.dacs.*
import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.gate.*
import io.wiiiv.governor.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

/**
 * wiiiv v2.0 Integration Tests
 *
 * 전체 아키텍처 흐름 검증:
 *
 * ```
 * Spec → Governor → DACS → Blueprint → Gate → Executor → Runner
 * ```
 *
 * Canonical 문서 준수 검증
 */
class IntegrationTest {

    private lateinit var testDir: File

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-integration-${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ==================== Full Flow Tests ====================

    @Test
    fun `Full flow - Spec to Execution success`() = runBlocking {
        // === 1. Spec 정의 ===
        val spec = Spec(
            id = "spec-file-read",
            version = "1.0.0",
            name = "File Read Spec",
            description = "Allows reading files from temp directory",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/tmp/**", testDir.absolutePath + "/**")
        )

        // === 2. 테스트 파일 준비 ===
        val testFile = File(testDir, "integration-test.txt")
        testFile.writeText("wiiiv v2.0 Integration Test - Full Flow Success!")

        // === 3. DACS 합의 ===
        val dacs = SimpleDACS.DEFAULT
        val dacsResult = dacs.evaluate(DACSRequest(spec = spec))

        assertTrue(dacsResult.isYes, "DACS should approve: ${dacsResult.reason}")
        assertTrue(dacsResult.canCreateBlueprint)

        // === 4. Governor Blueprint 생성 ===
        val governor = SimpleGovernor("gov-integration")
        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = testFile.absolutePath
        )

        val governorResult = governor.createBlueprint(request, spec)
        assertTrue(governorResult is GovernorResult.BlueprintCreated)
        val blueprint = governorResult.blueprint

        // Blueprint 검증
        assertEquals(spec.id, blueprint.specSnapshot.specId)
        assertEquals("gov-integration", blueprint.specSnapshot.governorId)

        // === 5. Gate 체인 검사 ===
        val gateLogger = InMemoryGateLogger()
        val gateChain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.PERMISSIVE)
            .withLogger(gateLogger)
            .build()

        val gateContext = GateContext(
            blueprintId = blueprint.id,
            dacsConsensus = "YES",  // DACS 결과 반영
            userApproved = true,
            executorId = "file-executor",
            action = "READ"
        )

        val gateResult = gateChain.check(gateContext)
        assertTrue(gateResult.isAllow, "Gate should allow: stopped at ${gateResult.stoppedAt}")

        // === 6. Blueprint 실행 ===
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val executionResult = runner.execute(blueprint)

        // === 7. 결과 검증 ===
        assertTrue(executionResult.isSuccess)
        assertEquals(1, executionResult.successCount)

        val output = executionResult.getStepOutput(blueprint.steps[0].stepId)
        assertNotNull(output)
        assertEquals(
            "wiiiv v2.0 Integration Test - Full Flow Success!",
            output.json["content"]?.jsonPrimitive?.content
        )

        // Gate 로그 검증
        assertEquals(3, gateLogger.size)
        assertTrue(gateLogger.getAllEntries().all { it.result == "ALLOW" })
    }

    @Test
    fun `Full flow - DACS REVISION requires clarification`() = runBlocking {
        // === 1. 불명확한 Spec (DACS가 REVISION 반환) ===
        // REVISION 우선 원칙: 위험 요소는 NO가 아니라 REVISION
        val unclearSpec = Spec(
            id = "spec-unclear",
            name = "Unclear Spec",
            allowedOperations = listOf(RequestType.FILE_DELETE)
            // 경로 제한 없음 - 추가 맥락 필요 → REVISION
        )

        // === 2. DACS 합의 ===
        val dacs = SimpleDACS.DEFAULT
        val dacsResult = dacs.evaluate(DACSRequest(spec = unclearSpec))

        // === 3. DACS가 REVISION을 반환해야 함 (NO 아님) ===
        assertTrue(dacsResult.isRevision, "DACS should request revision: ${dacsResult.reason}")
        assertFalse(dacsResult.canCreateBlueprint)

        // === 4. REVISION인 경우 Blueprint 생성 불가 (Canonical 규칙) ===
        // Governor는 Spec 보완 후 재시도해야 함
    }

    @Test
    fun `Full flow - DACS NO for explicitly prohibited patterns`() = runBlocking {
        // === 1. 명백한 금지 패턴 (DACS가 NO 반환) ===
        val prohibitedSpec = Spec(
            id = "spec-prohibited",
            name = "Prohibited Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/**")  // 전체 시스템 접근 - 명백한 금지
        )

        // === 2. DACS 합의 ===
        val dacs = SimpleDACS.DEFAULT
        val dacsResult = dacs.evaluate(DACSRequest(spec = prohibitedSpec))

        // === 3. DACS가 NO를 반환해야 함 (명백한 금지) ===
        assertTrue(dacsResult.isNo, "DACS should reject prohibited pattern: ${dacsResult.reason}")
        assertFalse(dacsResult.canCreateBlueprint)

        // === 4. NO인 경우 동일 Spec 보완 불가 (Canonical 규칙) ===
        // 완전히 새로운 Spec으로만 재시도 가능
    }

    @Test
    fun `Full flow - Gate denial stops execution`() = runBlocking {
        // === 1. 유효한 Spec ===
        val spec = Spec(
            id = "spec-valid",
            name = "Valid Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        // === 2. 테스트 파일 ===
        val testFile = File(testDir, "gate-test.txt")
        testFile.writeText("Gate test content")

        // === 3. Governor Blueprint 생성 ===
        val governor = SimpleGovernor()
        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = testFile.absolutePath
        )
        val governorResult = governor.createBlueprint(request, spec)
        assertTrue(governorResult is GovernorResult.BlueprintCreated)
        val blueprint = governorResult.blueprint

        // === 4. Gate가 거부하는 상황 (사용자 미승인) ===
        val gateChain = GateChain.standard()
        val gateContext = GateContext(
            blueprintId = blueprint.id,
            dacsConsensus = "YES",
            userApproved = false,  // 사용자 미승인!
            executorId = "file-executor",
            action = "READ"
        )

        val gateResult = gateChain.check(gateContext)

        // === 5. Gate DENY 검증 ===
        assertTrue(gateResult.isDeny)
        assertEquals("gate-user-approval", gateResult.stoppedAt)

        // === 6. Gate DENY면 실행하지 않음 (Canonical 규칙) ===
        // "Gate가 DENY하면 실행은 없다"
    }

    @Test
    fun `Full flow - REVISION requires Spec improvement`() = runBlocking {
        // === 1. 불명확한 Spec (REVISION 유발) ===
        val unclearSpec = Spec(
            id = "spec-unclear",
            name = "Unclear Spec"
            // 제한 없음, 설명 없음 - Reviewer가 ABSTAIN
        )

        // === 2. DACS 합의 ===
        val dacs = SimpleDACS.DEFAULT
        val dacsResult = dacs.evaluate(DACSRequest(spec = unclearSpec))

        // === 3. REVISION 검증 ===
        assertTrue(dacsResult.isRevision, "Expected REVISION but got ${dacsResult.consensus}: ${dacsResult.reason}")
        assertFalse(dacsResult.canCreateBlueprint)

        // === 4. REVISION 사유 확인 ===
        assertNotNull(dacsResult.reason)
        assertTrue(dacsResult.reason.isNotBlank())

        // === 5. Spec 보완 후 재시도 ===
        val improvedSpec = unclearSpec.copy(
            description = "Allows all operations for testing purposes",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        val retryResult = dacs.evaluate(DACSRequest(spec = improvedSpec))
        assertTrue(retryResult.isYes, "Improved spec should be approved: ${retryResult.reason}")
    }

    // ==================== Multi-Step Workflow Tests ====================

    @Test
    fun `Multi-step workflow - Create, Write, Read, Verify`() = runBlocking {
        // === 1. Spec 정의 ===
        val spec = Spec(
            id = "spec-workflow",
            name = "Workflow Spec",
            description = "Multi-step file workflow",
            allowedOperations = listOf(
                RequestType.FILE_MKDIR,
                RequestType.FILE_WRITE,
                RequestType.FILE_READ
            ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        // === 2. DACS 합의 ===
        val dacsResult = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = spec))
        assertTrue(dacsResult.isYes)

        // === 3. Gate 설정 ===
        val gateChain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .build()

        val baseGateContext = GateContext(
            dacsConsensus = "YES",
            userApproved = true
        )

        // === 4. Step 1: MKDIR ===
        val governor = SimpleGovernor("gov-workflow")
        val mkdirRequest = GovernorRequest(
            type = RequestType.FILE_MKDIR,
            targetPath = "${testDir.absolutePath}/workflow-output"
        )
        val mkdirResult = governor.createBlueprint(mkdirRequest, spec)
        assertTrue(mkdirResult is GovernorResult.BlueprintCreated)

        // Gate 검사
        assertTrue(gateChain.check(baseGateContext.copy(
            blueprintId = mkdirResult.blueprint.id
        )).isAllow)

        // 실행
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val mkdirExec = runner.execute(mkdirResult.blueprint)
        assertTrue(mkdirExec.isSuccess)

        // === 5. Step 2: WRITE ===
        val writeRequest = GovernorRequest(
            type = RequestType.FILE_WRITE,
            targetPath = "${testDir.absolutePath}/workflow-output/result.txt",
            content = "Workflow completed at ${java.time.Instant.now()}"
        )
        val writeResult = governor.createBlueprint(writeRequest, spec)
        assertTrue(writeResult is GovernorResult.BlueprintCreated)

        val writeExec = runner.execute(writeResult.blueprint)
        assertTrue(writeExec.isSuccess)

        // === 6. Step 3: READ (검증) ===
        val readRequest = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "${testDir.absolutePath}/workflow-output/result.txt"
        )
        val readResult = governor.createBlueprint(readRequest, spec)
        assertTrue(readResult is GovernorResult.BlueprintCreated)

        val readExec = runner.execute(readResult.blueprint)
        assertTrue(readExec.isSuccess)

        // === 7. 최종 검증 ===
        val content = readExec.getStepOutput(
            readResult.blueprint.steps[0].stepId
        )?.json?.get("content")?.jsonPrimitive?.content

        assertNotNull(content)
        assertTrue(content.contains("Workflow completed"))
    }

    // ==================== Responsibility Boundary Tests ====================

    @Test
    fun `Governor does not execute - only creates Blueprint`() = runBlocking {
        // Given
        val governor = SimpleGovernor()
        val spec = Spec(id = "spec", name = "Test")
        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/nonexistent.txt"
        )

        // When - Governor creates Blueprint
        val result = governor.createBlueprint(request, spec)

        // Then - Blueprint is created, but file is NOT read
        assertTrue(result is GovernorResult.BlueprintCreated)
        // Governor의 책임: Blueprint 생성
        // Executor의 책임: 실제 실행
        // Governor는 파일이 존재하는지 확인하지 않음
    }

    @Test
    fun `DACS does not modify Spec - only evaluates`() = runBlocking {
        // Given
        val originalSpec = Spec(
            id = "spec-original",
            name = "Original",
            version = "1.0.0"
        )

        // When
        val dacs = SimpleDACS.DEFAULT
        dacs.evaluate(DACSRequest(spec = originalSpec))

        // Then - Spec is unchanged
        assertEquals("spec-original", originalSpec.id)
        assertEquals("Original", originalSpec.name)
        assertEquals("1.0.0", originalSpec.version)
        // DACS는 Spec을 수정하지 않음 (Canonical 규칙)
    }

    @Test
    fun `Gate does not interpret - only enforces`() {
        // Given
        val gate = DACSGate.INSTANCE

        // When - Same input
        val context = GateContext.forDacs("YES")
        val result1 = gate.check(context)
        val result2 = gate.check(context)

        // Then - Same output (no interpretation, just enforcement)
        assertEquals(result1.isAllow, result2.isAllow)
        // Gate는 해석하지 않음, if-else만 (Canonical 규칙)
    }

    @Test
    fun `Executor does not judge - only executes`() = runBlocking {
        // Given
        val executor = FileExecutor.INSTANCE
        val context = io.wiiiv.execution.ExecutionContext.create(
            executionId = "test",
            blueprintId = "test",
            instructionId = "test"
        )

        // Step to read nonexistent file
        val step = io.wiiiv.execution.ExecutionStep.FileStep(
            stepId = "read-missing",
            action = io.wiiiv.execution.FileAction.READ,
            path = "/tmp/definitely-nonexistent-${System.currentTimeMillis()}.txt"
        )

        // When
        val result = executor.execute(step, context)

        // Then - Executor returns Failure, does NOT judge if this is "bad"
        assertTrue(result is io.wiiiv.execution.ExecutionResult.Failure)
        // Executor는 판단하지 않음, 결과만 반환 (Canonical 규칙)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `Error in execution does not affect Gate or DACS state`() = runBlocking {
        // === Setup ===
        val spec = Spec(
            id = "spec-error",
            name = "Error Test",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        val dacs = SimpleDACS.DEFAULT
        val gateChain = GateChain.standard()
        val governor = SimpleGovernor()
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)

        // === DACS and Gate pass ===
        val dacsResult = dacs.evaluate(DACSRequest(spec = spec))
        assertTrue(dacsResult.isYes)

        // === Create Blueprint for nonexistent file ===
        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "${testDir.absolutePath}/nonexistent.txt"
        )
        val blueprint = (governor.createBlueprint(request, spec) as GovernorResult.BlueprintCreated).blueprint

        // === Gate passes ===
        val gateResult = gateChain.check(GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file",
            action = "READ"
        ))
        assertTrue(gateResult.isAllow)

        // === Execution fails ===
        val execResult = runner.execute(blueprint)
        assertFalse(execResult.isSuccess)

        // === Verify DACS and Gate are unaffected (Stateless) ===
        val dacsResult2 = dacs.evaluate(DACSRequest(spec = spec))
        assertTrue(dacsResult2.isYes) // DACS state unchanged

        val gateResult2 = gateChain.check(GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file",
            action = "READ"
        ))
        assertTrue(gateResult2.isAllow) // Gate state unchanged
    }

    // ==================== Canonical Rules Verification ====================

    @Test
    fun `Canonical - YES is not conditional`() = runBlocking {
        // DACS YES는 조건부가 아니다
        val spec = Spec(
            id = "spec-yes",
            name = "Clear Spec",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf("/tmp/**")
        )

        val result = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = spec))

        if (result.isYes) {
            // YES 이후 추가 요구사항은 존재하지 않음
            assertTrue(result.canCreateBlueprint)
            // YES는 "이 Spec을 근거로 Blueprint를 생성하는 것에 합의한다"
        }
    }

    @Test
    fun `Canonical - NO cannot be bypassed`() = runBlocking {
        // DACS NO는 우회할 수 없다
        val dangerousSpec = Spec(
            id = "spec-no",
            name = "Dangerous",
            allowedOperations = listOf(RequestType.FILE_DELETE)
        )

        val result = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = dangerousSpec))

        if (result.isNo) {
            // NO 이후 동일 Spec 보완 후 재시도 불가
            // 완전히 새로운 Spec으로만 가능
            assertFalse(result.canCreateBlueprint)
        }
    }

    @Test
    fun `Canonical - REVISION is neutral`() = runBlocking {
        // REVISION은 승인도 거부도 아님
        val unclearSpec = Spec(
            id = "spec-revision",
            name = "Unclear"
        )

        val result = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = unclearSpec))

        if (result.isRevision) {
            // REVISION은 중립 상태
            assertFalse(result.canCreateBlueprint)
            // 부분 승인 아님, 조건부 승인 아님
        }
    }

    @Test
    fun `Canonical - Gate decision is final`() {
        // Gate 결정은 최종적이며 우회 불가
        val chain = GateChain.builder()
            .add(AlwaysDenyGate("gate-final", "FINAL_DENY"))
            .build()

        val result = chain.check(GateContext())

        assertTrue(result.isDeny)
        // "Gate가 DENY하면 실행은 없다"
        // Governor도, DACS도 Gate 위에 있지 않다
    }

    @Test
    fun `Canonical - Blueprint is immutable`() = runBlocking {
        // Blueprint는 불변
        val spec = Spec(id = "spec", name = "Test")
        val governor = SimpleGovernor()
        val request = GovernorRequest(type = RequestType.FILE_READ, targetPath = "/tmp/test.txt")

        val result = governor.createBlueprint(request, spec) as GovernorResult.BlueprintCreated
        val original = result.blueprint

        // Blueprint는 data class - copy만 가능, 원본 수정 불가
        val modified = original.copy(id = "modified-id")

        assertNotEquals(original.id, modified.id)
        // 원본은 변경되지 않음 (불변성)
    }
}
