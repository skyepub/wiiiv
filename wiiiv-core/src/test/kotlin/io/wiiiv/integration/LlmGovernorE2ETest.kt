package io.wiiiv.integration

import io.wiiiv.blueprint.*
import io.wiiiv.dacs.*
import io.wiiiv.execution.impl.*
import io.wiiiv.gate.*
import io.wiiiv.governor.*
import io.wiiiv.runner.*
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.*

/**
 * LlmGovernor E2E Tests
 *
 * LlmGovernor를 포함한 전체 파이프라인 검증:
 * ```
 * intent → LlmGovernor → enrichSpec → DACS → Gate → Blueprint → Executor → Result
 * ```
 *
 * SimpleGovernor 대신 LlmGovernor를 사용하는 E2E 테스트.
 * MockLlmProvider로 API 키 없이도 실행 가능.
 */
class LlmGovernorE2ETest {

    private lateinit var testDir: File

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-llm-e2e-${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    @Test
    fun `E2E success - intent to LlmGovernor to Gate to Executor`() = runBlocking {
        // === 1. 테스트 파일 생성 ===
        val testFile = File(testDir, "test.txt")
        testFile.writeText("E2E test content from LlmGovernor pipeline")

        // === 2. MockLlmProvider — enrichSpecFromIntent 응답 설정 ===
        val mockProvider = MockLlmProvider()
        val testPath = testDir.absolutePath.replace("\\", "/")
        mockProvider.setMockResponse(
            """{"operations": ["FILE_READ"], "paths": ["$testPath/test.txt"]}"""
        )

        // === 3. LlmGovernor + SimpleDACS (no API key needed) ===
        val governor = LlmGovernor(
            id = "gov-e2e-success",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = mockProvider,
            model = "mock-model"
        )

        // === 4. Spec: intent만 있고 allowedOperations 비어있음 → 보강 트리거 ===
        val spec = Spec(
            id = "spec-e2e-success",
            name = "Read test file",
            description = "Read a test file for E2E testing",
            intent = "Read the text file $testPath/test.txt"
        )

        // GovernorRequest: type/targetPath 없이 intent만 (하드코딩 제거 검증)
        val request = GovernorRequest(
            intent = "Read the text file $testPath/test.txt"
        )

        // === 5. Governor: enrichSpec → DACS → Blueprint 생성 ===
        val result = governor.createBlueprint(request, spec)
        assertTrue(result is GovernorResult.BlueprintCreated, "Expected BlueprintCreated, got $result")
        val blueprint = result.blueprint

        // Blueprint 검증
        assertEquals("YES", blueprint.specSnapshot.dacsResult)
        assertTrue(blueprint.steps.isNotEmpty())
        assertEquals(BlueprintStepType.FILE_READ, blueprint.steps[0].type)
        assertTrue(blueprint.steps[0].params["path"]!!.contains("test.txt"))

        // MockLlmProvider가 호출되었는지 검증 (enrichSpecFromIntent 동작 확인)
        assertTrue(mockProvider.getCallHistory().isNotEmpty(), "LLM should have been called for enrichment")

        // === 6. Gate 체인 통과 ===
        val gateLogger = InMemoryGateLogger()
        val gateChain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.PERMISSIVE)
            .withLogger(gateLogger)
            .build()

        val gateResult = gateChain.check(GateContext(
            blueprintId = blueprint.id,
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "FILE_READ"
        ))
        assertTrue(gateResult.isAllow, "Gate should allow approved request")

        // Gate 로그 검증
        val logs = gateLogger.getAllEntries()
        assertTrue(logs.isNotEmpty(), "Gate logs should be recorded")

        // === 7. Blueprint 실행 ===
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val execResult = runner.execute(blueprint)

        // === 8. 결과 검증 ===
        assertTrue(execResult.isSuccess, "Execution should succeed: ${execResult.runnerResult}")
        assertEquals(RunnerStatus.COMPLETED, execResult.runnerResult.status)
        assertEquals(1, execResult.successCount)

        // 파일 읽기 결과 확인
        val readOutput = execResult.getStepOutput(blueprint.steps[0].stepId)
        assertNotNull(readOutput, "Step output should exist")
    }

    @Test
    fun `E2E denial - dangerous intent rejected by DACS then blocked by Gate`() = runBlocking {
        // === 1. LlmGovernor (no LLM — DACS only) ===
        val governor = LlmGovernor(
            id = "gov-e2e-denied",
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null
        )

        // === 2. 위험한 Spec (금지 경로 포함) ===
        val spec = Spec(
            id = "spec-e2e-dangerous",
            name = "Delete system files",
            description = "Delete all system files recursively",
            intent = "Delete everything on the system",
            allowedPaths = listOf("/etc/passwd", "/**")
        )

        // GovernorRequest: intent만 (CUSTOM default)
        val request = GovernorRequest(
            intent = "Delete everything on the system"
        )

        // === 3. Governor → DACS NO → Denied ===
        val result = governor.createBlueprint(request, spec)
        assertTrue(
            result is GovernorResult.Denied,
            "Expected Denied for dangerous intent, got $result"
        )
        assertTrue(result.reason.contains("NO"))

        // === 4. Gate도 NO일 때 차단하는지 검증 ===
        val gateLogger = InMemoryGateLogger()
        val gateChain = GateChain.standard(gateLogger)

        val gateResult = gateChain.check(GateContext(
            dacsConsensus = "NO",
            userApproved = true
        ))
        assertTrue(gateResult.isDeny, "Gate should deny when DACS consensus is NO")

        // Gate 로그에 DENY 기록 확인
        val logs = gateLogger.getAllEntries()
        assertTrue(logs.isNotEmpty())
        assertTrue(logs.any { entry -> entry.result == "DENY" }, "Gate log should record DENY")
    }
}
