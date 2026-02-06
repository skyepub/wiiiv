package io.wiiiv.governor

import io.wiiiv.blueprint.*
import io.wiiiv.dacs.DACS
import io.wiiiv.dacs.DACSRequest
import io.wiiiv.dacs.DACSResult
import io.wiiiv.dacs.Consensus
import io.wiiiv.execution.LlmAction
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
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
    private val blueprintRunner: BlueprintRunner? = null
) : Governor {

    private val sessions = ConcurrentHashMap<String, ConversationSession>()
    private val json = Json { ignoreUnknownKeys = true }

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
    suspend fun chat(sessionId: String, userMessage: String): ConversationResponse {
        val session = sessions[sessionId]
            ?: return ConversationResponse(
                action = ActionType.CANCEL,
                message = "세션을 찾을 수 없습니다. 새 대화를 시작해주세요.",
                sessionId = sessionId
            )

        // 사용자 메시지 기록
        session.addUserMessage(userMessage)

        // LLM이 없으면 기본 처리
        if (llmProvider == null) {
            return handleWithoutLlm(session, userMessage)
        }

        // LLM으로 다음 행동 결정
        val governorAction = try {
            decideAction(session, userMessage)
        } catch (e: Exception) {
            GovernorAction(
                action = ActionType.REPLY,
                message = "죄송합니다. 처리 중 오류가 발생했습니다: ${e.message}"
            )
        }

        // specUpdates 적용
        governorAction.specUpdates?.let { updates ->
            session.updateSpec(updates)
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
    private suspend fun decideAction(session: ConversationSession, userMessage: String): GovernorAction {
        val systemPrompt = GovernorPrompt.withContext(
            draftSpec = session.draftSpec,
            recentHistory = session.getRecentHistory(10)
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
                maxTokens = 1000
            )
        )

        return parseGovernorAction(response.content)
    }

    /**
     * LLM 응답을 GovernorAction으로 파싱
     */
    private fun parseGovernorAction(response: String): GovernorAction {
        val jsonStr = extractJson(response)

        return try {
            val jsonElement = json.parseToJsonElement(jsonStr).jsonObject

            val actionStr = jsonElement["action"]?.jsonPrimitive?.contentOrNull ?: "REPLY"
            val action = try {
                ActionType.valueOf(actionStr)
            } catch (_: Exception) {
                ActionType.REPLY
            }

            val message = jsonElement["message"]?.jsonPrimitive?.contentOrNull ?: ""

            val specUpdates = jsonElement["specUpdates"]?.jsonObject?.let {
                it.entries.associate { (k, v) -> k to v }
            }

            val askingFor = jsonElement["askingFor"]?.jsonPrimitive?.contentOrNull

            GovernorAction(
                action = action,
                message = message,
                specUpdates = specUpdates,
                askingFor = askingFor
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
                executeWithDraftSpec(session)
            }

            ActionType.CANCEL -> {
                session.reset()
                ConversationResponse(
                    action = ActionType.CANCEL,
                    message = action.message.ifBlank { "알겠습니다. 언제든 새로 시작할 수 있어요." },
                    sessionId = session.sessionId
                )
            }
        }
    }

    /**
     * DraftSpec 기반 실행
     */
    private suspend fun executeWithDraftSpec(session: ConversationSession): ConversationResponse {
        val draftSpec = session.draftSpec

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

        // 4. 위험한 작업이면 DACS 호출
        if (draftSpec.isRisky()) {
            val dacsResult = try {
                dacs.evaluate(
                    DACSRequest(
                        requestId = session.sessionId,
                        spec = spec,
                        context = draftSpec.intent
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

            when (dacsResult.consensus) {
                Consensus.NO -> {
                    session.reset()
                    return ConversationResponse(
                        action = ActionType.CANCEL,
                        message = "보안상 이 요청을 실행할 수 없습니다: ${dacsResult.reason}",
                        sessionId = session.sessionId,
                        dacsResult = dacsResult
                    )
                }
                Consensus.REVISION -> {
                    // DACS 피드백을 히스토리에 SYSTEM 메시지로 기록
                    // → 다음 턴에서 LLM이 이 context를 참고하여 재질문 가능
                    session.history.add(ConversationMessage(
                        MessageRole.SYSTEM,
                        "DACS 추가 확인 필요: ${dacsResult.reason}"
                    ))
                    return ConversationResponse(
                        action = ActionType.ASK,
                        message = "추가 확인이 필요합니다: ${dacsResult.reason}",
                        sessionId = session.sessionId,
                        dacsResult = dacsResult
                    )
                }
                Consensus.YES -> {
                    // 계속 진행
                }
            }
        }

        // 5. Blueprint 생성
        val blueprint = createBlueprintFromDraftSpec(draftSpec, spec)

        // 6. 실행
        val executionResult = if (blueprintRunner != null) {
            try {
                blueprintRunner.execute(blueprint)
            } catch (e: Exception) {
                null // 실행 실패
            }
        } else {
            // BlueprintRunner가 없으면 Blueprint만 반환
            null
        }

        // 7. 세션 리셋 (성공 시)
        if (executionResult?.isSuccess != false) {
            session.reset()
        }

        return ConversationResponse(
            action = ActionType.EXECUTE,
            message = formatExecutionResult(executionResult),
            sessionId = session.sessionId,
            blueprint = blueprint,
            executionResult = executionResult
        )
    }

    /**
     * DraftSpec에서 Blueprint 생성
     */
    private fun createBlueprintFromDraftSpec(draftSpec: DraftSpec, spec: Spec): Blueprint {
        val now = Instant.now().toString()
        val steps = createSteps(draftSpec)

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
    private fun createSteps(draftSpec: DraftSpec): List<BlueprintStep> {
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
                // 복잡한 프로젝트 생성은 여러 스텝으로 분해
                val basePath = draftSpec.targetPath ?: "/tmp/wiiiv-project"
                listOf(
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
                session.reset()
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
     * JSON 추출 (마크다운 코드 블록 등 제거)
     */
    private fun extractJson(response: String): String {
        val codeBlockRegex = """```(?:json)?\s*([\s\S]*?)```""".toRegex()
        val codeBlockMatch = codeBlockRegex.find(response)
        if (codeBlockMatch != null) {
            return codeBlockMatch.groupValues[1].trim()
        }

        val jsonRegex = """\{[\s\S]*\}""".toRegex()
        val jsonMatch = jsonRegex.find(response)
        if (jsonMatch != null) {
            return jsonMatch.value
        }

        return response.trim()
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

    companion object {
        /**
         * ConversationalGovernor 생성
         */
        fun create(
            id: String = "gov-conv-${UUID.randomUUID().toString().take(8)}",
            dacs: DACS,
            llmProvider: LlmProvider? = null,
            model: String? = null,
            blueprintRunner: BlueprintRunner? = null
        ): ConversationalGovernor {
            return ConversationalGovernor(id, dacs, llmProvider, model, blueprintRunner)
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
    val error: String? = null
)
