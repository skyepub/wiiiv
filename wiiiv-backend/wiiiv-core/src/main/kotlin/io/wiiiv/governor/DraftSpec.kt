package io.wiiiv.governor

import io.wiiiv.blueprint.Blueprint
import io.wiiiv.blueprint.BlueprintExecutionResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * DraftSpec - 대화 중 수집되는 Spec 초안
 *
 * ConversationalGovernor가 사용자와 대화하며 점진적으로 채워나간다.
 * MissingSlots가 없으면 Spec이 완성된 것으로 판단.
 */
@Serializable
data class DraftSpec(
    /**
     * 초안 ID
     */
    val id: String = "draft-${UUID.randomUUID().toString().take(8)}",

    /**
     * 사용자의 원래 의도 (자연어)
     */
    val intent: String? = null,

    /**
     * 작업 유형 분류
     */
    val taskType: TaskType? = null,

    /**
     * 도메인/컨텍스트 (예: "대학교 학점 관리", "쇼핑몰 백엔드")
     */
    val domain: String? = null,

    /**
     * 기술 스택 (예: ["Kotlin", "JPA", "PostgreSQL"])
     */
    val techStack: List<String>? = null,

    /**
     * 대상 경로 (파일/디렉토리 작업 시)
     */
    val targetPath: String? = null,

    /**
     * 작업 내용 (쓰기/수정 작업 시)
     */
    val content: String? = null,

    /**
     * 규모/범위 (예: "5000명", "10개 API")
     */
    val scale: String? = null,

    /**
     * 추가 제약 조건
     */
    val constraints: List<String>? = null,

    /**
     * 작업지시서 내용 (CONFIRM 단계에서 LLM이 생성한 풍부한 마크다운)
     * PROJECT_CREATE 시 대화 내역 + DraftSpec을 기반으로 생성되며,
     * 이후 코드 생성 LLM에 전달되어 정확한 프로젝트를 만든다.
     */
    val workOrderContent: String? = null,

    /**
     * 사용자가 명시적으로 확인한 항목들
     */
    val confirmedSlots: Set<String> = emptySet()
) {
    /**
     * 작업 유형에 따른 필수 슬롯 정의
     */
    fun getRequiredSlots(): Set<String> = when (taskType) {
        TaskType.FILE_READ -> setOf("targetPath")
        TaskType.FILE_WRITE -> setOf("targetPath", "content")
        TaskType.FILE_DELETE -> setOf("targetPath")
        TaskType.COMMAND -> setOf("content") // command string
        TaskType.PROJECT_CREATE -> setOf("domain", "techStack", "scale")
        TaskType.INFORMATION -> emptySet() // 정보 질문은 필수 없음
        TaskType.CONVERSATION -> emptySet() // 일반 대화는 필수 없음
        TaskType.WORKFLOW_MANAGE -> emptySet() // 워크플로우 관리는 Pre-LLM 인터셉터가 처리
        TaskType.WORKFLOW_CREATE -> setOf("domain", "scale") // 복합 워크플로우: 목적 + 상세 흐름
        TaskType.API_WORKFLOW -> setOf("intent", "domain") // API 워크플로우
        TaskType.DB_QUERY -> setOf("intent", "domain") // DB 조회 (domain = DB명)
        null -> setOf("intent", "taskType") // 아직 유형 미정
    }

    /**
     * 현재 채워진 슬롯들
     */
    fun getFilledSlots(): Set<String> = buildSet {
        if (!intent.isNullOrBlank()) add("intent")
        if (taskType != null) add("taskType")
        if (!domain.isNullOrBlank()) add("domain")
        if (!techStack.isNullOrEmpty()) add("techStack")
        if (!targetPath.isNullOrBlank()) add("targetPath")
        if (!content.isNullOrBlank()) add("content")
        if (!scale.isNullOrBlank()) add("scale")
        if (!constraints.isNullOrEmpty()) add("constraints")
    }

    /**
     * 누락된 슬롯들 (아직 수집이 필요한 정보)
     */
    fun getMissingSlots(): Set<String> = getRequiredSlots() - getFilledSlots()

    /**
     * Spec이 완성되었는지 (실행 가능한지)
     */
    fun isComplete(): Boolean = getMissingSlots().isEmpty() && taskType != null

    /**
     * 실행이 필요한 작업인지 (CONVERSATION/INFORMATION이 아닌)
     */
    fun requiresExecution(): Boolean = when (taskType) {
        TaskType.CONVERSATION, TaskType.INFORMATION, null -> false
        else -> true // FILE_*, COMMAND, PROJECT_CREATE, API_WORKFLOW
    }

    /**
     * 위험한 작업인지 (DACS 필수)
     */
    fun isRisky(): Boolean = when (taskType) {
        TaskType.FILE_DELETE -> true
        TaskType.COMMAND -> {
            // COMMAND: 위험 패턴 포함 시만 risky
            // echo, ls, cat, pwd 등 안전 명령은 DACS 불필요
            content?.let { isDangerousCommand(it) } ?: true
        }
        TaskType.PROJECT_CREATE -> {
            // PROJECT_CREATE: 시스템 경로일 때만 위험
            // workspace 하위, /tmp, 상대 경로 등은 안전
            targetPath?.let { isSystemPath(it) } ?: false
        }
        TaskType.WORKFLOW_CREATE -> false  // 워크플로우 생성은 작업지시서 기반 — DACS 불필요
        TaskType.API_WORKFLOW -> false  // API 호출은 외부 서비스 읽기 — DACS 불필요
        TaskType.DB_QUERY -> false  // DB 조회는 GateChain이 통제 — DACS 불필요
        else -> {
            // 특정 경로 패턴만 위험 (시스템 경로)
            targetPath?.let { isSystemPath(it) } ?: false
        }
    }

    private fun isSystemPath(path: String): Boolean =
        path.contains("/etc") ||
        path.contains("/system") ||
        path.contains("/root") ||
        path.contains("/usr") ||
        path.contains("/**") ||
        path.contains("C:\\Windows") ||
        path.contains("C:\\Program")

    private fun isDangerousCommand(cmd: String): Boolean {
        val lower = cmd.lowercase().trim()

        // 파괴적 명령 패턴
        val dangerousPatterns = listOf(
            "rm ", "rm\t", "rmdir", "rm -",
            "kill ", "kill\t", "pkill", "killall",
            "mkfs", "dd ", "dd\t",
            "shutdown", "reboot", "halt", "poweroff",
            "chmod 777", "chown ",
            "> /dev/", ">/dev/",
            "format ", "fdisk",
            ":(){", "fork bomb",
            "wget ", "curl.*|.*sh", "curl.*|.*bash"
        )

        // 민감 파일/디렉토리 접근 패턴
        val sensitivePathPatterns = listOf(
            "/etc/passwd", "/etc/shadow", "/etc/sudoers", "/etc/gshadow",
            "/etc/ssl/private", "/etc/ssh/",
            "/.ssh/", "/id_rsa", "/id_ed25519", "/id_ecdsa",
            "/authorized_keys",
            "/root/.", // /root/.bashrc, /root/.ssh 등
            "/proc/", "/sys/",
            ".env", "credentials", "secret"
        )

        // 1. 전체 문자열에서 민감 파일 접근 검사
        if (sensitivePathPatterns.any { lower.contains(it) }) return true

        // 2. 명령 체인(;, &&, ||)으로 분리하여 각 서브 명령 개별 검사
        val subCommands = lower.split(Regex("""\s*;\s*|\s*&&\s*|\s*\|\|\s*"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        return subCommands.any { sub ->
            dangerousPatterns.any { pattern ->
                if (pattern.contains(".*")) Regex(pattern).containsMatchIn(sub)
                else sub.contains(pattern)
            }
        }
    }

    /**
     * 완성된 Spec으로 변환
     */
    fun toSpec(): Spec {
        // WorkOrder가 있으면 isComplete 체크 우회 — 작업지시서가 이미 사용자에게 확인되었으므로
        // LLM이 specUpdates에서 domain/techStack을 누락해도 WorkOrder에 모든 정보가 포함되어 있음
        if (workOrderContent == null) {
            require(isComplete()) { "DraftSpec is not complete. Missing: ${getMissingSlots()}" }
        }

        val operations = when (taskType) {
            TaskType.FILE_READ -> listOf(RequestType.FILE_READ)
            TaskType.FILE_WRITE -> listOf(RequestType.FILE_WRITE)
            TaskType.FILE_DELETE -> listOf(RequestType.FILE_DELETE)
            TaskType.COMMAND -> listOf(RequestType.COMMAND)
            TaskType.PROJECT_CREATE -> listOf(
                RequestType.FILE_MKDIR,
                RequestType.FILE_WRITE
            )
            TaskType.API_WORKFLOW -> listOf(RequestType.CUSTOM)
            TaskType.DB_QUERY -> listOf(RequestType.CUSTOM)
            else -> emptyList()
        }

        val paths = when (taskType) {
            TaskType.API_WORKFLOW -> {
                // API 워크플로우: domain을 논리적 경로 스코프로 사용
                listOfNotNull(domain?.let { "api://$it" } ?: "api://external")
            }
            TaskType.DB_QUERY -> {
                // DB 조회: domain을 DB 스코프로 사용
                listOfNotNull(domain?.let { "db://$it" } ?: "db://default")
            }
            else -> listOfNotNull(targetPath)
        }

        return Spec(
            id = "spec-${UUID.randomUUID().toString().take(8)}",
            name = intent?.take(50) ?: "Unnamed Spec",
            description = buildDescription(),
            intent = intent ?: "",
            allowedOperations = operations,
            allowedPaths = paths,
            constraints = constraints?.associateWith { "required" } ?: emptyMap()
        )
    }

    /**
     * 설명 문자열 생성
     */
    private fun buildDescription(): String = buildString {
        intent?.let { append(it) }
        if (taskType == TaskType.API_WORKFLOW) {
            domain?.let { append(" - API Workflow: $it domain") }
            append(" - Operation: External API call (read-only HTTP request)")
        } else if (taskType == TaskType.DB_QUERY) {
            domain?.let { append(" - DB Query: $it database") }
            append(" - Operation: Database query (governed by GateChain)")
        } else {
            domain?.let { append(" - Domain: $it") }
        }
        techStack?.let { append(" - Tech: ${it.joinToString(", ")}") }
        scale?.let { append(" - Scale: $it") }
    }

    /**
     * 요약 문자열 (확인용)
     */
    fun summarize(): String = workOrderContent ?: buildString {
        appendLine("작업: ${taskType?.displayName ?: "미정"}")
        intent?.let { appendLine("의도: $it") }
        domain?.let { appendLine("도메인: $it") }
        techStack?.let { appendLine("기술: ${it.joinToString(", ")}") }
        targetPath?.let { appendLine("경로: $it") }
        scale?.let { appendLine("규모: $it") }
        constraints?.let { appendLine("제약: ${it.joinToString(", ")}") }
    }

    companion object {
        /**
         * 빈 DraftSpec 생성
         */
        fun empty() = DraftSpec()

        /**
         * intent로부터 초기 DraftSpec 생성
         */
        fun fromIntent(intent: String) = DraftSpec(intent = intent)
    }
}

/**
 * 작업 유형 분류
 */
@Serializable
enum class TaskType(val displayName: String) {
    /** 파일 읽기 */
    FILE_READ("파일 읽기"),

    /** 파일 쓰기/생성 */
    FILE_WRITE("파일 쓰기"),

    /** 파일/디렉토리 삭제 */
    FILE_DELETE("파일 삭제"),

    /** 명령어 실행 */
    COMMAND("명령어 실행"),

    /** 프로젝트 생성 (복잡한 작업) */
    PROJECT_CREATE("프로젝트 생성"),

    /** @deprecated CONVERSATION으로 통합. 하위 호환용으로 유지. */
    INFORMATION("일반 대화"),

    /** 일반 대화 (실행 불필요) */
    CONVERSATION("일반 대화"),

    /** API 워크플로우 (반복적 API 호출) */
    API_WORKFLOW("API 워크플로우"),

    /** 데이터베이스 조회/실행 (Phase E: Governed HLX) */
    DB_QUERY("데이터베이스 조회"),

    /** 워크플로우 관리 (저장/로드/목록/삭제) — Pre-LLM 인터셉터로 처리 */
    WORKFLOW_MANAGE("워크플로우 관리"),

    /** 복합 워크플로우 생성 (인터뷰 → 작업지시서 → HLX 생성 → 실행) */
    WORKFLOW_CREATE("워크플로우 생성")
}

/**
 * Governor 응답 - LLM이 반환하는 구조
 */
@Serializable
data class GovernorAction(
    /**
     * 행동 유형
     */
    val action: ActionType,

    /**
     * 사용자에게 보낼 메시지
     */
    val message: String,

    /**
     * Spec 업데이트 (있으면 DraftSpec에 병합)
     */
    val specUpdates: Map<String, JsonElement>? = null,

    /**
     * 다음에 물어볼 슬롯 (ASK 시)
     */
    val askingFor: String? = null,

    /**
     * 작업 전환 신호 (이전 작업의 라벨/ID)
     */
    val taskSwitch: String? = null
)

/**
 * 행동 유형
 */
@Serializable
enum class ActionType {
    /** 직접 응답 (대화/지식) */
    REPLY,

    /** 추가 정보 요청 (인터뷰) */
    ASK,

    /** 확인 요청 */
    CONFIRM,

    /** 실행 진행 */
    EXECUTE,

    /** 취소/중단 */
    CANCEL
}

/**
 * Route Mode - EXECUTE 시 실행 경로 판단
 *
 * Canonical: Executor Governance Spec v1.0 §5
 *
 * executeTurn() 진입 시 결정되며, "이번 요청을 어떻게 실행할 것인가"를 나타낸다.
 * 판단 기준은 Executor의 메타가 아닌 "이번 요청의 행위"이다.
 *
 * ## 판단 우선순위
 *
 * 1. 결정론적 강제 규칙 (무조건)
 * 2. LLM routeHint (참고 — Phase B2에서 도입)
 * 3. 기본값 = HLX (보수적)
 */
enum class RouteMode {
    /** 단일 Executor 직접 실행 (READ 중심, 무부작용, 단일 스텝) */
    DIRECT,
    /** HLX 워크플로우 실행 (복합/쓰기/다중 Executor/분기/반복) */
    HLX
}

/**
 * 대화 메시지
 */
@Serializable
data class ConversationMessage(
    val role: MessageRole,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)

@Serializable
enum class MessageRole {
    USER,
    GOVERNOR,
    SYSTEM
}

/**
 * Task ID 타입 별칭
 */
typealias TaskId = String

/**
 * 작업 상태
 */
enum class TaskStatus {
    /** 현재 활성 작업 */
    ACTIVE,
    /** 일시 중단된 작업 */
    SUSPENDED,
    /** 완료된 작업 */
    COMPLETED
}

/**
 * 작업별 컨텍스트 - 하나의 TaskSlot이 보유하는 실행 이력 및 아티팩트
 */
data class TaskContext(
    val executionHistory: MutableList<TurnExecution> = mutableListOf(),
    val artifacts: MutableMap<String, String> = mutableMapOf(),
    val facts: MutableMap<String, String> = mutableMapOf()
) {
    fun clear() {
        executionHistory.clear()
        artifacts.clear()
        facts.clear()
    }
}

/**
 * TaskSlot - 하나의 작업을 담는 슬롯 ("책상 위의 폴더")
 *
 * 각 작업은 자신의 DraftSpec, 실행 이력, 아티팩트를 보유한다.
 */
data class TaskSlot(
    val id: TaskId,
    var label: String,
    var draftSpec: DraftSpec = DraftSpec.empty(),
    val context: TaskContext = TaskContext(),
    var status: TaskStatus = TaskStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 세션 컨텍스트 - 턴 간 실행 결과와 아티팩트를 보존
 *
 * Phase 6: 프록시 패턴 - executionHistory는 activeTask가 있으면 해당 task의 history에 위임,
 * 없으면 _fallbackHistory에 저장. Phase 5의 모든 호출 지점이 수정 없이 동작한다.
 */
class SessionContext(
    val tasks: MutableMap<TaskId, TaskSlot> = mutableMapOf(),
    var activeTaskId: TaskId? = null,
    private val _fallbackHistory: MutableList<TurnExecution> = mutableListOf(),
    val artifacts: MutableMap<String, String> = mutableMapOf(),
    val facts: MutableMap<String, String> = mutableMapOf(),
    var pendingAction: PendingAction? = null,
    var declaredWriteIntent: Boolean? = null,
    var workspace: String? = null,
    /** 시스템별 Bearer 토큰 저장소 — key: "host:port", value: token */
    val tokenStore: MutableMap<String, String> = mutableMapOf(),
    /** 마지막으로 실행/저장된 워크플로우 ID — "방금 워크플로우 저장해줘" 등에 활용 */
    var lastExecutedWorkflowId: String? = null
) {
    /**
     * 현재 활성 작업
     */
    val activeTask: TaskSlot? get() = activeTaskId?.let { tasks[it] }

    /**
     * 프록시: activeTask가 있으면 그 task의 executionHistory, 없으면 fallback
     */
    val executionHistory: MutableList<TurnExecution>
        get() = activeTask?.context?.executionHistory ?: _fallbackHistory

    /**
     * 세션 전체에서 실행이 있었는지 (완료된 task 포함)
     * activeTask가 null이어도 이전 완료 task의 실행 이력을 포함
     */
    fun hasAnyExecution(): Boolean =
        _fallbackHistory.isNotEmpty()
            || tasks.values.any { it.context.executionHistory.isNotEmpty() }

    /**
     * 세션의 모든 태스크 + fallback 이력을 시간순으로 수집
     *
     * 크로스턴 컨텍스트용: 이전 완료된 태스크의 실행 결과도 포함
     */
    fun allExecutionHistory(): List<TurnExecution> {
        val all = mutableListOf<TurnExecution>()
        all.addAll(_fallbackHistory)
        for (task in tasks.values) {
            all.addAll(task.context.executionHistory)
        }
        return all.sortedBy { it.timestamp }
    }

    /**
     * fallback history를 새 TaskSlot으로 마이그레이션하고 등록
     */
    fun promoteToTask(task: TaskSlot) {
        if (_fallbackHistory.isNotEmpty()) {
            task.context.executionHistory.addAll(_fallbackHistory)
            _fallbackHistory.clear()
        }
        tasks[task.id] = task
        activeTaskId = task.id
    }

    fun clear() {
        tasks.clear()
        activeTaskId = null
        _fallbackHistory.clear()
        artifacts.clear()
        facts.clear()
        pendingAction = null
        declaredWriteIntent = null
        tokenStore.clear()
        lastExecutedWorkflowId = null
        // workspace is intentionally preserved — it's an environment setting, not task state
    }
}

/**
 * 턴 실행 기록 - 하나의 턴에서 수행된 실행 결과
 */
data class TurnExecution(
    val turnIndex: Int,
    val blueprint: Blueprint?,
    val result: BlueprintExecutionResult?,
    val summary: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 보류 중인 행동 - 다음 턴에서 처리할 액션
 */
sealed class PendingAction {
    data class ContinueExecution(val reasoning: String, val ragContext: String? = null) : PendingAction()
    data class NeedsConfirmation(val description: String, val blueprint: Blueprint) : PendingAction()
}

/**
 * 다음 행동 힌트 - caller가 루프를 제어하기 위한 신호
 */
enum class NextAction {
    CONTINUE_EXECUTION,
    AWAIT_USER,
    NEEDS_CONFIRMATION
}

/**
 * 대화 세션
 *
 * Phase 6: class로 전환 - draftSpec이 activeTask에 위임되는 프록시 패턴.
 * activeTask가 없으면 _fallbackDraftSpec 사용 (하위 호환).
 */
class ConversationSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val history: MutableList<ConversationMessage> = mutableListOf(),
    @Volatile private var _fallbackDraftSpec: DraftSpec = DraftSpec.empty(),
    var confirmed: Boolean = false,
    val context: SessionContext = SessionContext(),
    val createdAt: Long = System.currentTimeMillis(),
    var projectId: Long? = null          // F-4: 프로젝트 스코핑
) {
    /**
     * 프록시: activeTask가 있으면 그 task의 draftSpec, 없으면 fallback
     */
    var draftSpec: DraftSpec
        get() = context.activeTask?.draftSpec ?: _fallbackDraftSpec
        set(value) {
            val task = context.activeTask
            if (task != null) {
                task.draftSpec = value
            } else {
                _fallbackDraftSpec = value
            }
        }

    /**
     * PROJECT_CREATE DraftSpec 스냅샷.
     * updateSpec에서 PROJECT_CREATE 타입의 의미있는 spec이 만들어질 때마다 자동 저장.
     * DraftSpec이 원인 불명으로 비워졌을 때 복원에 사용.
     */
    @Volatile
    private var _projectSpecSnapshot: DraftSpec? = null

    /** 마지막 사용자 입력 — Audit 기록용 */
    var lastUserInput: String? = null
        private set

    fun addUserMessage(content: String) {
        history.add(ConversationMessage(MessageRole.USER, content))
        lastUserInput = content
    }

    fun addGovernorMessage(content: String) {
        history.add(ConversationMessage(MessageRole.GOVERNOR, content))
    }

    fun getRecentHistory(count: Int = 10): List<ConversationMessage> {
        return history.takeLast(count)
    }

    fun updateSpec(updates: Map<String, JsonElement>) {
        draftSpec = applyUpdates(draftSpec, updates)
        // PROJECT_CREATE spec 스냅샷 자동 저장
        val current = draftSpec
        if (current.taskType == TaskType.PROJECT_CREATE && current.intent != null) {
            _projectSpecSnapshot = current
        }
    }

    /**
     * DraftSpec이 비어있을 때 스냅샷에서 복원 시도.
     * @return 복원 성공 시 true
     */
    fun restoreSpecIfLost(): Boolean {
        val current = draftSpec
        if (current.taskType != null || current.intent != null) return false // 비어있지 않음
        val snapshot = _projectSpecSnapshot ?: return false
        // 스냅샷이 있고 현재 spec이 비어있으면 복원
        draftSpec = snapshot
        return true
    }

    private fun applyUpdates(spec: DraftSpec, updates: Map<String, JsonElement>): DraftSpec {
        var updated = spec

        fun JsonElement.asPrimitiveOrNull(): String? = try {
            jsonPrimitive.contentOrNull
        } catch (_: IllegalArgumentException) {
            null
        }

        fun JsonElement.asArrayOrNull(): List<String>? = try {
            jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull }
        } catch (_: IllegalArgumentException) {
            try {
                jsonPrimitive.contentOrNull?.let { listOf(it) }
            } catch (_: IllegalArgumentException) {
                null
            }
        }

        updates["intent"]?.asPrimitiveOrNull()?.let {
            updated = updated.copy(intent = it)
        }
        updates["taskType"]?.asPrimitiveOrNull()?.let { typeName ->
            try {
                updated = updated.copy(taskType = TaskType.valueOf(typeName))
            } catch (_: Exception) {}
        }
        updates["domain"]?.asPrimitiveOrNull()?.let {
            updated = updated.copy(domain = it)
        }
        updates["techStack"]?.asArrayOrNull()?.let { arr ->
            updated = updated.copy(techStack = arr)
        }
        updates["targetPath"]?.asPrimitiveOrNull()?.let {
            updated = updated.copy(targetPath = it)
        }
        updates["content"]?.asPrimitiveOrNull()?.let {
            updated = updated.copy(content = it)
        }
        updates["scale"]?.asPrimitiveOrNull()?.let {
            updated = updated.copy(scale = it)
        }
        updates["constraints"]?.asArrayOrNull()?.let { arr ->
            updated = updated.copy(constraints = arr)
        }

        return updated
    }

    /**
     * 현재 활성 작업 확정 (없으면 생성)
     *
     * fallback 상태의 draftSpec/executionHistory를 새 TaskSlot으로 승격시킨다.
     */
    fun ensureActiveTask(label: String = draftSpec.intent ?: "작업"): TaskSlot {
        context.activeTask?.let { return it }

        val taskId = "task-${UUID.randomUUID().toString().take(8)}"
        val task = TaskSlot(
            id = taskId,
            label = label,
            draftSpec = _fallbackDraftSpec
        )
        println("[SPEC-TRACE] ensureActiveTask: promoting fallback → task $taskId (intent=${_fallbackDraftSpec.intent?.take(20)}, taskType=${_fallbackDraftSpec.taskType})")
        context.promoteToTask(task)
        _fallbackDraftSpec = DraftSpec.empty()
        return task
    }

    /**
     * 현재 작업을 SUSPENDED TaskSlot으로 보존
     *
     * - activeTask가 있으면 SUSPENDED로 전환
     * - activeTask가 없고 fallback DraftSpec에 의미 있는 내용이 있으면 TaskSlot으로 승격 후 SUSPENDED
     * - 보존할 내용이 없으면 null 반환
     *
     * @return 보존된 TaskSlot, 또는 null
     */
    fun suspendCurrentWork(): TaskSlot? {
        if (context.activeTask != null) {
            val task = context.activeTask!!
            println("[SPEC-TRACE] suspendCurrentWork: suspending active task ${task.id} (intent=${task.draftSpec.intent?.take(20)}, taskType=${task.draftSpec.taskType})")
            task.status = TaskStatus.SUSPENDED
            context.activeTaskId = null
            return task
        }

        val spec = draftSpec
        val intent = spec.intent
        if (intent != null) {
            val task = ensureActiveTask(intent)
            task.status = TaskStatus.SUSPENDED
            context.activeTaskId = null
            return task
        }

        return null
    }

    /**
     * Spec만 초기화 (context 보존) - 작업 완료 후 다음 작업 대기
     */
    fun resetSpec() {
        val activeTask = context.activeTask
        if (activeTask != null) {
            println("[SPEC-TRACE] resetSpec: clearing active task ${activeTask.id} spec (was: taskType=${activeTask.draftSpec.taskType})")
            activeTask.draftSpec = DraftSpec.empty()
        } else {
            println("[SPEC-TRACE] resetSpec: clearing fallback spec (was: taskType=${_fallbackDraftSpec.taskType}, intent=${_fallbackDraftSpec.intent?.take(20)})")
            _fallbackDraftSpec = DraftSpec.empty()
        }
        confirmed = false
    }

    /**
     * 현재 작업만 취소 (다른 SUSPENDED 작업은 보존)
     *
     * - activeTask가 있으면 tasks에서 제거하고 activeTaskId를 null로
     * - activeTask가 없으면 fallback DraftSpec만 초기화
     * - SUSPENDED/COMPLETED 작업은 그대로 보존
     */
    fun cancelCurrentTask() {
        val activeTask = context.activeTask
        if (activeTask != null) {
            context.tasks.remove(activeTask.id)
            context.activeTaskId = null
        }
        _fallbackDraftSpec = DraftSpec.empty()
        confirmed = false
        // 컨텍스트 경계 마커 — LLM이 이전 작업 맥락과 새 요청을 구분하도록
        history.add(ConversationMessage(
            MessageRole.SYSTEM,
            "--- 이전 작업이 취소되었습니다. 새 요청을 독립적으로 처리하세요. ---"
        ))
    }

    /**
     * 전체 초기화 (context 포함) - 세션 완전 리셋
     */
    fun resetAll() {
        _fallbackDraftSpec = DraftSpec.empty()
        confirmed = false
        context.clear()
    }

    /**
     * 하위 호환: 기존 reset()은 resetSpec()과 동일
     */
    fun reset() {
        resetSpec()
    }

    /**
     * 실행 결과를 context에 적재
     */
    fun integrateResult(blueprint: Blueprint?, result: BlueprintExecutionResult?, summary: String) {
        val turnIndex = context.executionHistory.size + 1
        context.executionHistory.add(
            TurnExecution(
                turnIndex = turnIndex,
                blueprint = blueprint,
                result = result,
                summary = summary
            )
        )
    }
}
