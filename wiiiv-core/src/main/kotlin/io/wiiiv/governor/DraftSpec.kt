package io.wiiiv.governor

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
        TaskType.PROJECT_CREATE -> setOf("domain", "techStack")
        TaskType.INFORMATION -> emptySet() // 정보 질문은 필수 없음
        TaskType.CONVERSATION -> emptySet() // 일반 대화는 필수 없음
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
        else -> true
    }

    /**
     * 위험한 작업인지 (DACS 필수)
     */
    fun isRisky(): Boolean = when (taskType) {
        TaskType.FILE_DELETE -> true
        TaskType.COMMAND -> true
        TaskType.PROJECT_CREATE -> true
        else -> {
            // 특정 경로 패턴도 위험
            targetPath?.let { path ->
                path.contains("/etc") ||
                path.contains("/system") ||
                path.contains("/**") ||
                path.startsWith("/")
            } ?: false
        }
    }

    /**
     * 완성된 Spec으로 변환
     */
    fun toSpec(): Spec {
        require(isComplete()) { "DraftSpec is not complete. Missing: ${getMissingSlots()}" }

        val operations = when (taskType) {
            TaskType.FILE_READ -> listOf(RequestType.FILE_READ)
            TaskType.FILE_WRITE -> listOf(RequestType.FILE_WRITE)
            TaskType.FILE_DELETE -> listOf(RequestType.FILE_DELETE)
            TaskType.COMMAND -> listOf(RequestType.COMMAND)
            TaskType.PROJECT_CREATE -> listOf(
                RequestType.FILE_MKDIR,
                RequestType.FILE_WRITE
            )
            else -> emptyList()
        }

        val paths = listOfNotNull(targetPath)

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
        domain?.let { append(" - Domain: $it") }
        techStack?.let { append(" - Tech: ${it.joinToString(", ")}") }
        scale?.let { append(" - Scale: $it") }
    }

    /**
     * 요약 문자열 (확인용)
     */
    fun summarize(): String = buildString {
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

    /** 정보 질문 (검색/API 호출 필요) */
    INFORMATION("정보 조회"),

    /** 일반 대화 (실행 불필요) */
    CONVERSATION("일반 대화")
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
    val askingFor: String? = null
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
 * 대화 세션
 */
data class ConversationSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val history: MutableList<ConversationMessage> = mutableListOf(),
    var draftSpec: DraftSpec = DraftSpec.empty(),
    var confirmed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun addUserMessage(content: String) {
        history.add(ConversationMessage(MessageRole.USER, content))
    }

    fun addGovernorMessage(content: String) {
        history.add(ConversationMessage(MessageRole.GOVERNOR, content))
    }

    fun getRecentHistory(count: Int = 10): List<ConversationMessage> {
        return history.takeLast(count)
    }

    fun updateSpec(updates: Map<String, JsonElement>) {
        draftSpec = applyUpdates(draftSpec, updates)
    }

    private fun applyUpdates(spec: DraftSpec, updates: Map<String, JsonElement>): DraftSpec {
        var updated = spec

        updates["intent"]?.jsonPrimitive?.contentOrNull?.let {
            updated = updated.copy(intent = it)
        }
        updates["taskType"]?.jsonPrimitive?.contentOrNull?.let { typeName ->
            try {
                updated = updated.copy(taskType = TaskType.valueOf(typeName))
            } catch (_: Exception) {}
        }
        updates["domain"]?.jsonPrimitive?.contentOrNull?.let {
            updated = updated.copy(domain = it)
        }
        updates["techStack"]?.jsonArray?.let { arr ->
            updated = updated.copy(techStack = arr.mapNotNull { it.jsonPrimitive.contentOrNull })
        }
        updates["targetPath"]?.jsonPrimitive?.contentOrNull?.let {
            updated = updated.copy(targetPath = it)
        }
        updates["content"]?.jsonPrimitive?.contentOrNull?.let {
            updated = updated.copy(content = it)
        }
        updates["scale"]?.jsonPrimitive?.contentOrNull?.let {
            updated = updated.copy(scale = it)
        }
        updates["constraints"]?.jsonArray?.let { arr ->
            updated = updated.copy(constraints = arr.mapNotNull { it.jsonPrimitive.contentOrNull })
        }

        return updated
    }

    fun reset() {
        draftSpec = DraftSpec.empty()
        confirmed = false
    }
}
