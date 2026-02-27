package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import io.wiiiv.execution.PathResolver
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant

/**
 * File Executor - 파일 작업 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: 파일이 위험한지, 적절한지 판단하지 않음
 * - 해석하지 않는다: 파일 내용의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 작업
 *
 * - READ: 파일 읽기 → Success (content in output)
 * - WRITE: 파일 쓰기 → Success (path in output)
 * - COPY: 파일 복사 → Success (target path in output)
 * - MOVE: 파일 이동 → Success (target path in output)
 * - DELETE: 파일 삭제 → Success (deleted path in output)
 * - MKDIR: 디렉토리 생성 → Success (created path in output)
 *
 * ## 오류 처리
 *
 * - 파일 없음 → Failure (RESOURCE_NOT_FOUND)
 * - 권한 없음 → Failure (PERMISSION_DENIED)
 * - IO 오류 → Failure (IO_ERROR)
 */
class FileExecutor : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        // Type check
        if (step !is ExecutionStep.FileStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "FileExecutor can only handle FileStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val result = when (step.action) {
                FileAction.READ -> executeRead(step, context)
                FileAction.WRITE -> executeWrite(step, context)
                FileAction.COPY -> executeCopy(step, context)
                FileAction.MOVE -> executeMove(step, context)
                FileAction.DELETE -> executeDelete(step, context)
                FileAction.MKDIR -> executeMkdir(step, context)
            }

            val endedAt = Instant.now()

            when (result) {
                is FileOperationResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.path)
                    )

                    // Add to context
                    context.addStepOutput(step.stepId, output)

                    ExecutionResult.Success(output = output, meta = meta)
                }
                is FileOperationResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.path)
                    )

                    ExecutionResult.Failure(error = result.error, meta = meta)
                }
            }
        } catch (e: SecurityException) {
            ExecutionResult.Failure(
                error = ExecutionError(
                    category = ErrorCategory.PERMISSION_DENIED,
                    code = "ACCESS_DENIED",
                    message = "Permission denied: ${e.message}"
                ),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.path)
                )
            )
        } catch (e: IOException) {
            ExecutionResult.Failure(
                error = ExecutionError(
                    category = ErrorCategory.IO_ERROR,
                    code = "IO_EXCEPTION",
                    message = "IO error: ${e.message}"
                ),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.path)
                )
            )
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.path)
                )
            )
        }
    }

    /**
     * READ - 파일 읽기
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeRead(step: ExecutionStep.FileStep, context: ExecutionContext): FileOperationResult {
        val file = File(PathResolver.resolve(step.path))

        if (!file.exists()) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.RESOURCE_NOT_FOUND,
                    code = "FILE_NOT_FOUND",
                    message = "File not found: ${step.path}"
                )
            )
        }

        if (!file.canRead()) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.PERMISSION_DENIED,
                    code = "READ_DENIED",
                    message = "Cannot read file: ${step.path}"
                )
            )
        }

        val content = file.readText()
        val size = file.length()

        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("path", JsonPrimitive(step.path))
                put("content", JsonPrimitive(content))
                put("size", JsonPrimitive(size))
                put("action", JsonPrimitive("READ"))
            }
        )

        return FileOperationResult.Success(output)
    }

    /**
     * WRITE - 파일 쓰기
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeWrite(step: ExecutionStep.FileStep, context: ExecutionContext): FileOperationResult {
        val file = File(PathResolver.resolve(step.path))
        val content = step.content ?: ""

        // Create parent directories if needed
        file.parentFile?.mkdirs()

        // If the target path exists as a directory, remove it before writing
        if (file.isDirectory) {
            file.deleteRecursively()
        }

        file.writeText(content)

        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("path", JsonPrimitive(step.path))
                put("size", JsonPrimitive(content.length.toLong()))
                put("action", JsonPrimitive("WRITE"))
            },
            artifacts = mapOf("written_file" to step.path)
        )

        return FileOperationResult.Success(output)
    }

    /**
     * COPY - 파일 복사
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeCopy(step: ExecutionStep.FileStep, context: ExecutionContext): FileOperationResult {
        val source = File(PathResolver.resolve(step.path))
        val targetPath = step.targetPath ?: return FileOperationResult.Error(
            ExecutionError.contractViolation("MISSING_TARGET", "Copy requires targetPath")
        )
        val target = File(PathResolver.resolve(targetPath))

        if (!source.exists()) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.RESOURCE_NOT_FOUND,
                    code = "SOURCE_NOT_FOUND",
                    message = "Source file not found: ${step.path}"
                )
            )
        }

        // Create parent directories if needed
        target.parentFile?.mkdirs()

        Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("source", JsonPrimitive(step.path))
                put("target", JsonPrimitive(step.targetPath))
                put("size", JsonPrimitive(source.length()))
                put("action", JsonPrimitive("COPY"))
            },
            artifacts = mapOf("copied_file" to step.targetPath)
        )

        return FileOperationResult.Success(output)
    }

    /**
     * MOVE - 파일 이동
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeMove(step: ExecutionStep.FileStep, context: ExecutionContext): FileOperationResult {
        val source = File(PathResolver.resolve(step.path))
        val targetPath = step.targetPath ?: return FileOperationResult.Error(
            ExecutionError.contractViolation("MISSING_TARGET", "Move requires targetPath")
        )
        val target = File(PathResolver.resolve(targetPath))

        if (!source.exists()) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.RESOURCE_NOT_FOUND,
                    code = "SOURCE_NOT_FOUND",
                    message = "Source file not found: ${step.path}"
                )
            )
        }

        // Create parent directories if needed
        target.parentFile?.mkdirs()

        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)

        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("source", JsonPrimitive(step.path))
                put("target", JsonPrimitive(step.targetPath))
                put("action", JsonPrimitive("MOVE"))
            },
            artifacts = mapOf("moved_file" to step.targetPath)
        )

        return FileOperationResult.Success(output)
    }

    /**
     * DELETE - 파일 삭제
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeDelete(step: ExecutionStep.FileStep, context: ExecutionContext): FileOperationResult {
        val file = File(PathResolver.resolve(step.path))

        if (!file.exists()) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.RESOURCE_NOT_FOUND,
                    code = "FILE_NOT_FOUND",
                    message = "File not found: ${step.path}"
                )
            )
        }

        val deleted = file.deleteRecursively()

        if (!deleted) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.IO_ERROR,
                    code = "DELETE_FAILED",
                    message = "Failed to delete: ${step.path}"
                )
            )
        }

        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("path", JsonPrimitive(step.path))
                put("action", JsonPrimitive("DELETE"))
            }
        )

        return FileOperationResult.Success(output)
    }

    /**
     * MKDIR - 디렉토리 생성
     */
    @Suppress("UNUSED_PARAMETER")
    private fun executeMkdir(step: ExecutionStep.FileStep, context: ExecutionContext): FileOperationResult {
        val dir = File(PathResolver.resolve(step.path))

        if (dir.exists()) {
            // Already exists - still success
            val output = StepOutput(
                stepId = step.stepId,
                json = buildJsonObject {
                    put("path", JsonPrimitive(step.path))
                    put("action", JsonPrimitive("MKDIR"))
                    put("already_exists", JsonPrimitive(true))
                }
            )
            return FileOperationResult.Success(output)
        }

        val created = dir.mkdirs()

        if (!created) {
            return FileOperationResult.Error(
                ExecutionError(
                    category = ErrorCategory.IO_ERROR,
                    code = "MKDIR_FAILED",
                    message = "Failed to create directory: ${step.path}"
                )
            )
        }

        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("path", JsonPrimitive(step.path))
                put("action", JsonPrimitive("MKDIR"))
                put("created", JsonPrimitive(true))
            }
        )

        return FileOperationResult.Success(output)
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // File operations are typically fast and atomic
        // Cancellation is not meaningful for most file ops
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.FileStep
    }

    /**
     * Internal result type for file operations
     */
    private sealed class FileOperationResult {
        data class Success(val output: StepOutput) : FileOperationResult()
        data class Error(val error: ExecutionError) : FileOperationResult()
    }

    companion object {
        val INSTANCE = FileExecutor()
    }
}
