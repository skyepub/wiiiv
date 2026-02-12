package io.wiiiv.server.session

import io.wiiiv.governor.GovernorProgressListener
import io.wiiiv.governor.ProgressEvent
import io.wiiiv.governor.ProgressPhase
import io.wiiiv.server.dto.session.ExecutionSummaryDto
import kotlinx.coroutines.channels.Channel

/**
 * SSE Progress Bridge - ProgressListener → Channel 브리지
 *
 * GovernorProgressListener를 구현하여 진행 이벤트를 Channel로 전달한다.
 * SessionRoutes에서 Channel을 소비하여 SSE 텍스트로 변환한다.
 */
class SseProgressBridge : GovernorProgressListener {

    val channel = Channel<SseEvent>(Channel.BUFFERED)

    override fun onProgress(event: ProgressEvent) {
        channel.trySend(
            SseEvent.Progress(
                phase = event.phase.name,
                detail = event.detail,
                stepIndex = event.stepIndex,
                totalSteps = event.totalSteps
            )
        )
    }

    fun sendResponse(
        action: String,
        message: String,
        sessionId: String,
        isFinal: Boolean,
        askingFor: String? = null,
        confirmationSummary: String? = null,
        blueprintId: String? = null,
        executionSuccess: Boolean? = null,
        executionStepCount: Int? = null,
        error: String? = null,
        nextAction: String? = null,
        executionSummary: ExecutionSummaryDto? = null
    ) {
        channel.trySend(
            SseEvent.Response(
                action = action,
                message = message,
                sessionId = sessionId,
                isFinal = isFinal,
                askingFor = askingFor,
                confirmationSummary = confirmationSummary,
                blueprintId = blueprintId,
                executionSuccess = executionSuccess,
                executionStepCount = executionStepCount,
                error = error,
                nextAction = nextAction,
                executionSummary = executionSummary
            )
        )
    }

    fun sendError(message: String) {
        channel.trySend(SseEvent.Error(message))
    }

    fun sendDone() {
        channel.trySend(SseEvent.Done)
    }

    fun close() {
        channel.close()
    }
}

/**
 * SSE 이벤트 타입
 */
sealed class SseEvent {
    data class Progress(
        val phase: String,
        val detail: String?,
        val stepIndex: Int?,
        val totalSteps: Int?
    ) : SseEvent()

    data class Response(
        val action: String,
        val message: String,
        val sessionId: String,
        val isFinal: Boolean,
        val askingFor: String? = null,
        val confirmationSummary: String? = null,
        val blueprintId: String? = null,
        val executionSuccess: Boolean? = null,
        val executionStepCount: Int? = null,
        val error: String? = null,
        val nextAction: String? = null,
        val executionSummary: ExecutionSummaryDto? = null
    ) : SseEvent()

    data class Error(val message: String) : SseEvent()

    data object Done : SseEvent()
}
