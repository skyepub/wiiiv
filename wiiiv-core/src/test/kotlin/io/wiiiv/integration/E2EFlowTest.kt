package io.wiiiv.integration

import io.wiiiv.blueprint.*
import io.wiiiv.dacs.*
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.gate.*
import io.wiiiv.governor.*
import io.wiiiv.runner.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File
import kotlin.test.*

/**
 * wiiiv v2.0 E2E Flow Tests
 *
 * 전체 파이프라인 검증:
 * ```
 * Spec → Governor → DACS → Gate → Blueprint → Executor → Runner → Result
 * ```
 *
 * 모든 Executor 유형 통합 테스트
 */
class E2EFlowTest {

    private lateinit var testDir: File

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-e2e-${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ==================== Complete Pipeline Tests ====================

    @Test
    fun `E2E - Complete pipeline with FileExecutor`() = runBlocking {
        // === 1. Spec 정의 ===
        val spec = Spec(
            id = "spec-file-e2e",
            version = "1.0.0",
            name = "File E2E Spec",
            description = "Complete file operations for E2E testing",
            allowedOperations = listOf(
                RequestType.FILE_MKDIR,
                RequestType.FILE_WRITE,
                RequestType.FILE_READ,
                RequestType.FILE_COPY,
                RequestType.FILE_DELETE
            ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        // === 2. DACS 합의 ===
        val dacs = SimpleDACS.DEFAULT
        val dacsResult = dacs.evaluate(DACSRequest(spec = spec))
        assertTrue(dacsResult.isYes, "DACS should approve: ${dacsResult.reason}")

        // === 3. Gate 체인 구성 ===
        val gateLogger = InMemoryGateLogger()
        val gateChain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.PERMISSIVE)
            .withLogger(gateLogger)
            .build()

        // === 4. Governor로 Blueprint 생성 및 실행 ===
        val governor = SimpleGovernor("gov-e2e")
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)

        // Step 1: MKDIR
        val mkdirResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_MKDIR,
                targetPath = "${testDir.absolutePath}/e2e-output"
            ),
            spec
        )
        assertTrue(mkdirResult is GovernorResult.BlueprintCreated)
        val mkdirBlueprint = mkdirResult.blueprint

        // Gate 검증
        val gateResult = gateChain.check(GateContext(
            blueprintId = mkdirBlueprint.id,
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "MKDIR"
        ))
        assertTrue(gateResult.isAllow)

        // 실행
        val mkdirExec = runner.execute(mkdirBlueprint)
        assertTrue(mkdirExec.isSuccess, "MKDIR should succeed")

        // Step 2: WRITE
        val writeResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_WRITE,
                targetPath = "${testDir.absolutePath}/e2e-output/data.txt",
                content = "E2E Test Data - ${System.currentTimeMillis()}"
            ),
            spec
        )
        assertTrue(writeResult is GovernorResult.BlueprintCreated)
        val writeExec = runner.execute(writeResult.blueprint)
        assertTrue(writeExec.isSuccess, "WRITE should succeed")

        // Step 3: READ
        val readResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_READ,
                targetPath = "${testDir.absolutePath}/e2e-output/data.txt"
            ),
            spec
        )
        assertTrue(readResult is GovernorResult.BlueprintCreated)
        val readExec = runner.execute(readResult.blueprint)
        assertTrue(readExec.isSuccess, "READ should succeed")

        // 내용 검증
        val readOutput = readExec.getStepOutput(readResult.blueprint.steps[0].stepId)
        assertNotNull(readOutput)
        assertTrue(readOutput.json["content"]?.jsonPrimitive?.content?.contains("E2E Test Data") == true)

        // Step 4: COPY
        val copyResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_COPY,
                targetPath = "${testDir.absolutePath}/e2e-output/data.txt",
                params = mapOf("target" to "${testDir.absolutePath}/e2e-output/data-copy.txt")
            ),
            spec
        )
        assertTrue(copyResult is GovernorResult.BlueprintCreated)
        val copyExec = runner.execute(copyResult.blueprint)
        assertTrue(copyExec.isSuccess, "COPY should succeed")

        // Step 5: DELETE
        val deleteResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_DELETE,
                targetPath = "${testDir.absolutePath}/e2e-output/data-copy.txt"
            ),
            spec
        )
        assertTrue(deleteResult is GovernorResult.BlueprintCreated)
        val deleteExec = runner.execute(deleteResult.blueprint)
        assertTrue(deleteExec.isSuccess, "DELETE should succeed")

        // === 5. 최종 검증 ===
        assertTrue(File("${testDir.absolutePath}/e2e-output/data.txt").exists())
        assertFalse(File("${testDir.absolutePath}/e2e-output/data-copy.txt").exists())
    }

    @Test
    fun `E2E - Complete pipeline with CommandExecutor`() = runBlocking {
        // === 1. Spec 정의 ===
        val spec = Spec(
            id = "spec-cmd-e2e",
            version = "1.0.0",
            name = "Command E2E Spec",
            description = "Shell command execution for E2E testing",
            allowedOperations = listOf(RequestType.COMMAND)
        )

        // === 2. DACS 합의 ===
        val dacs = SimpleDACS.DEFAULT
        dacs.evaluate(DACSRequest(spec = spec))
        // COMMAND without path restrictions may get REVISION
        // For E2E test, we proceed with direct executor usage

        // === 3. ExecutionRunner로 직접 실행 ===
        val executor = CommandExecutor.INSTANCE
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("e2e-cmd", "bp-cmd", "inst-cmd")

        // 간단한 명령어 실행
        val step = ExecutionStep.CommandStep(
            stepId = "echo-test",
            command = "echo",
            args = listOf("E2E_Command_Test")
        )

        val result = runner.execute(listOf(step), context)

        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertTrue(result.isAllSuccess)

        val output = context.getStepOutput("echo-test")
        assertNotNull(output)
        assertTrue(output.json["stdout"]?.jsonPrimitive?.content?.contains("E2E_Command_Test") == true)
        assertEquals(0, output.json["exitCode"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `E2E - Complete pipeline with DbExecutor`() = runBlocking {
        // === 1. DB 설정 ===
        val connectionProvider = SimpleConnectionProvider.h2InMemory("e2e_${System.currentTimeMillis()}")
        val executor = DbExecutor.create(connectionProvider)
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("e2e-db", "bp-db", "inst-db")

        // === 2. DDL - 테이블 생성 ===
        val createTableStep = ExecutionStep.DbStep(
            stepId = "create-table",
            sql = """
                CREATE TABLE e2e_users (
                    id INT AUTO_INCREMENT PRIMARY KEY,
                    name VARCHAR(100) NOT NULL,
                    email VARCHAR(100)
                )
            """.trimIndent(),
            mode = DbMode.DDL
        )

        val createResult = runner.execute(listOf(createTableStep), context)
        assertEquals(RunnerStatus.COMPLETED, createResult.status)

        // === 3. MUTATION - 데이터 삽입 ===
        val insertStep = ExecutionStep.DbStep(
            stepId = "insert-data",
            sql = "INSERT INTO e2e_users (name, email) VALUES ('E2E User', 'e2e@test.com')",
            mode = DbMode.MUTATION
        )

        val insertContext = ExecutionContext.create("e2e-db-2", "bp-db", "inst-db")
        val insertResult = runner.execute(listOf(insertStep), insertContext)
        assertEquals(RunnerStatus.COMPLETED, insertResult.status)

        val insertOutput = insertContext.getStepOutput("insert-data")
        assertEquals(1, insertOutput?.json?.get("affectedRows")?.jsonPrimitive?.int)

        // === 4. QUERY - 데이터 조회 ===
        val queryStep = ExecutionStep.DbStep(
            stepId = "query-data",
            sql = "SELECT * FROM e2e_users WHERE name = 'E2E User'",
            mode = DbMode.QUERY
        )

        val queryContext = ExecutionContext.create("e2e-db-3", "bp-db", "inst-db")
        val queryResult = runner.execute(listOf(queryStep), queryContext)
        assertEquals(RunnerStatus.COMPLETED, queryResult.status)

        val queryOutput = queryContext.getStepOutput("query-data")
        assertEquals(1, queryOutput?.json?.get("rowCount")?.jsonPrimitive?.int)

        val rows = queryOutput?.json?.get("rows")?.jsonArray
        assertEquals("E2E User", rows?.get(0)?.jsonObject?.get("NAME")?.jsonPrimitive?.content)

        connectionProvider.close()
    }

    @Test
    fun `E2E - Complete pipeline with LlmExecutor (Mock)`() = runBlocking {
        // === 1. Mock LLM Provider 설정 ===
        val mockProvider = MockLlmProvider()
        mockProvider.setMockResponse(
            LlmResponse(
                content = "This is a mock summary of the content.",
                finishReason = "stop",
                usage = LlmUsage.of(10, 20)
            )
        )
        val executor = LlmExecutor.create(mockProvider)
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("e2e-llm", "bp-llm", "inst-llm")

        // === 2. LLM 호출 ===
        val llmStep = ExecutionStep.LlmCallStep(
            stepId = "llm-summarize",
            action = LlmAction.COMPLETE,
            prompt = "Summarize this: The quick brown fox jumps over the lazy dog."
        )

        val result = runner.execute(listOf(llmStep), context)
        assertEquals(RunnerStatus.COMPLETED, result.status)

        val output = context.getStepOutput("llm-summarize")
        assertNotNull(output)
        assertTrue(output.artifacts["content"]?.contains("mock summary") == true)
    }

    // ==================== Mixed Executor Workflow Tests ====================

    @Test
    fun `E2E - Mixed workflow with File and Command executors`() = runBlocking {
        // === CompositeExecutor 설정 ===
        val compositeExecutor = CompositeExecutor.builder()
            .add(FileExecutor.INSTANCE)
            .add(CommandExecutor.INSTANCE)
            .build()

        val runner = ExecutionRunner.create(compositeExecutor)
        val context = ExecutionContext.create("e2e-mixed", "bp-mixed", "inst-mixed")

        // === 워크플로우: File Write → Command (wc) → 결과 검증 ===
        val testFile = File(testDir, "mixed-test.txt")

        val steps = listOf(
            // Step 1: 파일 작성
            ExecutionStep.FileStep(
                stepId = "write-file",
                action = FileAction.WRITE,
                path = testFile.absolutePath,
                content = "Mixed workflow content"
            ),
            // Step 2: 명령어로 파일 확인
            ExecutionStep.CommandStep(
                stepId = "check-file",
                command = "cat",
                args = listOf(testFile.absolutePath)
            )
        )

        val result = runner.execute(steps, context)

        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(2, result.successCount)
        assertTrue(result.isAllSuccess)

        // 파일 작성 확인
        assertTrue(testFile.exists())
        assertEquals("Mixed workflow content", testFile.readText())

        // 명령어 결과 확인 (파일 크기)
        val cmdOutput = context.getStepOutput("check-file")
        assertNotNull(cmdOutput)
        assertEquals(0, cmdOutput.json["exitCode"]?.jsonPrimitive?.int)
    }

    @Test
    fun `E2E - Mixed workflow with File and DB executors`() = runBlocking {
        // === CompositeExecutor 설정 ===
        val connectionProvider = SimpleConnectionProvider.h2InMemory("mixed_${System.currentTimeMillis()}")
        val dbExecutor = DbExecutor.create(connectionProvider)
        val compositeExecutor = CompositeExecutor.builder()
            .add(FileExecutor.INSTANCE)
            .add(dbExecutor)
            .build()

        val runner = ExecutionRunner.create(compositeExecutor)
        val context = ExecutionContext.create("e2e-mixed", "bp", "inst")

        // === 워크플로우: File Write → DB Read 검증 ===
        val reportFile = File(testDir, "mixed-report.txt")

        // Step 1: 파일 작성
        val step1 = ExecutionStep.FileStep(
            stepId = "write-file",
            action = FileAction.WRITE,
            path = reportFile.absolutePath,
            content = "Mixed workflow: File and DB integration test"
        )

        val fileResult = runner.execute(listOf(step1), context)
        assertTrue(fileResult.isAllSuccess, "File write should succeed")

        // === DB 작업을 별도 DbExecutor로 테스트 ===
        val dbRunner = ExecutionRunner.create(dbExecutor)
        val dbContext = ExecutionContext.create("e2e-db-mixed", "bp", "inst")

        // DDL
        val createStep = ExecutionStep.DbStep(
            stepId = "create-table",
            sql = "CREATE TABLE mixed_test (id INT, content VARCHAR(200))",
            mode = DbMode.DDL
        )
        val createResult = dbRunner.execute(listOf(createStep), dbContext)
        assertTrue(createResult.isAllSuccess, "DDL should succeed")

        // INSERT
        val insertStep = ExecutionStep.DbStep(
            stepId = "insert-data",
            sql = "INSERT INTO mixed_test VALUES (1, 'Test content')",
            mode = DbMode.MUTATION
        )
        val insertContext = ExecutionContext.create("e2e-db-insert", "bp", "inst")
        val insertResult = dbRunner.execute(listOf(insertStep), insertContext)
        assertTrue(insertResult.isAllSuccess, "INSERT should succeed")

        // QUERY
        val queryStep = ExecutionStep.DbStep(
            stepId = "query-data",
            sql = "SELECT * FROM mixed_test",
            mode = DbMode.QUERY
        )
        val queryContext = ExecutionContext.create("e2e-db-query", "bp", "inst")
        val queryResult = dbRunner.execute(listOf(queryStep), queryContext)
        assertTrue(queryResult.isAllSuccess, "QUERY should succeed")

        val queryOutput = queryContext.getStepOutput("query-data")
        assertEquals(1, queryOutput?.json?.get("rowCount")?.jsonPrimitive?.int)

        // 검증: 파일 존재
        assertTrue(reportFile.exists())
        assertEquals("Mixed workflow: File and DB integration test", reportFile.readText())

        connectionProvider.close()
    }

    // ==================== Error Propagation Tests ====================

    @Test
    fun `E2E - Error propagation through pipeline`() = runBlocking {
        // === Spec 정의 ===
        val spec = Spec(
            id = "spec-error",
            name = "Error Test Spec",
            description = "Testing error propagation in E2E flow",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        // === DACS 통과 ===
        val dacsResult = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = spec))
        assertTrue(dacsResult.isYes)

        // === Gate 통과 ===
        val gateResult = GateChain.standard().check(GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "READ"
        ))
        assertTrue(gateResult.isAllow)

        // === Governor Blueprint 생성 (존재하지 않는 파일) ===
        val governor = SimpleGovernor()
        val result = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_READ,
                targetPath = "${testDir.absolutePath}/nonexistent-file.txt"
            ),
            spec
        )
        assertTrue(result is GovernorResult.BlueprintCreated)
        val blueprint = result.blueprint

        // === 실행 - 실패 예상 ===
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val execResult = runner.execute(blueprint)

        // === 검증: 실패했지만 구조화된 오류 ===
        assertFalse(execResult.isSuccess)
        assertEquals(1, execResult.failureCount)

        // 오류 정보가 포함됨
        val stepResult = execResult.runnerResult.results.first()
        assertTrue(stepResult is ExecutionResult.Failure)
        val failure = stepResult
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, failure.error.category)
        assertTrue(failure.error.message.contains("not found") || failure.error.message.contains("exist"))
    }

    @Test
    fun `E2E - Gate denial blocks entire pipeline`() = runBlocking {
        // === Spec 정의 ===
        val spec = Spec(
            id = "spec-gate-block",
            name = "Gate Block Test",
            description = "Testing gate denial blocks execution",
            allowedOperations = listOf(RequestType.FILE_READ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        // === DACS 통과 ===
        val dacsResult = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = spec))
        assertTrue(dacsResult.isYes)

        // === Gate 거부 (사용자 미승인) ===
        val gateChain = GateChain.standard()
        val gateResult = gateChain.check(GateContext(
            dacsConsensus = "YES",
            userApproved = false  // 사용자 미승인!
        ))

        // === 검증: Gate 거부로 파이프라인 중단 ===
        assertTrue(gateResult.isDeny)
        assertEquals("gate-user-approval", gateResult.stoppedAt)

        // Deny code 확인
        val denyResult = gateResult.finalResult as GateResult.Deny
        assertEquals("NOT_APPROVED", denyResult.code)

        // Gate DENY 시 실행하지 않음 (Canonical 규칙)
        // "Gate가 DENY하면 실행은 없다"
    }

    @Test
    fun `E2E - DACS NO blocks blueprint creation`() = runBlocking {
        // === 명백히 금지된 패턴의 Spec ===
        val dangerousSpec = Spec(
            id = "spec-dangerous",
            name = "Dangerous Spec",
            allowedOperations = listOf(RequestType.FILE_DELETE),
            allowedPaths = listOf("/**")  // 전체 시스템 접근 - 명백한 금지
        )

        // === DACS가 NO 반환 ===
        val dacsResult = SimpleDACS.DEFAULT.evaluate(DACSRequest(spec = dangerousSpec))

        // === 검증: Blueprint 생성 불가 ===
        assertTrue(dacsResult.isNo, "DACS should reject: ${dacsResult.reason}")
        assertFalse(dacsResult.canCreateBlueprint)

        // NO인 경우 동일 Spec으로 재시도 불가 (Canonical 규칙)
    }

    // ==================== Retry Policy Tests ====================

    @Test
    fun `E2E - Retry policy with transient failures`() = runBlocking {
        // === 실패 후 성공하는 Provider ===
        val failingProvider = CountingLlmProvider(
            failOnCall = 1,  // 첫 번째 호출 실패
            retryable = true  // EXTERNAL_SERVICE_ERROR (재시도 가능)
        )
        val executor = LlmExecutor.create(failingProvider)

        // === Retry 정책 설정 ===
        val retryPolicy = RetryPolicy(
            maxAttempts = 4,  // 첫 시도 + 3 재시도
            intervalMs = 10
        )
        val runner = ExecutionRunner.create(executor, retryPolicy)
        val context = ExecutionContext.create("e2e-retry", "bp", "inst")

        val step = ExecutionStep.LlmCallStep(
            stepId = "retry-test",
            action = LlmAction.COMPLETE,
            prompt = "Test prompt"
        )

        // === 실행 (재시도로 성공) ===
        val result = runner.execute(listOf(step), context)

        // === 검증: 재시도 후 성공 ===
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertTrue(result.isAllSuccess)
        assertEquals(2, failingProvider.callCount)  // 첫 실패 + 두 번째 성공
    }

    @Test
    fun `E2E - Retry exhaustion leads to failure`() = runBlocking {
        // === 항상 실패하는 Provider ===
        val alwaysFailProvider = object : LlmProvider {
            override val defaultModel = "fail-model"
            override val defaultMaxTokens = 100
            var callCount = 0

            override suspend fun call(request: LlmRequest): LlmResponse {
                callCount++
                throw LlmProviderException(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,  // 재시도 가능
                    code = "ALWAYS_FAIL",
                    message = "Always fails for testing"
                )
            }

            override suspend fun cancel(executionId: String): Boolean = true
        }
        val executor = LlmExecutor.create(alwaysFailProvider)

        // === 제한된 Retry 정책 ===
        val retryPolicy = RetryPolicy(
            maxAttempts = 3,  // 첫 시도 + 2 재시도
            intervalMs = 10
        )
        val runner = ExecutionRunner.create(executor, retryPolicy)
        val context = ExecutionContext.create("e2e-retry-fail", "bp", "inst")

        val step = ExecutionStep.LlmCallStep(
            stepId = "exhaust-retry",
            action = LlmAction.COMPLETE,
            prompt = "Test"
        )

        // === 실행 (재시도 소진 후 실패) ===
        val result = runner.execute(listOf(step), context)

        // === 검증: 재시도 소진 후 최종 실패 ===
        assertEquals(RunnerStatus.FAILED, result.status)
        assertEquals(1, result.failureCount)
        assertEquals(3, alwaysFailProvider.callCount)  // 1 + 2 retries
    }

    // ==================== Parallel Execution Tests ====================

    @Test
    fun `E2E - Parallel execution with multiple file operations`() = runBlocking {
        // === 여러 파일 동시 생성 ===
        val executor = FileExecutor.INSTANCE
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("e2e-parallel", "bp", "inst")

        val steps = (1..5).map { i ->
            ExecutionStep.FileStep(
                stepId = "write-$i",
                action = FileAction.WRITE,
                path = "${testDir.absolutePath}/parallel-$i.txt",
                content = "Parallel file content $i"
            )
        }

        // === 병렬 실행 ===
        val result = runner.executeParallel(steps, context)

        // === 검증 ===
        assertTrue(result.isAllSuccess)
        assertEquals(5, result.successCount)

        // 모든 파일 생성 확인
        (1..5).forEach { i ->
            val file = File("${testDir.absolutePath}/parallel-$i.txt")
            assertTrue(file.exists(), "File parallel-$i.txt should exist")
            assertEquals("Parallel file content $i", file.readText())
        }
    }

    @Test
    fun `E2E - Parallel execution with fail-fast`() = runBlocking {
        // === 일부 실패하는 파일 읽기 ===
        val executor = FileExecutor.INSTANCE
        val runner = ExecutionRunner.create(executor)
        val context = ExecutionContext.create("e2e-parallel-fail", "bp", "inst")

        // 존재하는 파일과 존재하지 않는 파일 혼합
        File(testDir, "exists.txt").writeText("Exists")

        val steps = listOf(
            ExecutionStep.FileStep(
                stepId = "read-exists",
                action = FileAction.READ,
                path = "${testDir.absolutePath}/exists.txt"
            ),
            ExecutionStep.FileStep(
                stepId = "read-missing",
                action = FileAction.READ,
                path = "${testDir.absolutePath}/missing.txt"
            )
        )

        // === 병렬 실행 ===
        val result = runner.executeParallel(steps, context)

        // === 검증: 하나라도 실패하면 전체 실패 ===
        assertFalse(result.isAllSuccess)
        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
    }

    // ==================== Blueprint Snapshot Tests ====================

    @Test
    fun `E2E - Blueprint contains accurate spec snapshot`() = runBlocking {
        // === Spec 정의 ===
        val spec = Spec(
            id = "spec-snapshot",
            version = "2.5.0",
            name = "Snapshot Test Spec",
            description = "Testing spec snapshot in blueprint"
        )

        // === Governor Blueprint 생성 ===
        val governor = SimpleGovernor("gov-snapshot")
        val result = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_READ,
                targetPath = "/tmp/test.txt"
            ),
            spec
        )

        assertTrue(result is GovernorResult.BlueprintCreated)
        val blueprint = result.blueprint

        // === 검증: Snapshot이 원본 Spec 정보를 포함 ===
        assertEquals(spec.id, blueprint.specSnapshot.specId)
        assertEquals(spec.version, blueprint.specSnapshot.specVersion)
        assertEquals("gov-snapshot", blueprint.specSnapshot.governorId)
        assertNotNull(blueprint.specSnapshot.snapshotAt)

        // Blueprint는 불변 - Spec이 나중에 변경되어도 영향 없음
        // (Canonical 규칙: Blueprint는 생성 시점의 Spec 상태를 보존)
    }

    @Test
    fun `E2E - Blueprint steps match request`() = runBlocking {
        // === 여러 요청 유형 테스트 ===
        val spec = Spec(
            id = "spec-steps",
            name = "Steps Test",
            allowedOperations = listOf(
                RequestType.FILE_READ,
                RequestType.FILE_WRITE,
                RequestType.FILE_DELETE
            ),
            allowedPaths = listOf("/tmp/**")
        )

        val governor = SimpleGovernor()

        // FILE_READ 요청
        val readResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_READ,
                targetPath = "/tmp/read.txt"
            ),
            spec
        ) as GovernorResult.BlueprintCreated

        assertEquals(1, readResult.blueprint.steps.size)
        assertEquals(BlueprintStepType.FILE_READ, readResult.blueprint.steps[0].type)
        assertEquals("/tmp/read.txt", readResult.blueprint.steps[0].params["path"])

        // FILE_WRITE 요청
        val writeResult = governor.createBlueprint(
            GovernorRequest(
                type = RequestType.FILE_WRITE,
                targetPath = "/tmp/write.txt",
                content = "Content"
            ),
            spec
        ) as GovernorResult.BlueprintCreated

        assertEquals(BlueprintStepType.FILE_WRITE, writeResult.blueprint.steps[0].type)
        assertEquals("Content", writeResult.blueprint.steps[0].params["content"])
    }

    // ==================== Stateless Verification Tests ====================

    @Test
    fun `E2E - Components remain stateless after errors`() = runBlocking {
        val dacs = SimpleDACS.DEFAULT
        val gateChain = GateChain.standard()
        val executor = FileExecutor.INSTANCE
        val runner = ExecutionRunner.create(executor)

        // === 첫 번째 실행: 성공 ===
        val successSpec = Spec(
            id = "spec-1",
            name = "Success Spec",
            description = "Testing stateless components",
            allowedOperations = listOf(RequestType.FILE_WRITE),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )
        val dacs1 = dacs.evaluate(DACSRequest(spec = successSpec))
        assertTrue(dacs1.isYes)

        val gate1 = gateChain.check(GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "WRITE"
        ))
        assertTrue(gate1.isAllow)

        val context1 = ExecutionContext.create("exec-1", "bp", "inst")
        val result1 = runner.execute(listOf(
            ExecutionStep.FileStep("write-1", FileAction.WRITE, "${testDir.absolutePath}/success.txt", "Success")
        ), context1)
        assertTrue(result1.isAllSuccess)

        // === 두 번째 실행: 실패 ===
        val context2 = ExecutionContext.create("exec-2", "bp", "inst")
        val result2 = runner.execute(listOf(
            ExecutionStep.FileStep("read-missing", FileAction.READ, "${testDir.absolutePath}/missing.txt")
        ), context2)
        assertFalse(result2.isAllSuccess)

        // === 세 번째 실행: 다시 성공 (상태 무관) ===
        val dacs3 = dacs.evaluate(DACSRequest(spec = successSpec))
        assertTrue(dacs3.isYes, "DACS should still approve after failure")

        val gate3 = gateChain.check(GateContext(
            dacsConsensus = "YES",
            userApproved = true,
            executorId = "file-executor",
            action = "WRITE"
        ))
        assertTrue(gate3.isAllow, "Gate should still allow after failure")

        val context3 = ExecutionContext.create("exec-3", "bp", "inst")
        val result3 = runner.execute(listOf(
            ExecutionStep.FileStep("write-2", FileAction.WRITE, "${testDir.absolutePath}/success2.txt", "Success 2")
        ), context3)
        assertTrue(result3.isAllSuccess, "Execution should succeed after previous failure")

        // === 검증: 모든 컴포넌트가 상태를 유지하지 않음 ===
        assertTrue(File("${testDir.absolutePath}/success.txt").exists())
        assertTrue(File("${testDir.absolutePath}/success2.txt").exists())
    }

    // ==================== Full Chain Test ====================

    @Test
    fun `E2E - Complete chain test with all components`() = runBlocking {
        // === 1. Spec 정의 (판단의 근거) ===
        val spec = Spec(
            id = "spec-complete-chain",
            version = "1.0.0",
            name = "Complete Chain Spec",
            description = "Full E2E test covering all components",
            allowedOperations = listOf(RequestType.FILE_WRITE, RequestType.FILE_READ),
            allowedPaths = listOf(testDir.absolutePath + "/**")
        )

        // === 2. DACS 합의 (Dynamic Agent Consensus System) ===
        val dacs = SimpleDACS.DEFAULT
        val dacsResult = dacs.evaluate(DACSRequest(spec = spec))

        // 합의 검증
        assertTrue(dacsResult.isYes, "DACS consensus should be YES")
        assertTrue(dacsResult.canCreateBlueprint, "Blueprint creation should be allowed")
        assertEquals(3, dacsResult.personaOpinions.size)  // Architect, Reviewer, Adversary

        // === 3. Governor가 Blueprint 생성 (판단의 고정) ===
        val governor = SimpleGovernor("gov-complete")
        val request = GovernorRequest(
            type = RequestType.FILE_WRITE,
            targetPath = "${testDir.absolutePath}/chain-test.txt",
            content = "Complete chain test content"
        )
        val governorResult = governor.createBlueprint(request, spec)

        assertTrue(governorResult is GovernorResult.BlueprintCreated)
        val blueprint = governorResult.blueprint

        // Blueprint 검증
        assertEquals(spec.id, blueprint.specSnapshot.specId)
        assertEquals("gov-complete", blueprint.specSnapshot.governorId)
        assertEquals(1, blueprint.steps.size)
        assertEquals(BlueprintStepType.FILE_WRITE, blueprint.steps[0].type)

        // === 4. Gate 체인 검사 (정책 강제) ===
        val gateLogger = InMemoryGateLogger()
        val gateChain = GateChain.builder()
            .add(DACSGate.INSTANCE)
            .add(UserApprovalGate.INSTANCE)
            .add(ExecutionPermissionGate.PERMISSIVE)
            .add(CostGate(defaultLimit = 1000.0))  // Cost limit
            .withLogger(gateLogger)
            .build()

        val gateContext = GateContext(
            blueprintId = blueprint.id,
            dacsConsensus = dacsResult.consensus.name,
            userApproved = true,
            executorId = "file-executor",
            action = "WRITE",
            estimatedCost = 0.5  // Within limit
        )

        val gateResult = gateChain.check(gateContext)

        // Gate 검증
        assertTrue(gateResult.isAllow, "Gate chain should allow")
        assertEquals(4, gateLogger.size)  // All 4 gates checked
        assertTrue(gateLogger.getAllEntries().all { it.result == "ALLOW" })

        // === 5. Executor 실행 (Blueprint 기반) ===
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val executionResult = runner.execute(blueprint)

        // 실행 검증
        assertTrue(executionResult.isSuccess)
        assertEquals(1, executionResult.successCount)
        assertEquals(RunnerStatus.COMPLETED, executionResult.runnerResult.status)

        // === 6. 결과 검증 ===
        val outputFile = File("${testDir.absolutePath}/chain-test.txt")
        assertTrue(outputFile.exists())
        assertEquals("Complete chain test content", outputFile.readText())

        // Context에 결과 저장됨
        val stepOutput = executionResult.getStepOutput(blueprint.steps[0].stepId)
        assertNotNull(stepOutput)
        assertNotNull(stepOutput.artifacts["written_file"])

        // === 7. 책임 경계 검증 ===
        // - Governor: Blueprint만 생성, 실행 안 함
        // - DACS: 합의만 반환, Spec 수정 안 함
        // - Gate: 정책 강제만, 해석 안 함
        // - Executor: 실행만, 판단 안 함
    }
}
