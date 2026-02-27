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
import io.wiiiv.execution.impl.LlmResponse
import io.wiiiv.audit.AuditRecordFactory
import io.wiiiv.audit.AuditStore
import io.wiiiv.audit.ExecutionPath
import io.wiiiv.hlx.model.HlxNode
import io.wiiiv.hlx.model.HlxNodeType
import io.wiiiv.hlx.model.HlxWorkflow
import io.wiiiv.hlx.parser.HlxParser
import io.wiiiv.hlx.validation.HlxValidator
import io.wiiiv.hlx.runner.HlxExecutionResult
import io.wiiiv.hlx.runner.HlxExecutionStatus
import io.wiiiv.hlx.runner.HlxRunner
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
    private val ragPipeline: RagPipeline? = null,
    private val hlxRunner: HlxRunner? = null,
    private val auditStore: AuditStore? = null,
    private val workflowStore: io.wiiiv.hlx.store.WorkflowStore? = null
) : Governor {

    var progressListener: GovernorProgressListener? = null

    private val sessions = ConcurrentHashMap<String, ConversationSession>()
    private val json = Json {
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

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
    suspend fun chat(
        sessionId: String,
        userMessage: String,
        images: List<LlmImage> = emptyList(),
        userId: String? = null,
        role: String? = null
    ): ConversationResponse {
        val effectiveUserId = userId ?: "dev-user"
        val effectiveRole = role ?: "OPERATOR"

        val session = sessions[sessionId]
            ?: return ConversationResponse(
                action = ActionType.CANCEL,
                message = "세션을 찾을 수 없습니다. 새 대화를 시작해주세요.",
                sessionId = sessionId
            )

        // 사용자 메시지 기록
        session.addUserMessage(userMessage)

        // ── DraftSpec 소실 방어 ──
        // 원인 불명으로 DraftSpec이 비워지는 현상에 대한 방어.
        // 스냅샷이 있으면 자동 복원한다.
        println("[GOVERNOR] ── Turn start ── activeTaskId=${session.context.activeTaskId}, " +
            "intent=${session.draftSpec.intent?.take(20)}, taskType=${session.draftSpec.taskType}, " +
            "filled=${session.draftSpec.getFilledSlots()}, tasks=${session.context.tasks.size}")
        if (session.restoreSpecIfLost()) {
            println("[GOVERNOR] ⚠ DraftSpec was empty — RESTORED from snapshot: " +
                "intent=${session.draftSpec.intent?.take(20)}, taskType=${session.draftSpec.taskType}, " +
                "filled=${session.draftSpec.getFilledSlots()}")
        }

        // Pre-LLM 워크플로우 관리 명령 감지 — LLM 판단 전에 결정론적으로 처리
        // ⚠ WORKFLOW_CREATE/PROJECT_CREATE 진행 중에는 스킵 — 대화 중 "워크플로우 목록"
        //    같은 표현이 관리 명령으로 오인되어 인터뷰/실행 흐름을 가로채는 것 방지
        val inProtectedFlow = session.draftSpec.taskType in listOf(TaskType.WORKFLOW_CREATE, TaskType.PROJECT_CREATE)
        if (!inProtectedFlow) {
            val workflowCmd = detectWorkflowCommand(userMessage)
            if (workflowCmd != null) {
                val response = handleWorkflowCommand(workflowCmd, session, effectiveUserId)
                session.addGovernorMessage(response.message)
                return response
            }
        }

        // pendingAction이 ContinueExecution이면 LLM 판단 없이 바로 실행 계속
        val pending = session.context.pendingAction
        if (pending is PendingAction.ContinueExecution) {
            session.context.pendingAction = null
            val response = executeTurn(session, effectiveUserId, effectiveRole)
            session.addGovernorMessage(response.message)
            return response
        }

        // LLM이 없으면 기본 처리
        if (llmProvider == null) {
            return handleWithoutLlm(session, userMessage)
        }

        // LLM으로 다음 행동 결정
        if (images.isNotEmpty()) {
            emitProgress(ProgressPhase.IMAGE_ANALYZING, "Analyzing ${images.size} image(s)...")
        }
        emitProgress(ProgressPhase.LLM_THINKING, "Deciding next action...")
        val governorAction = try {
            decideAction(session, userMessage, images)
        } catch (e: Exception) {
            GovernorAction(
                action = ActionType.REPLY,
                message = "죄송합니다. 처리 중 오류가 발생했습니다: ${e.message}"
            )
        }

        // 작업 전환 처리
        // ⚠ PROJECT_CREATE 인터뷰/실행 중에는 taskSwitch 무시 — LLM이 확인 메시지를 전환 신호로 오인하는 것 방지
        governorAction.taskSwitch?.let { switchSignal ->
            val inProtectedTask = session.draftSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
            if (inProtectedTask) {
                println("[GOVERNOR] taskSwitch='$switchSignal' IGNORED — ${session.draftSpec.taskType} in progress")
            } else {
                handleTaskSwitch(session, switchSignal)
            }
        }

        // specUpdates 적용 (자동 작업 보존 포함)
        // EXECUTE 액션이면 taskType 변경 금지 — EXECUTE는 "현재 작업 진행"이므로 작업 전환 불가
        // 단, CONVERSATION/INFORMATION은 "진행 중인 작업"이 아니므로 taskType 변경을 허용한다
        // 단, executionHistory가 비어있으면(첫 실행) taskType 변경을 허용한다 — 새 요청이므로
        governorAction.specUpdates?.let { updates ->
            println("[GOVERNOR] specUpdates received: ${updates.keys} (action=${governorAction.action})")
            val currentTaskType = session.draftSpec.taskType
            val isActiveWork = currentTaskType != null
                && currentTaskType != TaskType.CONVERSATION
                && currentTaskType != TaskType.INFORMATION
            val isProtectedTask = currentTaskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
            // taskType 변경 차단 조건:
            // 1. 실행 이력이 있으면 어떤 액션이든 taskType 변경 차단 (REPLY/ASK 시에도 보호)
            // 2. WORKFLOW_CREATE/PROJECT_CREATE 진행 중에는 executionHistory 없어도 taskType 동결
            //    → LLM이 인터뷰 중 taskType을 CONVERSATION으로 바꾸는 것 방지
            val filteredUpdates = if (
                isActiveWork &&
                (session.context.executionHistory.isNotEmpty() || isProtectedTask)
            ) {
                if (isProtectedTask) {
                    val attempted = updates["taskType"]?.jsonPrimitive?.contentOrNull
                    if (attempted != null && attempted != currentTaskType?.name) {
                        println("[GOVERNOR] taskType change BLOCKED: $currentTaskType → $attempted (protected task in progress)")
                    }
                }
                updates.filterKeys { it != "taskType" }
            } else {
                updates
            }
            // taskSwitch가 이미 처리한 경우가 아니면, taskType 변경 감지 시 현재 작업을 자동 보존
            // CONVERSATION/INFORMATION은 보존 대상이 아님
            // ⚠ PROJECT_CREATE 진행 중에는 suspension 금지 — 인터뷰/작업지시서 흐름 보호
            if (governorAction.taskSwitch == null) {
                val newTaskType = filteredUpdates["taskType"]?.jsonPrimitive?.contentOrNull?.let { str ->
                    try { TaskType.valueOf(str) } catch (_: Exception) { null }
                }
                val currentSpec = session.draftSpec
                val isProtectedTaskInProgress = currentSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
                if (newTaskType != null && currentSpec.taskType != null
                    && currentSpec.taskType != TaskType.CONVERSATION
                    && currentSpec.taskType != TaskType.INFORMATION
                    && newTaskType != currentSpec.taskType && currentSpec.intent != null
                    && !isProtectedTaskInProgress) {
                    println("[GOVERNOR] ⚠ Task suspension triggered: ${currentSpec.taskType} → $newTaskType")
                    session.suspendCurrentWork()
                } else if (newTaskType != null && isProtectedTaskInProgress && newTaskType != currentSpec.taskType) {
                    println("[GOVERNOR] Task suspension BLOCKED — ${currentSpec.taskType} in progress (LLM wanted: $newTaskType)")
                }
            }
            session.updateSpec(filteredUpdates)
            // ── Post-specUpdates 교정: 파이프라인 전환 시 LLM의 taskType 오분류 보정 ──
            // 시나리오: PROJECT_CREATE 완료 후 사용자가 "워크플로우 만들어줘" → resetSpec() → taskType null
            // → LLM이 대화 이력(PROJECT_CREATE 컨텍스트)에 의해 taskType을 PROJECT_CREATE로 설정
            // → 실제로는 WORKFLOW_CREATE가 올바름
            // 조건: (1) 이전 실행 이력 존재 (2) LLM이 PROJECT_CREATE로 설정 (3) 사용자 메시지에 워크플로우 생성 의도
            if (session.context.hasAnyExecution()
                && session.draftSpec.taskType == TaskType.PROJECT_CREATE) {
                val lowerMsg = userMessage.lowercase()
                val hasWorkflowKeyword = lowerMsg.contains("워크플로우") || lowerMsg.contains("workflow")
                val hasCreationKeyword = listOf("만들", "생성", "구축", "자동화").any { lowerMsg.contains(it) }
                if (hasWorkflowKeyword && hasCreationKeyword) {
                    session.updateSpec(mapOf("taskType" to kotlinx.serialization.json.JsonPrimitive(TaskType.WORKFLOW_CREATE.name)))
                    println("[GOVERNOR] Post-specUpdates correction: PROJECT_CREATE → WORKFLOW_CREATE (user requested workflow creation after prior execution)")
                }
            }
            // intent가 null인데 taskType이 있으면 기본 intent 자동 설정
            if (session.draftSpec.intent == null && session.draftSpec.taskType != null) {
                val defaultIntent = when (session.draftSpec.taskType) {
                    TaskType.PROJECT_CREATE -> "프로젝트 생성"
                    TaskType.WORKFLOW_CREATE -> "워크플로우 생성"
                    TaskType.FILE_READ -> "파일 읽기"
                    TaskType.FILE_WRITE -> "파일 쓰기"
                    TaskType.COMMAND -> "명령어 실행"
                    else -> null
                }
                if (defaultIntent != null) {
                    session.updateSpec(mapOf("intent" to kotlinx.serialization.json.JsonPrimitive(defaultIntent)))
                    println("[GOVERNOR] intent was null — auto-filled: '$defaultIntent'")
                }
            }
            println("[GOVERNOR] DraftSpec after update: intent=${session.draftSpec.intent?.take(30)}, taskType=${session.draftSpec.taskType}, filled=${session.draftSpec.getFilledSlots()}")
        }

        // ── State Determinism Gate ──
        // LLM의 ASK/CONFIRM/EXECUTE 선택은 확률적이다.
        // Governor가 DraftSpec 상태를 기준으로 최종 상태 전환을 결정한다.
        val finalAction = when {
            // ── 범용 안전장치: DraftSpec이 비어있으면 CONFIRM/EXECUTE 금지 ──
            // LLM이 specUpdates를 보내지 않아 intent/taskType이 null인 상태에서
            // CONFIRM이나 EXECUTE를 시도하면 → 스냅샷 복원 시도, 실패하면 ASK로 강제 전환
            governorAction.action in listOf(ActionType.CONFIRM, ActionType.EXECUTE)
                && session.draftSpec.intent == null
                && session.draftSpec.taskType == null
                && !session.context.hasAnyExecution() -> {
                // 2차 복원 시도 (chat 시작 시 1차 복원이 실패했거나, specUpdates 처리 중 소실된 경우)
                if (session.restoreSpecIfLost()) {
                    println("[GOVERNOR] State Determinism Gate: DraftSpec was empty but RESTORED from snapshot → keeping ${governorAction.action}")
                    governorAction  // 복원 성공 — 원래 action 유지, 다음 gate에서 정상 처리
                } else {
                    println("[GOVERNOR] State Determinism Gate: ${governorAction.action} suppressed → ASK (DraftSpec empty — intent/taskType both null, no snapshot)")
                    governorAction.copy(action = ActionType.ASK)
                }
            }

            // ── 범용 안전장치 2: 대화에서 프로젝트 생성이 감지되면 taskType 강제 설정 ──
            // LLM이 specUpdates를 누락했지만, 대화 내용에 프로젝트 생성 의도가 명확한 경우
            // taskType을 PROJECT_CREATE로 추론 설정한 뒤 ASK 유지
            // ⚠ hasAnyExecution() 사용: 이전 task 완료 후 새 요청이면 이 gate를 건너뜀
            governorAction.action in listOf(ActionType.CONFIRM, ActionType.EXECUTE)
                && session.draftSpec.taskType == null
                && !session.context.hasAnyExecution()
                && isProjectCreationContext(session, userMessage) -> {
                println("[GOVERNOR] State Determinism Gate: ${governorAction.action} suppressed → ASK (project creation detected but taskType not set)")
                // 추론으로 taskType 설정
                session.updateSpec(mapOf(
                    "intent" to kotlinx.serialization.json.JsonPrimitive("프로젝트 생성"),
                    "taskType" to kotlinx.serialization.json.JsonPrimitive("PROJECT_CREATE")
                ))
                governorAction.copy(action = ActionType.ASK)
            }

            // ── EXECUTE 억제: 복잡한 멀티 시스템/파일 출력 요청에서 ASK 강제 ──
            // LLM이 복잡한 요청을 EXECUTE로 직행하는 것을 방지
            governorAction.action == ActionType.EXECUTE
                && session.draftSpec.taskType in listOf(TaskType.API_WORKFLOW, TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
                && !session.context.hasAnyExecution()     // 현재 세션에서 첫 실행
                && isComplexRequest(userMessage) -> {
                println("[GOVERNOR] State Determinism Gate: EXECUTE suppressed → ASK (complex first-turn request)")
                governorAction.copy(action = ActionType.ASK)
            }

            // ── EXECUTE 억제: PROJECT_CREATE에서 작업지시서 미표시 → 무조건 CONFIRM ──
            // WorkOrder가 없으면 사용자가 뭐라고 했든 EXECUTE 금지 — 작업지시서를 먼저 보여줘야 한다
            // WorkOrder가 있어도 사용자가 수정 요청이면 CONFIRM으로 되돌림
            governorAction.action == ActionType.EXECUTE
                && session.draftSpec.taskType == TaskType.PROJECT_CREATE
                && !session.context.hasAnyExecution()
                && !(isConfirmationMessage(userMessage) && !isModificationRequest(userMessage)
                    && session.draftSpec.workOrderContent != null) -> {
                val reason = if (session.draftSpec.workOrderContent == null)
                    "WorkOrder not shown yet — must show before execution"
                else
                    "PROJECT_CREATE modification in progress"
                println("[GOVERNOR] State Determinism Gate: EXECUTE suppressed → CONFIRM ($reason)")
                governorAction.copy(action = ActionType.CONFIRM)
            }

            // ── EXECUTE 억제: WORKFLOW_CREATE에서 작업지시서 미표시 → 무조건 CONFIRM ──
            governorAction.action == ActionType.EXECUTE
                && session.draftSpec.taskType == TaskType.WORKFLOW_CREATE
                && !session.context.hasAnyExecution()
                && !(isConfirmationMessage(userMessage) && !isModificationRequest(userMessage)
                    && session.draftSpec.workOrderContent != null) -> {
                val reason = if (session.draftSpec.workOrderContent == null)
                    "WorkOrder not shown yet — must show before execution"
                else
                    "WORKFLOW_CREATE modification in progress"
                println("[GOVERNOR] State Determinism Gate: EXECUTE suppressed → CONFIRM ($reason)")
                governorAction.copy(action = ActionType.CONFIRM)
            }

            // ── CONFIRM 억제: WORKFLOW_CREATE에서 사용자 허가 없이 작업지시서 생성 금지 ──
            governorAction.action == ActionType.CONFIRM
                && session.draftSpec.taskType == TaskType.WORKFLOW_CREATE
                && session.draftSpec.workOrderContent == null
                && !isConfirmationMessage(userMessage)
                && !isWorkOrderRequest(userMessage) -> {
                val turns = countInterviewTurns(session)
                if (turns < 3) {
                    println("[GOVERNOR] State Determinism Gate: CONFIRM suppressed → ASK (WORKFLOW_CREATE only $turns interview turns, need 3+)")
                    val missingSlots = session.draftSpec.getMissingSlots()
                    val nextQuestion = when {
                        "domain" in missingSlots ->
                            "이 워크플로우가 어떤 시스템/서비스를 대상으로 하나요?"
                        "scale" in missingSlots ->
                            "워크플로우의 처리 흐름을 알려주세요. 어떤 데이터를 어떤 순서로 처리하나요?"
                        else -> {
                            governorAction.message
                                .replace(Regex("이대로 진행할까요\\??|진행할까요\\??|작업지시서를 만들어도 될까요\\??"), "")
                                .trimEnd()
                                .ifBlank { "분기/반복 조건이나 에러 처리 방식이 있으면 알려주세요." }
                        }
                    }
                    governorAction.copy(action = ActionType.ASK, message = nextQuestion)
                } else {
                    println("[GOVERNOR] State Determinism Gate: CONFIRM suppressed → ASK (requesting work order permission, $turns interview turns)")
                    governorAction.copy(
                        action = ActionType.ASK,
                        message = "워크플로우 정보가 충분히 수집되었습니다. 작업지시서를 만들어도 될까요?"
                    )
                }
            }

            // ── ASK 억제: WORKFLOW_CREATE 완성 스펙 + 사용자 확인 → CONFIRM ──
            governorAction.action == ActionType.ASK
                && session.draftSpec.taskType == TaskType.WORKFLOW_CREATE
                && session.draftSpec.workOrderContent == null
                && (session.draftSpec.isComplete() || countInterviewTurns(session) >= 3)
                && (isConfirmationMessage(userMessage) || isWorkOrderRequest(userMessage)) -> {
                println("[GOVERNOR] State Determinism Gate: ASK → CONFIRM (WORKFLOW_CREATE user confirmed, ${countInterviewTurns(session)} interview turns)")
                governorAction.copy(
                    action = ActionType.CONFIRM,
                    message = "워크플로우 작업지시서를 정리해 보겠습니다."
                )
            }

            // ── CONFIRM 억제: PROJECT_CREATE에서 사용자 허가 없이 작업지시서 생성 금지 ──
            // LLM이 CONFIRM을 반환해도, 사용자가 명시적으로 확인하지 않았으면 ASK로 되돌림
            // 단, "작업지시서 만들어/완성해" 등 명시적 요청은 확인으로 인정
            // 인터뷰가 부족하면 인터뷰 질문, 충분하면 "작업지시서를 만들어도 될까요?" 질문
            governorAction.action == ActionType.CONFIRM
                && session.draftSpec.taskType == TaskType.PROJECT_CREATE
                && session.draftSpec.workOrderContent == null
                && !isConfirmationMessage(userMessage)
                && !isWorkOrderRequest(userMessage) -> {
                val turns = countInterviewTurns(session)
                if (turns < 4) {
                    println("[GOVERNOR] State Determinism Gate: CONFIRM suppressed → ASK (only $turns interview turns, need 4+)")
                    val missingSlots = session.draftSpec.getMissingSlots()
                    val nextQuestion = when {
                        "domain" in missingSlots ->
                            "어떤 목적의 프로젝트인가요? 용도를 알려주세요."
                        "techStack" in missingSlots ->
                            "어떤 기술 스택을 사용할 건가요?"
                        else -> {
                            // 필수 슬롯은 채워졌지만 인터뷰 부족 — LLM 메시지에서 확인 질문만 제거
                            governorAction.message
                                .replace(Regex("이대로 진행할까요\\??|이제 프로젝트를 진행할까요\\??|진행할까요\\??|작업지시서를 만들어도 될까요\\??"), "")
                                .trimEnd()
                                .ifBlank { "추가로 알려주실 내용이 있으면 말씀해주세요. 없으면 작업지시서를 만들겠습니다." }
                        }
                    }
                    governorAction.copy(action = ActionType.ASK, message = nextQuestion)
                } else {
                    println("[GOVERNOR] State Determinism Gate: CONFIRM suppressed → ASK (requesting work order permission, $turns interview turns)")
                    governorAction.copy(
                        action = ActionType.ASK,
                        message = "정보가 충분히 수집되었습니다. 작업지시서를 만들어도 될까요?"
                    )
                }
            }

            // ── ASK 억제: 완성된 스펙 + 사용자 확인 → CONFIRM 전환 ──
            // (A) PROJECT_CREATE이고 DraftSpec이 충분하고 사용자가 확인했으면 ASK → CONFIRM
            // ⚠ 인터뷰 턴이 충분해야(4턴+) CONFIRM 전환 — 너무 빨리 넘어가면 LLM이 추측으로 생성
            governorAction.action == ActionType.ASK
                && session.draftSpec.taskType == TaskType.PROJECT_CREATE
                && session.draftSpec.workOrderContent == null
                && (session.draftSpec.isComplete() || countInterviewTurns(session) >= 4)
                && (isConfirmationMessage(userMessage) || isWorkOrderRequest(userMessage)) -> {
                println("[GOVERNOR] State Determinism Gate: ASK suppressed → CONFIRM (user confirmed work order creation, ${countInterviewTurns(session)} interview turns)")
                governorAction.copy(
                    action = ActionType.CONFIRM,
                    message = "작업지시서를 정리해 보겠습니다."
                )
            }
            // (A-2) 실행 작업 + 사용자 확인 표현 → EXECUTE
            // 단순 작업(FILE_*, COMMAND)은 isComplete() 불요 — 확인만으로 충분
            // 3턴 이상 인터뷰 + 확인이면 isComplete() 불완전해도 EXECUTE 진행
            // ⚠ PROJECT_CREATE는 WorkOrder가 생성되기 전까지 ASK를 EXECUTE로 덮어쓸 수 없음
            governorAction.action == ActionType.ASK
                && session.draftSpec.requiresExecution()
                && isConfirmationMessage(userMessage)
                && (session.draftSpec.isComplete()
                    || session.draftSpec.taskType in listOf(
                        TaskType.FILE_READ, TaskType.FILE_WRITE, TaskType.FILE_DELETE, TaskType.COMMAND
                    )
                    || (session.draftSpec.intent != null && countInterviewTurns(session) >= 3
                        && userMessage.trim().length <= 10))  // 순수 확인만 (새 요청 오인 방지)
                && !(session.draftSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
                    && session.draftSpec.workOrderContent == null) -> {
                println("[GOVERNOR] State Determinism Gate: ASK suppressed → EXECUTE (confirmation + ${session.draftSpec.taskType})")
                governorAction.copy(action = ActionType.EXECUTE)
            }

            // (A-3) 실행 후 수정/추가 요청인데 ASK가 나오면 → EXECUTE
            // 이미 실행 히스토리가 있으면 인터뷰 불필요 — 바로 수정 실행
            governorAction.action == ActionType.ASK
                && session.context.executionHistory.isNotEmpty()
                && session.draftSpec.requiresExecution()
                && (isModificationRequest(userMessage) || isFollowUpDataRequest(userMessage)) -> {
                println("[GOVERNOR] State Determinism Gate: ASK suppressed → EXECUTE (post-execution action request)")
                governorAction.copy(action = ActionType.EXECUTE)
            }

            // (B) WorkOrder가 이미 존재하고 사용자가 확인 메시지를 보냈을 때 → EXECUTE
            // ⚠ 수정 키워드가 포함되면 EXECUTE 금지 — WorkOrder 수정 반복 허용
            governorAction.action in listOf(ActionType.CONFIRM, ActionType.ASK)
                && session.draftSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
                && session.draftSpec.workOrderContent != null
                && isConfirmationMessage(userMessage)
                && !isModificationRequest(userMessage) -> {
                println("[GOVERNOR] State Determinism Gate: ${governorAction.action} → EXECUTE (WorkOrder confirmed)")
                governorAction.copy(action = ActionType.EXECUTE)
            }

            // (B-2) PROJECT_CREATE/WORKFLOW_CREATE WorkOrder 수정 — REPLY/ASK → CONFIRM (WorkOrder 재생성)
            // WorkOrder가 있는데 사용자가 명시적으로 수정을 요청하면 CONFIRM으로 전환하여 WorkOrder를 갱신
            // ⚠ 단순 대화(질문/감사 등)는 REPLY 그대로 통과 — 장황한 사용자가 무한 CONFIRM 루프에 빠지는 것 방지
            governorAction.action in listOf(ActionType.REPLY, ActionType.ASK)
                && session.draftSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
                && session.draftSpec.workOrderContent != null
                && !session.context.hasAnyExecution()
                && isModificationRequest(userMessage) -> {
                println("[GOVERNOR] State Determinism Gate: ${governorAction.action} → CONFIRM (WorkOrder modification requested)")
                governorAction.copy(action = ActionType.CONFIRM)
            }

            // ── REPLY 억제: 수정/추가/후속 데이터 요청 → EXECUTE ──
            // 실행 히스토리가 있고, 사용자가 수정/추가/후속 데이터를 요청했는데 REPLY가 나오면 EXECUTE로 교정
            governorAction.action == ActionType.REPLY
                && session.context.executionHistory.isNotEmpty()
                && session.draftSpec.requiresExecution()
                && (isModificationRequest(userMessage) || isFollowUpDataRequest(userMessage)) -> {
                println("[GOVERNOR] State Determinism Gate: REPLY suppressed → EXECUTE (action request after execution)")
                governorAction.copy(action = ActionType.EXECUTE)
            }

            // ── CANCEL 억제: 확인 응답을 CANCEL로 잘못 분류하는 것 방지 ──
            governorAction.action == ActionType.CANCEL
                && isConfirmationMessage(userMessage) -> {
                println("[GOVERNOR] State Determinism Gate: CANCEL suppressed → EXECUTE (user confirming, not canceling)")
                governorAction.copy(action = ActionType.EXECUTE)
            }

            // ── CANCEL → REPLY: LLM이 직접 CANCEL을 출력해도 세션 파괴 대신 정중한 거절 ──
            // CANCEL은 사용자가 명시적으로 취소할 때만 허용. 위험 요청 거부는 REPLY로 충분
            governorAction.action == ActionType.CANCEL -> {
                println("[GOVERNOR] State Determinism Gate: CANCEL suppressed → REPLY (session preservation)")
                governorAction.copy(action = ActionType.REPLY)
            }

            else -> governorAction
        }

        // EXECUTE/CONFIRM 시 활성 작업 확정
        if (finalAction.action in listOf(ActionType.EXECUTE, ActionType.CONFIRM)) {
            session.ensureActiveTask()
        }

        // 행동 처리
        val response = processAction(session, finalAction, effectiveUserId, effectiveRole)

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

        // [DEBUG] RAG 상태 로깅
        if (ragContext != null) {
            println("[RAG] Context injected: ${ragContext.length} chars, first 100: ${ragContext.take(100)}")
        } else {
            println("[RAG] No context (ragPipeline=${if (ragPipeline != null) "yes" else "no"}, storeSize=${ragPipeline?.size() ?: 0})")
        }

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

            // RAG 최종 리마인더 — 사용자 메시지 직전에 한번 더 강조
            if (ragContext != null) {
                appendLine("⚠⚠⚠ CRITICAL REMINDER: 위의 '참고 문서 (RAG)' 섹션에 실제 문서가 제공되었다.")
                appendLine("너의 일반 지식이 아닌, **문서의 내용을 있는 그대로 인용하여** 답변하라.")
                appendLine("문서에 없는 내용만 일반 지식으로 보충하라.")
                appendLine()
            }

            appendLine("## 사용자 메시지")
            appendLine(userMessage)
        }

        // [DEBUG] 프롬프트 길이 로깅
        println("[PROMPT] Total length: ${fullPrompt.length} chars, ragContext: ${ragContext?.length ?: 0} chars")

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
     *
     * 다중 쿼리 전략: 원문 메시지와 결합 쿼리를 함께 검색하여
     * 추상적 질문("날씨 API?")과 구체적 질문("wttr.in API") 모두 커버한다.
     *
     * 검색 실패 시 1회 재시도한다. 임베딩 API가 일시적으로 불안정할 수 있기 때문.
     */
    private suspend fun consultRag(userMessage: String, draftSpec: DraftSpec): String? {
        if (ragPipeline == null) return null
        if (ragPipeline.size() == 0) return null  // 문서가 없으면 스킵

        // 다중 쿼리 생성: 원문 + 결합 쿼리로 검색 범위 확대
        val queries = buildList {
            // Query 1: 원문 메시지 (가장 구체적 — 사용자 키워드 보존)
            if (userMessage.isNotBlank()) add(userMessage.trim())

            // Query 2: 원문 + intent + domain 결합 (의미 확장)
            val combined = buildString {
                append(userMessage)
                draftSpec.intent?.let { append(" $it") }
                draftSpec.domain?.let { append(" $it") }
            }.trim()
            if (combined.isNotBlank() && combined != userMessage.trim()) {
                add(combined)
            }
        }.distinct()

        if (queries.isEmpty()) return null

        // 1회 재시도 (임베딩 API 일시 장애 대응)
        repeat(2) { attempt ->
            try {
                val result = if (queries.size == 1) {
                    ragPipeline.search(queries.first(), topK = 5)
                } else {
                    ragPipeline.searchMulti(queries, topK = 5)
                }
                if (result.isNotEmpty()) return result.toNumberedContext()
                return null  // 검색 성공했지만 결과 없음
            } catch (e: Exception) {
                if (attempt == 0) {
                    System.err.println("[RAG] Search failed (attempt 1), retrying: ${e.message}")
                    kotlinx.coroutines.delay(500)  // 500ms 대기 후 재시도
                } else {
                    System.err.println("[RAG] Search failed (attempt 2), skipping RAG: ${e.message}")
                }
            }
        }
        return null
    }

    /**
     * LLM JSON 응답에서 잘못된 이스케이프 시퀀스를 제거한다.
     *
     * LLM이 JSON 문자열 안에 \$ 등 유효하지 않은 이스케이프를 생성하는 경우
     * kotlinx.serialization 파서가 실패한다. 유효한 JSON 이스케이프만 남기고
     * 나머지는 백슬래시를 제거한다.
     *
     * 유효: \", \\, \/, \b, \f, \n, \r, \t, \uXXXX
     */
    private fun sanitizeJsonEscapes(jsonStr: String): String {
        val validEscapeChars = setOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u')
        val sb = StringBuilder(jsonStr.length)
        var i = 0
        while (i < jsonStr.length) {
            val c = jsonStr[i]
            if (c == '\\' && i + 1 < jsonStr.length) {
                val next = jsonStr[i + 1]
                if (next in validEscapeChars) {
                    sb.append(c)
                    sb.append(next)
                    i += 2
                } else {
                    // Invalid escape (e.g., \$): drop the backslash
                    sb.append(next)
                    i += 2
                }
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString()
    }

    /**
     * JSON 문자열 값 내의 이스케이프되지 않은 제어 문자(0x00~0x1F)를 이스케이프 시퀀스로 변환.
     * LLM이 실제 개행/탭 등을 JSON 문자열 안에 출력하면 JSON 파서가 실패하므로,
     * 파싱 전에 정리한다.
     */
    private fun sanitizeControlChars(jsonStr: String): String {
        val sb = StringBuilder(jsonStr.length)
        var inString = false
        var escaped = false
        for (c in jsonStr) {
            if (escaped) {
                sb.append(c)
                escaped = false
                continue
            }
            when {
                c == '\\' && inString -> { sb.append(c); escaped = true }
                c == '"' -> { sb.append(c); inString = !inString }
                inString && c.code in 0x00..0x1F -> {
                    when (c) {
                        '\n' -> sb.append("\\n")
                        '\r' -> sb.append("\\r")
                        '\t' -> sb.append("\\t")
                        else -> sb.append("\\u${String.format("%04x", c.code)}")
                    }
                }
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /**
     * LLM 응답을 GovernorAction으로 파싱
     */
    private fun parseGovernorAction(response: String): GovernorAction {
        val (jsonStr, tail) = extractJsonWithTail(response)

        return try {
            val sanitized = sanitizeControlChars(sanitizeJsonEscapes(jsonStr))
            val jsonElement = json.parseToJsonElement(sanitized).jsonObject

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
            println("[GOVERNOR] JSON parse error: ${e::class.simpleName}: ${e.message}")
            println("[GOVERNOR] JSON (first 300): ${jsonStr.take(300)}")

            // Fallback: regex로 action/message 추출 시도
            try {
                val actionMatch = Regex(""""action"\s*:\s*"(\w+)"""").find(jsonStr)
                val action = actionMatch?.groupValues?.get(1)?.let {
                    try { ActionType.valueOf(it) } catch (_: Exception) { null }
                } ?: ActionType.REPLY

                // specUpdates도 추출 시도
                val specMatch = Regex(""""specUpdates"\s*:\s*(\{[^}]*\})""").find(jsonStr)
                val specUpdates = specMatch?.groupValues?.get(1)?.let { specStr ->
                    try {
                        val cleaned = sanitizeControlChars(sanitizeJsonEscapes(specStr))
                        json.parseToJsonElement(cleaned).jsonObject.entries.associate { (k, v) -> k to v }
                    } catch (_: Exception) { null }
                }

                // message 필드는 길고 복잡하므로, 원본에서 action/specUpdates/JSON래핑 제거
                val cleanMessage = jsonStr
                    .replace(Regex("""^\s*\{"""), "")
                    .replace(Regex("""\}\s*$"""), "")
                    .replace(Regex(""""action"\s*:\s*"[^"]*"\s*,?"""), "")
                    .replace(Regex(""""askingFor"\s*:\s*"[^"]*"\s*,?"""), "")
                    .replace(Regex(""""specUpdates"\s*:\s*\{[^}]*\}\s*,?"""), "")
                    .replace(Regex(""""message"\s*:\s*""""), "")
                    .trimEnd('"', ',', ' ', '\n')
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .trim()

                println("[GOVERNOR] Fallback parse: action=$action, messageLen=${cleanMessage.length}")
                GovernorAction(
                    action = action,
                    message = cleanMessage.ifBlank { response },
                    specUpdates = specUpdates
                )
            } catch (_: Exception) {
                GovernorAction(action = ActionType.REPLY, message = response)
            }
        }
    }

    /**
     * 행동 처리
     */
    private suspend fun processAction(
        session: ConversationSession,
        action: GovernorAction,
        userId: String = "dev-user",
        role: String = "OPERATOR"
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
                // PROJECT_CREATE/WORKFLOW_CREATE이면 작업지시서(Work Order) 생성
                val summary = if (session.draftSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE) && llmProvider != null) {
                    try {
                        val label = if (session.draftSpec.taskType == TaskType.WORKFLOW_CREATE) "워크플로우 작업지시서" else "작업지시서"
                        emitProgress(ProgressPhase.LLM_THINKING, "$label 생성 중...")
                        val workOrder = if (session.draftSpec.taskType == TaskType.WORKFLOW_CREATE) {
                            generateWorkflowWorkOrder(session)
                        } else {
                            generateWorkOrder(session)
                        }
                        session.draftSpec = session.draftSpec.copy(workOrderContent = workOrder)
                        workOrder
                    } catch (e: Exception) {
                        println("[WARN] Work order generation failed: ${e::class.simpleName}: ${e.message}")
                        session.draftSpec.summarize()
                    }
                } else {
                    session.draftSpec.summarize()
                }

                // WorkOrder가 생성되었으면 메시지에 내용 포함 + 명시적 확인 요청
                val confirmMessage = if (session.draftSpec.workOrderContent != null && summary is String) {
                    val label = if (session.draftSpec.taskType == TaskType.WORKFLOW_CREATE) "워크플로우 작업지시서" else "작업지시서"
                    buildString {
                        appendLine("$label 를 정리했습니다.")
                        appendLine()
                        appendLine("---")
                        appendLine(summary)
                        appendLine("---")
                        appendLine()
                        appendLine("이대로 진행할까요?")
                    }
                } else {
                    action.message
                }

                ConversationResponse(
                    action = ActionType.CONFIRM,
                    message = confirmMessage,
                    sessionId = session.sessionId,
                    draftSpec = session.draftSpec,
                    confirmationSummary = summary
                )
            }

            ActionType.EXECUTE -> {
                executeTurn(session, userId, role)
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
    private suspend fun executeTurn(session: ConversationSession, userId: String = "dev-user", role: String = "OPERATOR"): ConversationResponse {
        var draftSpec = session.draftSpec
        val workspace = session.context.workspace

        // 1. 실행이 필요하지 않은 타입 (CONVERSATION, INFORMATION)
        // 단, 세션에서 실행 이력이 있고 수정/후속 요청이면 바이패스
        // — resetSpec 후 activeTask=null → executionHistory 비어보이지만, 완료된 task에 이력 존재
        val lastUserMsg = session.history.lastOrNull { it.role == MessageRole.USER }?.content ?: ""
        val hasPostExecContext = session.context.hasAnyExecution()
            && (isModificationRequest(lastUserMsg) || isFollowUpDataRequest(lastUserMsg))
        if (!draftSpec.requiresExecution() && !hasPostExecContext) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "이 요청은 별도의 실행이 필요하지 않습니다.",
                sessionId = session.sessionId
            )
        }

        // 1.5. 수정 요청 복원: 이전 완료 task의 taskType/target 복원
        if (hasPostExecContext && !draftSpec.requiresExecution()) {
            val lastCompletedTask = session.context.tasks.values
                .filter { it.context.executionHistory.isNotEmpty() }
                .maxByOrNull { it.context.executionHistory.last().turnIndex }
            val prevSpec = lastCompletedTask?.draftSpec
            if (prevSpec != null && prevSpec.taskType != null) {
                println("[GOVERNOR] Post-execution restore: taskType=${prevSpec.taskType}, targetPath=${prevSpec.targetPath}")
                draftSpec = draftSpec.copy(
                    taskType = prevSpec.taskType,
                    targetPath = draftSpec.targetPath ?: prevSpec.targetPath,
                    intent = draftSpec.intent ?: lastUserMsg
                )
                session.draftSpec = draftSpec
            }
        }

        // 1.9. FILE_WRITE Previous Results Content Gate (isComplete 체크 전에 실행!)
        // FILE_WRITE 시 content가 비어있거나 플레이스홀더이고,
        // 이전 HLX 실행 결과가 있으면, 실제 실행 데이터를 content로 교체한다.
        // LLM 슬롯 필링은 이전 턴의 실행 결과에 접근할 수 없으므로 content를 채우지 못한다.
        if (draftSpec.taskType == TaskType.FILE_WRITE && draftSpec.targetPath != null) {
            val content = draftSpec.content ?: ""
            val trimmed = content.trim()
            // content가 비어있거나, 구조화된 데이터가 아닌 짧은 텍스트면 플레이스홀더로 판단
            val isVagueContent = trimmed.length < 300
                    && !trimmed.startsWith("{") && !trimmed.startsWith("[")

            if (isVagueContent) {
                val actualContent = buildFileContentFromHistory(session, draftSpec.targetPath)
                if (actualContent != null) {
                    println("[CONTENT-GATE] FILE_WRITE content auto-filled from execution history")
                    println("[CONTENT-GATE] Original: \"${trimmed.take(60).ifEmpty { "(empty)" }}\" → ${actualContent.length} chars")
                    draftSpec = draftSpec.copy(content = actualContent)
                    session.draftSpec = draftSpec
                }
            }
        }

        // 2. Spec 완성 확인 (WorkOrder가 있으면 스킵 — 이미 충분한 정보 보유)
        // 3턴 이상 인터뷰 + intent 존재 + 사용자 확인이면 바이패스
        val interviewBypass = draftSpec.intent != null
            && draftSpec.taskType !in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)  // PROJECT_CREATE/WORKFLOW_CREATE는 bypass 불가
            && countInterviewTurns(session) >= 3
            && isConfirmationMessage(lastUserMsg)
            && lastUserMsg.trim().length <= 10  // 순수 확인만 (새 요청 오인 방지)
        if (!draftSpec.isComplete() && draftSpec.workOrderContent == null && !interviewBypass) {
            val missing = draftSpec.getMissingSlots()
            return ConversationResponse(
                action = ActionType.ASK,
                message = "실행하려면 추가 정보가 필요합니다: ${missing.joinToString(", ")}",
                sessionId = session.sessionId,
                askingFor = missing.firstOrNull()
            )
        }

        // 2.5. PROJECT_CREATE/WORKFLOW_CREATE: WorkOrder 강제 생성 게이트 (방어적 안전망)
        // CONFIRM 단계를 건너뛴 경우에도 EXECUTE 전에 반드시 WorkOrder를 생성한다.
        // WorkOrder 없이 코드/워크플로우 생성하면 DraftSpec 4줄 요약만으로 LLM이 추측 → 오류 발생.
        if (draftSpec.taskType in listOf(TaskType.PROJECT_CREATE, TaskType.WORKFLOW_CREATE)
            && draftSpec.workOrderContent == null
            && llmProvider != null
        ) {
            println("[GOVERNOR] WorkOrder Safety Gate: EXECUTE without WorkOrder detected → generating now (${draftSpec.taskType})")
            try {
                emitProgress(ProgressPhase.LLM_THINKING, "작업지시서 생성 중 (안전 게이트)...")
                val workOrder = if (draftSpec.taskType == TaskType.WORKFLOW_CREATE) {
                    generateWorkflowWorkOrder(session)
                } else {
                    generateWorkOrder(session)
                }
                draftSpec = draftSpec.copy(workOrderContent = workOrder)
                session.draftSpec = draftSpec
                println("[GOVERNOR] WorkOrder Safety Gate: generated ${workOrder.length} chars")
            } catch (e: Exception) {
                println("[WARN] WorkOrder Safety Gate failed: ${e::class.simpleName}: ${e.message}")
                // 실패해도 계속 진행 (기존 동작 보존)
            }
        }

        // 2.6. PROJECT_CREATE: workspace에서 targetPath 유도
        if (draftSpec.taskType == TaskType.PROJECT_CREATE && draftSpec.targetPath == null && workspace != null) {
            val derivedPath = deriveProjectPath(workspace, draftSpec)
            if (derivedPath != null) {
                draftSpec = draftSpec.copy(targetPath = derivedPath)
                session.draftSpec = draftSpec
            }
        }

        // 2.7. FILE_WRITE Patch READ→WRITE Gate
        // FILE_WRITE 시 대상 파일이 이미 존재하면, 기존 내용을 읽어 LLM에 주입한다.
        // LLM이 기존 코드를 보면서 수정하므로 전체 덮어쓰기로 인한 코드 소실을 방지한다.
        if (draftSpec.taskType == TaskType.FILE_WRITE && llmProvider != null) {
            val targetPath = draftSpec.targetPath
            if (targetPath != null) {
                val targetFile = java.io.File(targetPath)
                if (targetFile.exists() && targetFile.isFile) {
                    // 사용자 원본 메시지를 수정 의도로 사용 (draftSpec.intent는 LLM이 요약하여 정보 소실)
                    val userOriginalMessage = session.getRecentHistory(5)
                        .lastOrNull { it.role == MessageRole.USER }?.content
                    val modificationIntent = userOriginalMessage ?: draftSpec.intent ?: draftSpec.content ?: ""
                    println("[PATCH] READ→WRITE Gate: existing file detected at $targetPath (${targetFile.length()} bytes)")
                    println("[PATCH] modificationIntent: ${modificationIntent.take(100)}")
                    try {
                        emitProgress(ProgressPhase.LLM_THINKING, "기존 파일 분석 중...")
                        val existingContent = targetFile.readText()
                        val patchPrompt = GovernorPrompt.patchFilePrompt(
                            existingContent = existingContent,
                            filePath = targetPath,
                            modificationIntent = modificationIntent
                        )
                        val patchResponse = llmProvider!!.call(
                            LlmRequest(
                                action = LlmAction.COMPLETE,
                                prompt = patchPrompt,
                                model = model ?: llmProvider.defaultModel,
                                maxTokens = 4096
                            )
                        )
                        val patchedContent = stripMarkdownFence(patchResponse.content.trim())
                        draftSpec = draftSpec.copy(content = patchedContent)
                        session.draftSpec = draftSpec
                        println("[PATCH] READ→WRITE Gate: patched content generated (${patchedContent.length} chars)")
                    } catch (e: Exception) {
                        println("[WARN] Patch READ→WRITE failed: ${e::class.simpleName}: ${e.message}")
                        // 실패 시 기존 동작 유지 (DraftSpec.content 그대로 사용)
                    }
                }
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
            // DACS REVISION 후 사용자가 확인하면 재평가를 건너뛴다 (informed consent)
            val dacsAlreadyReviewed = session.history.any {
                it.role == MessageRole.SYSTEM && it.content.startsWith("DACS 추가 확인 필요")
            }
            val userConfirmed = session.history.lastOrNull { it.role == MessageRole.USER }
                ?.let { isConfirmationMessage(it.content) } ?: false

            if (dacsAlreadyReviewed && userConfirmed) {
                println("[GOVERNOR] DACS bypass: user confirmed after DACS REVISION")
            } else {
                emitProgress(ProgressPhase.DACS_EVALUATING, "DACS consensus evaluation...")
                val dacsResponse = evaluateDACS(session, spec, draftSpec)
                if (dacsResponse != null) {
                    return dacsResponse  // evaluateDACS 내부에서 이미 정리 완료
                }
            }
        }

        // 5. Route Mode 판단 (Phase B2: 거버넌스 판단 vs 실행 엔진 관찰)
        val routeMode = determineRouteMode(draftSpec)
        val enginePath = if (needsLlmDecision(draftSpec)) "HLX_ENGINE" else "BLUEPRINT_ENGINE"
        println("[ROUTE] mode=$routeMode engine=$enginePath taskType=${draftSpec.taskType} intent=${draftSpec.intent?.take(50)}")

        // 6. LLM 결정이 필요한 작업인지 판단 (기존 실행 경로 유지)
        return if (needsLlmDecision(draftSpec)) {
            executeLlmDecidedTurn(session, draftSpec, spec, userId, role)
        } else {
            executeDirectTurn(session, draftSpec, spec, workspace, userId, role)
        }
    }

    /**
     * LLM 결정이 필요한 TaskType인지 판단
     */
    private fun needsLlmDecision(draftSpec: DraftSpec): Boolean = when (draftSpec.taskType) {
        TaskType.API_WORKFLOW -> true
        TaskType.DB_QUERY -> true  // Phase E: Governed HLX 경로
        TaskType.WORKFLOW_CREATE -> true  // WorkOrder 기반 HLX 생성
        else -> false
    }

    /**
     * Route Mode 결정 — EXECUTE 시 DIRECT vs HLX 판단
     *
     * Canonical: Executor Governance Spec v1.0 §5
     *
     * "이번 요청이 무엇을 하려는가"를 기준으로 판단한다.
     * Executor의 메타(riskLevel)는 보조 신호이며, 요청의 행위가 1차 기준이다.
     *
     * ## 판단 우선순위
     * 1. 결정론적 강제 규칙 (무조건)
     * 2. LLM routeHint (참고 — Phase B2에서 도입)
     * 3. 기본값 = HLX (보수적)
     *
     * ## DIRECT 조건 (전부 충족)
     * - 단일 Executor로 끝남
     * - 요청 행위가 READ
     * - 분기/반복 불필요
     *
     * ## HLX 강제 조건 (하나라도 해당)
     * - WRITE/SEND/DELETE/EXECUTE 포함
     * - 다중 Executor 필요
     * - 조건 분기/반복 필요
     * - PROJECT_CREATE (항상 복합)
     */
    private fun determineRouteMode(draftSpec: DraftSpec): RouteMode {
        val taskType = draftSpec.taskType

        // === 결정론적 강제: HLX ===

        // PROJECT_CREATE → 항상 HLX (Multi-turn + IntegrityAnalyzer, 복합 작업)
        if (taskType == TaskType.PROJECT_CREATE) return RouteMode.HLX

        // API_WORKFLOW → 항상 HLX (다중 스텝, 외부 상호작용)
        if (taskType == TaskType.API_WORKFLOW) return RouteMode.HLX

        // DB_QUERY → 항상 HLX (Phase E: GateChain 거버넌스)
        if (taskType == TaskType.DB_QUERY) return RouteMode.HLX

        // FILE_WRITE → HLX (WRITE = 부작용)
        if (taskType == TaskType.FILE_WRITE) return RouteMode.HLX

        // FILE_DELETE → HLX (DELETE = 부작용)
        if (taskType == TaskType.FILE_DELETE) return RouteMode.HLX

        // COMMAND → HLX (EXECUTE 능력, HIGH risk)
        if (taskType == TaskType.COMMAND) return RouteMode.HLX

        // === 결정론적 허용: DIRECT ===

        // FILE_READ → DIRECT (READ only, 단일 Executor, 무부작용)
        if (taskType == TaskType.FILE_READ) return RouteMode.DIRECT

        // CONVERSATION, INFORMATION → DIRECT (실행 불필요, 응답만)
        if (taskType == TaskType.CONVERSATION || taskType == TaskType.INFORMATION) return RouteMode.DIRECT

        // === 기본값: HLX (보수적) ===
        return RouteMode.HLX
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
                session.resetSpec()  // 거부된 위험 스펙 정리 (세션은 유지)
                ConversationResponse(
                    action = ActionType.REPLY,  // CANCEL → REPLY: 세션 파괴 대신 정중한 거절
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
        workspace: String? = null,
        userId: String = "dev-user",
        role: String = "OPERATOR"
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
            emitProgress(ProgressPhase.EXECUTING, "Creating files...", 0, fileSteps.size)
            val fileResult = try {
                blueprintRunner.execute(fileBlueprint)
            } catch (e: Exception) {
                null
            }

            if (fileResult == null || !fileResult.isSuccess) {
                emitProgress(ProgressPhase.DONE)
                val summary = formatExecutionResult(fileResult)
                session.integrateResult(blueprint, fileResult, summary)
                // Audit hook: DIRECT_BLUEPRINT 파일 생성 실패
                try {
                    auditStore?.insert(
                        AuditRecordFactory.fromBlueprintResult(
                            blueprint = blueprint,
                            result = fileResult,
                            sessionId = session.sessionId,
                            userId = userId,
                            role = role,
                            userInput = session.lastUserInput,
                            intent = draftSpec.intent,
                            taskType = "PROJECT_CREATE",
                            dacsConsensus = blueprint.specSnapshot.dacsResult,
                            projectId = session.projectId
                        )
                    )
                } catch (e: Exception) {
                    println("[AUDIT] Failed to record DIRECT_BLUEPRINT: ${e.message}")
                }
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
                emitProgress(ProgressPhase.COMMAND_RUNNING, "Running build/test...")
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
            // Audit hook: DIRECT_BLUEPRINT PROJECT_CREATE 완료
            try {
                auditStore?.insert(
                    AuditRecordFactory.fromBlueprintResult(
                        blueprint = blueprint,
                        result = fileResult,
                        sessionId = session.sessionId,
                        userId = userId,
                        role = role,
                        userInput = session.lastUserInput,
                        intent = draftSpec.intent,
                        taskType = "PROJECT_CREATE",
                        dacsConsensus = blueprint.specSnapshot.dacsResult,
                        meta = if (cmdResult != null) mapOf(
                            "cmdSuccess" to cmdResult.isSuccess.toString()
                        ) else null,
                        projectId = session.projectId
                    )
                )
            } catch (e: Exception) {
                println("[AUDIT] Failed to record DIRECT_BLUEPRINT: ${e.message}")
            }
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
        emitProgress(ProgressPhase.EXECUTING, "Executing...", 0, blueprint.steps.size)
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
        // Audit hook: DIRECT_BLUEPRINT 일반 실행 기록
        try {
            auditStore?.insert(
                AuditRecordFactory.fromBlueprintResult(
                    blueprint = blueprint,
                    result = executionResult,
                    sessionId = session.sessionId,
                    userId = userId,
                    role = role,
                    userInput = session.lastUserInput,
                    intent = draftSpec.intent,
                    taskType = draftSpec.taskType?.name ?: "UNKNOWN",
                    dacsConsensus = blueprint.specSnapshot.dacsResult,
                    projectId = session.projectId
                )
            )
        } catch (e: Exception) {
            println("[AUDIT] Failed to record DIRECT_BLUEPRINT: ${e.message}")
        }

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
     * hlxRunner가 있으면 HLX 워크플로우 자동생성+실행 경로를 사용한다.
     * 없으면 기존 turn-by-turn 결정 경로 (fallback).
     */
    private suspend fun executeLlmDecidedTurn(
        session: ConversationSession,
        draftSpec: DraftSpec,
        spec: Spec,
        userId: String = "dev-user",
        role: String = "OPERATOR"
    ): ConversationResponse {
        // LLM이 없으면 실행 불가
        if (llmProvider == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "LLM이 연결되어 있지 않아 API 워크플로우를 실행할 수 없습니다.",
                sessionId = session.sessionId
            )
        }

        // Phase E: DB_QUERY → Governed HLX 경로 (GateChain이 통제)
        if (hlxRunner != null && draftSpec.taskType == TaskType.DB_QUERY) {
            return executeDbQueryHlx(session, draftSpec, userId, role)
        }

        // WORKFLOW_CREATE: WorkOrder 기반 대규모 HLX 생성 → 실행 → 저장
        if (hlxRunner != null && draftSpec.taskType == TaskType.WORKFLOW_CREATE) {
            return executeWorkflowCreate(session, draftSpec, userId, role)
        }

        // HLX 자동생성 경로: hlxRunner가 있으면 워크플로우를 한번에 생성하고 실행
        if (hlxRunner != null) {
            return executeHlxApiWorkflow(session, draftSpec, userId, role)
        }

        // Fallback: 기존 turn-by-turn 결정 경로
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
                    "writeIntent=true로 선언했으나 아직 쓰기 작업(PUT/POST/DELETE/PATCH)이 실행되지 않았습니다.\n" +
                    "사용자의 원래 의도: ${draftSpec.intent ?: "unknown"}\n" +
                    "이 의도를 다시 읽고, 아직 수행하지 않은 단계(다른 시스템 접근, 로그인, 데이터 조회 등)가 있으면 먼저 수행한 뒤 쓰기 작업을 진행하세요."
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
                    "writeIntent=true로 선언했으나 아직 쓰기 작업(PUT/POST/DELETE/PATCH)이 실행되지 않았습니다.\n" +
                    "사용자의 원래 의도: ${draftSpec.intent ?: "unknown"}\n" +
                    "이 의도를 다시 읽고, 아직 수행하지 않은 단계(다른 시스템 접근, 로그인, 데이터 조회 등)가 있으면 먼저 수행한 뒤 쓰기 작업을 진행하세요."
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

        // Case 4: 쓰기 미선언인데 쓰기 호출 존재 → Abort (auth 엔드포인트는 제외)
        val hasWriteCall = decision.calls.any {
            it.method.uppercase() in WRITE_HTTP_METHODS && !isAuthEndpoint(it.url)
        }
        if (!writeIntent && hasWriteCall) {
            val writeCalls = decision.calls
                .filter { it.method.uppercase() in WRITE_HTTP_METHODS && !isAuthEndpoint(it.url) }
                .joinToString(", ") { "${it.method} ${it.url}" }
            return ConversationResponse(
                action = ActionType.CANCEL,
                message = "writeIntent=false로 선언했으나 쓰기 API 호출($writeCalls)이 포함되어 있습니다. 워크플로우를 중단합니다.",
                sessionId = session.sessionId
            )
        }

        // Blueprint 생성 및 실행 (tokenStore에서 자동 주입)
        val iterationIndex = session.context.executionHistory.size + 1
        val blueprint = createApiCallBlueprint(decision, spec, iterationIndex, session.context.tokenStore)
        val executionResult = if (blueprintRunner != null) {
            try {
                blueprintRunner.execute(blueprint)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }

        // 토큰 자동 추출: auth 엔드포인트 응답에서 토큰을 tokenStore에 저장
        extractAndStoreTokens(session, blueprint, executionResult)

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

    // ==========================================================
    // HLX 워크플로우 자동생성 + 실행
    // ==========================================================

    /**
     * HLX API 워크플로우 자동생성 및 실행
     *
     * 1. RAG에서 API 스펙 조회
     * 2. LLM에게 HLX 워크플로우 JSON 생성 요청
     * 3. HlxParser로 파싱
     * 4. HlxRunner로 실행
     * 5. 결과 포맷팅 및 반환
     *
     * 기존 turn-by-turn 방식과의 차이:
     * - 완전한 실행 계획을 한번에 생성 (LLM이 컨텍스트를 잃지 않음)
     * - HlxRunner가 순차 실행 (노드 간 데이터 전달 보장)
     * - 인증 → 조회 → 변환 → 행동 흐름이 워크플로우 구조에 의해 강제됨
     */

    /**
     * Phase E: DB 조회 → Governed HLX 실행
     *
     * 자연어 DB 요청을 HLX ACT 워크플로우로 변환하고 GateChain 거버넌스 하에서 실행한다.
     * DACS를 우회하고 GateChain + RiskLevel이 실행 통제를 담당한다.
     *
     * 흐름: 자연어 → LLM(HLX 생성) → HlxRunner(userId, role) → GateTrace 포함 결과
     */
    private suspend fun executeDbQueryHlx(
        session: ConversationSession,
        draftSpec: DraftSpec,
        userId: String = "dev-user",
        role: String = "OPERATOR"
    ): ConversationResponse {
        emitProgress(ProgressPhase.LLM_THINKING, "Generating DB query workflow...")
        println("[DB-QUERY-HLX] intent=${draftSpec.intent} domain=${draftSpec.domain}")

        if (llmProvider == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "LLM이 연결되어 있지 않아 DB 쿼리를 생성할 수 없습니다.",
                sessionId = session.sessionId
            )
        }

        // 1. LLM에게 HLX 워크플로우 JSON 생성 요청
        val prompt = GovernorPrompt.dbQueryHlxPrompt(
            intent = draftSpec.intent ?: "",
            domain = draftSpec.domain
        )
        val llmResponse = try {
            llmProvider.call(
                LlmRequest(
                    action = LlmAction.COMPLETE,
                    prompt = prompt,
                    model = model ?: llmProvider.defaultModel,
                    maxTokens = 2048
                )
            )
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "DB 쿼리 HLX 생성 중 LLM 오류: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 2. HLX JSON 파싱
        val hlxJson = llmResponse.content
            .replace("```json", "").replace("```", "")
            .trim()
        println("[DB-QUERY-HLX] Generated HLX: ${hlxJson.take(500)}")

        val workflow = try {
            HlxParser.parse(hlxJson)
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "DB 쿼리 HLX 파싱 실패: ${e.message}\n\nLLM 응답:\n${hlxJson.take(300)}",
                sessionId = session.sessionId,
                error = "HLX parse error"
            )
        }

        // 3. HlxRunner로 실행 (userId/role → GateChain 거버넌스)
        emitProgress(ProgressPhase.EXECUTING, "Executing DB query with governance...")
        val hlxRole = role
        println("[DB-QUERY-HLX] Running with role=$hlxRole")

        val hlxResult = try {
            hlxRunner!!.run(
                workflow = workflow,
                userId = userId,
                role = hlxRole
            )
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "DB 쿼리 실행 오류: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 4. 결과 포맷팅 (Gate Trace 포함)
        val technicalSummary = formatHlxExecutionResult(hlxResult, workflow)
        println("[DB-QUERY-HLX] Result: status=${hlxResult.status} duration=${hlxResult.totalDurationMs}ms")

        // 5. LLM에게 자연어 요약 요청
        emitProgress(ProgressPhase.LLM_THINKING, "Summarizing results...")
        val naturalSummary = try {
            summarizeHlxResult(
                intent = draftSpec.intent ?: "",
                hlxResult = hlxResult,
                workflow = workflow
            )
        } catch (_: Exception) {
            null // 요약 실패 시 기술 요약만 사용
        }

        // 6. 세션에 실행 기록 적재 + 리셋
        val fullMessage = if (naturalSummary != null) {
            "$naturalSummary\n\n$technicalSummary"
        } else {
            technicalSummary
        }

        session.integrateResult(null, null, buildHlxResultSummaryForContext(hlxResult, workflow))

        // Audit hook: DB_QUERY_HLX 실행 기록
        try {
            auditStore?.insert(
                AuditRecordFactory.fromHlxResult(
                    hlxResult = hlxResult,
                    workflow = workflow,
                    executionPath = ExecutionPath.DB_QUERY_HLX,
                    sessionId = session.sessionId,
                    userId = userId,
                    role = hlxRole,
                    userInput = session.lastUserInput,
                    intent = draftSpec.intent,
                    taskType = "DB_QUERY",
                    projectId = session.projectId
                )
            )
        } catch (e: Exception) {
            println("[AUDIT] Failed to record DB_QUERY_HLX: ${e.message}")
        }

        // 워크플로우 영구 저장
        persistWorkflow(workflow, hlxJson, session, userId)

        session.resetSpec()
        session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
        session.context.activeTaskId = null

        return ConversationResponse(
            action = ActionType.EXECUTE,
            message = fullMessage,
            sessionId = session.sessionId
        )
    }

    private suspend fun executeHlxApiWorkflow(
        session: ConversationSession,
        draftSpec: DraftSpec,
        userId: String = "dev-user",
        role: String = "OPERATOR"
    ): ConversationResponse {
        emitProgress(ProgressPhase.LLM_THINKING, "Generating HLX workflow...")

        // 1. RAG에서 API 스펙 조회 (일반 + 인증 전용 검색 병합)
        val lastUserMessage = session.getRecentHistory(5)
            .lastOrNull { it.role == MessageRole.USER }?.content
        val ragContext = consultRagForApiKnowledge(draftSpec, lastUserMessage)
        val authContext = consultRagForAuthCredentials()
        val mergedRagContext = mergeRagContexts(ragContext, authContext)
        println("[RAG] Context injected: ${mergedRagContext?.length ?: 0} chars, first 100: ${mergedRagContext?.take(100) ?: "null"}")

        // 1-b. RAG context에서 시스템별 credentials 추출
        val credentialsTable = extractCredentialsFromRag(mergedRagContext)
        if (credentialsTable.isNotBlank()) {
            println("[CRED] Extracted credentials table:\n$credentialsTable")
        }

        // 1-c. 이전 턴 실행 결과 (크로스턴 컨텍스트)
        val previousResults = buildPreviousResultsContext(session)

        // 2. LLM에게 HLX 워크플로우 JSON 생성 요청
        val prompt = GovernorPrompt.hlxApiGenerationPrompt(
            intent = draftSpec.intent ?: "",
            domain = draftSpec.domain,
            ragContext = mergedRagContext,
            credentialsTable = credentialsTable.ifBlank { null },
            targetPath = draftSpec.targetPath,
            cachedTokens = session.context.tokenStore,
            previousResults = previousResults
        )
        println("[PROMPT] Total length: ${prompt.length} chars, ragContext: ${mergedRagContext?.length ?: 0} chars")

        val llmResponse = try {
            llmProvider!!.call(
                LlmRequest(
                    action = LlmAction.COMPLETE,
                    prompt = prompt,
                    model = model ?: llmProvider.defaultModel,
                    maxTokens = 4000,
                    params = mapOf("temperature" to "0")
                )
            )
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "HLX 워크플로우 생성 실패: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 3. HLX JSON 파싱
        val hlxJson = extractJson(llmResponse.content)
        println("[HLX] Generated workflow JSON (${hlxJson.length} chars):\n${hlxJson.take(3000)}")

        val workflow = try {
            HlxParser.parse(hlxJson)
        } catch (e: Exception) {
            // sanitize 후 재시도
            println("[HLX] Parse failed: ${e.message}, trying sanitize...")
            try {
                val sanitized = sanitizeHlxJson(hlxJson)
                HlxParser.parse(sanitized).also {
                    println("[HLX] Sanitized parse succeeded")
                }
            } catch (e2: Exception) {
                println("[HLX] Sanitized parse also failed: ${e2.message}")
                println("[HLX] Raw JSON:\n${hlxJson.take(2000)}")
                return ConversationResponse(
                    action = ActionType.EXECUTE,
                    message = "HLX 워크플로우 파싱 실패: ${e.message}\n\n생성된 JSON:\n${hlxJson.take(500)}...",
                    sessionId = session.sessionId,
                    error = e.message
                )
            }
        }

        println("[HLX] Workflow parsed: ${workflow.name}, ${workflow.nodes.size} nodes")
        workflow.nodes.forEach { node ->
            println("[HLX]   Node: ${node.id} (${node.type}) - ${node.description.take(80)}")
        }

        // 3.5. HLX Validator - 실행 전 검증
        val validationErrors = HlxValidator.validate(workflow)
        if (validationErrors.isNotEmpty()) {
            val errorMsg = validationErrors.joinToString("\n") { "- ${it.path}: ${it.message}" }
            println("[HLX] Validation failed:\n$errorMsg")
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "HLX 워크플로우 검증 실패:\n$errorMsg",
                sessionId = session.sessionId,
                error = "Validation failed"
            )
        }

        // 4. HlxRunner로 실행 (Phase D: userId/role 전달 → GateChain 거버넌스)
        emitProgress(ProgressPhase.EXECUTING, "Executing HLX workflow: ${workflow.name}...")
        val sessionRole = role

        val hlxResult = try {
            hlxRunner!!.run(workflow, userId = userId, role = sessionRole, ragContext = mergedRagContext)
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "HLX 워크플로우 실행 실패: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 5. 실행 결과에서 토큰 추출 (engine-level tokenStore에도 저장)
        extractTokensFromHlxResult(session, hlxResult)

        // 6. 결과 포맷팅
        val technicalSummary = formatHlxExecutionResult(hlxResult, workflow)

        // 7. LLM에게 자연어 요약 요청
        val naturalSummary = summarizeHlxResult(
            intent = draftSpec.intent ?: "",
            hlxResult = hlxResult,
            workflow = workflow
        )

        emitProgress(ProgressPhase.DONE)

        val fullMessage = if (naturalSummary != null) {
            "$naturalSummary\n\n$technicalSummary"
        } else {
            technicalSummary
        }

        // 8. 세션에 결과 적재 (크로스턴 컨텍스트를 위해 ACT 노드 출력 포함)
        session.integrateResult(null, null, buildHlxResultSummaryForContext(hlxResult, workflow))

        // Audit hook: API_WORKFLOW_HLX 실행 기록
        try {
            auditStore?.insert(
                AuditRecordFactory.fromHlxResult(
                    hlxResult = hlxResult,
                    workflow = workflow,
                    executionPath = ExecutionPath.API_WORKFLOW_HLX,
                    sessionId = session.sessionId,
                    userId = userId,
                    role = sessionRole,
                    userInput = session.lastUserInput,
                    intent = draftSpec.intent,
                    taskType = "API_WORKFLOW",
                    projectId = session.projectId
                )
            )
        } catch (e: Exception) {
            println("[AUDIT] Failed to record API_WORKFLOW_HLX: ${e.message}")
        }

        // 워크플로우 영구 저장
        persistWorkflow(workflow, hlxJson, session, userId)

        session.resetSpec()
        session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
        session.context.activeTaskId = null

        return ConversationResponse(
            action = ActionType.EXECUTE,
            message = fullMessage,
            sessionId = session.sessionId
        )
    }

    /**
     * WORKFLOW_CREATE: 작업지시서(WorkOrder) 기반 대규모 HLX 워크플로우 생성 + 실행 + 저장
     *
     * 1. RAG에서 API 스펙 조회
     * 2. WorkOrder + API 스펙 → LLM에게 HLX JSON 생성 요청
     * 3. HlxParser로 파싱 + HlxValidator로 검증
     * 4. HlxRunner로 실행
     * 5. 워크플로우 영구 저장
     * 6. 결과 포맷팅 및 반환
     */
    private suspend fun executeWorkflowCreate(
        session: ConversationSession,
        draftSpec: DraftSpec,
        userId: String = "dev-user",
        role: String = "OPERATOR"
    ): ConversationResponse {
        val workOrder = draftSpec.workOrderContent
        if (workOrder == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우 생성에 필요한 작업지시서가 없습니다. 인터뷰를 다시 시작해주세요.",
                sessionId = session.sessionId
            )
        }

        emitProgress(ProgressPhase.LLM_THINKING, "WorkOrder 기반 HLX 워크플로우 생성 중...")

        // 1. RAG에서 API 스펙 조회
        val lastUserMessage = session.getRecentHistory(5)
            .lastOrNull { it.role == MessageRole.USER }?.content
        val ragContext = consultRagForApiKnowledge(draftSpec, lastUserMessage)
        val authContext = consultRagForAuthCredentials()
        val mergedRagContext = mergeRagContexts(ragContext, authContext)
        println("[WORKFLOW_CREATE] RAG context: ${mergedRagContext?.length ?: 0} chars")

        // 1-b. credentials 추출
        val credentialsTable = extractCredentialsFromRag(mergedRagContext)

        // 2. LLM에게 HLX 워크플로우 JSON 생성 요청
        val previousResults = buildPreviousResultsContext(session)
        val prompt = GovernorPrompt.hlxFromWorkOrderPrompt(
            workOrderContent = workOrder,
            ragContext = mergedRagContext,
            credentialsTable = credentialsTable.ifBlank { null },
            targetPath = draftSpec.targetPath,
            cachedTokens = session.context.tokenStore,
            previousResults = previousResults
        )
        println("[WORKFLOW_CREATE] Prompt length: ${prompt.length} chars")

        val llmResponse = try {
            llmProvider!!.call(
                LlmRequest(
                    action = LlmAction.COMPLETE,
                    prompt = prompt,
                    model = model ?: llmProvider.defaultModel,
                    maxTokens = 8000,
                    params = mapOf("temperature" to "0"),
                    timeoutMs = 180_000L  // 대규모 워크플로우: 3분
                )
            )
        } catch (e: Exception) {
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "HLX 워크플로우 생성 실패: ${e.message}",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 3. HLX JSON 파싱
        val hlxJson = extractJson(llmResponse.content)
        println("[WORKFLOW_CREATE] Generated HLX (${hlxJson.length} chars):\n${hlxJson.take(3000)}")

        val workflow = try {
            HlxParser.parse(hlxJson)
        } catch (e: Exception) {
            println("[WORKFLOW_CREATE] Parse failed: ${e.message}, trying sanitize...")
            try {
                val sanitized = sanitizeHlxJson(hlxJson)
                HlxParser.parse(sanitized).also {
                    println("[WORKFLOW_CREATE] Sanitized parse succeeded")
                }
            } catch (e2: Exception) {
                println("[WORKFLOW_CREATE] Sanitized parse also failed: ${e2.message}")
                return ConversationResponse(
                    action = ActionType.EXECUTE,
                    message = "HLX 워크플로우 파싱 실패: ${e.message}\n\n작업지시서는 정상적으로 생성되었으나, HLX JSON 변환에 실패했습니다.\n생성된 JSON (일부):\n${hlxJson.take(500)}...",
                    sessionId = session.sessionId,
                    error = e.message
                )
            }
        }

        println("[WORKFLOW_CREATE] Workflow parsed: ${workflow.name}, ${workflow.nodes.size} nodes")
        workflow.nodes.forEach { node ->
            println("[WORKFLOW_CREATE]   Node: ${node.id} (${node.type}) - ${node.description.take(80)}")
        }

        // 3.5. 검증
        val validationErrors = HlxValidator.validate(workflow)
        if (validationErrors.isNotEmpty()) {
            val errorMsg = validationErrors.joinToString("\n") { "- ${it.path}: ${it.message}" }
            println("[WORKFLOW_CREATE] Validation failed:\n$errorMsg")
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "HLX 워크플로우 검증 실패:\n$errorMsg\n\n워크플로우가 생성되었으나 구조적 오류가 있습니다.",
                sessionId = session.sessionId,
                error = "Validation failed"
            )
        }

        // 4. HlxRunner로 실행
        emitProgress(ProgressPhase.EXECUTING, "HLX 워크플로우 실행: ${workflow.name} (${workflow.nodes.size} nodes)...")

        val hlxResult = try {
            hlxRunner!!.run(workflow, userId = userId, role = role, ragContext = mergedRagContext)
        } catch (e: Exception) {
            // 실행 실패해도 워크플로우 자체는 저장 (재실행 가능)
            persistWorkflow(workflow, hlxJson, session, userId)
            return ConversationResponse(
                action = ActionType.EXECUTE,
                message = "HLX 워크플로우 실행 실패: ${e.message}\n\n워크플로우는 저장되었습니다. '/hlx run ${workflow.name}'으로 재실행 가능합니다.",
                sessionId = session.sessionId,
                error = e.message
            )
        }

        // 5. 토큰 추출
        extractTokensFromHlxResult(session, hlxResult)

        // 6. 결과 포맷팅
        val technicalSummary = formatHlxExecutionResult(hlxResult, workflow)
        val naturalSummary = summarizeHlxResult(
            intent = draftSpec.intent ?: "",
            hlxResult = hlxResult,
            workflow = workflow
        )

        emitProgress(ProgressPhase.DONE)

        val fullMessage = buildString {
            if (naturalSummary != null) {
                appendLine(naturalSummary)
                appendLine()
            }
            appendLine(technicalSummary)
            appendLine()
            appendLine("---")
            appendLine("워크플로우 '${workflow.name}' (${workflow.nodes.size} nodes)이 저장되었습니다.")
            appendLine("재실행: `/hlx run ${workflow.name}`")
        }

        // 7. 세션 결과 적재
        session.integrateResult(null, null, buildHlxResultSummaryForContext(hlxResult, workflow))

        // 8. Audit
        try {
            auditStore?.insert(
                AuditRecordFactory.fromHlxResult(
                    hlxResult = hlxResult,
                    workflow = workflow,
                    executionPath = ExecutionPath.API_WORKFLOW_HLX,
                    sessionId = session.sessionId,
                    userId = userId,
                    role = role,
                    userInput = session.lastUserInput,
                    intent = draftSpec.intent,
                    taskType = "WORKFLOW_CREATE",
                    projectId = session.projectId
                )
            )
        } catch (e: Exception) {
            println("[AUDIT] Failed to record WORKFLOW_CREATE: ${e.message}")
        }

        // 9. 워크플로우 영구 저장
        persistWorkflow(workflow, hlxJson, session, userId)

        session.resetSpec()
        session.context.activeTask?.let { it.status = TaskStatus.COMPLETED }
        session.context.activeTaskId = null

        return ConversationResponse(
            action = ActionType.EXECUTE,
            message = fullMessage,
            sessionId = session.sessionId
        )
    }

    /**
     * HLX 실행 결과를 크로스턴 컨텍스트용 요약으로 변환
     *
     * 다음 턴의 HLX 생성 시 이전 데이터를 참조할 수 있도록 ACT 노드 출력을 포함한다.
     */
    private fun buildHlxResultSummaryForContext(
        result: HlxExecutionResult,
        workflow: io.wiiiv.hlx.model.HlxWorkflow
    ): String = buildString {
        appendLine("HLX: ${result.status} - ${workflow.name}")
        for (record in result.nodeRecords) {
            if (record.nodeType != HlxNodeType.ACT) continue
            val output = record.output ?: continue
            val outputStr = output.toString()
            // 각 ACT 노드의 출력 (최대 4000자로 트리밍 — 크로스턴 컨텍스트 + FILE_WRITE 게이트에 사용)
            if (outputStr.length <= 4000) {
                appendLine("[${record.nodeId}] $outputStr")
            } else {
                appendLine("[${record.nodeId}] ${outputStr.take(4000)}...")
            }
        }
    }

    /**
     * 세션의 최근 실행 이력에서 크로스턴 컨텍스트를 빌드
     *
     * 이전 턴의 HLX 실행 결과를 요약하여 다음 HLX 생성에 제공한다.
     * 사용자가 "아까 그 데이터에서..." 라고 참조할 때 LLM이 올바른 데이터를 참조할 수 있다.
     *
     * 주의: session.context.executionHistory는 activeTask 프록시이므로,
     * 완료된 태스크의 이력을 포함하려면 allExecutionHistory()를 사용해야 한다.
     */
    private fun buildPreviousResultsContext(session: ConversationSession): String? {
        val allHistory = session.context.allExecutionHistory()
        if (allHistory.isEmpty()) return null

        val recentHlxResults = allHistory
            .filter { it.summary.startsWith("HLX:") && "COMPLETED" in it.summary }
            .takeLast(3) // 최근 3개의 성공한 HLX 실행

        if (recentHlxResults.isEmpty()) return null

        val result = recentHlxResults.joinToString("\n---\n") { turn ->
            "턴 ${turn.turnIndex}: ${turn.summary}"
        }
        println("[CROSS-TURN] previousResults injected: ${result.length} chars, ${recentHlxResults.size} turns")
        return result
    }

    /**
     * FILE_WRITE용: 이전 HLX 실행 결과에서 실제 데이터를 추출하여 파일 콘텐츠를 빌드
     *
     * LLM 슬롯 필링은 이전 턴의 실행 결과에 접근할 수 없으므로,
     * "결과를 저장해주세요" 같은 요청에서 content가 플레이스홀더가 된다.
     * 이 함수는 executionHistory에서 ACT 노드의 실제 출력 데이터를 추출한다.
     */
    private fun buildFileContentFromHistory(session: ConversationSession, targetPath: String?): String? {
        val allHistory = session.context.allExecutionHistory()
        if (allHistory.isEmpty()) return null

        val recentHlxResults = allHistory
            .filter { it.summary.startsWith("HLX:") && "COMPLETED" in it.summary }
            .takeLast(5)

        if (recentHlxResults.isEmpty()) return null

        // ACT 노드 출력에서 JSON 데이터 추출
        // summary 형식: "HLX: COMPLETED - workflow-name\n[node-id] {json data}\n..."
        val dataEntries = mutableListOf<String>()
        for (turn in recentHlxResults) {
            for (line in turn.summary.lines()) {
                if (!line.startsWith("[")) continue
                val closeBracket = line.indexOf(']')
                if (closeBracket < 0) continue
                val data = line.substring(closeBracket + 1).trim()
                // JSON 응답만 수집 (API 결과는 보통 {/[ 로 시작)
                if (data.isNotBlank() && (data.startsWith("{") || data.startsWith("["))) {
                    dataEntries.add(data)
                }
            }
        }

        if (dataEntries.isEmpty()) return null

        val isJson = targetPath?.endsWith(".json", ignoreCase = true) == true

        return if (isJson) {
            // JSON 파일: 유효한 JSON 구조로 래핑
            if (dataEntries.size == 1) {
                dataEntries[0]
            } else {
                "[\n${dataEntries.joinToString(",\n")}\n]"
            }
        } else {
            // 텍스트 파일: 실행 결과를 그대로 연결
            dataEntries.joinToString("\n\n---\n\n")
        }
    }

    /**
     * HLX 실행 결과에서 토큰을 추출하여 세션 tokenStore에 저장
     *
     * Act 노드 결과 중 auth 엔드포인트 호출이 있으면 토큰을 추출한다.
     */
    private fun extractTokensFromHlxResult(session: ConversationSession, result: HlxExecutionResult) {
        for (record in result.nodeRecords) {
            if (record.nodeType != HlxNodeType.ACT) continue
            val output = record.output ?: continue

            // Act 노드 output이 JSON 객체이고 HTTP 응답인 경우 토큰 추출 시도
            try {
                val jsonObj = when (output) {
                    is JsonObject -> output
                    is JsonPrimitive -> {
                        val content = output.contentOrNull ?: continue
                        json.parseToJsonElement(content).jsonObject
                    }
                    else -> continue
                }

                // body 필드에서 토큰 추출 (API 응답 형식)
                val body = jsonObj["body"]?.jsonPrimitive?.contentOrNull
                    ?: jsonObj.toString() // body가 없으면 전체를 시도

                val token = extractTokenFromBody(body)
                if (token != null) {
                    // URL에서 hostPort 추출 (statusCode가 있으면 API 응답)
                    val url = jsonObj["url"]?.jsonPrimitive?.contentOrNull
                    if (url != null) {
                        val hostPort = extractHostPort(url)
                        if (hostPort != null) {
                            session.context.tokenStore[hostPort] = token
                            println("[HLX-AUTH] Token stored for $hostPort")
                        }
                    }
                }
            } catch (_: Exception) {
                // 파싱 실패는 무시 (모든 Act 결과가 토큰을 포함하진 않음)
            }
        }
    }

    /**
     * HLX 실행 결과를 LLM에게 보내 자연어 요약을 생성한다.
     *
     * 사용자의 원래 의도와 실행 결과의 핵심 데이터를 전달하여
     * 자연어 결론/분석을 생성한다.
     */
    private suspend fun summarizeHlxResult(
        intent: String,
        hlxResult: HlxExecutionResult,
        workflow: io.wiiiv.hlx.model.HlxWorkflow
    ): String? {
        if (llmProvider == null) return null
        if (hlxResult.status != HlxExecutionStatus.COMPLETED &&
            hlxResult.status != HlxExecutionStatus.ENDED_EARLY) return null

        // 마지막 의미있는 노드 결과 수집 (login/token 제외)
        val meaningfulResults = hlxResult.nodeRecords
            .filter { it.output != null && it.nodeType != HlxNodeType.ACT }
            .takeLast(2)
            .map { "${it.nodeId}: ${it.output.toString().take(500)}" }

        if (meaningfulResults.isEmpty()) return null

        val prompt = buildString {
            appendLine("사용자가 다음을 요청했고, API 워크플로우를 실행하여 데이터를 얻었다.")
            appendLine()
            appendLine("## 사용자 요청")
            appendLine(intent)
            appendLine()
            appendLine("## 워크플로우 결과")
            appendLine("이름: ${workflow.name}")
            for (line in meaningfulResults) {
                appendLine(line)
            }
            appendLine()
            appendLine("## 지시")
            appendLine("위 데이터를 기반으로 사용자의 질문에 **자연어로 간결하게 답변**하라.")
            appendLine("- 핵심 수치와 이름을 포함하라")
            appendLine("- 불필요한 기술 상세(JSON, 토큰 등)는 제외하라")
            appendLine("- 3~5문장 이내로 답변하라")
        }

        return try {
            val response = llmProvider.call(
                LlmRequest(
                    action = LlmAction.COMPLETE,
                    prompt = prompt,
                    model = model ?: llmProvider.defaultModel,
                    maxTokens = 500
                )
            )
            response.content.trim()
        } catch (e: Exception) {
            println("[HLX-SUMMARY] LLM summarization failed: ${e.message}")
            null
        }
    }

    /**
     * HLX 워크플로우 실행 결과를 사용자 친화적 문자열로 포맷팅
     */
    private fun formatHlxExecutionResult(result: HlxExecutionResult, workflow: io.wiiiv.hlx.model.HlxWorkflow): String = buildString {
        val statusEmoji = when (result.status) {
            HlxExecutionStatus.COMPLETED -> "OK"
            HlxExecutionStatus.ENDED_EARLY -> "OK (early)"
            HlxExecutionStatus.FAILED -> "FAILED"
            HlxExecutionStatus.ABORTED -> "ABORTED"
        }

        val durationSec = String.format("%.1fs", result.totalDurationMs / 1000.0)

        // === 컴팩트 섹션 (verbose 1+) ===
        appendLine("=== HLX Workflow: ${workflow.name} ===")
        appendLine("Status: $statusEmoji | Duration: $durationSec | Nodes: ${result.nodeRecords.size}")
        result.error?.let { appendLine("Error: $it") }
        appendLine()

        for (record in result.nodeRecords) {
            val nodeStatus = when (record.status) {
                io.wiiiv.hlx.model.HlxStatus.SUCCESS -> "[OK]"
                io.wiiiv.hlx.model.HlxStatus.FAILED -> "[FAIL]"
                else -> "[${record.status}]"
            }
            val durSec = String.format("%.1fs", record.durationMs / 1000.0)
            append("$nodeStatus ${record.nodeId} (${record.nodeType}) $durSec")
            record.error?.let { append(" - $it") }
            appendLine()
        }

        // 마지막 노드 결과만 간략히 표시 (실질적 결과)
        val lastOutput = result.nodeRecords.lastOrNull { it.output != null }?.output
        if (lastOutput != null) {
            val outputStr = lastOutput.toString()
            appendLine()
            appendLine("Result: ${if (outputStr.length <= 200) outputStr else outputStr.take(200) + "..."}")
        }

        // === 상세 섹션 (verbose 2+) ===
        appendLine()
        appendLine("=== HLX Node Details ===")

        for (record in result.nodeRecords) {
            val nodeStatus = when (record.status) {
                io.wiiiv.hlx.model.HlxStatus.SUCCESS -> "[OK]"
                io.wiiiv.hlx.model.HlxStatus.FAILED -> "[FAIL]"
                else -> "[${record.status}]"
            }
            append("$nodeStatus ${record.nodeId} (${record.nodeType})")
            if (record.durationMs > 0) append(" ${record.durationMs}ms")
            record.error?.let { append(" - $it") }
            appendLine()

            record.output?.let { output ->
                val outputStr = output.toString()
                if (outputStr.length <= 500) {
                    appendLine("  → $outputStr")
                } else {
                    appendLine("  → ${outputStr.take(500)}...")
                }
            }
        }

        if (result.context.variables.isNotEmpty()) {
            appendLine()
            appendLine("=== Final Variables ===")
            result.context.variables.forEach { (key, value) ->
                val valueStr = value.toString()
                if (valueStr.length <= 300) {
                    appendLine("$key: $valueStr")
                } else {
                    appendLine("$key: ${valueStr.take(300)}...")
                }
            }
        }
    }

    /**
     * DraftSpec에서 Blueprint 생성
     */
    private suspend fun createBlueprintFromDraftSpec(draftSpec: DraftSpec, spec: Spec, workspace: String? = null): Blueprint {
        emitProgress(ProgressPhase.BLUEPRINT_CREATING, "Creating Blueprint...")
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
                val rawTarget = draftSpec.targetPath
                // targetPath가 URL/호스트:포트 패턴이면 프로젝트 경로로 부적절 → 무시
                val sanitizedTarget = rawTarget?.takeIf { path ->
                    !path.matches(Regex("^(https?://)?[\\w.-]+(:\\d+).*")) &&  // localhost:9091, http://...
                    !path.matches(Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+.*")) &&    // IP주소
                    !path.contains("://")                                       // 프로토콜 포함
                }
                val basePath = sanitizedTarget
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

            TaskType.DB_QUERY -> {
                // DB_QUERY는 executeDbQueryHlx()에서 HLX 워크플로우를 동적 생성/실행
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.NOOP,
                        params = mapOf("reason" to "DB_QUERY handled by executeDbQueryHlx via HLX")
                    )
                )
            }

            TaskType.WORKFLOW_CREATE -> {
                // WORKFLOW_CREATE는 executeWorkflowCreate()에서 WorkOrder 기반 HLX 생성/실행
                listOf(
                    BlueprintStep(
                        stepId = stepId,
                        type = BlueprintStepType.NOOP,
                        params = mapOf("reason" to "WORKFLOW_CREATE handled by executeWorkflowCreate via HLX")
                    )
                )
            }

            TaskType.INFORMATION, TaskType.CONVERSATION, TaskType.WORKFLOW_MANAGE, null -> {
                // 실행이 필요하지 않은 타입 (WORKFLOW_MANAGE는 Pre-LLM 인터셉터가 처리)
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
     * 대화 내역 + DraftSpec으로부터 작업지시서(Work Order)를 생성한다.
     *
     * CONFIRM 단계에서 호출되며, 생성된 마크다운은:
     * 1. 사용자에게 확인용으로 보여주고
     * 2. DraftSpec.workOrderContent에 저장되어 이후 코드 생성에 사용된다.
     */
    private suspend fun generateWorkOrder(session: ConversationSession): String {
        val prompt = GovernorPrompt.workOrderGenerationPrompt(session.history, session.draftSpec)

        val response = llmProvider!!.call(
            LlmRequest(
                action = LlmAction.COMPLETE,
                prompt = prompt,
                model = model ?: llmProvider.defaultModel,
                maxTokens = 8192,
                timeoutMs = 120_000L  // 작업지시서 생성: 2분
            )
        )

        return stripMarkdownFence(response.content.trim())
    }

    /**
     * WORKFLOW_CREATE용 워크플로우 작업지시서 생성
     *
     * 대화 내역 + DraftSpec → HLX 워크플로우를 위한 상세 작업지시서
     */
    private suspend fun generateWorkflowWorkOrder(session: ConversationSession): String {
        // RAG에서 API 스펙 조회 (Base URL, 엔드포인트 등 실제 정보 제공)
        val lastUserMessage = session.getRecentHistory(5)
            .lastOrNull { it.role == MessageRole.USER }?.content
        val ragContext = consultRagForApiKnowledge(session.draftSpec, lastUserMessage)
        val authContext = consultRagForAuthCredentials()
        val mergedRagContext = mergeRagContexts(ragContext, authContext)

        val prompt = GovernorPrompt.workflowWorkOrderGenerationPrompt(
            session.history, session.draftSpec, mergedRagContext
        )

        val response = llmProvider!!.call(
            LlmRequest(
                action = LlmAction.COMPLETE,
                prompt = prompt,
                model = model ?: llmProvider.defaultModel,
                maxTokens = 8192,
                timeoutMs = 120_000L  // 워크플로우 작업지시서 생성: 2분
            )
        )

        return stripMarkdownFence(response.content.trim())
    }

    /**
     * LLM이 마크다운을 코드 펜스(```markdown ... ```)로 감싸는 경우 벗겨낸다.
     */
    private fun stripMarkdownFence(content: String): String {
        var result = content
        // ```markdown 또는 ```md 또는 ``` 로 시작하면 제거
        if (result.startsWith("```")) {
            val firstNewline = result.indexOf('\n')
            if (firstNewline >= 0) {
                result = result.substring(firstNewline + 1)
            }
        }
        // 끝의 ``` 제거
        if (result.trimEnd().endsWith("```")) {
            result = result.trimEnd().removeSuffix("```").trimEnd()
        }
        return result
    }

    /**
     * LLM을 사용하여 프로젝트 파일 구조를 생성하고 BlueprintStep 목록으로 변환
     *
     * 작업지시서가 있으면 풍부한 컨텍스트로 코드 생성, 없으면 기존 DraftSpec 기반.
     */
    private suspend fun generateProjectBlueprint(draftSpec: DraftSpec, basePath: String): List<BlueprintStep> {
        val workOrder = draftSpec.workOrderContent

        // WorkOrder가 없으면 기존 단일턴 방식
        if (workOrder == null) {
            return generateProjectBlueprintSingleTurn(draftSpec, basePath)
        }

        // ── Multi-turn Generation ──
        val log = org.slf4j.LoggerFactory.getLogger("MultiTurnGeneration")
        log.info("[MULTI-TURN] Starting multi-turn project generation for basePath={}", basePath)

        val allFiles = mutableMapOf<String, IntegrityAnalyzer.GeneratedFile>() // path → file (후속 덮어쓰기)
        var buildCommand: String? = null
        var testCommand: String? = null
        val maxTurns = 5
        var useForcePrompt = false
        var lastMissingLayers: List<String> = emptyList()

        for (turn in 1..maxTurns) {
            val prompt = if (turn == 1) {
                GovernorPrompt.multiTurnFirstPrompt(workOrder)
            } else if (useForcePrompt && lastMissingLayers.isNotEmpty()) {
                useForcePrompt = false  // 플래그 리셋
                val generatedPaths = allFiles.keys.sorted()
                val gradleDeps = extractGradleDependencies(allFiles.values.toList())
                val basePackage = inferBasePackage(allFiles.values.toList())
                GovernorPrompt.multiTurnForcedContinuationPrompt(
                    workOrderContent = workOrder,
                    generatedPaths = generatedPaths,
                    gradleDependencies = gradleDeps,
                    basePackage = basePackage,
                    missingLayers = lastMissingLayers,
                    turnNumber = turn
                )
            } else {
                val generatedPaths = allFiles.keys.sorted()
                val gradleDeps = extractGradleDependencies(allFiles.values.toList())
                val basePackage = inferBasePackage(allFiles.values.toList())
                GovernorPrompt.multiTurnContinuationPrompt(
                    workOrderContent = workOrder,
                    generatedPaths = generatedPaths,
                    gradleDependencies = gradleDeps,
                    basePackage = basePackage,
                    turnNumber = turn
                )
            }

            val response: LlmResponse
            try {
                response = llmProvider!!.call(
                    LlmRequest(
                        action = LlmAction.COMPLETE,
                        prompt = prompt,
                        model = model ?: llmProvider.defaultModel,
                        maxTokens = 16384,
                        timeoutMs = 180_000L
                    )
                )
            } catch (e: Exception) {
                log.warn("[MULTI-TURN] Turn {} LLM call failed: {}, proceeding with collected files", turn, e.message)
                break
            }

            // JSON 파싱
            var turnFiles: List<IntegrityAnalyzer.GeneratedFile>
            var turnBuildCmd: String? = null
            var turnTestCmd: String? = null
            try {
                val rawJson = extractJson(response.content)
                val jsonStr = sanitizeInvalidEscapes(sanitizeTripleQuotes(rawJson))
                val jsonElement = json.parseToJsonElement(jsonStr).jsonObject

                val filesArray = jsonElement["files"]?.jsonArray
                if (filesArray == null || filesArray.isEmpty()) {
                    val missingLayers = findMissingCriticalLayers(allFiles.values.toList())
                    if (missingLayers.isNotEmpty() && !useForcePrompt && turn < maxTurns) {
                        log.info("[MULTI-TURN] Turn {} returned empty files[] but missing layers: {} — forcing retry", turn, missingLayers)
                        useForcePrompt = true
                        lastMissingLayers = missingLayers
                        continue
                    }
                    log.info("[MULTI-TURN] Turn {} returned empty files[], ending", turn)
                    break
                }

                turnFiles = filesArray.mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    val rawContent = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    // LLM이 JSON에서 \\n (double-escaped)을 사용한 경우 복구
                    val content = unescapeDoubleEscapedContent(rawContent)
                    IntegrityAnalyzer.GeneratedFile(path, content)
                }

                // Turn 1에서만 buildCommand/testCommand 추출
                if (turn == 1) {
                    turnBuildCmd = jsonElement["buildCommand"]?.jsonPrimitive?.contentOrNull
                    turnTestCmd = jsonElement["testCommand"]?.jsonPrimitive?.contentOrNull
                }
            } catch (e: Exception) {
                log.warn("[MULTI-TURN] Turn {} JSON parse failed: {}, trying fallback extraction", turn, e.message)
                val fallbackResult = fallbackExtractFiles(response.content)
                if (fallbackResult.isNotEmpty()) {
                    turnFiles = fallbackResult
                    log.info("[MULTI-TURN] Turn {} fallback extracted {} files", turn, fallbackResult.size)
                } else {
                    log.warn("[MULTI-TURN] Turn {} fallback also extracted 0 files, proceeding with collected", turn)
                    break
                }
            }

            if (turnFiles.isEmpty()) {
                val missingLayers = findMissingCriticalLayers(allFiles.values.toList())
                if (missingLayers.isNotEmpty() && !useForcePrompt && turn < maxTurns) {
                    log.info("[MULTI-TURN] Turn {} parsed 0 files but missing layers: {} — forcing retry", turn, missingLayers)
                    useForcePrompt = true
                    lastMissingLayers = missingLayers
                    continue
                }
                log.info("[MULTI-TURN] Turn {} parsed 0 files, ending", turn)
                break
            }

            // 파일 병합 (퇴화 방지: 후속 턴 파일이 기존보다 현저히 짧으면 교체 안 함)
            var newPathCount = 0
            var overwrittenCount = 0
            var skippedDegradation = 0
            for (file in turnFiles) {
                val existing = allFiles[file.path]
                if (existing != null) {
                    // 퇴화 방지: 기존 대비 30% 미만 길이면 교체 안 함
                    if (file.content.length < existing.content.length * 0.3) {
                        skippedDegradation++
                        log.debug("[MULTI-TURN] Skipped degraded overwrite: {} ({}→{} chars)",
                            file.path, existing.content.length, file.content.length)
                        continue
                    }
                    overwrittenCount++
                } else {
                    newPathCount++
                }
                allFiles[file.path] = file
            }

            if (turnBuildCmd != null && turnBuildCmd != "null" && turnBuildCmd.isNotBlank()) buildCommand = turnBuildCmd
            if (turnTestCmd != null && turnTestCmd != "null" && turnTestCmd.isNotBlank()) testCommand = turnTestCmd

            log.info("[MULTI-TURN] Turn {} complete: finishReason={}, filesReturned={}, newPaths={}, overwritten={}, skippedDegradation={}, totalAccumulated={}",
                turn, response.finishReason, turnFiles.size, newPathCount, overwrittenCount, skippedDegradation, allFiles.size)

            // 종료 조건 3단계:
            // 1) finishReason=length/max_tokens → 무조건 continue (토큰 부족)
            // 2) finishReason=stop/end_turn → 레이어 완성 체크 후 판단
            // 3) 신규 path 0 → 종료
            if (response.finishReason in listOf("end_turn", "stop")) {
                val missingLayers = findMissingCriticalLayers(allFiles.values.toList())
                if (missingLayers.isEmpty()) {
                    log.info("[MULTI-TURN] LLM signaled completion and all critical layers present, ending after turn {}", turn)
                    break
                } else {
                    log.info("[MULTI-TURN] LLM signaled stop but missing layers: {} — continuing to turn {}", missingLayers, turn + 1)
                    // continue to next turn
                }
            }

            // 종료 조건: 신규 path가 0개면 더 생성할 것이 없음
            if (newPathCount == 0) {
                val missingLayers = findMissingCriticalLayers(allFiles.values.toList())
                if (missingLayers.isNotEmpty() && !useForcePrompt && turn < maxTurns) {
                    log.info("[MULTI-TURN] No new paths in turn {} but missing layers: {} — forcing retry", turn, missingLayers)
                    useForcePrompt = true
                    lastMissingLayers = missingLayers
                    continue
                }
                log.info("[MULTI-TURN] No new paths in turn {}, ending", turn)
                break
            }
        }

        log.info("[MULTI-TURN] Generation complete: total {} files collected", allFiles.size)

        // ★ Integrity Gate: 전체 합산 파일에 대해 1번 실행
        val integrityResult = IntegrityAnalyzer.analyze(allFiles.values.toList())
        val verifiedFiles = integrityResult.files

        return buildBlueprintSteps(verifiedFiles, basePath, workOrder, buildCommand, testCommand)
    }

    /**
     * 기존 단일턴 프로젝트 생성 (비WorkOrder용)
     */
    private suspend fun generateProjectBlueprintSingleTurn(draftSpec: DraftSpec, basePath: String): List<BlueprintStep> {
        val prompt = GovernorPrompt.projectGenerationPrompt(draftSpec)

        val response = llmProvider!!.call(
            LlmRequest(
                action = LlmAction.COMPLETE,
                prompt = prompt,
                model = model ?: llmProvider.defaultModel,
                maxTokens = 4096
            )
        )

        val rawJson = extractJson(response.content)
        val jsonStr = sanitizeInvalidEscapes(sanitizeTripleQuotes(rawJson))
        val jsonElement = json.parseToJsonElement(jsonStr).jsonObject

        val filesArray = jsonElement["files"]?.jsonArray
            ?: throw IllegalStateException("LLM response missing 'files' array")

        val rawFiles = filesArray.mapNotNull { elem ->
            val obj = elem.jsonObject
            val path = obj["path"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val rawContent = obj["content"]?.jsonPrimitive?.contentOrNull ?: ""
            val content = unescapeDoubleEscapedContent(rawContent)
            IntegrityAnalyzer.GeneratedFile(path, content)
        }
        val integrityResult = IntegrityAnalyzer.analyze(rawFiles)
        val verifiedFiles = integrityResult.files

        val buildCommand = jsonElement["buildCommand"]?.jsonPrimitive?.contentOrNull
        val testCommand = jsonElement["testCommand"]?.jsonPrimitive?.contentOrNull

        return buildBlueprintSteps(verifiedFiles, basePath, null, buildCommand, testCommand)
    }

    /**
     * build.gradle.kts에서 dependencies { ... } 블록 추출
     *
     * brace matching으로 안정적으로 추출한다.
     */
    private fun extractGradleDependencies(files: List<IntegrityAnalyzer.GeneratedFile>): String? {
        val gradleFile = files.find { it.path.endsWith("build.gradle.kts") } ?: return null
        val content = gradleFile.content
        val startIdx = content.indexOf("dependencies {")
        if (startIdx == -1) return null

        // brace matching
        var braceCount = 0
        var foundFirst = false
        for (i in startIdx until content.length) {
            when (content[i]) {
                '{' -> { braceCount++; foundFirst = true }
                '}' -> { braceCount-- }
            }
            if (foundFirst && braceCount == 0) {
                return content.substring(startIdx, i + 1)
            }
        }
        // 닫는 brace를 못 찾으면 끝까지
        return content.substring(startIdx)
    }

    /**
     * 생성된 파일에서 루트 패키지를 추론한다.
     *
     * Application.kt 또는 첫 번째 Kotlin 파일의 package 선언에서 추출.
     */
    private fun inferBasePackage(files: List<IntegrityAnalyzer.GeneratedFile>): String? {
        // Application 클래스 우선
        val appFile = files.find { it.path.contains("Application.kt") }
        val targetFile = appFile ?: files.find { it.path.endsWith(".kt") }
        targetFile ?: return null

        val packageLine = targetFile.content.lineSequence()
            .firstOrNull { it.trimStart().startsWith("package ") }
            ?: return null

        return packageLine.trim().removePrefix("package ").trim()
    }

    /**
     * 생성된 파일에서 누락된 핵심 레이어를 찾는다.
     *
     * coverageRatio 기반: Controller/Service 수가 Entity 수의 50% 미만이면 미완성.
     * 프로젝트 크기에 자동 적응한다:
     * - Entity 6개 → Controller 3개 이상 필요
     * - Entity 2개 → Controller 1개 이상 필요
     */
    private fun findMissingCriticalLayers(files: List<IntegrityAnalyzer.GeneratedFile>): List<String> {
        val missing = mutableListOf<String>()

        // Entity 수 파악 (Config, DTO 제외)
        val entityCount = files.count { file ->
            file.path.endsWith(".kt") &&
            !file.path.contains("config/", ignoreCase = true) &&
            !file.path.contains("dto/", ignoreCase = true) &&
            (file.content.contains("@Entity") || file.path.contains("model/") || file.path.contains("entity/"))
        }

        val controllerCount = files.count { file ->
            file.path.contains("Controller.kt", ignoreCase = true) ||
            file.content.contains("@RestController") ||
            file.content.contains("@Controller")
        }

        val serviceCount = files.count { file ->
            (file.path.contains("Service.kt", ignoreCase = true) ||
            file.content.contains("@Service")) &&
            !file.path.contains("config/", ignoreCase = true)
        }

        val repositoryCount = files.count { file ->
            file.path.contains("Repository.kt", ignoreCase = true) ||
            file.content.contains("JpaRepository")
        }

        // 최소 기준: Entity가 있으면 Controller/Service/Repository 커버리지 50% 이상
        val minRequired = if (entityCount > 0) maxOf(1, entityCount / 2) else 1

        if (controllerCount < minRequired) {
            missing.add("Controller(${controllerCount}/${minRequired})")
        }
        if (serviceCount < minRequired) {
            missing.add("Service(${serviceCount}/${minRequired})")
        }
        if (repositoryCount < minRequired) {
            missing.add("Repository(${repositoryCount}/${minRequired})")
        }

        // JWT/Security 관련 필수 컴포넌트 검증
        val hasSecurityDep = files.any { it.content.contains("spring-boot-starter-security") }
        val hasJwtDep = files.any { it.content.contains("jjwt-api") || it.content.contains("jsonwebtoken") }

        if (hasSecurityDep || hasJwtDep) {
            val hasAuthController = files.any {
                it.path.contains("AuthController", ignoreCase = true) &&
                (it.content.contains("@RestController") || it.content.contains("@Controller"))
            }
            val hasJwtProvider = files.any {
                it.path.contains("JwtProvider", ignoreCase = true) ||
                (it.content.contains("Jwts.builder") && it.content.contains("@Component"))
            }
            val hasJwtFilter = files.any {
                it.path.contains("JwtAuthFilter", ignoreCase = true) ||
                it.path.contains("JwtFilter", ignoreCase = true) ||
                it.content.contains("OncePerRequestFilter")
            }
            val hasSecurityConfig = files.any {
                it.content.contains("@EnableWebSecurity") ||
                it.path.contains("SecurityConfig", ignoreCase = true)
            }

            if (!hasAuthController) missing.add("AuthController(필수: JWT 인증 login/register)")
            if (!hasJwtProvider) missing.add("JwtProvider(필수: JWT 토큰 생성/검증)")
            if (!hasJwtFilter) missing.add("JwtAuthFilter(필수: JWT 인증 필터)")
            if (!hasSecurityConfig) missing.add("SecurityConfig(필수: @EnableWebSecurity)")
        }

        // DataInitializer 검증 — 초기 데이터가 작업지시서에 명시된 경우
        val hasDataInitializer = files.any {
            it.path.contains("DataInitializer", ignoreCase = true) ||
            it.path.contains("data.sql", ignoreCase = true) ||
            it.content.contains("CommandLineRunner")
        }
        if (entityCount > 0 && !hasDataInitializer) {
            missing.add("DataInitializer(초기 데이터 누락)")
        }

        return missing
    }

    /**
     * 검증된 파일 목록 → BlueprintStep 리스트 생성 (공통 로직)
     */
    private fun buildBlueprintSteps(
        verifiedFiles: List<IntegrityAnalyzer.GeneratedFile>,
        basePath: String,
        workOrder: String?,
        buildCommand: String?,
        testCommand: String?
    ): List<BlueprintStep> {
        val steps = mutableListOf<BlueprintStep>()

        // 1. 루트 디렉토리 생성
        steps.add(BlueprintStep(
            stepId = "step-mkdir-root-${UUID.randomUUID().toString().take(4)}",
            type = BlueprintStepType.FILE_MKDIR,
            params = mapOf("path" to basePath)
        ))

        // 1.5. 작업지시서를 프로젝트에 저장 (.wiiiv/work-order.md)
        if (workOrder != null) {
            steps.add(BlueprintStep(
                stepId = "step-mkdir-wiiiv-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_MKDIR,
                params = mapOf("path" to "$basePath/.wiiiv")
            ))
            steps.add(BlueprintStep(
                stepId = "step-write-workorder-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_WRITE,
                params = mapOf(
                    "path" to "$basePath/.wiiiv/work-order.md",
                    "content" to workOrder
                )
            ))
        }

        // 2. 필요한 디렉토리 수집 및 생성
        val dirs = mutableSetOf<String>()
        for (file in verifiedFiles) {
            val fullPath = "$basePath/${file.path}"
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
        for (file in verifiedFiles) {
            val fullPath = "$basePath/${file.path}"
            steps.add(BlueprintStep(
                stepId = "step-write-${UUID.randomUUID().toString().take(4)}",
                type = BlueprintStepType.FILE_WRITE,
                params = mapOf("path" to fullPath, "content" to file.content)
            ))
        }

        // 4. 빌드 명령어 (있으면)
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

                // FILE_WRITE: path + action
                output.json["action"]?.let { action ->
                    val actionStr = action.jsonPrimitive.contentOrNull ?: return@let
                    if (actionStr == "WRITE") {
                        val path = output.json["path"]?.jsonPrimitive?.contentOrNull ?: ""
                        appendLine()
                        appendLine("파일 저장 완료: $path")
                    }
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
            lower in listOf("안녕", "안녕하세요") -> {
                val greetingMessage = "저는 wiiiv Governor입니다.\n요청을 명확한 작업으로 정의하고, 필요한 경우 검증과 설계를 거쳐 안전하게 실행까지 연결합니다.\n무엇을 도와드릴까요?"
                session.addGovernorMessage(greetingMessage)
                ConversationResponse(
                    action = ActionType.REPLY,
                    message = greetingMessage,
                    sessionId = session.sessionId
                )
            }

            lower.contains("취소") || lower.contains("됐어") || lower.contains("그만") -> {
                val hasActiveWork = session.draftSpec.taskType != null
                    && session.draftSpec.taskType != TaskType.CONVERSATION
                    && session.draftSpec.taskType != TaskType.INFORMATION
                if (hasActiveWork) {
                    session.cancelCurrentTask()
                    session.addGovernorMessage("작업을 취소하겠습니다.")
                    ConversationResponse(
                        action = ActionType.CANCEL,
                        message = "작업을 취소하겠습니다.",
                        sessionId = session.sessionId
                    )
                } else {
                    val msg = "알겠습니다. 다른 것이 필요하면 말씀해주세요."
                    session.addGovernorMessage(msg)
                    ConversationResponse(
                        action = ActionType.REPLY,
                        message = msg,
                        sessionId = session.sessionId
                    )
                }
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
     * HLX JSON sanitizer — LLM이 생성한 흔한 구조 오류를 교정
     *
     * - decide 노드의 branches가 배열이면 → 배열 내 아이템을 별도 노드로 추출 + branches를 Map으로 변환
     * - branches가 null이면 → transform으로 변환
     */
    /**
     * input 필드가 배열이면 comma-separated 문자열로 변환
     * 예: "input": ["a", "b"] → "input": "a, b"
     */
    private fun fixInputArray(nodeObj: JsonObject): JsonObject {
        val inputValue = nodeObj["input"]
        if (inputValue is JsonArray) {
            val joined = inputValue.mapNotNull { it.jsonPrimitive?.contentOrNull }.joinToString(", ")
            println("[HLX-SANITIZE] Converted input array → string: $joined")
            return buildJsonObject {
                nodeObj.forEach { (k, v) ->
                    if (k == "input") put(k, JsonPrimitive(joined)) else put(k, v)
                }
            }
        }
        return nodeObj
    }

    /**
     * repeat body 내부 노드들도 재귀적으로 sanitize
     */
    private fun sanitizeNodeRecursive(nodeObj: JsonObject): JsonObject {
        var fixed = fixInputArray(nodeObj)
        // repeat body 재귀 처리
        val body = fixed["body"]
        if (body is JsonArray) {
            val fixedBody = buildJsonArray {
                for (child in body) {
                    if (child is JsonObject) {
                        add(sanitizeNodeRecursive(child))
                    } else {
                        add(child)
                    }
                }
            }
            fixed = buildJsonObject {
                fixed.forEach { (k, v) ->
                    if (k == "body") put(k, fixedBody) else put(k, v)
                }
            }
        }
        return fixed
    }

    private fun sanitizeHlxJson(rawJson: String): String {
        val jsonParser = Json { ignoreUnknownKeys = true }
        val root = jsonParser.parseToJsonElement(rawJson).jsonObject
        val nodes = root["nodes"]?.jsonArray ?: return rawJson

        val fixedNodes = buildJsonArray {
            for (node in nodes) {
                var nodeObj = sanitizeNodeRecursive(node.jsonObject)
                val type = nodeObj["type"]?.jsonPrimitive?.contentOrNull

                if (type == "decide") {
                    val branches = nodeObj["branches"]

                    if (branches is JsonArray && branches.isNotEmpty()) {
                        // decide + branches가 배열 → 배열 아이템을 별도 노드로 추출, branches를 Map으로 변환
                        val branchMap = buildJsonObject {}
                        val extractedNodes = mutableListOf<JsonObject>()
                        val mapBuilder = mutableMapOf<String, String>()

                        for (branchItem in branches) {
                            val branchObj = branchItem as? JsonObject ?: continue
                            val branchId = branchObj["id"]?.jsonPrimitive?.contentOrNull ?: continue
                            val branchType = branchObj["type"]?.jsonPrimitive?.contentOrNull

                            if (branchType != null) {
                                // 내장 노드 → 별도 노드로 추출
                                extractedNodes.add(branchObj)
                                mapBuilder[branchId] = branchId
                            } else {
                                // 단순 {id: "...", ...} → condition으로 처리
                                val target = branchObj["target"]?.jsonPrimitive?.contentOrNull ?: "end"
                                mapBuilder[branchId] = target
                            }
                        }

                        // Decide 노드 재구성 (branches를 Map으로)
                        val fixedBranches = buildJsonObject {
                            mapBuilder.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                        }
                        val fixed = buildJsonObject {
                            nodeObj.forEach { (k, v) ->
                                if (k == "branches") put(k, fixedBranches) else put(k, v)
                            }
                        }
                        add(fixed)

                        // 추출된 내장 노드를 Decide 뒤에 삽입
                        for (extracted in extractedNodes) {
                            add(extracted)
                            println("[HLX-SANITIZE] Extracted embedded node '${extracted["id"]?.jsonPrimitive?.contentOrNull}' from decide branches")
                        }

                        println("[HLX-SANITIZE] Converted decide branches array → map: $mapBuilder")
                        continue

                    } else if (branches == null) {
                        // branches가 null → transform으로 변환 (안전)
                        println("[HLX-SANITIZE] Converting decide node '${nodeObj["id"]?.jsonPrimitive?.contentOrNull}' to transform (null branches)")
                        val fixed = buildJsonObject {
                            nodeObj.forEach { (k, v) ->
                                when (k) {
                                    "type" -> put(k, JsonPrimitive("transform"))
                                    "branches" -> {}
                                    else -> put(k, v)
                                }
                            }
                            if ("hint" !in nodeObj) put("hint", JsonPrimitive("summarize"))
                        }
                        add(fixed)
                        continue
                    }
                }

                add(nodeObj)
            }
        }

        // ID 중복 제거 패스 (body 포함 전체 노드 대상)
        val deduped = deduplicateNodeIds(fixedNodes)

        val fixedRoot = buildJsonObject {
            root.forEach { (k, v) ->
                if (k == "nodes") put(k, deduped) else put(k, v)
            }
        }
        return jsonParser.encodeToString(JsonElement.serializer(), fixedRoot)
    }

    /**
     * 노드 ID 중복 제거 (body 포함 재귀)
     */
    private fun deduplicateNodeIds(nodes: JsonArray): JsonArray {
        val seenIds = mutableSetOf<String>()

        fun dedup(node: JsonObject): JsonObject {
            var result = node
            val id = node["id"]?.jsonPrimitive?.contentOrNull
            if (id != null && !seenIds.add(id)) {
                // 중복 → 접미사 추가
                var suffix = 2
                while (!seenIds.add("$id-$suffix")) suffix++
                val newId = "$id-$suffix"
                println("[HLX-SANITIZE] Dedup: '$id' → '$newId'")
                result = buildJsonObject {
                    node.forEach { (k, v) ->
                        if (k == "id") put(k, JsonPrimitive(newId)) else put(k, v)
                    }
                }
            }
            // body 재귀
            val body = result["body"]
            if (body is JsonArray) {
                val dedupedBody = buildJsonArray {
                    for (child in body) {
                        if (child is JsonObject) add(dedup(child)) else add(child)
                    }
                }
                result = buildJsonObject {
                    result.forEach { (k, v) ->
                        if (k == "body") put(k, dedupedBody) else put(k, v)
                    }
                }
            }
            return result
        }

        return buildJsonArray {
            for (node in nodes) {
                if (node is JsonObject) add(dedup(node)) else add(node)
            }
        }
    }

    /**
     * JSON만 추출 (tail 불필요한 호출용)
     */
    private fun extractJson(response: String): String {
        return extractJsonWithTail(response).first
    }

    /**
     * LLM이 JSON content 필드에 triple-quote(""")를 사용한 경우 교정.
     * "content": """...""" → "content": "..." (내부 줄바꿈은 \n으로 이스케이프)
     */
    private fun sanitizeTripleQuotes(json: String): String {
        if (!json.contains("\"\"\"")) return json

        println("[SANITIZE] Triple-quote detected in JSON, fixing...")

        val result = StringBuilder()
        var i = 0
        while (i < json.length) {
            // "content": """ 패턴 감지
            if (i + 3 <= json.length && json.substring(i, i + 3) == "\"\"\"") {
                // 앞쪽 컨텍스트 확인: "content": 뒤의 triple-quote인지
                val before = json.substring(0, i).trimEnd()
                if (before.endsWith(":") || before.endsWith(": ")) {
                    // triple-quote 시작 → 닫는 triple-quote까지의 내용을 JSON string으로 변환
                    val contentStart = i + 3
                    val closingIdx = json.indexOf("\"\"\"", contentStart)
                    if (closingIdx != -1) {
                        val rawContent = json.substring(contentStart, closingIdx)
                        // JSON string으로 이스케이프
                        val escaped = rawContent
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t")
                        result.append("\"")
                        result.append(escaped)
                        result.append("\"")
                        i = closingIdx + 3
                        continue
                    }
                }
            }
            result.append(json[i])
            i++
        }

        return result.toString()
    }

    /**
     * LLM이 JSON content 필드에 유효하지 않은 이스케이프 시퀀스를 사용한 경우 교정.
     * JSON에서 유효한 이스케이프: \", \\, \/, \b, \f, \n, \r, \t, \uXXXX
     * LLM이 코드에서 \d, \s, \w 등 regex 패턴을 그대로 쓰면 JSON 파싱 에러.
     * \X (X가 유효하지 않은 문자) → \\X 로 변환
     */
    /**
     * LLM이 JSON content에서 \\n (double-escaped)을 사용한 경우 복구.
     * 조건: 실제 줄바꿈(0x0A)이 없고 리터럴 \n(0x5C 0x6E)이 있으면 double-escaped로 판정.
     * 한 레벨만 unescape하여 \n→newline, \\→\, \"→" 등을 복구한다.
     */
    private fun unescapeDoubleEscapedContent(s: String): String {
        // 실제 줄바꿈이 있으면 정상 — 그대로 반환
        if ('\n' in s) return s
        // 리터럴 \n이 없으면 unescape 불필요
        if (!s.contains("\\n")) return s

        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            if (s[i] == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    else -> { sb.append(s[i]); i++ }
                }
            } else {
                sb.append(s[i])
                i++
            }
        }
        return sb.toString()
    }

    private fun sanitizeInvalidEscapes(json: String): String {
        val validEscapeChars = setOf('"', '\\', '/', 'b', 'f', 'n', 'r', 't', 'u')
        val sb = StringBuilder(json.length)
        var i = 0
        var fixed = false
        while (i < json.length) {
            if (json[i] == '\\' && i + 1 < json.length) {
                val next = json[i + 1]
                if (next in validEscapeChars) {
                    // 유효한 이스케이프 — 그대로 유지
                    sb.append('\\')
                    sb.append(next)
                    i += 2
                } else {
                    // 유효하지 않은 이스케이프 (\d, \s, \w 등) → \\ + next
                    sb.append('\\')
                    sb.append('\\')
                    sb.append(next)
                    i += 2
                    fixed = true
                }
            } else {
                sb.append(json[i])
                i++
            }
        }
        if (fixed) {
            println("[SANITIZE] Invalid escape sequences fixed in JSON")
        }
        return sb.toString()
    }

    /**
     * JSON 파싱 실패 시 폴백: "path" 키를 기준으로 파일 경계를 찾아 추출.
     *
     * LLM이 코드 내 따옴표를 제대로 이스케이프하지 않으면 (예: claims["role"])
     * JSON 파서가 실패한다. 이 함수는 "path": "xxx.kt" 패턴으로
     * 파일 경계를 찾고 그 사이의 content를 추출한다.
     */
    private fun fallbackExtractFiles(rawLlmOutput: String): List<IntegrityAnalyzer.GeneratedFile> {
        val files = mutableListOf<IntegrityAnalyzer.GeneratedFile>()

        // "path": "xxx.ext" 패턴으로 각 파일 시작점을 찾기
        val pathPattern = Regex(""""path"\s*:\s*"([^"]+\.(?:kt|kts|yml|yaml|sql|properties|xml|json|gradle|java|md|txt))"""")
        val matches = pathPattern.findAll(rawLlmOutput).toList()
        if (matches.isEmpty()) return files

        for ((idx, pm) in matches.withIndex()) {
            val filePath = pm.groupValues[1]

            // 이 path 다음에 나오는 "content": " 찾기
            val searchStart = pm.range.last + 1
            val searchEnd = if (idx + 1 < matches.size) matches[idx + 1].range.first else rawLlmOutput.length
            val searchRegion = rawLlmOutput.substring(searchStart, minOf(searchStart + 200, searchEnd))

            val contentKeyIdx = searchRegion.indexOf("\"content\"")
            if (contentKeyIdx < 0) continue

            val afterContentKey = searchRegion.substring(contentKeyIdx + "\"content\"".length)
            val openingQuoteIdx = afterContentKey.indexOf('"')
            if (openingQuoteIdx < 0) continue

            val absoluteContentStart = searchStart + contentKeyIdx + "\"content\"".length + openingQuoteIdx + 1

            // 다음 "path" 키 이전까지가 content 범위
            val contentEndBound = if (idx + 1 < matches.size) matches[idx + 1].range.first else rawLlmOutput.length
            val rawRegion = rawLlmOutput.substring(absoluteContentStart, contentEndBound)

            // content 값의 끝 찾기: }, 또는 }] 패턴으로 역방향 검색
            val contentStr = findContentEnd(rawRegion)

            // JSON 문자열 언이스케이프 (순서 중요: \\ → placeholder → 나머지 → placeholder 복원)
            val unescaped = contentStr
                .replace("\\\\", "\u0000")  // \\ → placeholder (다른 이스케이프와 간섭 방지)
                .replace("\\n", "\n")
                .replace("\\t", "\t")
                .replace("\\r", "\r")
                .replace("\\\"", "\"")
                .replace("\\/", "/")
                .replace("\u0000", "\\")    // placeholder → \

            if (filePath.isNotBlank() && unescaped.isNotBlank()) {
                files.add(IntegrityAnalyzer.GeneratedFile(filePath, unescaped))
            }
        }

        return files
    }

    /** content 영역에서 실제 값이 끝나는 지점 찾기 (역방향 검색) */
    private fun findContentEnd(rawRegion: String): String {
        val trimmed = rawRegion.trimEnd()

        // 패턴 1: "}, { (다음 파일 객체 시작)
        // 패턴 2: "}] (files 배열 끝)
        // 패턴 3: "} (객체 끝)
        // 역방향으로 } 를 찾고, 그 앞의 " 위치를 찾는다
        val lastBrace = trimmed.lastIndexOf('}')
        if (lastBrace > 0) {
            // } 앞에서 " 찾기 (공백/줄바꿈 건너뛰기)
            var i = lastBrace - 1
            while (i >= 0 && (trimmed[i] == ' ' || trimmed[i] == '\n' || trimmed[i] == '\r' || trimmed[i] == '\t')) i--
            if (i >= 0 && trimmed[i] == '"') {
                return trimmed.substring(0, i)
            }
        }

        // 폴백: 마지막 " 기준
        val lastQuote = trimmed.lastIndexOf('"')
        return if (lastQuote > 0) trimmed.substring(0, lastQuote) else trimmed
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
        // RAG 컨텍스트 조회 — 세션의 마지막 사용자 메시지를 보조 쿼리로 전달
        val lastUserMessage = session.getRecentHistory(5)
            .lastOrNull { it.role == MessageRole.USER }?.content
        val ragContext = consultRagForApiKnowledge(draftSpec, lastUserMessage)

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
     *
     * 다중 쿼리 전략: intent/domain + 사용자 원문 메시지를 결합하여 검색.
     * 추상적 의도("날씨 조회")만으로 매칭 안 되는 경우,
     * 원문 사용자 메시지("서울 날씨 알려줘")가 보조 쿼리로 작동한다.
     */
    private suspend fun consultRagForApiKnowledge(draftSpec: DraftSpec, userMessage: String? = null): String? {
        if (ragPipeline == null) return null
        if (ragPipeline.size() == 0) return null

        val queries = buildList {
            // Query 1: intent + domain (기존)
            val intentQuery = buildString {
                draftSpec.intent?.let { append(it) }
                draftSpec.domain?.let { append(" $it") }
            }.trim()
            if (intentQuery.isNotBlank()) add(intentQuery)

            // Query 2: 사용자 원문 메시지 (키워드 보존)
            if (!userMessage.isNullOrBlank() && userMessage.trim() != intentQuery) {
                add(userMessage.trim())
            }
        }.distinct()

        if (queries.isEmpty()) return null

        repeat(2) { attempt ->
            try {
                val result = if (queries.size == 1) {
                    ragPipeline.search(queries.first(), topK = 5)
                } else {
                    ragPipeline.searchMulti(queries, topK = 5)
                }
                if (result.isNotEmpty()) return result.toNumberedContext()
                return null
            } catch (e: Exception) {
                if (attempt == 0) {
                    System.err.println("[RAG] API knowledge search failed (attempt 1), retrying: ${e.message}")
                    kotlinx.coroutines.delay(500)
                } else {
                    System.err.println("[RAG] API knowledge search failed (attempt 2), skipping: ${e.message}")
                }
            }
        }
        return null
    }

    /**
     * RAG에서 인증/계정 정보 전용 검색
     *
     * HLX 워크플로우 생성 시 credentials가 누락되는 문제를 방지한다.
     * 일반 검색과 별도로 auth/login/credentials 키워드로 검색한다.
     */
    private suspend fun consultRagForAuthCredentials(): String? {
        if (ragPipeline == null) return null
        if (ragPipeline.size() == 0) return null

        return try {
            val results = ragPipeline.searchMulti(
                listOf(
                    "login credentials username password 계정 정보",
                    "auth login token 인증 로그인",
                    "admin password role ADMIN 계정"
                ),
                topK = 5
            )
            if (results.isNotEmpty()) results.toNumberedContext() else null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 두 RAG context를 병합 (중복 제거)
     */
    private fun mergeRagContexts(primary: String?, auth: String?): String? {
        if (primary == null && auth == null) return null
        if (primary == null) return auth
        if (auth == null) return primary

        // auth context에서 primary에 이미 포함된 줄 제거
        val primaryLines = primary.lines().toSet()
        val newAuthLines = auth.lines().filter { line ->
            line.isNotBlank() && !primaryLines.contains(line)
        }
        return if (newAuthLines.isEmpty()) {
            primary
        } else {
            "$primary\n\n--- 인증/계정 정보 ---\n${newAuthLines.joinToString("\n")}"
        }
    }

    /**
     * RAG context에서 시스템별 로그인 credentials를 코드로 추출
     *
     * gpt-4o-mini가 RAG 텍스트에서 credentials를 안정적으로 추출하지 못하므로,
     * 코드로 파싱하여 구조화된 테이블로 제공한다.
     */
    private fun extractCredentialsFromRag(ragContext: String?): String {
        if (ragContext.isNullOrBlank()) return ""

        val entries = mutableListOf<String>()
        val jsonCredRegex = Regex(""""username"\s*:\s*"([^"]+)"\s*,\s*"password"\s*:\s*"([^"]+)"""")
        val tableRowRegex = Regex("""\|\s*(\w+)\s*\|\s*(\w+)\s*\|\s*(ADMIN)\s*\|""")
        val hostRegex = Regex("""(https?://[^\s/`"]+:\d+)""")

        // 1단계: RAG 청크([N] 패턴) 단위로 분할
        val chunkBoundary = Regex("""(?:^|\n)\[(\d+)\]\s*""")
        val bounds = chunkBoundary.findAll(ragContext).toList()

        val chunks = if (bounds.isEmpty()) {
            listOf(ragContext)
        } else {
            bounds.mapIndexed { i, bound ->
                val start = bound.range.last + 1
                val end = if (i + 1 < bounds.size) bounds[i + 1].range.first else ragContext.length
                ragContext.substring(start, end)
            }
        }

        // 2단계: 각 청크에서 호스트 식별 + credentials 추출
        for (chunk in chunks) {
            // 청크 내 모든 호스트 URL 추출 → 가장 빈번한 것이 이 청크의 시스템
            val chunkHosts = hostRegex.findAll(chunk).map { it.groupValues[1] }.toList()
            val chunkHost = chunkHosts.groupingBy { it }.eachCount()
                .maxByOrNull { it.value }?.key

            // JSON credentials — {"username":"X","password":"Y"}
            for (match in jsonCredRegex.findAll(chunk)) {
                val username = match.groupValues[1]
                val password = match.groupValues[2]
                val entry = if (chunkHost != null) {
                    "- $chunkHost → username: \"$username\", password: \"$password\""
                } else {
                    "- username: \"$username\", password: \"$password\""
                }
                if (entry !in entries) entries.add(entry)
            }

            // 테이블 credentials — | username | password | ADMIN |
            for (match in tableRowRegex.findAll(chunk)) {
                val username = match.groupValues[1]
                val password = match.groupValues[2]
                val role = match.groupValues[3]
                if (username == "username") continue
                val entry = if (chunkHost != null) {
                    "- $chunkHost → username: \"$username\", password: \"$password\" (role: $role)"
                } else {
                    "- username: \"$username\", password: \"$password\" (role: $role)"
                }
                if (entry !in entries) entries.add(entry)
            }
        }

        return if (entries.isEmpty()) "" else entries.joinToString("\n")
    }

    /**
     * LLM 응답을 ApiWorkflowDecision으로 파싱
     */
    private fun parseApiWorkflowDecision(response: String): ApiWorkflowDecision {
        val jsonStr = extractJson(response)

        return try {
            val sanitized = sanitizeJsonEscapes(jsonStr)
            val jsonElement = json.parseToJsonElement(sanitized).jsonObject

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
     *
     * 토큰 자동 주입: LLM이 Authorization 헤더를 빠뜨려도,
     * tokenStore에 해당 host:port의 토큰이 있으면 자동으로 주입한다.
     */
    private fun createApiCallBlueprint(
        decision: ApiWorkflowDecision,
        spec: Spec,
        iteration: Int,
        tokenStore: Map<String, String> = emptyMap()
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

            // 토큰 자동 주입: Authorization 헤더가 없고, tokenStore에 토큰이 있으면 주입
            if (!params.keys.any { it.equals("header:Authorization", ignoreCase = true) }) {
                val hostPort = extractHostPort(call.url)
                if (hostPort != null) {
                    tokenStore[hostPort]?.let { token ->
                        params["header:Authorization"] = "Bearer $token"
                    }
                }
            }

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
     * auth 엔드포인트(POST /auth/login 등)는 쓰기로 간주하지 않음
     */
    private fun hasExecutedWriteOperation(session: ConversationSession): Boolean {
        return session.context.executionHistory.any { turn ->
            turn.blueprint?.steps?.any { step ->
                step.type == BlueprintStepType.API_CALL &&
                    step.params["method"] in WRITE_HTTP_METHODS &&
                    !isAuthEndpoint(step.params["url"] ?: "")
            } ?: false
        }
    }

    /**
     * URL이 인증 엔드포인트인지 확인
     * POST /auth/login 등은 "쓰기 작업"이 아닌 인증 작업으로 분류
     */
    private fun isAuthEndpoint(url: String): Boolean {
        val path = try { java.net.URI.create(url).path } catch (_: Exception) { url }
        return AUTH_PATH_PATTERNS.any { pattern -> path.endsWith(pattern) }
    }

    /**
     * API 실행 결과에서 인증 토큰을 자동 추출하여 세션 tokenStore에 저장한다.
     *
     * 범용 메커니즘: auth 엔드포인트(POST /auth/login 등) 응답 바디에서
     * 공통 토큰 필드(accessToken, token, access_token)를 탐색한다.
     * 특정 백엔드에 종속되지 않는다.
     */
    private fun extractAndStoreTokens(
        session: ConversationSession,
        blueprint: Blueprint,
        result: BlueprintExecutionResult?
    ) {
        if (result == null || !result.isSuccess) return

        for (step in blueprint.steps) {
            if (step.type != BlueprintStepType.API_CALL) continue
            val url = step.params["url"] ?: continue
            if (!isAuthEndpoint(url)) continue

            val hostPort = extractHostPort(url) ?: continue
            val output = result.context.getStepOutput(step.stepId) ?: continue
            val body = output.json["body"]?.jsonPrimitive?.contentOrNull ?: continue

            // 응답 바디에서 토큰 추출 (공통 필드명 탐색)
            val token = extractTokenFromBody(body)
            if (token != null) {
                session.context.tokenStore[hostPort] = token
                println("[AUTH] Token stored for $hostPort (${token.take(20)}...)")
            }
        }
    }

    /**
     * JSON 응답 바디에서 Bearer 토큰을 추출한다.
     * 공통 필드: accessToken, token, access_token, jwt
     */
    private fun extractTokenFromBody(body: String): String? {
        return try {
            val jsonObj = json.parseToJsonElement(body).jsonObject
            TOKEN_FIELD_NAMES.firstNotNullOfOrNull { field ->
                jsonObj[field]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * URL에서 host:port를 추출한다.
     * 예: "http://home.skyepub.net:9090/api/auth/login" → "home.skyepub.net:9090"
     */
    private fun extractHostPort(url: String): String? {
        return try {
            val uri = java.net.URI.create(url)
            val host = uri.host ?: return null
            val port = if (uri.port > 0) uri.port else if (uri.scheme == "https") 443 else 80
            "$host:$port"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 워크스페이스와 DraftSpec으로부터 프로젝트 경로를 유도한다.
     *
     * domain 또는 intent에서 slug를 생성하여 workspace 하위에 프로젝트 디렉토리 경로를 반환.
     * workspace가 null이면 ~/wiiiv_projects 하위에 프로젝트 디렉토리를 생성한다.
     */
    internal fun deriveProjectPath(workspace: String?, draftSpec: DraftSpec): String? {
        val slug = generateSlug(draftSpec.domain ?: draftSpec.intent ?: return null)
        val base = workspace ?: "${System.getProperty("user.home")}/.wiiiv/projects"
        return "$base/$slug"
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

    // === 워크플로우 영구 저장/로드 ===

    /**
     * 워크플로우 실행 후 자동 영구 저장
     */
    private fun persistWorkflow(
        workflow: io.wiiiv.hlx.model.HlxWorkflow,
        workflowJson: String,
        session: ConversationSession,
        userId: String
    ) {
        try {
            workflowStore?.save(
                io.wiiiv.hlx.store.WorkflowRecord(
                    workflowId = workflow.id,
                    name = workflow.name,
                    description = workflow.description,
                    workflowJson = workflowJson,
                    sessionId = session.sessionId,
                    userId = userId,
                    projectId = session.projectId
                )
            )
            session.context.lastExecutedWorkflowId = workflow.id
            println("[WORKFLOW-STORE] Saved: ${workflow.name} (id=${workflow.id})")
        } catch (e: Exception) {
            println("[WORKFLOW-STORE] Failed to save: ${e.message}")
        }
    }

    /**
     * 이름으로 워크플로우 로드 (외부에서 호출 가능)
     */
    fun loadWorkflow(name: String, projectId: Long? = null): io.wiiiv.hlx.store.WorkflowRecord? {
        return workflowStore?.findByName(name, projectId)
    }

    /**
     * 프로젝트별 저장된 워크플로우 목록
     */
    fun listWorkflows(projectId: Long? = null): List<io.wiiiv.hlx.store.WorkflowRecord> {
        return workflowStore?.listByProject(projectId) ?: emptyList()
    }

    // === Pre-LLM 워크플로우 관리 명령 감지/처리 ===

    /**
     * 워크플로우 관리 명령 유형
     */
    enum class WorkflowCommand {
        SAVE, LOAD, LIST, DELETE
    }

    /**
     * 워크플로우 관리 명령 감지 결과
     */
    data class WorkflowCommandMatch(
        val command: WorkflowCommand,
        val name: String? = null
    )

    /**
     * 자연어 메시지에서 워크플로우 관리 명령을 감지한다.
     * LLM 판단 전에 정규식으로 결정론적으로 감지하여 오분류를 방지한다.
     *
     * @return 감지된 명령 또는 null (일반 메시지)
     */
    internal fun detectWorkflowCommand(message: String): WorkflowCommandMatch? {
        val msg = message.trim()
        val lower = msg.lowercase()

        // 생성/구축 의도가 있으면 관리 명령으로 해석하지 않음
        // "워크플로우 만들어줘", "워크플로우를 생성하고 싶어" 등은 WORKFLOW_CREATE이지 관리 명령이 아님
        val creationKeywords = listOf("만들", "생성", "구축", "설정", "세팅", "자동화")
        if (creationKeywords.any { lower.contains(it) }) {
            return null
        }

        // 이름 추출: "이름" 또는 '이름' 패턴
        val namePattern = Regex("""["'"](.+?)["'"]""")
        val extractedName = namePattern.find(msg)?.groupValues?.get(1)

        // 인용부호 안의 텍스트를 제거하여 패턴 매칭 시 이름과 명령어를 혼동하지 않도록 함
        val msgWithoutQuotes = namePattern.replace(msg, " ").lowercase()

        // SAVE 패턴: 워크플로우 저장
        val savePatterns = listOf(
            Regex("""워크플로우.*저장"""),
            Regex("""방금.*(저장|세이브)"""),
            Regex("""save\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
            Regex("""워크플로우.*이름.*(바꿔|변경|지정)"""),
        )
        if (savePatterns.any { it.containsMatchIn(msgWithoutQuotes) }) {
            return WorkflowCommandMatch(WorkflowCommand.SAVE, extractedName)
        }

        // LIST 패턴 (LOAD보다 먼저 — "목록 보여줘"가 LOAD에 매칭되는 것 방지)
        val listPatterns = listOf(
            Regex("""워크플로우.*(목록|리스트|보여줘|조회)"""),
            Regex("""저장.*(워크플로우|workflow).*(목록|리스트|보여줘|조회)"""),
            Regex("""list\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
            Regex("""show\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
        )
        if (listPatterns.any { it.containsMatchIn(msgWithoutQuotes) }) {
            return WorkflowCommandMatch(WorkflowCommand.LIST)
        }

        // DELETE 패턴
        val deletePatterns = listOf(
            Regex("""워크플로우.*(?:삭제|제거|지워)"""),
            Regex("""delete\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
            Regex("""remove\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
        )
        if (deletePatterns.any { it.containsMatchIn(msgWithoutQuotes) }) {
            return WorkflowCommandMatch(WorkflowCommand.DELETE, extractedName)
        }

        // LOAD 패턴
        val loadPatterns = listOf(
            Regex("""워크플로우.*(로드|불러|실행|열어|가져)"""),
            Regex("""load\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
            Regex("""run\s+(?:the\s+)?workflow""", RegexOption.IGNORE_CASE),
        )
        if (loadPatterns.any { it.containsMatchIn(msgWithoutQuotes) }) {
            return WorkflowCommandMatch(WorkflowCommand.LOAD, extractedName)
        }

        return null
    }

    /**
     * 워크플로우 관리 명령을 처리하고 응답을 반환한다.
     * LLM을 거치지 않고 직접 WorkflowStore를 호출한다.
     */
    private suspend fun handleWorkflowCommand(
        cmd: WorkflowCommandMatch,
        session: ConversationSession,
        userId: String
    ): ConversationResponse {
        if (workflowStore == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우 저장소가 설정되지 않았습니다.",
                sessionId = session.sessionId
            )
        }

        return when (cmd.command) {
            WorkflowCommand.SAVE -> handleWorkflowSave(cmd, session, userId)
            WorkflowCommand.LOAD -> handleWorkflowLoad(cmd, session, userId)
            WorkflowCommand.LIST -> handleWorkflowList(session)
            WorkflowCommand.DELETE -> handleWorkflowDelete(cmd, session)
        }
    }

    private suspend fun handleWorkflowSave(
        cmd: WorkflowCommandMatch,
        session: ConversationSession,
        userId: String
    ): ConversationResponse {
        val lastWfId = session.context.lastExecutedWorkflowId
        if (lastWfId == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "저장할 워크플로우가 없습니다. 먼저 워크플로우를 실행해주세요.",
                sessionId = session.sessionId
            )
        }

        val record = workflowStore!!.findById(lastWfId)
        if (record == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우(id=$lastWfId)를 찾을 수 없습니다.",
                sessionId = session.sessionId
            )
        }

        // 이름 변경 요청이면 업데이트
        if (cmd.name != null && cmd.name != record.name) {
            val updated = record.copy(
                name = cmd.name,
                updatedAt = System.currentTimeMillis()
            )
            workflowStore!!.save(updated)
            println("[WORKFLOW-CMD] Renamed: ${record.name} → ${cmd.name}")
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우가 \"${cmd.name}\" 이름으로 저장되었습니다.",
                sessionId = session.sessionId
            )
        }

        // 이미 저장됨
        return ConversationResponse(
            action = ActionType.REPLY,
            message = "워크플로우 \"${record.name}\"이(가) 이미 저장되어 있습니다. (id=${record.workflowId})",
            sessionId = session.sessionId
        )
    }

    private suspend fun handleWorkflowLoad(
        cmd: WorkflowCommandMatch,
        session: ConversationSession,
        userId: String
    ): ConversationResponse {
        if (cmd.name == null) {
            // 이름 없이 로드 요청 → 목록 보여주기
            val workflows = workflowStore!!.listByProject(session.projectId, limit = 10)
            if (workflows.isEmpty()) {
                return ConversationResponse(
                    action = ActionType.REPLY,
                    message = "저장된 워크플로우가 없습니다.",
                    sessionId = session.sessionId
                )
            }
            val list = workflows.mapIndexed { i, w -> "${i + 1}. ${w.name}" }.joinToString("\n")
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "어떤 워크플로우를 로드할까요?\n\n$list",
                sessionId = session.sessionId
            )
        }

        val record = workflowStore!!.findByName(cmd.name, session.projectId)
            ?: return ConversationResponse(
                action = ActionType.REPLY,
                message = "\"${cmd.name}\" 워크플로우를 찾을 수 없습니다.",
                sessionId = session.sessionId
            )

        // HLX Runner로 실행
        if (hlxRunner == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "HLX Runner가 설정되지 않아 워크플로우를 실행할 수 없습니다.",
                sessionId = session.sessionId
            )
        }

        return try {
            emitProgress(ProgressPhase.EXECUTING, "Loading workflow: ${record.name}...")
            val workflow = HlxParser.parse(record.workflowJson)
            val result = hlxRunner!!.run(workflow)
            session.context.lastExecutedWorkflowId = record.workflowId
            emitProgress(ProgressPhase.DONE)

            val statusText = if (result.status == HlxExecutionStatus.COMPLETED) "성공" else "실패"
            ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우 \"${record.name}\" 실행 $statusText. (${result.nodeRecords.size}개 노드, ${result.totalDurationMs}ms)",
                sessionId = session.sessionId
            )
        } catch (e: Exception) {
            emitProgress(ProgressPhase.DONE)
            ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우 \"${record.name}\" 실행 중 오류: ${e.message}",
                sessionId = session.sessionId
            )
        }
    }

    private fun handleWorkflowList(session: ConversationSession): ConversationResponse {
        val workflows = workflowStore!!.listByProject(session.projectId)
        if (workflows.isEmpty()) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "저장된 워크플로우가 없습니다.",
                sessionId = session.sessionId
            )
        }

        val list = workflows.mapIndexed { i, w ->
            val desc = w.description?.let { " — $it" } ?: ""
            "${i + 1}. **${w.name}**$desc (id=${w.workflowId})"
        }.joinToString("\n")

        return ConversationResponse(
            action = ActionType.REPLY,
            message = "저장된 워크플로우 목록:\n\n$list",
            sessionId = session.sessionId
        )
    }

    private fun handleWorkflowDelete(
        cmd: WorkflowCommandMatch,
        session: ConversationSession
    ): ConversationResponse {
        if (cmd.name == null) {
            return ConversationResponse(
                action = ActionType.REPLY,
                message = "삭제할 워크플로우 이름을 지정해주세요. 예: 워크플로우 \"이름\" 삭제해줘",
                sessionId = session.sessionId
            )
        }

        val record = workflowStore!!.findByName(cmd.name, session.projectId)
            ?: return ConversationResponse(
                action = ActionType.REPLY,
                message = "\"${cmd.name}\" 워크플로우를 찾을 수 없습니다.",
                sessionId = session.sessionId
            )

        val deleted = workflowStore!!.delete(record.workflowId)
        return if (deleted) {
            println("[WORKFLOW-CMD] Deleted: ${record.name} (id=${record.workflowId})")
            ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우 \"${record.name}\"이(가) 삭제되었습니다.",
                sessionId = session.sessionId
            )
        } else {
            ConversationResponse(
                action = ActionType.REPLY,
                message = "워크플로우 삭제에 실패했습니다.",
                sessionId = session.sessionId
            )
        }
    }

    /**
     * 복잡한 요청인지 판단 — 인터뷰(ASK)가 필요한 수준의 복잡도
     * 범용 기준: 다중 시스템, 파일 출력 지정, 3개 이상 조건/단계
     */
    private fun isComplexRequest(message: String): Boolean {
        val lower = message.lowercase()
        var complexity = 0
        // 다중 시스템 언급
        val systems = listOf("skymall", "skystock", "skypay")
        if (systems.count { lower.contains(it) } >= 2) complexity += 2
        // 파일 출력 지정
        if (lower.contains(".csv") || lower.contains(".txt") || lower.contains(".html") ||
            lower.contains(".json") || lower.contains(".py") || lower.contains(".kt")) complexity++
        // 다단계 지시 (접속사/순서) — 어간 사용
        val conjunctions = listOf("그리고", "한 뒤", "후에", "다음에", "조합", "결합", "분석")
        complexity += conjunctions.count { lower.contains(it) }
        // 3단어 이상 동사 (복잡한 작업) — 어간 사용
        val actionVerbs = listOf("찾", "확인", "정리", "저장", "만들", "조회", "분류", "출력", "보고서", "리포트")
        complexity += (actionVerbs.count { lower.contains(it) } - 1).coerceAtLeast(0)
        // 의도/바람 표현 (복잡한 요구)
        val intentPatterns = listOf("싶", "필요", "원하", "해야", "하고 싶")
        val hasIntent = intentPatterns.any { lower.contains(it) }
        if (hasIntent) complexity++
        // 분석/통계 + 의도 콤보 — 인터뷰가 필요한 복잡 요청
        val analysisWords = listOf("분석", "현황", "통계", "보고서", "리포트", "추이", "비교", "요약")
        val hasAnalysis = analysisWords.any { lower.contains(it) }
        if (hasAnalysis && hasIntent) complexity++

        return complexity >= 3
    }

    /**
     * 대화 컨텍스트에서 프로젝트 생성 의도를 감지
     * LLM이 specUpdates를 누락했을 때, 대화 내용으로 PROJECT_CREATE를 추론
     */
    private fun isProjectCreationContext(session: ConversationSession, currentMessage: String): Boolean {
        // 현재 메시지 + 최근 대화 이력에서 프로젝트 생성 키워드 탐색
        val allText = buildString {
            // 최근 대화 이력 (최대 10개)
            for (msg in session.history.takeLast(10)) {
                append(msg.content)
                append(" ")
            }
            append(currentMessage)
        }.lowercase()

        // 프로젝트 생성을 암시하는 키워드 조합
        val projectKeywords = listOf("프로젝트", "시스템", "서버", "백엔드", "어플리케이션", "앱")
        val createKeywords = listOf("만들", "생성", "구축", "개발")
        val techKeywords = listOf("spring boot", "kotlin", "java", "react", "python", "ktor",
            "jpa", "gradle", "maven", "포트", "패키지")
        val entityKeywords = listOf("엔티티", "entity", "테이블", "모델", "스키마")
        val apiKeywords = listOf("/api/", "controller", "엔드포인트", "endpoint", "rest api")

        val hasProject = projectKeywords.any { allText.contains(it) }
        val hasCreate = createKeywords.any { allText.contains(it) }
        val hasTech = techKeywords.count { allText.contains(it) } >= 2
        val hasEntity = entityKeywords.any { allText.contains(it) }
        val hasApi = apiKeywords.any { allText.contains(it) }

        // 프로젝트+생성 키워드가 있거나, 기술스택이 2개 이상 + (엔티티 or API 언급)
        return (hasProject && hasCreate) || (hasTech && (hasEntity || hasApi))
    }

    /**
     * 사용자 확인 메시지인지 판단
     */
    private fun isConfirmationMessage(message: String): Boolean {
        val lower = message.trim().lowercase()
        val confirmPatterns = listOf(
            "응", "네", "예", "ㅇㅇ", "ㅇ", "ok", "yes", "진행", "실행",
            "만들어", "해줘", "해", "삭제해", "진행해", "실행해", "시작해"
        )
        // 짧은 메시지: 키워드 포함이면 확인
        // 긴 메시지: 시작/끝 매칭 + 강력 확인 패턴
        val strongConfirmPatterns = listOf(
            "이대로 진행", "이대로 하", "이대로 실행", "이대로 쭉",
            "진행해 주", "진행해주", "실행해 주", "실행해주",
            "그래 그래", "좋아요", "좋습니다",
            "부탁드", "부탁합", "부탁할", "잘 부탁",
        )
        return confirmPatterns.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }
            || (lower.length <= 20 && confirmPatterns.any { lower.contains(it) })
            || strongConfirmPatterns.any { lower.contains(it) }
    }

    /**
     * 사용자가 명시적으로 작업지시서 생성을 요청하는지 판단
     * "작업지시서 만들어", "작업지시서를 완성해줘", "작업지시서 작성해줘" 등
     */
    private fun isWorkOrderRequest(message: String): Boolean {
        val lower = message.lowercase()
        return lower.contains("작업지시서") && listOf(
            "만들", "작성", "완성", "생성", "정리", "시작", "보여"
        ).any { lower.contains(it) }
    }

    /**
     * 현재 인터뷰(ASK-응답) 턴 수를 계산
     * SYSTEM 메시지(DACS 등)를 만나면 이전 대화이므로 중단
     */
    private fun countInterviewTurns(session: ConversationSession): Int {
        var count = 0
        for (msg in session.history.reversed()) {
            if (msg.role == MessageRole.SYSTEM) break
            if (msg.role == MessageRole.USER) count++
        }
        return count
    }

    /**
     * 후속 데이터 요청인지 판단 — 실행 결과를 기반으로 추가 조회/상세/드릴다운 요청
     */
    private fun isFollowUpDataRequest(message: String): Boolean {
        val lower = message.lowercase()
        val followUpPatterns = listOf(
            "상세", "자세", "보여", "알려", "확인", "조회",
            "더 ", "좀 더", "다시", "또 ", "나머지",
            "목록", "리스트", "내용", "데이터",
            "가져", "불러", "검색", "찾아",
            "어떤", "몇 개", "얼마"
        )
        return followUpPatterns.any { lower.contains(it) }
    }

    /**
     * 코드/작업 수정 요청인지 판단
     */
    private fun isModificationRequest(message: String): Boolean {
        val lower = message.lowercase()
        val modifyPatterns = listOf(
            "수정해", "고쳐", "변경해", "바꿔", "추가해", "넣어", "빼",
            "기능도", "도 넣어", "도 추가", "도 만들어",
            "에러 처리", "예외 처리", "타임아웃",
            "fix", "modify", "change", "add", "update", "refactor"
        )
        return modifyPatterns.any { lower.contains(it) }
    }

    companion object {
        /** 쓰기 HTTP 메서드 */
        private val WRITE_HTTP_METHODS = setOf("PUT", "POST", "DELETE", "PATCH")

        /** 인증 엔드포인트 — writeIntent 검사에서 제외 */
        private val AUTH_PATH_PATTERNS = listOf("/auth/login", "/auth/register", "/auth/token", "/auth/refresh")

        /** 응답 바디에서 탐색할 토큰 필드명 (범용) */
        private val TOKEN_FIELD_NAMES = listOf("accessToken", "token", "access_token", "jwt", "id_token")

        /**
         * ConversationalGovernor 생성
         */
        fun create(
            id: String = "gov-conv-${UUID.randomUUID().toString().take(8)}",
            dacs: DACS,
            llmProvider: LlmProvider? = null,
            model: String? = null,
            blueprintRunner: BlueprintRunner? = null,
            ragPipeline: RagPipeline? = null,
            hlxRunner: HlxRunner? = null,
            auditStore: AuditStore? = null,
            workflowStore: io.wiiiv.hlx.store.WorkflowStore? = null
        ): ConversationalGovernor {
            return ConversationalGovernor(id, dacs, llmProvider, model, blueprintRunner, ragPipeline, hlxRunner, auditStore, workflowStore)
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

