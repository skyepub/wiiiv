package io.wiiiv.execution

import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

/**
 * FileExecutor Tests
 *
 * 실제 파일 시스템과 상호작용하는 테스트
 */
class FileExecutorTest {

    private lateinit var testDir: File
    private lateinit var executor: FileExecutor
    private lateinit var context: ExecutionContext

    @BeforeTest
    fun setup() {
        // Create temp directory for tests
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-test-${System.currentTimeMillis()}")
        testDir.mkdirs()

        executor = FileExecutor.INSTANCE
        context = ExecutionContext.create(
            executionId = "test-exec",
            blueprintId = "test-bp",
            instructionId = "test-inst"
        )
    }

    @AfterTest
    fun cleanup() {
        // Clean up test directory
        testDir.deleteRecursively()
    }

    // ==================== READ Tests ====================

    @Test
    fun `READ should return file content`() = runBlocking {
        // Given
        val testFile = File(testDir, "read-test.txt")
        testFile.writeText("Hello, wiiiv!")

        val step = ExecutionStep.FileStep(
            stepId = "read-1",
            action = FileAction.READ,
            path = testFile.absolutePath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val output = result.output
        assertEquals("Hello, wiiiv!", output.json["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `READ should return RESOURCE_NOT_FOUND for missing file`() = runBlocking {
        // Given
        val step = ExecutionStep.FileStep(
            stepId = "read-2",
            action = FileAction.READ,
            path = "${testDir.absolutePath}/nonexistent.txt"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, result.error.category)
        assertEquals("FILE_NOT_FOUND", result.error.code)
    }

    // ==================== WRITE Tests ====================

    @Test
    fun `WRITE should create file with content`() = runBlocking {
        // Given
        val targetPath = "${testDir.absolutePath}/write-test.txt"
        val content = "Written by wiiiv v2.0"

        val step = ExecutionStep.FileStep(
            stepId = "write-1",
            action = FileAction.WRITE,
            path = targetPath,
            content = content
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)

        val writtenFile = File(targetPath)
        assertTrue(writtenFile.exists())
        assertEquals(content, writtenFile.readText())
    }

    @Test
    fun `WRITE should create parent directories`() = runBlocking {
        // Given
        val targetPath = "${testDir.absolutePath}/nested/dir/write-test.txt"
        val content = "Nested content"

        val step = ExecutionStep.FileStep(
            stepId = "write-2",
            action = FileAction.WRITE,
            path = targetPath,
            content = content
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        assertTrue(File(targetPath).exists())
    }

    // ==================== COPY Tests ====================

    @Test
    fun `COPY should copy file to target`() = runBlocking {
        // Given
        val sourceFile = File(testDir, "copy-source.txt")
        sourceFile.writeText("Copy me!")
        val targetPath = "${testDir.absolutePath}/copy-target.txt"

        val step = ExecutionStep.FileStep(
            stepId = "copy-1",
            action = FileAction.COPY,
            path = sourceFile.absolutePath,
            targetPath = targetPath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)

        val targetFile = File(targetPath)
        assertTrue(targetFile.exists())
        assertEquals("Copy me!", targetFile.readText())
        assertTrue(sourceFile.exists()) // Source still exists
    }

    @Test
    fun `COPY should return error for missing source`() = runBlocking {
        // Given
        val step = ExecutionStep.FileStep(
            stepId = "copy-2",
            action = FileAction.COPY,
            path = "${testDir.absolutePath}/nonexistent.txt",
            targetPath = "${testDir.absolutePath}/target.txt"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, result.error.category)
    }

    // ==================== MOVE Tests ====================

    @Test
    fun `MOVE should move file to target`() = runBlocking {
        // Given
        val sourceFile = File(testDir, "move-source.txt")
        sourceFile.writeText("Move me!")
        val targetPath = "${testDir.absolutePath}/move-target.txt"

        val step = ExecutionStep.FileStep(
            stepId = "move-1",
            action = FileAction.MOVE,
            path = sourceFile.absolutePath,
            targetPath = targetPath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)

        val targetFile = File(targetPath)
        assertTrue(targetFile.exists())
        assertEquals("Move me!", targetFile.readText())
        assertFalse(sourceFile.exists()) // Source is gone
    }

    // ==================== DELETE Tests ====================

    @Test
    fun `DELETE should remove file`() = runBlocking {
        // Given
        val fileToDelete = File(testDir, "delete-me.txt")
        fileToDelete.writeText("Delete me!")

        val step = ExecutionStep.FileStep(
            stepId = "delete-1",
            action = FileAction.DELETE,
            path = fileToDelete.absolutePath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        assertFalse(fileToDelete.exists())
    }

    @Test
    fun `DELETE should return error for missing file`() = runBlocking {
        // Given
        val step = ExecutionStep.FileStep(
            stepId = "delete-2",
            action = FileAction.DELETE,
            path = "${testDir.absolutePath}/nonexistent.txt"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, result.error.category)
    }

    // ==================== MKDIR Tests ====================

    @Test
    fun `MKDIR should create directory`() = runBlocking {
        // Given
        val dirPath = "${testDir.absolutePath}/new-dir"

        val step = ExecutionStep.FileStep(
            stepId = "mkdir-1",
            action = FileAction.MKDIR,
            path = dirPath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        assertTrue(File(dirPath).isDirectory)
    }

    @Test
    fun `MKDIR should succeed for existing directory`() = runBlocking {
        // Given
        val existingDir = File(testDir, "existing-dir")
        existingDir.mkdirs()

        val step = ExecutionStep.FileStep(
            stepId = "mkdir-2",
            action = FileAction.MKDIR,
            path = existingDir.absolutePath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
    }

    // ==================== Integration with Runner ====================

    @Test
    fun `Runner should execute multiple file steps sequentially`() = runBlocking {
        // Given
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            // 1. Create directory
            ExecutionStep.FileStep(
                stepId = "step-1-mkdir",
                action = FileAction.MKDIR,
                path = "${testDir.absolutePath}/workflow"
            ),
            // 2. Write file
            ExecutionStep.FileStep(
                stepId = "step-2-write",
                action = FileAction.WRITE,
                path = "${testDir.absolutePath}/workflow/data.txt",
                content = "wiiiv workflow test"
            ),
            // 3. Copy file
            ExecutionStep.FileStep(
                stepId = "step-3-copy",
                action = FileAction.COPY,
                path = "${testDir.absolutePath}/workflow/data.txt",
                targetPath = "${testDir.absolutePath}/workflow/data-backup.txt"
            ),
            // 4. Read file
            ExecutionStep.FileStep(
                stepId = "step-4-read",
                action = FileAction.READ,
                path = "${testDir.absolutePath}/workflow/data-backup.txt"
            )
        )

        val runnerContext = ExecutionContext.create(
            executionId = "workflow-exec",
            blueprintId = "workflow-bp",
            instructionId = "workflow-inst"
        )

        // When
        val result = runner.execute(steps, runnerContext)

        // Then
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(4, result.successCount)
        assertTrue(result.isAllSuccess)

        // Verify files exist
        assertTrue(File("${testDir.absolutePath}/workflow").isDirectory)
        assertTrue(File("${testDir.absolutePath}/workflow/data.txt").exists())
        assertTrue(File("${testDir.absolutePath}/workflow/data-backup.txt").exists())

        // Verify content via context
        val readOutput = runnerContext.getStepOutput("step-4-read")
        assertNotNull(readOutput)
        assertEquals("wiiiv workflow test", readOutput.json["content"]?.jsonPrimitive?.content)
    }

    // ==================== canHandle Tests ====================

    @Test
    fun `canHandle should return true for FileStep`() {
        val step = ExecutionStep.FileStep(
            stepId = "test",
            action = FileAction.READ,
            path = "/tmp/test.txt"
        )
        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `canHandle should return false for other steps`() {
        val step = ExecutionStep.CommandStep(
            stepId = "test",
            command = "echo hello"
        )
        assertFalse(executor.canHandle(step))
    }
}
