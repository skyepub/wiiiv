package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Command Executor - 셸 명령 실행 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: 명령이 위험한지, 적절한지 판단하지 않음
 * - 해석하지 않는다: 명령 출력의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - 셸 명령 실행
 * - 인자 전달
 * - 작업 디렉토리 설정
 * - 환경 변수 설정
 * - 타임아웃 지원
 * - stdin 입력 지원
 *
 * ## 오류 처리
 *
 * - 명령 없음 → Failure (RESOURCE_NOT_FOUND)
 * - 타임아웃 → Failure (TIMEOUT)
 * - 실행 오류 → Failure (IO_ERROR)
 * - 0이 아닌 종료 코드 → Failure (EXTERNAL_SERVICE_ERROR)
 *
 * ## 보안 주의사항
 *
 * CommandExecutor는 판단하지 않는다. 보안 검증은 Governor와 Gate의 책임이다.
 * Blueprint에 포함된 명령은 이미 검증되었다고 가정한다.
 */
class CommandExecutor : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        // Type check
        if (step !is ExecutionStep.CommandStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "CommandExecutor can only handle CommandStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val result = executeCommand(step, context)

            val endedAt = Instant.now()

            when (result) {
                is CommandResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.command)
                    )

                    // Add to context
                    context.addStepOutput(step.stepId, output)

                    ExecutionResult.Success(output = output, meta = meta)
                }
                is CommandResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.command)
                    )

                    ExecutionResult.Failure(
                        error = result.error,
                        partialOutput = result.partialOutput,
                        meta = meta
                    )
                }
            }
        } catch (e: IOException) {
            ExecutionResult.Failure(
                error = ExecutionError(
                    category = ErrorCategory.IO_ERROR,
                    code = "COMMAND_IO_ERROR",
                    message = "IO error executing command: ${e.message}"
                ),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.command)
                )
            )
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.command)
                )
            )
        }
    }

    /**
     * 명령 실행
     */
    private fun executeCommand(step: ExecutionStep.CommandStep, context: ExecutionContext): CommandResult {
        // Build command
        val commandList = buildList {
            add(step.command)
            addAll(step.args)
        }

        // Create process builder
        val processBuilder = ProcessBuilder(commandList)

        // Set working directory
        step.workingDir?.let { dir ->
            val workDir = File(dir)
            if (!workDir.exists()) {
                return CommandResult.Error(
                    ExecutionError(
                        category = ErrorCategory.RESOURCE_NOT_FOUND,
                        code = "WORKING_DIR_NOT_FOUND",
                        message = "Working directory not found: $dir"
                    )
                )
            }
            if (!workDir.isDirectory) {
                return CommandResult.Error(
                    ExecutionError(
                        category = ErrorCategory.CONTRACT_VIOLATION,
                        code = "INVALID_WORKING_DIR",
                        message = "Working directory is not a directory: $dir"
                    )
                )
            }
            processBuilder.directory(workDir)
        }

        // Set environment variables
        if (step.env.isNotEmpty()) {
            processBuilder.environment().putAll(step.env)
        }

        // Redirect error stream to output
        processBuilder.redirectErrorStream(true)

        // Start process
        val process = try {
            processBuilder.start()
        } catch (e: IOException) {
            return CommandResult.Error(
                ExecutionError(
                    category = ErrorCategory.RESOURCE_NOT_FOUND,
                    code = "COMMAND_NOT_FOUND",
                    message = "Command not found or cannot be executed: ${step.command}"
                )
            )
        }

        // Write stdin if provided
        step.stdin?.let { input ->
            process.outputStream.bufferedWriter().use { writer ->
                writer.write(input)
            }
        }

        // Wait for completion with timeout
        val completed = process.waitFor(step.timeoutMs, TimeUnit.MILLISECONDS)

        if (!completed) {
            process.destroyForcibly()
            val partialOutput = readProcessOutput(process, maxLength = 10000)
            return CommandResult.Error(
                error = ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "COMMAND_TIMEOUT",
                    message = "Command timed out after ${step.timeoutMs}ms"
                ),
                partialOutput = createPartialOutput(step.stepId, partialOutput, -1, true)
            )
        }

        // Read output
        val stdout = readProcessOutput(process, maxLength = MAX_OUTPUT_LENGTH)
        val exitCode = process.exitValue()

        // Check exit code
        if (exitCode != 0) {
            return CommandResult.Error(
                error = ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "COMMAND_FAILED",
                    message = "Command exited with code $exitCode"
                ),
                partialOutput = createPartialOutput(step.stepId, stdout, exitCode, false)
            )
        }

        // Success
        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("command", JsonPrimitive(step.command))
                put("args", JsonPrimitive(step.args.joinToString(" ")))
                put("exitCode", JsonPrimitive(exitCode))
                put("stdout", JsonPrimitive(stdout))
                put("truncated", JsonPrimitive(stdout.length >= MAX_OUTPUT_LENGTH))
            },
            artifacts = mapOf("stdout" to stdout)
        )

        return CommandResult.Success(output)
    }

    /**
     * 프로세스 출력 읽기
     */
    private fun readProcessOutput(process: Process, maxLength: Int): String {
        return try {
            process.inputStream.bufferedReader().use { reader ->
                val output = StringBuilder()
                val buffer = CharArray(8192)
                var read: Int
                while (reader.read(buffer).also { read = it } != -1 && output.length < maxLength) {
                    val toAppend = minOf(read, maxLength - output.length)
                    output.append(buffer, 0, toAppend)
                }
                output.toString()
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 부분 출력 생성
     */
    private fun createPartialOutput(stepId: String, stdout: String, exitCode: Int, timedOut: Boolean): StepOutput {
        return StepOutput(
            stepId = stepId,
            json = buildJsonObject {
                put("exitCode", JsonPrimitive(exitCode))
                put("stdout", JsonPrimitive(stdout))
                put("timedOut", JsonPrimitive(timedOut))
                put("partial", JsonPrimitive(true))
            }
        )
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // 현재 실행 중인 프로세스 취소는 복잡한 상태 관리 필요
        // v1.0에서는 간단히 true 반환 (실제 취소는 타임아웃에 의존)
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.CommandStep
    }

    /**
     * Internal result type for command operations
     */
    private sealed class CommandResult {
        data class Success(val output: StepOutput) : CommandResult()
        data class Error(
            val error: ExecutionError,
            val partialOutput: StepOutput? = null
        ) : CommandResult()
    }

    companion object {
        /**
         * 최대 출력 길이 (1MB)
         */
        const val MAX_OUTPUT_LENGTH = 1024 * 1024

        /**
         * 기본 인스턴스
         */
        val INSTANCE = CommandExecutor()
    }
}
