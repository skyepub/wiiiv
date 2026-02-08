package io.wiiiv.execution

import io.wiiiv.execution.impl.CommandExecutor
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

/**
 * CommandExecutor Tests
 *
 * 셸 명령 실행 테스트
 */
class CommandExecutorTest {

    private lateinit var testDir: File
    private lateinit var executor: CommandExecutor
    private lateinit var context: ExecutionContext

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-cmd-test-${System.currentTimeMillis()}")
        testDir.mkdirs()

        executor = CommandExecutor.INSTANCE
        context = ExecutionContext.create(
            executionId = "test-exec",
            blueprintId = "test-bp",
            instructionId = "test-inst"
        )
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ==================== Basic Command Tests ====================

    @Test
    fun `should execute simple echo command`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "echo-1",
            command = "echo",
            args = listOf("Hello", "wiiiv")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val output = result.output
        assertEquals(0, output.json["exitCode"]?.jsonPrimitive?.content?.toInt())
        assertTrue(output.json["stdout"]?.jsonPrimitive?.content?.contains("Hello wiiiv") == true)
    }

    @Test
    fun `should capture command output`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "pwd-1",
            command = "pwd"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content
        assertNotNull(stdout)
        assertTrue(stdout.isNotBlank())
    }

    @Test
    fun `should pass arguments correctly`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "expr-1",
            command = "expr",
            args = listOf("2", "+", "3")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content?.trim()
        assertEquals("5", stdout)
    }

    // ==================== Working Directory Tests ====================

    @Test
    fun `should execute in specified working directory`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "pwd-workdir",
            command = "pwd",
            workingDir = testDir.absolutePath
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content?.trim()
        assertTrue(stdout?.contains(testDir.name) == true)
    }

    @Test
    fun `should return error for non-existent working directory`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "pwd-nodir",
            command = "pwd",
            workingDir = "/nonexistent/directory/path"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, result.error.category)
        assertEquals("WORKING_DIR_NOT_FOUND", result.error.code)
    }

    // ==================== Environment Variables Tests ====================

    @Test
    fun `should set environment variables`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "env-test",
            command = "sh",
            args = listOf("-c", "echo \$WIIIV_TEST_VAR"),
            env = mapOf("WIIIV_TEST_VAR" to "test_value_123")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content?.trim()
        assertEquals("test_value_123", stdout)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `should return error for non-existent command`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "noexist-1",
            command = "nonexistent_command_xyz_12345"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.RESOURCE_NOT_FOUND, result.error.category)
        assertEquals("COMMAND_NOT_FOUND", result.error.code)
    }

    @Test
    fun `should return error for non-zero exit code`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "false-1",
            command = "false"  // Always returns exit code 1
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, result.error.category)
        assertEquals("COMMAND_FAILED", result.error.code)

        // Should have partial output with exit code
        assertNotNull(result.partialOutput)
    }

    @Test
    fun `should capture stderr in output`() = runBlocking {
        // Given - Command that writes to stderr
        val step = ExecutionStep.CommandStep(
            stepId = "stderr-test",
            command = "sh",
            args = listOf("-c", "echo 'error message' >&2 && exit 1")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        val partialOutput = result.partialOutput
        assertNotNull(partialOutput)
        assertTrue(partialOutput.json["stdout"]?.jsonPrimitive?.content?.contains("error message") == true)
    }

    // ==================== Timeout Tests ====================

    @Test
    fun `should timeout long running command`() = runBlocking {
        // Given - Command that sleeps for 10 seconds but timeout is 500ms
        val step = ExecutionStep.CommandStep(
            stepId = "sleep-timeout",
            command = "sleep",
            args = listOf("10"),
            timeoutMs = 500
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.TIMEOUT, result.error.category)
        assertEquals("COMMAND_TIMEOUT", result.error.code)
    }

    @Test
    fun `should complete before timeout`() = runBlocking {
        // Given - Quick command with generous timeout
        val step = ExecutionStep.CommandStep(
            stepId = "fast-cmd",
            command = "echo",
            args = listOf("fast"),
            timeoutMs = 5000
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
    }

    // ==================== stdin Tests ====================

    @Test
    fun `should pass stdin to command`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "stdin-test",
            command = "cat",
            stdin = "Hello from stdin"
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content
        assertEquals("Hello from stdin", stdout)
    }

    // ==================== Complex Command Tests ====================

    @Test
    fun `should execute shell script via sh -c`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "script-1",
            command = "sh",
            args = listOf("-c", "echo 'line1' && echo 'line2'")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content
        assertTrue(stdout?.contains("line1") == true)
        assertTrue(stdout?.contains("line2") == true)
    }

    @Test
    fun `should handle pipe commands via shell`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "pipe-1",
            command = "sh",
            args = listOf("-c", "echo 'hello world' | wc -w")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val stdout = result.output.json["stdout"]?.jsonPrimitive?.content?.trim()
        assertEquals("2", stdout)
    }

    // ==================== Integration with Runner ====================

    @Test
    fun `Runner should execute multiple command steps`() = runBlocking {
        // Given
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.CommandStep(
                stepId = "step-1-mkdir",
                command = "mkdir",
                args = listOf("-p", "${testDir.absolutePath}/cmd-output")
            ),
            ExecutionStep.CommandStep(
                stepId = "step-2-touch",
                command = "touch",
                args = listOf("${testDir.absolutePath}/cmd-output/test.txt")
            ),
            ExecutionStep.CommandStep(
                stepId = "step-3-ls",
                command = "ls",
                args = listOf("${testDir.absolutePath}/cmd-output")
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
        assertEquals(3, result.successCount)
        assertTrue(result.isAllSuccess)

        // Verify file was created
        assertTrue(File("${testDir.absolutePath}/cmd-output/test.txt").exists())

        // Verify ls output
        val lsOutput = runnerContext.getStepOutput("step-3-ls")
        assertNotNull(lsOutput)
        assertTrue(lsOutput.json["stdout"]?.jsonPrimitive?.content?.contains("test.txt") == true)
    }

    // ==================== canHandle Tests ====================

    @Test
    fun `canHandle should return true for CommandStep`() {
        val step = ExecutionStep.CommandStep(
            stepId = "test",
            command = "echo"
        )
        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `canHandle should return false for other steps`() {
        val step = ExecutionStep.FileStep(
            stepId = "test",
            action = FileAction.READ,
            path = "/tmp/test.txt"
        )
        assertFalse(executor.canHandle(step))
    }

    // ==================== Output Metadata Tests ====================

    @Test
    fun `output should include command and args`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "meta-test",
            command = "echo",
            args = listOf("arg1", "arg2")
        )

        // When
        val result = executor.execute(step, context)

        // Then
        assertTrue(result is ExecutionResult.Success)
        val output = result.output.json
        assertEquals("echo", output["command"]?.jsonPrimitive?.content)
        assertEquals("arg1 arg2", output["args"]?.jsonPrimitive?.content)
    }

    @Test
    fun `output should be added to context`() = runBlocking {
        // Given
        val step = ExecutionStep.CommandStep(
            stepId = "context-test",
            command = "echo",
            args = listOf("context output")
        )

        // When
        executor.execute(step, context)

        // Then
        val storedOutput = context.getStepOutput("context-test")
        assertNotNull(storedOutput)
        assertTrue(storedOutput.json["stdout"]?.jsonPrimitive?.content?.contains("context output") == true)
    }
}
