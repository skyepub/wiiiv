package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.response.*
import io.wiiiv.execution.ExecutionResult
import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.governor.ActionType
import io.wiiiv.governor.ConversationResponse
import io.wiiiv.governor.NextAction
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.session.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.server.session.SseEvent
import io.wiiiv.server.session.SseProgressBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val chatMutexShared = Mutex()
private val sseJsonShared = Json { encodeDefaults = false }

/**
 * SSE 채팅 공통 핸들러
 *
 * SessionRoutes와 ProjectScopedRoutes 양쪽에서 호출.
 * governor.chat → auto-continue 루프 → SSE stream 전체 생명주기를 관리한다.
 */
suspend fun handleChatSse(
    call: ApplicationCall,
    sessionId: String,
    principal: UserPrincipal,
    request: ChatRequest,
    images: List<LlmImage>,
    maxContinue: Int
) {
    call.respondTextWriter(contentType = ContentType.Text.EventStream) {
        val bridge = SseProgressBridge()
        val scope = CoroutineScope(Dispatchers.IO)

        val writerJob = scope.launch {
            for (event in bridge.channel) {
                val (eventType, data) = when (event) {
                    is SseEvent.Progress -> "progress" to sseJsonShared.encodeToString(
                        ProgressEventDto(
                            phase = event.phase,
                            detail = event.detail,
                            stepIndex = event.stepIndex,
                            totalSteps = event.totalSteps
                        )
                    )
                    is SseEvent.Response -> "response" to sseJsonShared.encodeToString(
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

        // SSE heartbeat: 15초 간격으로 keep-alive 코멘트 전송 (ISSUE-001)
        val heartbeatJob = scope.launch {
            while (isActive) {
                delay(15_000)
                try {
                    write(": heartbeat\n\n")
                    flush()
                } catch (_: Exception) {
                    break
                }
            }
        }

        try {
            chatMutexShared.withLock {
                val governor = WiiivRegistry.conversationalGovernor
                governor.progressListener = bridge

                try {
                    val sessionInfo = WiiivRegistry.sessionManager.getSessionInfo(sessionId)
                    val effectiveRole = sessionInfo?.role ?: principal.roles.firstOrNull() ?: "MEMBER"

                    var response = governor.chat(
                        sessionId, request.message, images,
                        userId = principal.userId,
                        role = effectiveRole
                    )
                    var continueCount = 0

                    while (
                        request.autoContinue &&
                        response.nextAction == NextAction.CONTINUE_EXECUTION &&
                        continueCount < maxContinue
                    ) {
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
                            executionSummary = buildExecutionSummaryShared(response)
                        )

                        continueCount++
                        response = governor.chat(
                            sessionId, "/continue", emptyList(),
                            userId = principal.userId,
                            role = effectiveRole
                        )
                    }

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
                        executionSummary = buildExecutionSummaryShared(response)
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
            heartbeatJob.cancel()
        }

        writerJob.join()
    }
}

/**
 * ConversationResponse → ExecutionSummaryDto 변환 (EXECUTE 액션일 때)
 */
internal fun buildExecutionSummaryShared(response: ConversationResponse): ExecutionSummaryDto? {
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
