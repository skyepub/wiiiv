package io.wiiiv.governor

import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.blueprint.BlueprintStepType
import io.wiiiv.execution.impl.FileExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

/**
 * Governor Tests
 *
 * Governor의 판단 및 Blueprint 생성 검증
 */
class GovernorTest {

    private lateinit var testDir: File
    private lateinit var governor: SimpleGovernor

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-governor-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
        governor = SimpleGovernor("gov-test")
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ==================== Blueprint Creation Tests ====================

    @Test
    fun `Governor should create Blueprint for allowed operation`() = runBlocking {
        // Given
        val spec = Spec(
            id = "spec-file-ops",
            name = "File Operations Spec",
            allowedOperations = listOf(RequestType.FILE_READ, RequestType.FILE_WRITE)
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
        val blueprint = result.blueprint

        assertEquals(1, blueprint.steps.size)
        assertEquals(BlueprintStepType.FILE_READ, blueprint.steps[0].type)
        assertEquals("/tmp/test.txt", blueprint.steps[0].params["path"])
        assertEquals("spec-file-ops", blueprint.specSnapshot.specId)
        assertEquals("gov-test", blueprint.specSnapshot.governorId)
    }

    @Test
    fun `Governor should deny operation not in allowedOperations`() = runBlocking {
        // Given
        val spec = Spec(
            id = "spec-read-only",
            name = "Read Only Spec",
            allowedOperations = listOf(RequestType.FILE_READ)
        )

        val request = GovernorRequest(
            type = RequestType.FILE_WRITE,
            targetPath = "/tmp/test.txt",
            content = "forbidden content"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.Denied)
        assertEquals("spec-read-only", result.specId)
        assertTrue(result.reason.contains("FILE_WRITE"))
    }

    @Test
    fun `Governor should allow all operations when allowedOperations is empty`() = runBlocking {
        // Given - Empty allowedOperations means all allowed
        val spec = Spec(
            id = "spec-permissive",
            name = "Permissive Spec",
            allowedOperations = emptyList()
        )

        val request = GovernorRequest(
            type = RequestType.FILE_DELETE,
            targetPath = "/tmp/test.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
    }

    // ==================== Path Validation Tests ====================

    @Test
    fun `Governor should allow path matching glob pattern`() = runBlocking {
        // Given
        val spec = Spec(
            id = "spec-tmp-only",
            name = "Temp Directory Spec",
            allowedPaths = listOf("/tmp/**")
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/subdir/file.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
    }

    @Test
    fun `Governor should deny path not matching any pattern`() = runBlocking {
        // Given
        val spec = Spec(
            id = "spec-restricted",
            name = "Restricted Paths Spec",
            allowedPaths = listOf("/tmp/**", "/home/user/safe/**")
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/etc/passwd"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.Denied)
        assertTrue(result.reason.contains("/etc/passwd"))
    }

    @Test
    fun `Governor should allow any path when allowedPaths is empty`() = runBlocking {
        // Given - Empty allowedPaths means all paths allowed
        val spec = Spec(
            id = "spec-any-path",
            name = "Any Path Spec",
            allowedPaths = emptyList()
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/any/path/anywhere.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
    }

    // ==================== Different Operation Types ====================

    @Test
    fun `Governor should create Blueprint for FILE_WRITE`() = runBlocking {
        // Given
        val spec = Spec(id = "spec-write", name = "Write Spec")
        val request = GovernorRequest(
            type = RequestType.FILE_WRITE,
            targetPath = "/tmp/output.txt",
            content = "Hello wiiiv v2.0"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
        val step = result.blueprint.steps[0]
        assertEquals(BlueprintStepType.FILE_WRITE, step.type)
        assertEquals("/tmp/output.txt", step.params["path"])
        assertEquals("Hello wiiiv v2.0", step.params["content"])
    }

    @Test
    fun `Governor should create Blueprint for FILE_COPY`() = runBlocking {
        // Given
        val spec = Spec(id = "spec-copy", name = "Copy Spec")
        val request = GovernorRequest(
            type = RequestType.FILE_COPY,
            targetPath = "/tmp/source.txt",
            params = mapOf("target" to "/tmp/dest.txt")
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
        val step = result.blueprint.steps[0]
        assertEquals(BlueprintStepType.FILE_COPY, step.type)
        assertEquals("/tmp/source.txt", step.params["source"])
        assertEquals("/tmp/dest.txt", step.params["target"])
    }

    @Test
    fun `Governor should create Blueprint for FILE_MKDIR`() = runBlocking {
        // Given
        val spec = Spec(id = "spec-mkdir", name = "Mkdir Spec")
        val request = GovernorRequest(
            type = RequestType.FILE_MKDIR,
            targetPath = "/tmp/new-directory"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
        val step = result.blueprint.steps[0]
        assertEquals(BlueprintStepType.FILE_MKDIR, step.type)
    }

    @Test
    fun `Governor should fail for CUSTOM request type`() = runBlocking {
        // Given
        val spec = Spec(id = "spec-custom", name = "Custom Spec")
        val request = GovernorRequest(type = RequestType.CUSTOM)

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.Failed)
    }

    // ==================== End-to-End Integration Test ====================

    @Test
    fun `Governor to BlueprintRunner integration should work`() = runBlocking {
        // Given
        val sourceFile = File(testDir, "source.txt")
        sourceFile.writeText("Governor → Blueprint → Runner → Executor")

        val spec = Spec(
            id = "spec-integration-test",
            version = "1.0.0",
            name = "Integration Test Spec",
            description = "Full integration test from Governor to Executor"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = sourceFile.absolutePath
        )

        // When - Governor creates Blueprint
        val governorResult = governor.createBlueprint(request, spec)
        assertTrue(governorResult is GovernorResult.BlueprintCreated)
        val blueprint = governorResult.blueprint

        // When - BlueprintRunner executes Blueprint
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val executionResult = runner.execute(blueprint)

        // Then
        assertTrue(executionResult.isSuccess)
        assertEquals(blueprint.id, executionResult.blueprintId)

        val output = executionResult.getStepOutput(blueprint.steps[0].stepId)
        assertNotNull(output)
        assertEquals(
            "Governor → Blueprint → Runner → Executor",
            output.json["content"]?.jsonPrimitive?.content
        )
    }

    @Test
    fun `Complete workflow - Governor creates and executes write operation`() = runBlocking {
        // Given
        val spec = Spec(
            id = "spec-complete-workflow",
            name = "Complete Workflow Spec",
            allowedOperations = listOf(RequestType.FILE_WRITE, RequestType.FILE_READ)
        )

        val outputPath = "${testDir.absolutePath}/governor-output.txt"
        val writeRequest = GovernorRequest(
            type = RequestType.FILE_WRITE,
            targetPath = outputPath,
            content = "Written by wiiiv v2.0 Governor"
        )

        // Step 1: Governor creates write Blueprint
        val writeResult = governor.createBlueprint(writeRequest, spec)
        assertTrue(writeResult is GovernorResult.BlueprintCreated)

        // Step 2: Execute write Blueprint
        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)
        val writeExecution = runner.execute(writeResult.blueprint)
        assertTrue(writeExecution.isSuccess)

        // Verify file was written
        assertTrue(File(outputPath).exists())
        assertEquals("Written by wiiiv v2.0 Governor", File(outputPath).readText())

        // Step 3: Governor creates read Blueprint
        val readRequest = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = outputPath
        )
        val readResult = governor.createBlueprint(readRequest, spec)
        assertTrue(readResult is GovernorResult.BlueprintCreated)

        // Step 4: Execute read Blueprint
        val readExecution = runner.execute(readResult.blueprint)
        assertTrue(readExecution.isSuccess)

        // Verify content matches
        val readOutput = readExecution.getStepOutput(
            readResult.blueprint.steps[0].stepId
        )
        assertEquals(
            "Written by wiiiv v2.0 Governor",
            readOutput?.json?.get("content")?.jsonPrimitive?.content
        )
    }

    // ==================== SpecSnapshot Tests ====================

    @Test
    fun `Blueprint should contain correct SpecSnapshot`() = runBlocking {
        // Given
        val spec = Spec(
            id = "spec-snapshot-test",
            version = "2.1.0",
            name = "Snapshot Test Spec"
        )

        val request = GovernorRequest(
            type = RequestType.FILE_READ,
            targetPath = "/tmp/test.txt"
        )

        // When
        val result = governor.createBlueprint(request, spec)

        // Then
        assertTrue(result is GovernorResult.BlueprintCreated)
        val snapshot = result.blueprint.specSnapshot

        assertEquals("spec-snapshot-test", snapshot.specId)
        assertEquals("2.1.0", snapshot.specVersion)
        assertEquals("gov-test", snapshot.governorId)
        assertEquals("DIRECT_ALLOW", snapshot.dacsResult)
        assertNotNull(snapshot.snapshotAt) // ISO-8601 timestamp
    }
}
