package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.governor.ConversationResponse
import io.wiiiv.governor.NextAction
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
                    val writerJob = launch {
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
                                        nextAction = event.nextAction
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
                                        nextAction = response.nextAction?.name
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
                                    nextAction = response.nextAction?.name
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
        }
    }
}
