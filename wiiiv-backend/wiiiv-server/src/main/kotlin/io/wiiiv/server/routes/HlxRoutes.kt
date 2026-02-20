package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.audit.AuditRecordFactory
import io.wiiiv.audit.ExecutionPath
import io.wiiiv.hlx.parser.HlxParseResult
import io.wiiiv.hlx.parser.HlxParser
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.hlx.*
import io.wiiiv.server.registry.HlxExecutionEntry
import io.wiiiv.server.registry.HlxWorkflowEntry
import io.wiiiv.server.registry.WiiivRegistry
import java.time.Instant
import java.util.UUID

/**
 * HLX Routes - HLX 워크플로우 관리/실행
 *
 * POST   /api/v2/workflows                          워크플로우 등록
 * GET    /api/v2/workflows                          워크플로우 목록
 * GET    /api/v2/workflows/{id}                     워크플로우 상세
 * POST   /api/v2/workflows/{id}/validate            워크플로우 검증
 * DELETE /api/v2/workflows/{id}                     워크플로우 삭제
 * POST   /api/v2/workflows/{id}/execute             워크플로우 실행
 * GET    /api/v2/workflows/{id}/executions          워크플로우별 실행 이력
 * GET    /api/v2/workflows/executions/{executionId} 실행 결과 상세
 */
fun Route.hlxRoutes() {
    route("/workflows") {
        authenticate("auth-jwt") {
            // POST /workflows — 워크플로우 등록
            post {
                val request = call.receive<HlxWorkflowCreateRequest>()

                val result = HlxParser.parseAndValidate(request.workflow)

                when (result) {
                    is HlxParseResult.ParseError -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<HlxWorkflowResponse>(
                                ApiError(code = "PARSE_ERROR", message = result.message)
                            )
                        )
                    }
                    is HlxParseResult.ValidationError -> {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<HlxWorkflowResponse>(
                                ApiError(
                                    code = "VALIDATION_ERROR",
                                    message = result.errors.joinToString("; ") { it.message }
                                )
                            )
                        )
                    }
                    is HlxParseResult.Success -> {
                        val workflow = result.workflow
                        val entry = HlxWorkflowEntry(
                            id = workflow.id,
                            workflow = workflow,
                            rawJson = request.workflow,
                            createdAt = Instant.now().toString()
                        )
                        WiiivRegistry.storeHlxWorkflow(entry)

                        call.respond(
                            HttpStatusCode.Created,
                            ApiResponse.success(
                                HlxWorkflowResponse(
                                    id = workflow.id,
                                    name = workflow.name,
                                    description = workflow.description,
                                    version = workflow.version,
                                    nodeCount = workflow.nodes.size,
                                    createdAt = entry.createdAt
                                )
                            )
                        )
                    }
                }
            }

            // GET /workflows — 워크플로우 목록
            get {
                val entries = WiiivRegistry.listHlxWorkflows()
                call.respond(
                    ApiResponse.success(
                        HlxWorkflowListResponse(
                            workflows = entries.map { entry ->
                                HlxWorkflowResponse(
                                    id = entry.id,
                                    name = entry.workflow.name,
                                    description = entry.workflow.description,
                                    version = entry.workflow.version,
                                    nodeCount = entry.workflow.nodes.size,
                                    createdAt = entry.createdAt
                                )
                            },
                            total = entries.size
                        )
                    )
                )
            }

            // GET /workflows/executions/{executionId} — 실행 결과 상세
            // 경로 충돌 방지: {id} 보다 먼저 등록
            route("/executions") {
                get("/{executionId}") {
                    val executionId = call.parameters["executionId"]
                        ?: return@get call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<HlxExecutionResponse>(
                                ApiError(code = "MISSING_PARAM", message = "executionId required")
                            )
                        )

                    val entry = WiiivRegistry.getHlxExecution(executionId)
                        ?: return@get call.respond(
                            HttpStatusCode.NotFound,
                            ApiResponse.error<HlxExecutionResponse>(
                                ApiError(code = "NOT_FOUND", message = "Execution not found: $executionId")
                            )
                        )

                    call.respond(
                        ApiResponse.success(toExecutionResponse(entry))
                    )
                }
            }

            // GET /workflows/{id} — 워크플로우 상세
            get("/{id}") {
                val id = call.parameters["id"]!!
                val entry = WiiivRegistry.getHlxWorkflow(id)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<HlxWorkflowDetailResponse>(
                            ApiError(code = "NOT_FOUND", message = "Workflow not found: $id")
                        )
                    )

                call.respond(
                    ApiResponse.success(
                        HlxWorkflowDetailResponse(
                            id = entry.id,
                            name = entry.workflow.name,
                            description = entry.workflow.description,
                            version = entry.workflow.version,
                            nodes = entry.workflow.nodes.map { node ->
                                HlxNodeSummary(
                                    id = node.id,
                                    type = node.type.name.lowercase(),
                                    description = node.description
                                )
                            },
                            trigger = entry.workflow.trigger.type.name.lowercase(),
                            createdAt = entry.createdAt,
                            rawJson = entry.rawJson
                        )
                    )
                )
            }

            // POST /workflows/{id}/validate — 워크플로우 검증
            post("/{id}/validate") {
                val id = call.parameters["id"]!!
                val entry = WiiivRegistry.getHlxWorkflow(id)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<HlxValidateResponse>(
                            ApiError(code = "NOT_FOUND", message = "Workflow not found: $id")
                        )
                    )

                val result = HlxParser.parseAndValidate(entry.rawJson)

                when (result) {
                    is HlxParseResult.Success -> {
                        call.respond(
                            ApiResponse.success(HlxValidateResponse(valid = true))
                        )
                    }
                    is HlxParseResult.ParseError -> {
                        call.respond(
                            ApiResponse.success(
                                HlxValidateResponse(valid = false, errors = listOf(result.message))
                            )
                        )
                    }
                    is HlxParseResult.ValidationError -> {
                        call.respond(
                            ApiResponse.success(
                                HlxValidateResponse(
                                    valid = false,
                                    errors = result.errors.map { it.message }
                                )
                            )
                        )
                    }
                }
            }

            // DELETE /workflows/{id} — 워크플로우 삭제
            delete("/{id}") {
                val id = call.parameters["id"]!!
                val deleted = WiiivRegistry.deleteHlxWorkflow(id)

                if (deleted) {
                    call.respond(
                        ApiResponse.success(HlxDeleteResponse(id = id, deleted = true))
                    )
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<HlxDeleteResponse>(
                            ApiError(code = "NOT_FOUND", message = "Workflow not found: $id")
                        )
                    )
                }
            }

            // POST /workflows/{id}/execute — 워크플로우 실행
            post("/{id}/execute") {
                val id = call.parameters["id"]!!
                val entry = WiiivRegistry.getHlxWorkflow(id)
                    ?: return@post call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<HlxExecutionResponse>(
                            ApiError(code = "NOT_FOUND", message = "Workflow not found: $id")
                        )
                    )

                val runner = WiiivRegistry.hlxRunner
                    ?: return@post call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<HlxExecutionResponse>(
                            ApiError(code = "LLM_UNAVAILABLE", message = "HLX Runner not available (no LLM provider)")
                        )
                    )

                val request = call.receive<HlxExecuteRequest>()
                val role = request.role ?: "OPERATOR"

                val result = runner.run(
                    workflow = entry.workflow,
                    initialVariables = request.variables,
                    userId = "dev-user",
                    role = role
                )

                val executionId = UUID.randomUUID().toString()
                val executedAt = Instant.now().toString()

                val execEntry = HlxExecutionEntry(
                    executionId = executionId,
                    workflowId = id,
                    result = result,
                    executedAt = executedAt
                )
                WiiivRegistry.storeHlxExecution(execEntry)

                // Audit hook: DIRECT_HLX_API 실행 기록
                try {
                    WiiivRegistry.auditStore?.insert(
                        AuditRecordFactory.fromHlxResult(
                            hlxResult = result,
                            workflow = entry.workflow,
                            executionPath = ExecutionPath.DIRECT_HLX_API,
                            userId = "dev-user",
                            role = role,
                            taskType = "DIRECT_HLX"
                        )
                    )
                } catch (e: Exception) {
                    println("[AUDIT] Failed to record DIRECT_HLX_API: ${e.message}")
                }

                call.respond(
                    ApiResponse.success(toExecutionResponse(execEntry))
                )
            }

            // GET /workflows/{id}/executions — 워크플로우별 실행 이력
            get("/{id}/executions") {
                val id = call.parameters["id"]!!

                WiiivRegistry.getHlxWorkflow(id)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<HlxExecutionListResponse>(
                            ApiError(code = "NOT_FOUND", message = "Workflow not found: $id")
                        )
                    )

                val executions = WiiivRegistry.listHlxExecutions(id)
                call.respond(
                    ApiResponse.success(
                        HlxExecutionListResponse(
                            executions = executions.map { exec ->
                                HlxExecutionSummary(
                                    executionId = exec.executionId,
                                    workflowId = exec.workflowId,
                                    status = exec.result.status.name,
                                    totalDurationMs = exec.result.totalDurationMs,
                                    executedAt = exec.executedAt
                                )
                            },
                            total = executions.size
                        )
                    )
                )
            }
        }
    }
}

private fun toExecutionResponse(entry: HlxExecutionEntry): HlxExecutionResponse {
    val result = entry.result
    return HlxExecutionResponse(
        executionId = entry.executionId,
        workflowId = entry.workflowId,
        status = result.status.name,
        nodeRecords = result.nodeRecords.map { record ->
            HlxNodeRecordDto(
                nodeId = record.nodeId,
                nodeType = record.nodeType.name.lowercase(),
                status = record.status.name.lowercase(),
                durationMs = record.durationMs,
                error = record.error,
                selectedBranch = record.selectedBranch,
                iterationCount = record.iterationCount,
                gate = record.gate
            )
        },
        totalDurationMs = result.totalDurationMs,
        variables = result.context.variables,
        error = result.error
    )
}
