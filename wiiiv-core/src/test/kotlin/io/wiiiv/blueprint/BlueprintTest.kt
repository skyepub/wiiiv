package io.wiiiv.blueprint

import io.wiiiv.execution.ExecutionStep
import io.wiiiv.execution.FileAction
import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.*

/**
 * Blueprint Tests
 *
 * Blueprint 파싱 및 실행 검증
 */
class BlueprintTest {

    /** Escape backslashes for safe JSON string interpolation (Windows paths) */
    private fun String.jsonEscape() = replace("\\", "\\\\")

    private lateinit var testDir: File

    @BeforeTest
    fun setup() {
        testDir = File(System.getProperty("java.io.tmpdir"), "wiiiv-blueprint-test-${System.currentTimeMillis()}")
        testDir.mkdirs()
    }

    @AfterTest
    fun cleanup() {
        testDir.deleteRecursively()
    }

    // ==================== Blueprint Parsing Tests ====================

    @Test
    fun `Blueprint should parse from JSON`() {
        // Given
        val json = """
            {
                "id": "bp-001",
                "version": "1.0",
                "specSnapshot": {
                    "specId": "spec-file-backup",
                    "specVersion": "1.0.0",
                    "snapshotAt": "2024-01-15T10:00:00Z",
                    "governorId": "gov-main"
                },
                "steps": [
                    {
                        "stepId": "step-1",
                        "type": "file_read",
                        "params": {
                            "path": "/tmp/source.txt"
                        }
                    }
                ]
            }
        """.trimIndent()

        // When
        val blueprint = Blueprint.fromJson(json)

        // Then
        assertEquals("bp-001", blueprint.id)
        assertEquals("1.0", blueprint.version)
        assertEquals("spec-file-backup", blueprint.specSnapshot.specId)
        assertEquals("gov-main", blueprint.specSnapshot.governorId)
        assertEquals(1, blueprint.steps.size)
        assertEquals("step-1", blueprint.steps[0].stepId)
        assertEquals(BlueprintStepType.FILE_READ, blueprint.steps[0].type)
    }

    @Test
    fun `Blueprint should parse all step types`() {
        // Given
        val json = """
            {
                "id": "bp-002",
                "specSnapshot": {
                    "specId": "spec-all-ops",
                    "snapshotAt": "2024-01-15T10:00:00Z",
                    "governorId": "gov-main"
                },
                "steps": [
                    {"stepId": "s1", "type": "file_read", "params": {"path": "/tmp/a.txt"}},
                    {"stepId": "s2", "type": "file_write", "params": {"path": "/tmp/b.txt", "content": "hello"}},
                    {"stepId": "s3", "type": "file_copy", "params": {"source": "/tmp/a.txt", "target": "/tmp/c.txt"}},
                    {"stepId": "s4", "type": "file_move", "params": {"source": "/tmp/c.txt", "target": "/tmp/d.txt"}},
                    {"stepId": "s5", "type": "file_delete", "params": {"path": "/tmp/d.txt"}},
                    {"stepId": "s6", "type": "file_mkdir", "params": {"path": "/tmp/newdir"}},
                    {"stepId": "s7", "type": "command", "params": {"command": "echo", "args": "hello"}},
                    {"stepId": "s8", "type": "noop", "params": {"reason": "placeholder"}}
                ]
            }
        """.trimIndent()

        // When
        val blueprint = Blueprint.fromJson(json)

        // Then
        assertEquals(8, blueprint.steps.size)
        assertEquals(BlueprintStepType.FILE_READ, blueprint.steps[0].type)
        assertEquals(BlueprintStepType.FILE_WRITE, blueprint.steps[1].type)
        assertEquals(BlueprintStepType.FILE_COPY, blueprint.steps[2].type)
        assertEquals(BlueprintStepType.FILE_MOVE, blueprint.steps[3].type)
        assertEquals(BlueprintStepType.FILE_DELETE, blueprint.steps[4].type)
        assertEquals(BlueprintStepType.FILE_MKDIR, blueprint.steps[5].type)
        assertEquals(BlueprintStepType.COMMAND, blueprint.steps[6].type)
        assertEquals(BlueprintStepType.NOOP, blueprint.steps[7].type)
    }

    @Test
    fun `BlueprintStep should convert to ExecutionStep correctly`() {
        // Given
        val blueprintStep = BlueprintStep(
            stepId = "test-step",
            type = BlueprintStepType.FILE_WRITE,
            params = mapOf(
                "path" to "/tmp/test.txt",
                "content" to "test content"
            )
        )

        // When
        val executionStep = blueprintStep.toExecutionStep()

        // Then
        assertTrue(executionStep is ExecutionStep.FileStep)
        val fileStep = executionStep as ExecutionStep.FileStep
        assertEquals("test-step", fileStep.stepId)
        assertEquals(FileAction.WRITE, fileStep.action)
        assertEquals("/tmp/test.txt", fileStep.path)
        assertEquals("test content", fileStep.content)
    }

    @Test
    fun `Blueprint toJson should produce valid JSON`() {
        // Given
        val blueprint = Blueprint(
            id = "bp-roundtrip",
            specSnapshot = SpecSnapshot(
                specId = "spec-test",
                snapshotAt = "2024-01-15T10:00:00Z",
                governorId = "gov-test"
            ),
            steps = listOf(
                BlueprintStep(
                    stepId = "s1",
                    type = BlueprintStepType.FILE_READ,
                    params = mapOf("path" to "/tmp/test.txt")
                )
            )
        )

        // When
        val json = Blueprint.toJson(blueprint)
        val parsed = Blueprint.fromJson(json)

        // Then
        assertEquals(blueprint.id, parsed.id)
        assertEquals(blueprint.specSnapshot.specId, parsed.specSnapshot.specId)
        assertEquals(blueprint.steps.size, parsed.steps.size)
    }

    // ==================== BlueprintRunner Tests ====================

    @Test
    fun `BlueprintRunner should execute Blueprint with FileExecutor`() = runBlocking {
        // Given
        val sourceFile = File(testDir, "source.txt")
        sourceFile.writeText("Blueprint execution test!")

        val blueprintJson = """
            {
                "id": "bp-file-workflow",
                "specSnapshot": {
                    "specId": "spec-file-ops",
                    "snapshotAt": "2024-01-15T10:00:00Z",
                    "governorId": "gov-wiiiv"
                },
                "steps": [
                    {
                        "stepId": "step-read",
                        "type": "file_read",
                        "params": {
                            "path": "${sourceFile.absolutePath.jsonEscape()}"
                        }
                    },
                    {
                        "stepId": "step-mkdir",
                        "type": "file_mkdir",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/output"
                        }
                    },
                    {
                        "stepId": "step-write",
                        "type": "file_write",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/output/result.txt",
                            "content": "Processed by wiiiv v2.0"
                        }
                    }
                ]
            }
        """.trimIndent()

        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)

        // When
        val result = runner.executeFromJson(blueprintJson)

        // Then
        assertTrue(result.isSuccess)
        assertEquals("bp-file-workflow", result.blueprintId)
        assertEquals(3, result.successCount)
        assertEquals(0, result.failureCount)

        // Verify file was created
        val outputFile = File("${testDir.absolutePath}/output/result.txt")
        assertTrue(outputFile.exists())
        assertEquals("Processed by wiiiv v2.0", outputFile.readText())

        // Verify step output is accessible
        val readOutput = result.getStepOutput("step-read")
        assertNotNull(readOutput)
        assertEquals("Blueprint execution test!", readOutput.json["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `BlueprintRunner should return failure on missing file`() = runBlocking {
        // Given
        val blueprintJson = """
            {
                "id": "bp-fail-test",
                "specSnapshot": {
                    "specId": "spec-fail",
                    "snapshotAt": "2024-01-15T10:00:00Z",
                    "governorId": "gov-test"
                },
                "steps": [
                    {
                        "stepId": "step-read-missing",
                        "type": "file_read",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/nonexistent.txt"
                        }
                    }
                ]
            }
        """.trimIndent()

        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)

        // When
        val result = runner.executeFromJson(blueprintJson)

        // Then
        assertFalse(result.isSuccess)
        assertEquals(RunnerStatus.FAILED, result.runnerResult.status)
        assertEquals(0, result.successCount)
        assertEquals(1, result.failureCount)
    }

    @Test
    fun `BlueprintRunner should stop on first failure (fail-fast)`() = runBlocking {
        // Given
        val blueprintJson = """
            {
                "id": "bp-failfast",
                "specSnapshot": {
                    "specId": "spec-failfast",
                    "snapshotAt": "2024-01-15T10:00:00Z",
                    "governorId": "gov-test"
                },
                "steps": [
                    {
                        "stepId": "step-1-fail",
                        "type": "file_read",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/missing.txt"
                        }
                    },
                    {
                        "stepId": "step-2-never-executed",
                        "type": "file_mkdir",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/never-created"
                        }
                    }
                ]
            }
        """.trimIndent()

        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)

        // When
        val result = runner.executeFromJson(blueprintJson)

        // Then
        assertFalse(result.isSuccess)
        assertEquals(1, result.runnerResult.results.size) // Only 1 step executed
        assertFalse(File("${testDir.absolutePath}/never-created").exists())
    }

    // ==================== Blueprint Immutability Tests ====================

    @Test
    fun `Blueprint should be immutable (data class)`() {
        // Given
        val blueprint = Blueprint(
            id = "bp-immutable",
            specSnapshot = SpecSnapshot(
                specId = "spec-test",
                snapshotAt = "2024-01-15T10:00:00Z",
                governorId = "gov-test"
            ),
            steps = listOf(
                BlueprintStep(
                    stepId = "s1",
                    type = BlueprintStepType.NOOP
                )
            )
        )

        // When - Create a copy with different id
        val copy = blueprint.copy(id = "bp-copy")

        // Then - Original is unchanged
        assertEquals("bp-immutable", blueprint.id)
        assertEquals("bp-copy", copy.id)
    }

    // ==================== Complex Workflow Test ====================

    @Test
    fun `BlueprintRunner should execute complex file workflow`() = runBlocking {
        // Given: A complete file backup workflow
        val originalFile = File(testDir, "original.txt")
        originalFile.writeText("Original content - wiiiv v2.0 test")

        val blueprintJson = """
            {
                "id": "bp-backup-workflow",
                "version": "1.0",
                "specSnapshot": {
                    "specId": "spec-file-backup",
                    "specVersion": "1.0.0",
                    "snapshotAt": "2024-01-15T10:00:00Z",
                    "governorId": "gov-wiiiv-main",
                    "dacsResult": "ALLOW"
                },
                "steps": [
                    {
                        "stepId": "step-1-read-original",
                        "type": "file_read",
                        "params": {
                            "path": "${originalFile.absolutePath.jsonEscape()}"
                        }
                    },
                    {
                        "stepId": "step-2-create-backup-dir",
                        "type": "file_mkdir",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/backup"
                        }
                    },
                    {
                        "stepId": "step-3-copy-to-backup",
                        "type": "file_copy",
                        "params": {
                            "source": "${originalFile.absolutePath.jsonEscape()}",
                            "target": "${testDir.absolutePath.jsonEscape()}/backup/original.txt.bak"
                        }
                    },
                    {
                        "stepId": "step-4-write-log",
                        "type": "file_write",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/backup/backup.log",
                            "content": "Backup completed successfully"
                        }
                    },
                    {
                        "stepId": "step-5-verify-backup",
                        "type": "file_read",
                        "params": {
                            "path": "${testDir.absolutePath.jsonEscape()}/backup/original.txt.bak"
                        }
                    }
                ],
                "metadata": {
                    "createdBy": "test",
                    "description": "Complete file backup workflow test",
                    "tags": ["test", "backup", "file-ops"]
                }
            }
        """.trimIndent()

        val runner = BlueprintRunner.create(FileExecutor.INSTANCE)

        // When
        val result = runner.executeFromJson(blueprintJson)

        // Then
        assertTrue(result.isSuccess, "Workflow should succeed")
        assertEquals("bp-backup-workflow", result.blueprintId)
        assertEquals(5, result.successCount)
        assertEquals(RunnerStatus.COMPLETED, result.runnerResult.status)

        // Verify all files exist
        assertTrue(File("${testDir.absolutePath}/backup").isDirectory)
        assertTrue(File("${testDir.absolutePath}/backup/original.txt.bak").exists())
        assertTrue(File("${testDir.absolutePath}/backup/backup.log").exists())

        // Verify backup content matches original
        val backupContent = File("${testDir.absolutePath}/backup/original.txt.bak").readText()
        assertEquals("Original content - wiiiv v2.0 test", backupContent)

        // Verify log content
        val logContent = File("${testDir.absolutePath}/backup/backup.log").readText()
        assertEquals("Backup completed successfully", logContent)

        // Verify step output from context
        val verifyOutput = result.getStepOutput("step-5-verify-backup")
        assertNotNull(verifyOutput)
        assertEquals(
            "Original content - wiiiv v2.0 test",
            verifyOutput.json["content"]?.jsonPrimitive?.content
        )
    }
}
