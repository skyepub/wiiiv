package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.execution.ExecutionResult
import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.governor.ActionType
import io.wiiiv.governor.ConversationResponse
import io.wiiiv.governor.NextAction
import io.wiiiv.governor.TaskStatus
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.session.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.server.session.SseEvent
import io.wiiiv.server.session.SseProgressBridge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64

private val chatMutex = Mutex()
private val sseJson = Json { encodeDefaults = false }

/**
 * Session Routes - 대화형 세션 API
 *
 * POST   /sessions          - 세션 생성
 * GET    /sessions          - 세션 목록
 * GET    /sessions/{id}     - 세션 정보
 * DELETE /sessions/{id}     - 세션 종료
 * POST   /sessions/{id}/chat - 메시지 전송 (SSE 스트리밍)
 * GET    /sessions/{id}/state   - 세션 상태 조회
 * GET    /sessions/{id}/history - 대화 이력 조회
 * POST   /sessions/{id}/control - 세션 제어
 */
fun Route.sessionRoutes() {
    route("/sessions") {
        authenticate("auth-jwt") {

            // POST /sessions - 세션 생성
            post {
                val principal = call.principal<UserPrincipal>()!!
                val request = try {
                    call.receive<CreateSessionRequest>()
                } catch (_: Exception) {
                    CreateSessionRequest()
                }

                val session = WiiivRegistry.sessionManager.createSession(principal.userId)

                // workspace 설정
                request.workspace?.let {
                    session.context.workspace = it
                }

                val info = WiiivRegistry.sessionManager.getSessionInfo(session.sessionId)!!

                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(
                        SessionResponse(
                            sessionId = info.sessionId,
                            userId = info.userId,
                            createdAt = info.createdAt
                        )
                    )
                )
            }

            // GET /sessions - 세션 목록
            get {
                val principal = call.principal<UserPrincipal>()!!
                val sessions = WiiivRegistry.sessionManager.listUserSessions(principal.userId)

                call.respond(
                    ApiResponse.success(
                        SessionListResponse(
                            sessions = sessions.map { info ->
                                SessionResponse(
                                    sessionId = info.sessionId,
                                    userId = info.userId,
                                    createdAt = info.createdAt
                                )
                            },
                            total = sessions.size
                        )
                    )
                )
            }

            // GET /sessions/{id} - 세션 정보
            get("/{id}") {
                val principal = call.principal<UserPrincipal>()!!
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Session ID required")

                if (!WiiivRegistry.sessionManager.isOwner(principal.userId, sessionId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(
                            ApiError(code = "FORBIDDEN", message = "Not session owner")
                        )
                    )
                    return@get
                }

                val info = WiiivRegistry.sessionManager.getSessionInfo(sessionId)
                    ?: throw NoSuchElementException("Session not found: $sessionId")

                call.respond(
                    ApiResponse.success(
                        SessionResponse(
                            sessionId = info.sessionId,
                            userId = info.userId,
                            createdAt = info.createdAt
                        )
                    )
                )
            }

            // DELETE /sessions/{id} - 세션 종료
            delete("/{id}") {
                val principal = call.principal<UserPrincipal>()!!
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Session ID required")

                if (!WiiivRegistry.sessionManager.isOwner(principal.userId, sessionId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(
                            ApiError(code = "FORBIDDEN", message = "Not session owner")
                        )
                    )
                    return@delete
                }

                WiiivRegistry.sessionManager.getSessionInfo(sessionId)
                    ?: throw NoSuchElementException("Session not found: $sessionId")

                WiiivRegistry.sessionManager.endSession(principal.userId, sessionId)

                call.respond(
                    ApiResponse.success(
                        DeleteSessionResponse(
                            sessionId = sessionId,
                            deleted = true,
                            message = "Session ended"
                        )
                    )
                )
            }

            // POST /sessions/{id}/chat - 메시지 전송 (SSE 스트리밍)
            post("/{id}/chat") {
                val principal = call.principal<UserPrincipal>()!!
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Session ID required")

                if (!WiiivRegistry.sessionManager.isOwner(principal.userId, sessionId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(
                            ApiError(code = "FORBIDDEN", message = "Not session owner")
                        )
                    )
                    return@post
                }

                WiiivRegistry.sessionManager.getSession(sessionId)
                    ?: throw NoSuchElementException("Session not found: $sessionId")

                val request = try {
                    call.receive<ChatRequest>()
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid chat request: ${e.message}")
                }

                // 이미지 변환
                val images = request.images?.map { img ->
                    LlmImage(
                        data = Base64.getDecoder().decode(img.base64),
                        mimeType = img.mimeType
                    )
                } ?: emptyList()

                val maxContinue = request.maxContinue.coerceIn(1, 20)

                // SSE 스트림 응답
                call.respondTextWriter(contentType = ContentType.Text.EventStream) {
                    val bridge = SseProgressBridge()

                    // Channel 소비 코루틴 — SSE 이벤트를 writer로 출력
                    val writerJob = launch(kotlinx.coroutines.Dispatchers.IO) {
                        for (event in bridge.channel) {
                            val (eventType, data) = when (event) {
                                is SseEvent.Progress -> "progress" to sseJson.encodeToString(
                                    ProgressEventDto(
                                        phase = event.phase,
                                        detail = event.detail,
                                        stepIndex = event.stepIndex,
                                        totalSteps = event.totalSteps
                                    )
                                )
                                is SseEvent.Response -> "response" to sseJson.encodeToString(
                                    ChatResponse(
                                        action = event.action,
                                        message = event.message,
                                        sessionId = event.sessionId,
                                        isFinal = event.isFinal,
                                        askingFor = event.askingFor,
                                        confirmationSummary = event.confirmationSummary,
                                        blueprintId = event.blueprintId,
                                        executionSuccess = event.executionSuccess,
                                        executionStepCount = event.executionStepCount,
                                        error = event.error,
                                        nextAction = event.nextAction,
                                        executionSummary = event.executionSummary
                                    )
                                )
                                is SseEvent.Error -> "error" to """{"message":"${event.message}"}"""
                                is SseEvent.Done -> "done" to "{}"
                            }
                            write("event: $eventType\ndata: $data\n\n")
                            flush()
                        }
                    }

                    // chat 실행
                    try {
                        chatMutex.withLock {
                            val governor = WiiivRegistry.conversationalGovernor
                            governor.progressListener = bridge

                            try {
                                var response = governor.chat(sessionId, request.message, images)
                                var continueCount = 0

                                // auto-continue 루프
                                while (
                                    request.autoContinue &&
                                    response.nextAction == NextAction.CONTINUE_EXECUTION &&
                                    continueCount < maxContinue
                                ) {
                                    // 중간 결과 전송
                                    bridge.sendResponse(
                                        action = response.action.name,
                                        message = response.message,
                                        sessionId = response.sessionId,
                                        isFinal = false,
                                        askingFor = response.askingFor,
                                        confirmationSummary = response.confirmationSummary,
                                        blueprintId = response.blueprint?.id,
                                        executionSuccess = response.executionResult?.isSuccess,
                                        executionStepCount = response.executionResult?.let {
                                            it.successCount + it.failureCount
                                        },
                                        error = response.error,
                                        nextAction = response.nextAction?.name,
                                        executionSummary = buildExecutionSummary(response)
                                    )

                                    continueCount++
                                    response = governor.chat(sessionId, "/continue", emptyList())
                                }

                                // 최종 결과 전송
                                bridge.sendResponse(
                                    action = response.action.name,
                                    message = response.message,
                                    sessionId = response.sessionId,
                                    isFinal = true,
                                    askingFor = response.askingFor,
                                    confirmationSummary = response.confirmationSummary,
                                    blueprintId = response.blueprint?.id,
                                    executionSuccess = response.executionResult?.isSuccess,
                                    executionStepCount = response.executionResult?.let {
                                        it.successCount + it.failureCount
                                    },
                                    error = response.error,
                                    nextAction = response.nextAction?.name,
                                    executionSummary = buildExecutionSummary(response)
                                )
                            } finally {
                                governor.progressListener = null
                            }
                        }
                    } catch (e: Exception) {
                        bridge.sendError(e.message ?: "Unknown error")
                    } finally {
                        bridge.sendDone()
                        bridge.close()
                    }

                    // writer 코루틴 완료 대기
                    writerJob.join()
                }
            }

            // GET /sessions/{id}/state - 세션 상태 조회
            get("/{id}/state") {
                val principal = call.principal<UserPrincipal>()!!
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Session ID required")

                if (!WiiivRegistry.sessionManager.isOwner(principal.userId, sessionId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(
                            ApiError(code = "FORBIDDEN", message = "Not session owner")
                        )
                    )
                    return@get
                }

                val session = WiiivRegistry.sessionManager.getSession(sessionId)
                    ?: throw NoSuchElementException("Session not found: $sessionId")

                val context = session.context
                val spec = session.draftSpec

                val specDto = DraftSpecDto(
                    id = spec.id,
                    intent = spec.intent,
                    taskType = spec.taskType?.name,
                    taskTypeDisplayName = spec.taskType?.displayName,
                    domain = spec.domain,
                    techStack = spec.techStack,
                    targetPath = spec.targetPath,
                    content = spec.content,
                    scale = spec.scale,
                    constraints = spec.constraints,
                    isComplete = spec.isComplete(),
                    isRisky = spec.isRisky(),
                    filledSlots = spec.getFilledSlots().toList(),
                    requiredSlots = spec.getRequiredSlots().toList(),
                    missingSlots = spec.getMissingSlots().toList()
                )

                val activeTaskDto = context.activeTask?.let { taskToDto(it) }
                val taskDtos = context.tasks.values.map { taskToDto(it) }

                val llmProvider = WiiivRegistry.llmProvider
                val serverInfo = ServerInfoDto(
                    modelName = if (llmProvider != null) "gpt-4o-mini" else null,
                    dacsTypeName = if (llmProvider != null) "HybridDACS" else "SimpleDACS",
                    llmAvailable = llmProvider != null,
                    ragAvailable = llmProvider != null
                )

                call.respond(
                    ApiResponse.success(
                        SessionStateResponse(
                            sessionId = session.sessionId,
                            createdAt = session.createdAt,
                            turnCount = session.history.size,
                            spec = specDto,
                            activeTask = activeTaskDto,
                            tasks = taskDtos,
                            declaredWriteIntent = context.declaredWriteIntent,
                            workspace = context.workspace,
                            serverInfo = serverInfo
                        )
                    )
                )
            }

            // GET /sessions/{id}/history - 대화 이력 조회
            get("/{id}/history") {
                val principal = call.principal<UserPrincipal>()!!
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Session ID required")

                if (!WiiivRegistry.sessionManager.isOwner(principal.userId, sessionId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(
                            ApiError(code = "FORBIDDEN", message = "Not session owner")
                        )
                    )
                    return@get
                }

                val session = WiiivRegistry.sessionManager.getSession(sessionId)
                    ?: throw NoSuchElementException("Session not found: $sessionId")

                val count = call.request.queryParameters["count"]?.toIntOrNull() ?: 20
                val messages = session.getRecentHistory(count)

                call.respond(
                    ApiResponse.success(
                        HistoryResponse(
                            messages = messages.map { msg ->
                                HistoryMessageDto(
                                    role = msg.role.name,
                                    content = msg.content,
                                    timestamp = msg.timestamp
                                )
                            },
                            total = session.history.size
                        )
                    )
                )
            }

            // POST /sessions/{id}/control - 세션 제어
            post("/{id}/control") {
                val principal = call.principal<UserPrincipal>()!!
                val sessionId = call.parameters["id"]
                    ?: throw IllegalArgumentException("Session ID required")

                if (!WiiivRegistry.sessionManager.isOwner(principal.userId, sessionId)) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        ApiResponse.error<Unit>(
                            ApiError(code = "FORBIDDEN", message = "Not session owner")
                        )
                    )
                    return@post
                }

                val session = WiiivRegistry.sessionManager.getSession(sessionId)
                    ?: throw NoSuchElementException("Session not found: $sessionId")

                val request = try {
                    call.receive<ControlRequest>()
                } catch (e: Exception) {
                    throw IllegalArgumentException("Invalid control request: ${e.message}")
                }

                val result = when (request.action) {
                    "switch" -> {
                        val targetId = request.targetId
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse.error<Unit>(
                                    ApiError(code = "BAD_REQUEST", message = "targetId required for switch")
                                )
                            )
                        val context = session.context
                        val targetTask = context.tasks[targetId]
                            ?: return@post call.respond(
                                HttpStatusCode.NotFound,
                                ApiResponse.error<Unit>(
                                    ApiError(code = "NOT_FOUND", message = "Task not found: $targetId")
                                )
                            )
                        session.suspendCurrentWork()
                        targetTask.status = TaskStatus.ACTIVE
                        context.activeTaskId = targetTask.id
                        ControlResponse(true, "Switched to task #${targetTask.id}", targetTask.id)
                    }
                    "cancel" -> {
                        val activeTask = session.context.activeTask
                        val taskId = activeTask?.id
                        session.cancelCurrentTask()
                        ControlResponse(true, "Current task cancelled", taskId)
                    }
                    "cancelAll" -> {
                        session.resetAll()
                        ControlResponse(true, "All tasks cancelled, session reset")
                    }
                    "resetSpec" -> {
                        session.resetSpec()
                        ControlResponse(true, "Current spec cleared")
                    }
                    "setWorkspace" -> {
                        val workspace = request.workspace
                            ?: return@post call.respond(
                                HttpStatusCode.BadRequest,
                                ApiResponse.error<Unit>(
                                    ApiError(code = "BAD_REQUEST", message = "workspace required for setWorkspace")
                                )
                            )
                        session.context.workspace = workspace
                        ControlResponse(true, "Workspace set to $workspace")
                    }
                    else -> {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<Unit>(
                                ApiError(code = "BAD_REQUEST", message = "Unknown action: ${request.action}")
                            )
                        )
                    }
                }

                call.respond(ApiResponse.success(result))
            }
        }
    }
}

/**
 * TaskSlot → TaskSummaryDto 변환
 */
private fun taskToDto(task: io.wiiiv.governor.TaskSlot): TaskSummaryDto {
    val spec = task.draftSpec
    return TaskSummaryDto(
        id = task.id,
        label = task.label,
        status = task.status.name,
        taskType = spec.taskType?.name,
        taskTypeDisplayName = spec.taskType?.displayName,
        targetPath = spec.targetPath,
        specComplete = spec.isComplete(),
        specRisky = spec.isRisky(),
        filledSlotCount = spec.getFilledSlots().size,
        requiredSlotCount = spec.getRequiredSlots().size,
        executionCount = task.context.executionHistory.size,
        createdAt = task.createdAt,
        artifacts = task.context.artifacts.toMap()
    )
}

/**
 * ConversationResponse → ExecutionSummaryDto 변환 (EXECUTE 액션일 때)
 */
private fun buildExecutionSummary(response: ConversationResponse): ExecutionSummaryDto? {
    if (response.action != ActionType.EXECUTE) return null
    val blueprint = response.blueprint ?: return null
    val execResult = response.executionResult ?: return null

    val results = execResult.runnerResult.results
    val steps = blueprint.steps.mapIndexed { i, step ->
        val result = results.getOrNull(i)
        val output = execResult.getStepOutput(step.stepId)
        StepSummaryDto(
            stepId = step.stepId,
            type = step.type.name,
            params = step.params.filterValues { it.length <= 200 },
            success = result is ExecutionResult.Success,
            durationMs = result?.meta?.durationMs ?: 0,
            stdout = output?.stdout,
            stderr = output?.stderr,
            exitCode = output?.exitCode,
            error = if (result is ExecutionResult.Failure) result.error.message else null,
            artifacts = output?.artifacts?.mapValues { it.value.toString() } ?: emptyMap()
        )
    }

    return ExecutionSummaryDto(
        blueprintId = blueprint.id,
        steps = steps,
        totalDurationMs = results.sumOf { it.meta.durationMs },
        successCount = execResult.successCount,
        failureCount = execResult.failureCount
    )
}
