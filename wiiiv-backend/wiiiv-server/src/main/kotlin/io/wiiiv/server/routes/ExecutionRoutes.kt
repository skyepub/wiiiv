package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.execution.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.server.registry.ExecutionRecord
import io.wiiiv.execution.ExecutionContext
import io.wiiiv.execution.ExecutionResult
import io.wiiiv.gate.GateContext
import io.wiiiv.gate.GateResult
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.*

/**
 * Execution Routes - Blueprint 실행
 *
 * POST /api/v2/executions - 새 실행 시작 (Gate 체크 포함)
 * GET  /api/v2/executions/{id} - 실행 상태 조회
 * POST /api/v2/executions/{id}/cancel - 실행 취소
 *
 * ## Gate 체크 순서 (Canonical)
 *
 * 1. DACS Gate - DACS 합의 결과 확인 (YES만 통과)
 * 2. User Approval Gate - 사용자 승인 확인
 * 3. Permission Gate - Executor 권한 확인
 * 4. Cost Gate - 비용 한도 확인
 *
 * 하나라도 DENY → 실행 거부
 */
fun Route.executionRoutes() {
    route("/executions") {
        authenticate("auth-jwt", "auth-apikey", strategy = AuthenticationStrategy.FirstSuccessful) {
            // Start new execution
            post {
                val request = call.receive<ExecutionRequest>()
                val executionId = UUID.randomUUID().toString()

                // 1. Blueprint 조회
                val blueprint = WiiivRegistry.getBlueprint(request.blueprintId)
                    ?: throw NoSuchElementException("Blueprint not found: ${request.blueprintId}")

                // 2. Decision 조회 (Gate 체크용)
                val decision = WiiivRegistry.getDecisionByBlueprintId(request.blueprintId)
                    ?: throw NoSuchElementException("Decision not found for blueprint: ${request.blueprintId}")

                // 3. Gate Chain 체크
                val gateContext = GateContext(
                    requestId = executionId,
                    blueprintId = blueprint.id,
                    dacsConsensus = decision.dacsResult.consensus.name,
                    userApproved = decision.userApproved,
                    executorId = "composite-executor",
                    action = "EXECUTE",
                    estimatedCost = 0.0,
                    costLimit = 100.0
                )

                val gateResult = WiiivRegistry.gateChain.check(gateContext)

                // Gate 실패시 거부
                if (gateResult.isDeny) {
                    val denyResult = gateResult.finalResult as GateResult.Deny
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<ExecutionResponse>(
                            ApiError(
                                code = "GATE_DENIED",
                                message = "Execution blocked by ${denyResult.gateId}",
                                details = mapOf(
                                    "gateId" to denyResult.gateId,
                                    "denyCode" to denyResult.code,
                                    "passedGates" to gateResult.passedCount.toString()
                                )
                            )
                        )
                    )
                    return@post
                }

                // 4. 실행 기록 생성
                val record = ExecutionRecord(
                    executionId = executionId,
                    blueprintId = request.blueprintId,
                    status = "RUNNING",
                    startedAt = Instant.now().toString()
                )
                WiiivRegistry.storeExecution(record)

                // 5. 비동기 실행 (dry-run이 아닌 경우)
                if (request.options?.dryRun != true) {
                    call.application.launch {
                        try {
                            val context = ExecutionContext(
                                executionId = executionId,
                                blueprintId = blueprint.id,
                                instructionId = "instr-${executionId.take(8)}"
                            )

                            val steps = blueprint.toExecutionSteps()
                            val result = WiiivRegistry.executorRunner.execute(steps, context)

                            // 결과 업데이트
                            WiiivRegistry.updateExecution(executionId) { old ->
                                old.copy(
                                    status = when (result.status) {
                                        RunnerStatus.COMPLETED -> "COMPLETED"
                                        RunnerStatus.FAILED -> "FAILED"
                                        RunnerStatus.CANCELLED -> "CANCELLED"
                                    },
                                    completedAt = Instant.now().toString(),
                                    runnerResult = result
                                )
                            }
                        } catch (e: Exception) {
                            WiiivRegistry.updateExecution(executionId) { old ->
                                old.copy(
                                    status = "FAILED",
                                    completedAt = Instant.now().toString()
                                )
                            }
                        }
                    }
                } else {
                    // Dry-run: 즉시 완료로 표시
                    WiiivRegistry.updateExecution(executionId) { old ->
                        old.copy(
                            status = "COMPLETED",
                            completedAt = Instant.now().toString()
                        )
                    }
                }

                call.respond(
                    HttpStatusCode.Accepted,
                    ApiResponse.success(
                        ExecutionResponse(
                            executionId = executionId,
                            blueprintId = request.blueprintId,
                            status = ExecutionStatus.RUNNING,
                            startedAt = record.startedAt
                        )
                    )
                )
            }

            // List executions
            get {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20
                val blueprintId = call.request.queryParameters["blueprintId"]

                var executions = WiiivRegistry.listExecutions()

                // 필터링
                if (blueprintId != null) {
                    executions = executions.filter { it.blueprintId == blueprintId }
                }

                val startIndex = (page - 1) * pageSize
                val endIndex = minOf(startIndex + pageSize, executions.size)
                val paged = if (startIndex < executions.size) {
                    executions.subList(startIndex, endIndex)
                } else {
                    emptyList()
                }

                call.respond(
                    ApiResponse.success(
                        ExecutionListResponse(
                            executions = paged.map { it.toSummary() },
                            total = executions.size,
                            page = page,
                            pageSize = pageSize
                        )
                    )
                )
            }

            // Get execution by ID
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Execution ID required")

                val record = WiiivRegistry.getExecution(id)
                    ?: throw NoSuchElementException("Execution not found: $id")

                call.respond(
                    ApiResponse.success(record.toResponse())
                )
            }

            // Cancel execution
            post("/{id}/cancel") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Execution ID required")

                WiiivRegistry.getExecution(id)
                    ?: throw NoSuchElementException("Execution not found: $id")

                // TODO: 실제 취소 로직 (coroutine cancellation)
                WiiivRegistry.updateExecution(id) { old ->
                    old.copy(
                        status = "CANCELLED",
                        completedAt = Instant.now().toString()
                    )
                }

                call.respond(
                    ApiResponse.success(
                        CancelResponse(
                            executionId = id,
                            cancelled = true,
                            message = "Execution cancellation requested"
                        )
                    )
                )
            }

            // Get execution logs/output
            get("/{id}/logs") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Execution ID required")

                val record = WiiivRegistry.getExecution(id)
                    ?: throw NoSuchElementException("Execution not found: $id")

                val logs = mutableListOf<String>()
                logs.add("[${record.startedAt}] Starting execution ${record.executionId}")

                record.runnerResult?.results?.forEach { result ->
                    when (result) {
                        is ExecutionResult.Success -> {
                            val output = result.output.stdout ?: result.output.json.toString().take(100)
                            logs.add("[${result.meta.endedAt}] ${result.meta.stepId}: SUCCESS - $output")
                        }
                        is ExecutionResult.Failure -> {
                            logs.add("[${result.meta.endedAt}] ${result.meta.stepId}: FAILED - ${result.error.message}")
                        }
                        is ExecutionResult.Cancelled -> {
                            logs.add("[${result.meta.endedAt}] ${result.meta.stepId}: CANCELLED - ${result.reason.message}")
                        }
                    }
                }

                if (record.completedAt != null) {
                    logs.add("[${record.completedAt}] Execution ${record.status.lowercase()}")
                }

                call.respond(
                    ApiResponse.success(
                        LogsResponse(
                            executionId = id,
                            logs = logs
                        )
                    )
                )
            }
        }
    }
}

// Extension functions
private fun ExecutionRecord.toSummary() = ExecutionSummary(
    executionId = executionId,
    blueprintId = blueprintId,
    status = ExecutionStatus.valueOf(status),
    startedAt = startedAt,
    completedAt = completedAt
)

private fun ExecutionRecord.toResponse(): ExecutionResponse {
    val stepResults = runnerResult?.results?.map { result ->
        when (result) {
            is ExecutionResult.Success -> StepResult(
                nodeId = result.meta.stepId,
                status = "SUCCESS",
                output = result.output.stdout ?: result.output.json.toString().take(500),
                duration = result.meta.durationMs
            )
            is ExecutionResult.Failure -> StepResult(
                nodeId = result.meta.stepId,
                status = "FAILED",
                error = result.error.message,
                duration = result.meta.durationMs
            )
            is ExecutionResult.Cancelled -> StepResult(
                nodeId = result.meta.stepId,
                status = "CANCELLED",
                error = result.reason.message,
                duration = result.meta.durationMs
            )
        }
    }

    return ExecutionResponse(
        executionId = executionId,
        blueprintId = blueprintId,
        status = ExecutionStatus.valueOf(status),
        startedAt = startedAt,
        completedAt = completedAt,
        results = stepResults
    )
}
