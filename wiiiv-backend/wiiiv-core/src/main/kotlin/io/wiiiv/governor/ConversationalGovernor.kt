package io.wiiiv.governor

import io.wiiiv.blueprint.*
import io.wiiiv.dacs.DACS
import io.wiiiv.dacs.DACSRequest
import io.wiiiv.dacs.DACSResult
import io.wiiiv.dacs.Consensus
import io.wiiiv.execution.HttpMethod
import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.rag.RagPipeline
import kotlinx.serialization.json.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Conversational Governor - 대화형 Governor
 *
 * wiiiv 2.1: 사용자와 자연스럽게 대화하며 Spec을 수집하고 실행을 관리하는 Governor
 *
 * ## 핵심 철학
 *
 * - Governor는 대화 상대이자, 인터뷰어이자, 실행자다
 * - 상태는 enum이 아닌 DraftSpec + MissingSlots로 표현
 * - DACS는 실행 결정 시점에만 호출 (헌법기관)
 * - 모든 실행은 Blueprint를 통과
 *
 * ## 행동 원칙
 *
 * 1. 일반 대화/지식 질문 → 직접 응답 (REPLY)
 * 2. 단순 요청 (파일 읽기 등) → 바로 실행 (EXECUTE)
 * 3. 복잡한 작업 → 인터뷰로 Spec 수집 (ASK)
 * 4. Spec 완성 → 확인 요청 (CONFIRM)
 * 5. 확인 후 → 실행 (EXECUTE)
 * 6. 취소/중단 → 세션 리셋 (CANCEL)
 *
 * ## LLM 기반 의사결정
 *
 * LLM이 System Prompt를 기반으로 다음 행동을 결정:
 * - action: REPLY | ASK | CONFIRM | EXECUTE | CANCEL
 * - message: 사용자에게 보낼 메시지
 * - specUpdates: DraftSpec에 병합할 업데이트
 * - askingFor: 다음에 물어볼 슬롯
 */
class ConversationalGovernor(
    override val id: String,
    private val dacs: DACS,
    private val llmProvider: LlmProvider? = null,
    private val model: String? = null,
    private val blueprintRunner: BlueprintRunner? = null,
    private val ragPipeline: RagPipeline? = null
) : Governor {

    var progressListener: GovernorProgressListener? = null

    private val sessions = ConcurrentHashMap<String, ConversationSession>()
    private val json = Json { ignoreUnknownKeys = true }

    private fun emitProgress(
        phase: ProgressPhase,
        detail: String? = null,
        stepIndex: Int? = null,
        totalSteps: Int? = null
    ) {
        progressListener?.onProgress(ProgressEvent(phase, detail, stepIndex, totalSteps))
    }

    /**
     * 새 대화 세션 시작
     */
    fun startSession(sessionId: String = UUID.randomUUID().toString()): ConversationSession {
        val session = ConversationSession(sessionId = sessionId)
        sessions[sessionId] = session
        return session
    }

    /**
     * 세션 조회
     */
    fun getSession(sessionId: String): ConversationSession? = sessions[sessionId]

    /**
     * 세션 삭제
     */
    fun endSession(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * 대화 처리 - 사용자 메시지를 받아 응답 반환
     *
     * @param sessionId 세션 ID
     * @param userMessage 사용자 메시지
     * @return Governor 응답
     */
    suspend fun chat(sessionId: String, userMessage: String, images: List<LlmImage> = emptyList()): ConversationResponse {
        val session = sessions[sessionId]
            ?: return ConversationResponse(
                action = ActionType.CANCEL,
                message = "세션을 찾을 수 없습니다. 새 대화를 시작해주세요.",
                sessionId = sessionId
            )

        // 사용자 메시지 기록
        session.addUserMessage(userMessage)

        // pendingAction이 ContinueExecution이면 LLM 판단 없이 바로 실행 계속
        val pending = session.context.pendingAction
        if (pending is PendingAction.ContinueExecution) {
            session.context.pendingAction = null
            val response = executeTurn(session)
            session.addGovernorMessage(response.message)
            return response
        }

        // LLM이 없으면 기본 처리
        if (llmProvider == null) {
            return handleWithoutLlm(session, userMessage)
        }

        // LLM으로 다음 행동 결정
        if (images.isNotEmpty()) {
            emitProgress(ProgressPhase.IMAGE_ANALYZING, "${images.size}개 이미지 분석 중...")
        }
        emitProgress(ProgressPhase.LLM_THINKING, "다음 행동 결정 중...")
        val governorAction = try {
            decideAction(session, userMessage, images)
        } catch (e: Exception) {
            GovernorAction(
                action = ActionType.REPLY,
                message = "죄송합니다. 처리 중 오류가 발생했습니다: ${e.message}"
            )
        }

        // 작업 전환 처리
        governorAction.taskSwitch?.let { switchSignal ->
            handleTaskSwitch(session, switchSignal)
        }

        // specUpdates 적용 (자동 작업 보존 포함)
        governorAction.specUpdates?.let { updates ->
            // taskSwitch가 이미 처리한 경우가 아니면, taskType 변경 감지 시 현재 작업을 자동 보존
            if (governorAction.taskSwitch == null) {
                val newTaskType = updates["taskType"]?.jsonPrimitive?.contentOrNull?.let { str ->
                    try { TaskType.valueOf(str) } catch (_: Exception) { null }
                }
                val currentSpec = session.draftSpec
                if (newTaskType != null && currentSpec.taskType != null
                    && newTaskType != currentSpec.taskType && currentSpec.intent != null) {
                    session.suspendCurrentWork()
                }
            }
            session.updateSpec(updates)
        }

        // EXECUTE/CONFIRM 시 활성 작업 확정
        if (governorAction.action in listOf(ActionType.EXECUTE, ActionType.CONFIRM)) {
            session.ensureActiveTask()
        }

        // 행동 처리
        val response = processAction(session, governorAction)

        // Governor 메시지 기록
        session.addGovernorMessage(response.message)

        return response
    }

    /**
     * LLM을 통해 다음 행동 결정
     */
    private suspend fun decideAction(session: ConversationSession, userMessage: String, images: List<LlmImage> = emptyList()): GovernorAction {
        // RAG 검색 — 사용자 메시지로 관련 문서 조회
        val ragContext = consultRag(userMessage, session.draftSpec)

        val systemPrompt = GovernorPrompt.withContext(
            draftSpec = session.draftSpec,
            recentHistory = session.getRecentHistory(10),
            executionHistory = session.context.executionHistory,
            taskList = session.context.tasks.values.toList(),
            ragContext = ragContext,
            workspace = session.context.workspace,
            imageCount = images.size
        )

        // System prompt + 사용자 메시지를 하나의 프롬프트로 결합
        val fullPrompt = buildString {
            appendLine(systemPrompt)
            appendLine()
            appendLine("## 사용자 메시지")
            appendLine(userMessage)
        }

        val response = llmProvider!!.call(
            LlmRequest(
                action = LlmAction.COMPLETE,
                prompt = fullPrompt,
                model = model ?: llmProvider.defaultModel,
                maxTokens = 2000,
                images = images
            )
        )

        return parseGovernorAction(response.content)
    }

    /**
     * RAG 검색 — 사용자 메시지 + DraftSpec 기반
     */
    private suspend fun consultRag(userMessage: String, draftSpec: DraftSpec): String? {
        if (ragPipeline == null) return null

        val query = buildString {
            append(userMessage)
            draftSpec.intent?.let { append(" $it") }
            draftSpec.domain?.let { append(" $it") }
        }.trim()

        if (query.isBlank()) return null

        return try {
            val result = ragPipeline.search(query, topK = 5)
            if (result.isEmpty()) null else result.toNumberedContext()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * LLM 응답을 GovernorAction으로 파싱
     */
    private fun parseGovernorAction(response: String): GovernorAction {
        val (jsonStr, tail) = extractJsonWithTail(response)

        return try {
            val jsonElement = json.parseToJsonElement(jsonStr).jsonObject

            val actionStr = jsonElement["action"]?.jsonPrimitive?.contentOrNull ?: "REPLY"
            val action = try {
                ActionType.valueOf(actionStr)
            } catch (_: Exception) {
                ActionType.REPLY
            }

            // JSON 뒤의 내용(코드블록 등)을 message에 합침
            val jsonMessage = jsonElement["message"]?.jsonPrimitive?.contentOrNull ?: ""
            val message = if (tail.isNotBlank()) {
                "$jsonMessage\n\n$tail"
            } else {
                jsonMessage
            }

            val specUpdates = jsonElement["specUpdates"]?.jsonObject?.let {
                it.entries.associate { (k, v) -> k to v }
            }

            val askingFor = jsonElement["askingFor"]?.jsonPrimitive?.contentOrNull
            val taskSwitch = jsonElement["taskSwitch"]?.jsonPrimitive?.contentOrNull

            GovernorAction(
                action = action,
                message = message,
                specUpdates = specUpdates,
                askingFor = askingFor,
                taskSwitch = taskSwitch
            )
        } catch (e: Exception) {
            // 파싱 실패 시 원본 텍스트를 REPLY로 반환
            GovernorAction(
                action = ActionType.REPLY,
                message = response
            )
        }
    }

    /**
     * 행동 처리
     */
    private suspend fun processAction(
        session: ConversationSession,
        action: GovernorAction
    ): ConversationResponse {
        return when (action.action) {
            ActionType.REPLY -> {
                ConversationResponse(
                    action = ActionType.REPLY,
                    message = action.message,
                    sessionId = session.sessionId
                )
            }

            ActionType.ASK -> {
                ConversationResponse(
                    action = ActionType.ASK,
                    message = action.message,
                    sessionId = session.sessionId,
                    askingFor = action.askingFor,
                    draftSpec = session.draftSpec
                )
            }

            ActionType.CONFIRM -> {
                ConversationResponse(
                    action = ActionType.CONFIRM,
                    message = action.message,
                    sessionId = session.sessionId,
                    draftSpec = session.draftSpec,
                    confirmationSummary = session.draftSpec.summarize()
                )
            }

            ActionType.EXECUTE -> {
                executeTurn(session)
            }

            ActionType.CANCEL -> {
                session.cancelCurrentTask()
                ConversationResponse(
                    action = ActionType.CANCEL,
                    message = action.message.ifBlank { "알겠습니다. 언제든 새로 시작할 수 있어요." },
                    sessionId = session.sessionId
                )
            }
        }
    }

    /**
     * 통합 턴 실행 - 모든 TaskType이 동일한 턴 모델 사용
     *
     * 1. 검증 (requiresExecution, isComplete)
     * 2. Spec 변환
     * 3. DACS (risky이고 첫 실행일 때만)
     * 4. needsLlmDecision() 판단:
     *    - true → executeLlmDecidedTurn() (API_WORKFLOW 등)
     *    - false → executeDirectTurn() (FILE_READ, COMMAND 등)
     */
    private suspend fun executeTurn(session: ConversationSession): ConversationResponse {
        var draftSpec = session.draftSpec
        val workspace = session.context.workspace

        // 1. 실행이 필요하지 않은 타입 (CONVERSATION, INFORMATION)
        if (!draftSpec.requiresExecution()) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "이 요청은 별도의 실행이 필요하지 않습니다.",
                sessionId = session.sessionId
            )
        }

        // 2. Spec 완성 확인
        if (!draftSpec.isComplete()) {
            val missing = draftSpec.getMissingSlots()
            return ConversationResponse(
                action = ActionType.ASK,
                message = "실행하려면 추가 정보가 필요합니다: ${missing.joinToString(", ")}",
                sessionId = session.sessionId,
                askingFor = missing.firstOrNull()
            )
        }

        // 2.5. PROJECT_CREATE: workspace에서 targetPath 유도
        if (draftSpec.taskType == TaskType.PROJECT_CREATE && draftSpec.targetPath == null && workspace != null) {
            val derivedPath = deriveProjectPath(workspace, draftSpec)
            if (derivedPath != null) {
                draftSpec = draftSpec.copy(targetPath = derivedPath)
                session.draftSpec = draftSpec
            }
        }

        // 3. DraftSpec → Spec 변환
        val spec = try {
            draftSpec.toSpec()
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "Spec 변환 실패: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 4. DACS (risky이고 첫 실행일 때만)
        if (draftSpec.isRisky() && session.context.executionHistory.isEmpty()) {
            emitProgress(ProgressPhase.DACS_EVALUATING, "DACS 합의 평가 중...")
            val dacsResponse = evaluateDACS(session, spec, draftSpec)
            if (dacsResponse != null) return dacsResponse
        }

        // 5. LLM 결정이 필요한 작업인지 판단
        return if (needsLlmDecision(draftSpec)) {
            executeLlmDecidedTurn(session, draftSpec, spec)
        } else {
            executeDirectTurn(session, draftSpec, spec, workspace)
        }
    }

    /**
     * LLM 결정이 필요한 TaskType인지 판단
     */
    private fun needsLlmDecision(draftSpec: DraftSpec): Boolean = when (draftSpec.taskType) {
        TaskType.API_WORKFLOW -> true
        else -> false
    }

    /**
     * DACS 평가 헬퍼 - 공통 DACS 로직 추출
     *
     * @return null이면 계속 진행, non-null이면 즉시 반환할 응답
     */
    private suspend fun evaluateDACS(
        session: ConversationSession,
        spec: Spec,
        draftSpec: DraftSpec
    ): ConversationResponse? {
        val dacsContext = buildString {
            draftSpec.intent?.let { append(it) }
            session.context.workspace?.let { append("\nworkspace: $it") }
        }

        val dacsResult = try {
            dacs.evaluate(
                DACSRequest(
                    requestId = session.sessionId,
                    spec = spec,
                    context = dacsContext.ifBlank { null }
                )
            )
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "보안 검토 중 오류 발생: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        return when (dacsResult.consensus) {
            Consensus.NO -> {
                ConversationResponse(
                    action = ActionType.CANCEL,
                    message = "보안상 이 요청을 실행할 수 없습니다: ${dacsResult.reason}",
                    sessionId = session.sessionId,
                    dacsResult = dacsResult
                )
            }
            Consensus.REVISION -> {
                session.history.add(ConversationMessage(
                    MessageRole.SYSTEM,
                    "DACS 추가 확인 필요: ${dacsResult.reason}"
                ))
                ConversationResponse(
                    action = ActionType.ASK,
                    message = "추가 확인이 필요합니다: ${dacsResult.reason}",
                    sessionId = session.sessionId,
                    dacsResult = dacsResult
                )
            }
            Consensus.YES -> null // 계속 진행
        }
    }

    /**
     * 단순 작업 실행 (FILE_READ, FILE_WRITE, COMMAND, PROJECT_CREATE 등)
     *
     * Blueprint 생성 → 실행 → 결과 적재 (세션 리셋 없음)
     *
     * PROJECT_CREATE의 경우 COMMAND step을 분리 실행하여,
     * 파일 생성은 성공했으나 빌드/테스트 명령만 실패한 경우
     * partial success로 처리한다.
     */
    private suspend fun executeDirectTurn(
        session: ConversationSession,
        draftSpec: DraftSpec,
        spec: Spec,
        workspace: String? = null
    ): ConversationResponse {
        // Blueprint 생성
        val blueprint = createBlueprintFromDraftSpec(draftSpec, spec, workspace)

        // PROJECT_CREATE: COMMAND step 분리 실행 (soft-fail)
        if (draftSpec.taskType == TaskType.PROJECT_CREATE && blueprintRunner != null) {
            val (fileSteps, cmdSteps) = blueprint.steps.partition {
                it.type != BlueprintStepType.COMMAND
            }

            // 1. 파일 생성 (필수)
            val fileBlueprint = blueprint.copy(steps = fileSteps)
            emitProgress(ProgressPhase.EXECUTING, "파일 생성 중...", 0, fileSteps.size)
            val fileResult = try {
                blueprintRunner.execute(fileBlueprint)
            } catch (e: Exception) {
                null
            }

            if (fileResult == null || !fileResult.isSuccess) {
                emitProgress(ProgressPhase.DONE)
                val summary = formatExecutionResult(fileResult)
                session.integrateResult(blueprint, fileResult, summary)
                session.resetSpec()
                session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
                session.context.activeTaskId = null
                return ConversationResponse(
                    action = ActionType.EXECUTE,
                    message = summary,
                    sessionId = session.sessionId,
                    blueprint = blueprint,
                    executionResult = fileResult
                )
            }

            // 2. 명령 실행 (선택 — 실패해도 전체 성공)
            var cmdResult: BlueprintExecutionResult? = null
            if (cmdSteps.isNotEmpty()) {
                emitProgress(ProgressPhase.COMMAND_RUNNING, "빌드/테스트 실행 중...")
                val cmdBlueprint = blueprint.copy(steps = cmdSteps)
                cmdResult = try {
                    blueprintRunner.execute(cmdBlueprint)
                } catch (_: Exception) {
                    null
                }
            }

            emitProgress(ProgressPhase.DONE)

            // 통합 결과
            val summary = formatProjectResult(fileResult, cmdResult)
            session.integrateResult(blueprint, fileResult, summary)
            session.resetSpec()
            session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
            session.context.activeTaskId = null

            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = summary,
                sessionId = session.sessionId,
                blueprint = blueprint,
                executionResult = fileResult
            )
        }

        // 일반 실행 (PROJECT_CREATE 외)
        emitProgress(ProgressPhase.EXECUTING, "실행 중...", 0, blueprint.steps.size)
        val executionResult = if (blueprintRunner != null) {
            try {
                blueprintRunner.execute(blueprint)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        emitProgress(ProgressPhase.DONE)

        // 결과를 session context에 적재
        val summary = formatExecutionResult(executionResult)
        session.integrateResult(blueprint, executionResult, summary)

        // 작업 완료: Spec 초기화 + 작업 상태 전이
        session.resetSpec()
        session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
        session.context.activeTaskId = null

        return ConversationResponse(
            action = ActionType.EXECUTE,
            message = summary,
            sessionId = session.sessionId,
            blueprint = blueprint,
            executionResult = executionResult
        )
    }

    /**
     * LLM 결정 작업 실행 (API_WORKFLOW 등)
     *
     * 1회 배치만 수행하고 nextAction 힌트로 caller에게 제어 반환.
     */
    private suspend fun executeLlmDecidedTurn(
        session: ConversationSession,
        draftSpec: DraftSpec,
        spec: Spec
    ): ConversationResponse {
        // LLM이 없으면 실행 불가
        if (llmProvider == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "LLM이 연결되어 있지 않아 API 워크플로우를 실행할 수 없습니다.",
                sessionId = session.sessionId
            )
        }

        // LLM에게 다음 API 호출 결정 요청
        val decision = try {
            decideNextApiCall(draftSpec, session.context.executionHistory, session)
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "API 워크플로우 결정 중 오류: ${e.message}\n\n" +
                    formatTurnExecutionSummary(session.context.executionHistory),
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // writeIntent 첫 선언 저장 (세션 내 고정)
        if (session.context.declaredWriteIntent == null) {
            session.context.declaredWriteIntent = decision.writeIntent
        }
        val writeIntent = session.context.declaredWriteIntent ?: decision.writeIntent

        // 완료 확인 (writeIntent 일관성 검증)
        if (decision.isComplete) {
            if (writeIntent && !hasExecutedWriteOperation(session)) {
                // Case 3: 쓰기 선언했으나 쓰기 미실행 → Continue 강제
                session.history.add(ConversationMessage(
                    MessageRole.SYSTEM,
                    "writeIntent=true로 선언했으나 아직 쓰기 작업(PUT/POST/DELETE/PATCH)이 실행되지 않았습니다. 남은 쓰기 작업을 계속하세요."
                ))
                session.context.pendingAction = PendingAction.ContinueExecution(
                    reasoning = "writeIntent=true declared but no write operation executed"
                )
                return ConversationResponse(
                    action = ActionType.EXECUTE,
                    message = "조회 완료. 쓰기 작업 진행 중...\n\n" +
                        formatTurnExecutionSummary(session.context.executionHistory),
                    sessionId = session.sessionId,
                    nextAction = NextAction.CONTINUE_EXECUTION
                )
            }

            val summary = decision.summary.ifBlank { "API 워크플로우 완료" } + "\n\n" +
                formatTurnExecutionSummary(session.context.executionHistory)
            session.integrateResult(null, null, "완료: ${decision.summary}")
            session.resetSpec()
            session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
            session.context.activeTaskId = null
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = summary,
                sessionId = session.sessionId
            )
        }

        // 중단 확인
        if (decision.isAbort) {
            val summary = "API 워크플로우 중단: ${decision.summary}\n\n" +
                formatTurnExecutionSummary(session.context.executionHistory)
            session.resetSpec()
            session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
            session.context.activeTaskId = null
            return ConversationResponse(
                action = ActionType.CANCEL,
                message = summary,
                sessionId = session.sessionId
            )
        }

        // API 호출이 비어있으면 완료로 간주 (writeIntent 일관성 검증 동일 적용)
        if (decision.calls.isEmpty()) {
            if (writeIntent && !hasExecutedWriteOperation(session)) {
                // Case 3: 쓰기 선언했으나 쓰기 미실행 → Continue 강제
                session.history.add(ConversationMessage(
                    MessageRole.SYSTEM,
                    "writeIntent=true로 선언했으나 아직 쓰기 작업(PUT/POST/DELETE/PATCH)이 실행되지 않았습니다. 남은 쓰기 작업을 계속하세요."
                ))
                session.context.pendingAction = PendingAction.ContinueExecution(
                    reasoning = "writeIntent=true declared but no write operation executed"
                )
                return ConversationResponse(
                    action = ActionType.EXECUTE,
                    message = "조회 완료. 쓰기 작업 진행 중...\n\n" +
                        formatTurnExecutionSummary(session.context.executionHistory),
                    sessionId = session.sessionId,
                    nextAction = NextAction.CONTINUE_EXECUTION
                )
            }

            val summary = decision.summary.ifBlank { "API 워크플로우 완료 (호출 없음)" } + "\n\n" +
                formatTurnExecutionSummary(session.context.executionHistory)
            session.integrateResult(null, null, "완료 (호출 없음)")
            session.resetSpec()
            session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
            session.context.activeTaskId = null
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = summary,
                sessionId = session.sessionId
            )
        }

        // Case 4: 쓰기 미선언인데 쓰기 호출 존재 → Abort
        val hasWriteCall = decision.calls.any { it.method.uppercase() in WRITE_HTTP_METHODS }
        if (!writeIntent && hasWriteCall) {
            val writeCalls = decision.calls
                .filter { it.method.uppercase() in WRITE_HTTP_METHODS }
                .joinToString(", ") { "${it.method} ${it.url}" }
            return ConversationResponse(
                action = ActionType.CANCEL,
                message = "writeIntent=false로 선언했으나 쓰기 API 호출($writeCalls)이 포함되어 있습니다. 워크플로우를 중단합니다.",
                sessionId = session.sessionId
            )
        }

        // Blueprint 생성 및 실행
        val iterationIndex = session.context.executionHistory.size + 1
        val blueprint = createApiCallBlueprint(decision, spec, iterationIndex)
        val executionResult = if (blueprintRunner != null) {
            try {
                blueprintRunner.execute(blueprint)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        // 결과를 session context에 적재
        val resultSummary = formatBlueprintExecutionResult(executionResult)
        session.integrateResult(blueprint, executionResult, resultSummary)

        // 세션 히스토리에도 SYSTEM 메시지로 기록 (LLM이 다음 턴에서 참조)
        session.history.add(ConversationMessage(
            MessageRole.SYSTEM,
            "API Workflow Turn $iterationIndex:\n" +
                "Calls: ${decision.calls.map { "${it.method} ${it.url}" }}\n" +
                "Result: $resultSummary"
        ))

        // pendingAction 설정 → 다음 chat()에서 LLM 판단 없이 바로 executeTurn()
        session.context.pendingAction = PendingAction.ContinueExecution(
            reasoning = decision.reasoning
        )

        // nextAction = CONTINUE_EXECUTION → caller가 자동 계속
        // 사용자에게는 간단한 상태만, 전체 결과는 세션 히스토리에 이미 기록됨
        val briefStatus = if (executionResult?.isSuccess == true) {
            "호출 성공 (${executionResult.successCount} steps) — 결과 분석 중..."
        } else {
            "호출 실패 — 재시도 판단 중..."
        }
        return ConversationResponse(
            action = ActionType.EXECUTE,
            message = "API 실행 중 (Turn $iterationIndex)...\n" +
                "호출: ${decision.calls.map { "[${it.method} ${it.url}]" }.joinToString(", ")}\n" +
                briefStatus,
            sessionId = session.sessionId,
            blueprint = blueprint,
            executionResult = executionResult,
            nextAction = NextAction.CONTINUE_EXECUTION
        )
    }

    /**
     * DraftSpec에서 Blueprint 생성
     */
    private suspend fun createBlueprintFromDraftSpec(draftSpec: DraftSpec, spec: Spec, workspace: String? = null): Blueprint {
        emitProgress(ProgressPhase.BLUEPRINT_CREATING, "Blueprint 생성 중...")
        val now = Instant.now().toString()
        val steps = createSteps(draftSpec, workspace)

        return Blueprint(
            id = "bp-${UUID.randomUUID()}",
            version = "1.0",
            specSnapshot = SpecSnapshot(
                specId = spec.id,
                specVersion = spec.version,
                snapshotAt = now,
                governorId = id,
                dacsResult = if (draftSpec.isRisky()) "YES" else "DIRECT_ALLOW"
            ),
            steps = steps,
            metadata = BlueprintMetadata(
                createdAt = now,
                createdBy = id,
                description = draftSpec.intent?.take(80) ?: "Conversational Governor Blueprint",
                tags = listOf("conversational", draftSpec.taskType?.name?.lowercase() ?: "unknown")
            )
        )
    }

    /**
     * DraftSpec에서 BlueprintStep 생성
     */
    private suspend fun createSteps(draftSpec: DraftSpec, workspace: String? = null): List<BlueprintStep> {
        val stepId = "step-${UUID.randomUUID().toString().take(8)}"

        return when (draftSpec.taskType) {
            TaskType.FILE_READ -> {
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.FILE_READ,
                        params = mapOf("path" to (draftSpec.targetPath ?: ""))
                    )
                )
            }

            TaskType.FILE_WRITE -> {
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.FILE_WRITE,
                        params = mapOf(
                            "path" to (draftSpec.targetPath ?: ""),
                            "content" to (draftSpec.content ?: "")
                        )
                    )
                )
            }

            TaskType.FILE_DELETE -> {
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.FILE_DELETE,
                        params = mapOf("path" to (draftSpec.targetPath ?: ""))
                    )
                )
            }

            TaskType.COMMAND -> {
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.COMMAND,
                        params = mapOf(
                            "command" to (draftSpec.content ?: ""),
                            "args" to "",
                            "workingDir" to (draftSpec.targetPath ?: ""),
                            "timeoutMs" to "60000"
                        )
                    )
                )
            }

            TaskType.PROJECT_CREATE -> {
                val basePath = draftSpec.targetPath
                    ?: deriveProjectPath(workspace, draftSpec)
                    ?: "/tmp/wiiiv-project"
                // LLM이 있으면 실제 프로젝트 파일 생성 시도
                if (llmProvider != null) {
                    try {
                        generateProjectBlueprint(draftSpec, basePath)
                    } catch (e: Exception) {
                        // fallback: 기존 mkdir + README
                        println("[WARN] generateProjectBlueprint failed: ${e::class.simpleName}: ${e.message}")
                        createFallbackProjectSteps(draftSpec, basePath)
                    }
                } else {
                    createFallbackProjectSteps(draftSpec, basePath)
                }
            }

            TaskType.API_WORKFLOW -> {
                // API_WORKFLOW는 executeApiWorkflow()에서 동적으로 Blueprint 생성
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.NOOP,
                        params = mapOf("reason" to "API_WORKFLOW handled by iterative loop")
                    )
                )
            }

            TaskType.INFORMATION, TaskType.CONVERSATION, null -> {
                // 실행이 필요하지 않은 타입
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.NOOP,
                        params = mapOf("reason" to (draftSpec.intent ?: "No operation needed"))
                    )
                )
            }
        }
    }

    /**
     * LLM을 사용하여 프로젝트 파일 구조를 생성하고 BlueprintStep 목록으로 변환
     */
    private suspend fun generateProjectBlueprint(draftSpec: DraftSpec, basePath: String): List<BlueprintStep> {
        val prompt = GovernorPrompt.projectGenerationPrompt(draftSpec)

        val response = llmProvider!!.call(
            LlmRequest(
                action = LlmAction.COMPLETE,
                prompt = prompt,
                model = model ?: llmProvider.defaultModel,
                maxTokens = 4096
            )
        )

        // JSON 파싱
        val jsonStr = extractJson(response.content)
        val jsonElement = json.parseToJsonElement(jsonStr).jsonObject

        val filesArray = jsonElement["files"]?.jsonArray
            ?: throw IllegalStateException("LLM response missing 'files' array")

        val steps = mutableListOf<BlueprintStep>()

        // 1. 루트 디렉토리 생성
        steps.add(BlueprintStep(
            stepId = "step-mkdir-root-${UUID.randomUUID().toString().take(4)}",
            type = BlueprintStepType.FILE_MKDIR,
            params = mapOf("path" to basePath)
        ))

        // 2. 필요한 디렉토리 수집 및 생성
        val dirs = mutableSetOf<String>()
        for (fileElement in filesArray) {
            val fileObj = fileElement.jsonObject
            val relativePath = fileObj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val fullPath = "$basePath/$relativePath"
            val parentDir = java.io.File(fullPath).parent
            if (parentDir != null && parentDir != basePath) {
                dirs.add(parentDir)
            }
        }

        for (dir in dirs.sorted()) {
            steps.add(BlueprintStep(
                stepId = "step-mkdir-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_MKDIR,
                params = mapOf("path" to dir)
            ))
        }

        // 3. 파일 쓰기
        for (fileElement in filesArray) {
            val fileObj = fileElement.jsonObject
            val relativePath = fileObj["path"]?.jsonPrimitive?.contentOrNull ?: continue
            val content = fileObj["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val fullPath = "$basePath/$relativePath"

            steps.add(BlueprintStep(
                stepId = "step-write-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_WRITE,
                params = mapOf("path" to fullPath, "content" to content)
            ))
        }

        // 4. 빌드 명령어 (있으면)
        val buildCommand = jsonElement["buildCommand"]?.jsonPrimitive?.contentOrNull
        if (!buildCommand.isNullOrBlank() && buildCommand != "null") {
            steps.add(BlueprintStep(
                stepId = "step-build-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.COMMAND,
                params = mapOf(
                    "command" to "sh",
                    "args" to "-c $buildCommand",
                    "workingDir" to basePath,
                    "timeoutMs" to "300000"
                )
            ))
        }

        // 5. 테스트 명령어 (있으면)
        val testCommand = jsonElement["testCommand"]?.jsonPrimitive?.contentOrNull
        if (!testCommand.isNullOrBlank() && testCommand != "null") {
            steps.add(BlueprintStep(
                stepId = "step-test-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.COMMAND,
                params = mapOf(
                    "command" to "sh",
                    "args" to "-c $testCommand",
                    "workingDir" to basePath,
                    "timeoutMs" to "300000"
                )
            ))
        }

        return steps
    }

    /**
     * LLM 실패 시 fallback: 기존 mkdir + README 생성
     */
    private fun createFallbackProjectSteps(draftSpec: DraftSpec, basePath: String): List<BlueprintStep> {
        return listOf(
            BlueprintStep(
                stepId = "step-mkdir-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_MKDIR,
                params = mapOf("path" to basePath)
            ),
            BlueprintStep(
                stepId = "step-write-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_WRITE,
                params = mapOf(
                    "path" to "$basePath/README.md",
                    "content" to buildProjectReadme(draftSpec)
                )
            )
        )
    }

    /**
     * 프로젝트 README 생성
     */
    private fun buildProjectReadme(draftSpec: DraftSpec): String = buildString {
        appendLine("# ${draftSpec.domain ?: "New Project"}")
        appendLine()
        draftSpec.intent?.let {
            appendLine("## 설명")
            appendLine(it)
            appendLine()
        }
        draftSpec.techStack?.let { techs ->
            appendLine("## 기술 스택")
            techs.forEach { appendLine("- $it") }
            appendLine()
        }
        draftSpec.scale?.let {
            appendLine("## 규모")
            appendLine(it)
            appendLine()
        }
        draftSpec.constraints?.let { constraints ->
            if (constraints.isNotEmpty()) {
                appendLine("## 제약 조건")
                constraints.forEach { appendLine("- $it") }
            }
        }
    }

    /**
     * 실행 결과 포맷팅
     */
    private fun formatExecutionResult(result: BlueprintExecutionResult?): String {
        if (result == null) {
            return "Blueprint가 생성되었습니다. 실행기가 연결되지 않아 자동 실행되지 않았습니다."
        }

        return buildString {
            if (result.isSuccess) {
                appendLine("실행 완료!")
                appendLine()
                appendLine("성공: ${result.successCount}개 step")
            } else {
                appendLine("실행 중 문제 발생")
                appendLine()
                appendLine("성공: ${result.successCount}개, 실패: ${result.failureCount}개")
            }

            // Step 출력 포함 (파일 내용, 명령 결과 등)
            result.runnerResult.results.forEach { stepResult ->
                val stepId = stepResult.meta.stepId
                val output = result.context.getStepOutput(stepId) ?: return@forEach

                // FILE_READ: content
                output.json["content"]?.let { content ->
                    val text = content.jsonPrimitive.contentOrNull ?: return@let
                    val path = output.json["path"]?.jsonPrimitive?.contentOrNull ?: ""
                    appendLine()
                    appendLine("[$path]")
                    appendLine(text.take(2000))
                }

                // COMMAND: stdout
                output.stdout?.let { stdout ->
                    if (stdout.isNotBlank()) {
                        appendLine()
                        appendLine("[stdout]")
                        appendLine(stdout.take(2000))
                    }
                }

                // API: statusCode + body
                output.json["statusCode"]?.let { statusCode ->
                    val code = statusCode.jsonPrimitive.contentOrNull
                    val body = output.json["body"]?.jsonPrimitive?.contentOrNull
                    appendLine()
                    appendLine("[HTTP $code] ${body?.take(1000) ?: ""}")
                }
            }
        }
    }

    /**
     * PROJECT_CREATE 결과 포맷팅 — 파일 생성 + COMMAND 분리 실행 결과
     *
     * 파일 생성 성공 + COMMAND 실패 → partial success 메시지
     */
    private fun formatProjectResult(
        fileResult: BlueprintExecutionResult,
        cmdResult: BlueprintExecutionResult?
    ): String = buildString {
        appendLine("프로젝트 생성 완료!")
        appendLine()
        appendLine("파일 생성: ${fileResult.successCount}개 step 성공")

        if (cmdResult != null) {
            if (cmdResult.isSuccess) {
                appendLine("빌드/테스트: 성공 (${cmdResult.successCount}개 step)")
            } else {
                appendLine()
                appendLine("⚠ 빌드/테스트 실행 실패 — 수동 확인 필요")
                // 실패 상세 (어떤 명령이 실패했는지)
                cmdResult.runnerResult.results.forEach { stepResult ->
                    if (stepResult is io.wiiiv.execution.ExecutionResult.Failure) {
                        val stepId = stepResult.meta.stepId
                        appendLine("  - $stepId: ${stepResult.error.message}")
                    }
                }
            }
        }

        // 파일 생성 결과 출력
        fileResult.runnerResult.results.forEach { stepResult ->
            val stepId = stepResult.meta.stepId
            val output = fileResult.context.getStepOutput(stepId) ?: return@forEach
            output.json["content"]?.let { content ->
                val text = content.jsonPrimitive.contentOrNull ?: return@let
                val path = output.json["path"]?.jsonPrimitive?.contentOrNull ?: ""
                appendLine()
                appendLine("[$path]")
                appendLine(text.take(2000))
            }
        }
    }

    /**
     * LLM 없이 기본 처리
     */
    private fun handleWithoutLlm(session: ConversationSession, userMessage: String): ConversationResponse {
        // 간단한 키워드 기반 처리
        val lower = userMessage.lowercase()

        return when {
            lower in listOf("안녕", "hello", "hi", "안녕하세요") -> {
                session.addGovernorMessage("안녕하세요! wiiiv Governor입니다. 무엇을 도와드릴까요?")
                ConversationResponse(
                    action = ActionType.REPLY,
                    message = "안녕하세요! wiiiv Governor입니다. 무엇을 도와드릴까요?",
                    sessionId = session.sessionId
                )
            }

            lower.contains("취소") || lower.contains("됐어") || lower.contains("그만") -> {
                session.cancelCurrentTask()
                session.addGovernorMessage("알겠습니다. 언제든 새로 시작할 수 있어요.")
                ConversationResponse(
                    action = ActionType.CANCEL,
                    message = "알겠습니다. 언제든 새로 시작할 수 있어요.",
                    sessionId = session.sessionId
                )
            }

            else -> {
                // LLM 없이는 제한적 기능만 제공
                val message = "죄송합니다. 현재 LLM이 연결되어 있지 않아 기본 기능만 제공됩니다. " +
                    "API 키를 설정하면 더 자연스러운 대화가 가능합니다."
                session.addGovernorMessage(message)
                ConversationResponse(
                    action = ActionType.REPLY,
                    message = message,
                    sessionId = session.sessionId
                )
            }
        }
    }

    /**
     * JSON 추출 + tail — 중괄호 균형 매칭으로 JSON 객체와 나머지 텍스트를 분리한다.
     *
     * LLM이 JSON 뒤에 코드블록 등을 추가로 출력하는 경우,
     * JSON 부분과 나머지(tail)를 모두 반환하여 message에 합칠 수 있게 한다.
     *
     * @return Pair(jsonString, tailContent)
     */
    private fun extractJsonWithTail(response: String): Pair<String, String> {
        val trimmed = response.trim()

        // 1. 직접 JSON인 경우 (tail 없음)
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return Pair(trimmed, "")
        }

        // 2. 중괄호 균형 매칭으로 JSON + tail 분리
        val start = trimmed.indexOf('{')
        if (start == -1) return Pair(trimmed, "")

        var depth = 0
        var inString = false
        var escaped = false

        for (i in start until trimmed.length) {
            val c = trimmed[i]
            if (escaped) {
                escaped = false
                continue
            }
            when {
                c == '\\' && inString -> escaped = true
                c == '"' && !escaped -> inString = !inString
                c == '{' && !inString -> depth++
                c == '}' && !inString -> {
                    depth--
                    if (depth == 0) {
                        val jsonPart = trimmed.substring(start, i + 1)
                        // JSON 뒤의 텍스트에서 코드블록 래핑(```) 제거
                        val tailRaw = trimmed.substring(i + 1).trim()
                        val tail = tailRaw
                            .removePrefix("```")  // JSON 코드블록 닫힘
                            .trim()
                        return Pair(jsonPart, tail)
                    }
                }
            }
        }

        return Pair(trimmed, "")
    }

    /**
     * JSON만 추출 (tail 불필요한 호출용)
     */
    private fun extractJson(response: String): String {
        return extractJsonWithTail(response).first
    }

    /**
     * 작업 전환 처리
     *
     * 현재 활성 작업을 SUSPENDED로 변경하고, 대상 작업을 찾아 ACTIVE로 복원한다.
     * 대상을 찾지 못하면 activeTaskId를 null로 설정 (새 작업 생성 대기).
     */
    private fun handleTaskSwitch(session: ConversationSession, switchSignal: String) {
        // 현재 작업 보존 (활성 TaskSlot 또는 인터뷰 중 fallback DraftSpec)
        val suspendedId = session.suspendCurrentWork()?.id

        // 대상 작업 검색 (방금 중단한 작업 제외, ID 또는 라벨 매칭)
        val targetTask = session.context.tasks.values.find { task ->
            task.id != suspendedId &&
            task.status == TaskStatus.SUSPENDED &&
            (task.id == switchSignal || task.label.contains(switchSignal, ignoreCase = true))
        }

        if (targetTask != null) {
            targetTask.status = TaskStatus.ACTIVE
            session.context.activeTaskId = targetTask.id
        }
    }

    /**
     * LLM에게 다음 API 호출 결정 요청
     */
    private suspend fun decideNextApiCall(
        draftSpec: DraftSpec,
        history: List<TurnExecution>,
        session: ConversationSession
    ): ApiWorkflowDecision {
        // RAG 컨텍스트 조회
        val ragContext = consultRagForApiKnowledge(draftSpec)

        // 실행 히스토리 포맷 (TurnExecution → 문자열)
        val executionHistory = history.map { turn ->
            buildString {
                appendLine("Turn ${turn.turnIndex}: ${turn.summary}")
            }
        }

        // 이미 호출한 API 목록 추출 (Blueprint steps에서 METHOD + URL)
        val calledApis = history.flatMap { turn ->
            turn.blueprint?.steps
                ?.filter { it.type == BlueprintStepType.API_CALL }
                ?.map { step ->
                    val method = step.params["method"] ?: "GET"
                    val url = step.params["url"] ?: ""
                    "$method $url"
                } ?: emptyList()
        }

        // 프롬프트 생성
        val prompt = GovernorPrompt.apiWorkflowPrompt(
            intent = draftSpec.intent ?: "",
            domain = draftSpec.domain,
            ragContext = ragContext,
            executionHistory = executionHistory,
            calledApis = calledApis,
            recentHistory = session.getRecentHistory(5)
        )

        // LLM 호출
        val response = llmProvider!!.call(
            LlmRequest(
                action = LlmAction.COMPLETE,
                prompt = prompt,
                model = model ?: llmProvider.defaultModel,
                maxTokens = 2000
            )
        )

        return parseApiWorkflowDecision(response.content)
    }

    /**
     * RAG를 통해 API 지식 검색
     */
    private suspend fun consultRagForApiKnowledge(draftSpec: DraftSpec): String? {
        if (ragPipeline == null) return null

        val query = buildString {
            draftSpec.intent?.let { append(it) }
            draftSpec.domain?.let { append(" $it") }
        }.trim()

        if (query.isBlank()) return null

        return try {
            val result = ragPipeline.search(query, topK = 5)
            if (result.isEmpty()) null else result.toNumberedContext()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * LLM 응답을 ApiWorkflowDecision으로 파싱
     */
    private fun parseApiWorkflowDecision(response: String): ApiWorkflowDecision {
        val jsonStr = extractJson(response)

        return try {
            val jsonElement = json.parseToJsonElement(jsonStr).jsonObject

            val isComplete = jsonElement["isComplete"]?.jsonPrimitive?.booleanOrNull ?: false
            val isAbort = jsonElement["isAbort"]?.jsonPrimitive?.booleanOrNull ?: false
            val reasoning = jsonElement["reasoning"]?.jsonPrimitive?.contentOrNull ?: ""
            val summary = jsonElement["summary"]?.jsonPrimitive?.contentOrNull ?: ""

            val calls = jsonElement["calls"]?.jsonArray?.mapNotNull { callElement ->
                try {
                    val callObj = callElement.jsonObject
                    ApiCallDecision(
                        method = callObj["method"]?.jsonPrimitive?.contentOrNull ?: "GET",
                        url = callObj["url"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        headers = callObj["headers"]?.jsonObject?.entries?.associate {
                            it.key to (it.value.jsonPrimitive.contentOrNull ?: "")
                        } ?: emptyMap(),
                        body = callObj["body"]?.let {
                            if (it is JsonNull) null
                            else it.jsonPrimitive.contentOrNull
                        }
                    )
                } catch (_: Exception) {
                    null
                }
            } ?: emptyList()

            val writeIntent = jsonElement["writeIntent"]?.jsonPrimitive?.booleanOrNull ?: false

            ApiWorkflowDecision(
                isComplete = isComplete,
                isAbort = isAbort,
                reasoning = reasoning,
                summary = summary,
                calls = calls,
                writeIntent = writeIntent
            )
        } catch (e: Exception) {
            // 파싱 실패 시 abort
            ApiWorkflowDecision(
                isComplete = false,
                isAbort = true,
                reasoning = "Failed to parse LLM response: ${e.message}",
                summary = "파싱 실패로 워크플로우 중단",
                calls = emptyList()
            )
        }
    }

    /**
     * ApiWorkflowDecision을 Blueprint로 변환
     */
    private fun createApiCallBlueprint(
        decision: ApiWorkflowDecision,
        spec: Spec,
        iteration: Int
    ): Blueprint {
        val now = Instant.now().toString()

        val steps = decision.calls.mapIndexed { idx, call ->
            val method = try { HttpMethod.valueOf(call.method.uppercase()) }
                         catch (_: Exception) { HttpMethod.GET }

            val params = mutableMapOf(
                "method" to method.name,
                "url" to call.url,
                "timeoutMs" to "30000"
            )
            call.headers.forEach { (k, v) -> params["header:$k"] = v }
            call.body?.let { params["body"] = it }

            BlueprintStep(
                stepId = "step-api-iter${iteration}-${idx}",
                type = BlueprintStepType.API_CALL,
                params = params
            )
        }

        return Blueprint(
            id = "bp-workflow-iter${iteration}-${UUID.randomUUID().toString().take(8)}",
            version = "1.0",
            specSnapshot = SpecSnapshot(
                specId = spec.id,
                specVersion = spec.version,
                snapshotAt = now,
                governorId = id,
                dacsResult = "YES"
            ),
            steps = steps,
            metadata = BlueprintMetadata(
                createdAt = now,
                createdBy = id,
                description = "API Workflow iteration $iteration: ${decision.reasoning.take(80)}",
                tags = listOf("api-workflow", "iteration-$iteration")
            )
        )
    }

    /**
     * Blueprint 실행 결과 포맷팅
     */
    private fun formatBlueprintExecutionResult(result: BlueprintExecutionResult?): String {
        if (result == null) return "실행기 미연결"

        return buildString {
            if (result.isSuccess) {
                append("성공 (${result.successCount} steps)")
                // Step 출력 수집
                result.runnerResult.results.forEach { stepResult ->
                    val stepId = stepResult.meta.stepId
                    val output = result.context.getStepOutput(stepId)
                    if (output != null) {
                        val statusCode = output.json["statusCode"]?.jsonPrimitive?.contentOrNull
                        val body = output.json["body"]?.jsonPrimitive?.contentOrNull
                        append("\n  [$stepId] HTTP $statusCode: ${body?.take(4000) ?: "empty"}")
                    }
                }
            } else {
                append("실패 (성공: ${result.successCount}, 실패: ${result.failureCount})")
            }
        }
    }

    /**
     * 턴 실행 히스토리 전체 요약 포맷팅
     */
    private fun formatTurnExecutionSummary(turns: List<TurnExecution>): String = buildString {
        appendLine("=== API Workflow Summary ===")
        appendLine("Total iterations: ${turns.size}")
        for (turn in turns) {
            appendLine("  [Iteration ${turn.turnIndex}]")
            appendLine("    Result: ${turn.summary.take(200)}")
        }
    }

    // === Governor interface 구현 (하위 호환) ===

    /**
     * 기존 Governor 인터페이스 구현
     *
     * ConversationalGovernor는 대화형이지만, 기존 createBlueprint도 지원
     * (LlmGovernor 로직 위임)
     */
    override suspend fun createBlueprint(request: GovernorRequest, spec: Spec): GovernorResult {
        // DACS 합의 요청
        val dacsResult = try {
            dacs.evaluate(
                DACSRequest(
                    requestId = request.requestId,
                    spec = spec,
                    context = request.intent
                )
            )
        } catch (e: Exception) {
            return GovernorResult.Failed("DACS evaluation failed: ${e.message}")
        }

        return when (dacsResult.consensus) {
            Consensus.YES -> {
                val now = Instant.now().toString()
                val steps = createStepsFromRequest(request)

                val blueprint = Blueprint(
                    id = "bp-${UUID.randomUUID()}",
                    version = "1.0",
                    specSnapshot = SpecSnapshot(
                        specId = spec.id,
                        specVersion = spec.version,
                        snapshotAt = now,
                        governorId = id,
                        dacsResult = "YES"
                    ),
                    steps = steps,
                    metadata = BlueprintMetadata(
                        createdAt = now,
                        createdBy = id,
                        description = spec.intent.take(80).ifBlank { "Conversational Governor Blueprint" },
                        tags = listOf("conversational")
                    )
                )

                GovernorResult.BlueprintCreated(blueprint)
            }

            Consensus.NO -> GovernorResult.Denied(
                reason = "DACS consensus: NO - ${dacsResult.reason}",
                specId = spec.id
            )

            Consensus.REVISION -> GovernorResult.Failed("REVISION - ${dacsResult.reason}")
        }
    }

    private fun createStepsFromRequest(request: GovernorRequest): List<BlueprintStep> {
        val stepId = "step-${UUID.randomUUID().toString().take(8)}"

        return when (request.type) {
            RequestType.FILE_READ -> listOf(
                BlueprintStep(stepId, BlueprintStepType.FILE_READ, mapOf("path" to (request.targetPath ?: "")))
            )
            RequestType.FILE_WRITE -> listOf(
                BlueprintStep(stepId, BlueprintStepType.FILE_WRITE, mapOf(
                    "path" to (request.targetPath ?: ""),
                    "content" to (request.content ?: "")
                ))
            )
            RequestType.FILE_DELETE -> listOf(
                BlueprintStep(stepId, BlueprintStepType.FILE_DELETE, mapOf("path" to (request.targetPath ?: "")))
            )
            RequestType.COMMAND -> listOf(
                BlueprintStep(stepId, BlueprintStepType.COMMAND, mapOf(
                    "command" to (request.params["command"] ?: ""),
                    "args" to (request.params["args"] ?: ""),
                    "workingDir" to (request.params["workingDir"] ?: ""),
                    "timeoutMs" to (request.params["timeoutMs"] ?: "60000")
                ))
            )
            else -> listOf(
                BlueprintStep(stepId, BlueprintStepType.NOOP, mapOf("reason" to "No operation for type ${request.type}"))
            )
        }
    }

    /**
     * 실행 히스토리에서 쓰기 작업(PUT/POST/DELETE/PATCH) 존재 여부 확인
     */
    private fun hasExecutedWriteOperation(session: ConversationSession): Boolean {
        return session.context.executionHistory.any { turn ->
            turn.blueprint?.steps?.any { step ->
                step.type == BlueprintStepType.API_CALL &&
                    step.params["method"] in WRITE_HTTP_METHODS
            } ?: false
        }
    }

    /**
     * 워크스페이스와 DraftSpec으로부터 프로젝트 경로를 유도한다.
     *
     * domain 또는 intent에서 slug를 생성하여 workspace 하위에 프로젝트 디렉토리 경로를 반환.
     * workspace가 null이면 null 반환.
     */
    internal fun deriveProjectPath(workspace: String?, draftSpec: DraftSpec): String? {
        if (workspace == null) return null

        val slug = generateSlug(draftSpec.domain ?: draftSpec.intent ?: return null)
        return "$workspace/$slug"
    }

    /**
     * 한국어/영어 텍스트를 kebab-case slug로 변환한다.
     *
     * 예: "연락처 관리" → "contact-manager"
     *     "E-commerce Backend" → "e-commerce-backend"
     */
    internal fun generateSlug(text: String): String {
        // 한국어 도메인 → 영문 slug 매핑 (공통 패턴)
        val koreanMappings = mapOf(
            "연락처" to "contact",
            "주소록" to "addressbook",
            "할일" to "todo",
            "관리" to "manager",
            "쇼핑몰" to "shopping-mall",
            "쇼핑" to "shopping",
            "게시판" to "board",
            "블로그" to "blog",
            "채팅" to "chat",
            "일정" to "schedule",
            "학생" to "student",
            "학점" to "grade",
            "성적" to "grade",
            "도서" to "library",
            "재고" to "inventory",
            "주문" to "order",
            "회원" to "member",
            "사용자" to "user",
            "인증" to "auth",
            "결제" to "payment",
            "배송" to "delivery",
            "메모" to "memo",
            "노트" to "note",
            "프로젝트" to "project",
            "시스템" to "system",
            "서버" to "server",
            "백엔드" to "backend",
            "프론트엔드" to "frontend",
            "데이터" to "data",
            "분석" to "analytics",
            "대시보드" to "dashboard",
            "API" to "api",
            "앱" to "app"
        )

        // 한국어 텍스트에서 매핑 가능한 단어들을 영문으로 변환
        val parts = mutableListOf<String>()
        var remaining = text.trim()

        for ((korean, english) in koreanMappings) {
            if (remaining.contains(korean)) {
                parts.add(english)
                remaining = remaining.replace(korean, " ")
            }
        }

        // 영문 단어도 추출
        val englishWords = remaining
            .replace(Regex("[^a-zA-Z0-9\\s-]"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .map { it.lowercase() }

        parts.addAll(englishWords)

        val slug = parts
            .distinct()
            .take(3)
            .joinToString("-")

        return slug.ifBlank { "wiiiv-project" }
    }

    companion object {
        /** 쓰기 HTTP 메서드 */
        private val WRITE_HTTP_METHODS = setOf("PUT", "POST", "DELETE", "PATCH")

        /**
         * ConversationalGovernor 생성
         */
        fun create(
            id: String = "gov-conv-${UUID.randomUUID().toString().take(8)}",
            dacs: DACS,
            llmProvider: LlmProvider? = null,
            model: String? = null,
            blueprintRunner: BlueprintRunner? = null,
            ragPipeline: RagPipeline? = null
        ): ConversationalGovernor {
            return ConversationalGovernor(id, dacs, llmProvider, model, blueprintRunner, ragPipeline)
        }
    }
}

/**
 * 대화 응답
 */
data class ConversationResponse(
    /**
     * 행동 유형
     */
    val action: ActionType,

    /**
     * 사용자에게 보낼 메시지
     */
    val message: String,

    /**
     * 세션 ID
     */
    val sessionId: String,

    /**
     * 현재 DraftSpec 상태 (ASK/CONFIRM 시)
     */
    val draftSpec: DraftSpec? = null,

    /**
     * 다음에 물어볼 슬롯 (ASK 시)
     */
    val askingFor: String? = null,

    /**
     * 확인 요약 (CONFIRM 시)
     */
    val confirmationSummary: String? = null,

    /**
     * DACS 결과 (EXECUTE 시)
     */
    val dacsResult: DACSResult? = null,

    /**
     * 생성된 Blueprint (EXECUTE 시)
     */
    val blueprint: Blueprint? = null,

    /**
     * 실행 결과 (EXECUTE 시)
     */
    val executionResult: BlueprintExecutionResult? = null,

    /**
     * 오류 메시지
     */
    val error: String? = null,

    /**
     * 다음 행동 힌트 (턴 기반 실행 모델)
     * null이면 단일 턴 완료, CONTINUE_EXECUTION이면 caller가 자동 계속
     */
    val nextAction: NextAction? = null
)

/**
 * API 워크플로우 결정 - LLM이 반환하는 다음 API 호출 결정
 */
data class ApiWorkflowDecision(
    /** 작업 완료 여부 */
    val isComplete: Boolean,
    /** 작업 중단 여부 */
    val isAbort: Boolean,
    /** 다음 호출 이유 */
    val reasoning: String,
    /** 현재까지 진행 요약 */
    val summary: String,
    /** 호출할 API 목록 */
    val calls: List<ApiCallDecision>,
    /** 쓰기 의도 선언 (LLM이 첫 응답에서 선언) */
    val writeIntent: Boolean = false
)

/**
 * 단일 API 호출 결정
 */
data class ApiCallDecision(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null
)

