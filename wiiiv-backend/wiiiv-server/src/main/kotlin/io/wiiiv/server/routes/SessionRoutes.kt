package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.governor.TaskStatus
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.session.*
import io.wiiiv.server.registry.WiiivRegistry
import java.util.Base64

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
        authenticate("auth-jwt", "auth-apikey", strategy = AuthenticationStrategy.FirstSuccessful) {

            // POST /sessions - 세션 생성
            post {
                val principal = call.principal<UserPrincipal>()!!
                val request = try {
                    call.receive<CreateSessionRequest>()
                } catch (_: Exception) {
                    CreateSessionRequest()
                }

                val session = WiiivRegistry.sessionManager.createSession(
                    principal.userId,
                    principal.roles.firstOrNull() ?: "MEMBER"
                )

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
                            createdAt = info.createdAt,
                            projectId = info.projectId
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
                                    createdAt = info.createdAt,
                                    projectId = info.projectId
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
                            createdAt = info.createdAt,
                            projectId = info.projectId
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

                val images = request.images?.map { img ->
                    LlmImage(
                        data = Base64.getDecoder().decode(img.base64),
                        mimeType = img.mimeType
                    )
                } ?: emptyList()

                val maxContinue = request.maxContinue.coerceIn(1, 20)

                handleChatSse(call, sessionId, principal, request, images, maxContinue)
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

