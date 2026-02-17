package io.wiiiv.execution

import kotlinx.serialization.Serializable

/**
 * Step Type - 실행 단계 유형
 *
 * Canonical: Executor Interface Spec v1.0 §2.1
 */
enum class StepType {
    /**
     * 코드 생성
     */
    CODE_GENERATION,

    /**
     * 파일 작업 (읽기, 쓰기, 복사, 이동, 삭제)
     */
    FILE_OPERATION,

    /**
     * LLM 호출
     *
     * Canonical: Executor 정의서 v1.0 §6
     * LLM 호출은 "실행"으로 허용되지만, "판단"은 금지된다.
     * Executor는 LLM을 도구처럼 호출하고, 결과를 그대로 반환한다.
     */
    LLM_CALL,

    /**
     * API 호출 (HTTP)
     */
    API_CALL,

    /**
     * 사용자 상호작용
     *
     * Canonical: Executor Interface Spec v1.0 §2.3
     * v1.0에서는 예약된 타입이며, 사용되지 않는다.
     * Blueprint에 포함 시 CONTRACT_VIOLATION.
     */
    USER_INTERACTION,

    /**
     * 셸 명령 실행
     */
    COMMAND,

    /**
     * 데이터베이스 작업 (쿼리, 변경, DDL)
     */
    DB_OPERATION,

    /**
     * 멀티모달 처리 (이미지, 문서)
     */
    MULTIMODAL,

    /**
     * WebSocket 통신
     */
    WEBSOCKET,

    /**
     * 메시지 큐 (Kafka, RabbitMQ 등)
     */
    MESSAGE_QUEUE,

    /**
     * gRPC 호출
     */
    GRPC,

    /**
     * RAG (Retrieval-Augmented Generation)
     *
     * 문서 수집, 임베딩, 벡터 검색
     */
    RAG
}

/**
 * Execution Step - 실행 단계
 *
 * Canonical: Executor Interface Spec v1.0 §2
 *
 * Blueprint의 step을 Executor가 소비할 수 있는 형태로 표현한다.
 * 각 step은 고유한 stepId를 가지며, 특정 StepType에 해당한다.
 */
@Serializable
sealed class ExecutionStep {
    /**
     * Step의 고유 식별자
     */
    abstract val stepId: String

    /**
     * Step의 유형
     */
    abstract val type: StepType

    /**
     * 추가 파라미터
     */
    abstract val params: Map<String, String>

    /**
     * File Operation Step
     *
     * Canonical: Executor Interface Spec v1.0 §2.2
     */
    @Serializable
    data class FileStep(
        override val stepId: String,
        val action: FileAction,
        val path: String,
        val content: String? = null,
        val targetPath: String? = null,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.FILE_OPERATION
    }

    /**
     * Command Step
     *
     * Canonical: Executor Interface Spec v1.0 §2.2
     */
    @Serializable
    data class CommandStep(
        override val stepId: String,
        val command: String,
        val args: List<String> = emptyList(),
        val workingDir: String? = null,
        val env: Map<String, String> = emptyMap(),
        val timeoutMs: Long = 60_000,
        val stdin: String? = null,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.COMMAND
    }

    /**
     * LLM Call Step
     *
     * Canonical: Executor Interface Spec v1.0 §2.2, Executor 정의서 v1.0 §6
     */
    @Serializable
    data class LlmCallStep(
        override val stepId: String,
        val action: LlmAction,
        val prompt: String,
        val model: String? = null,
        val maxTokens: Int? = null,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.LLM_CALL
    }

    /**
     * Database Operation Step
     *
     * Canonical: Executor Interface Spec v1.0 §2.2
     */
    @Serializable
    data class DbStep(
        override val stepId: String,
        val sql: String,
        val mode: DbMode,
        val connectionId: String? = null,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.DB_OPERATION
    }

    /**
     * API Call Step
     *
     * Canonical: Executor Interface Spec v1.0 §2.2
     */
    @Serializable
    data class ApiCallStep(
        override val stepId: String,
        val method: HttpMethod,
        val url: String,
        val headers: Map<String, String> = emptyMap(),
        val body: String? = null,
        val timeoutMs: Long = 60_000,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.API_CALL
    }

    /**
     * Multimodal Step
     *
     * 이미지, 문서, 오디오 등 멀티모달 데이터 처리
     */
    @Serializable
    data class MultimodalStep(
        override val stepId: String,
        val action: MultimodalAction,
        val inputPath: String,
        val prompt: String? = null,
        val outputFormat: String? = null,
        val providerId: String? = null,
        val timeoutMs: Long = 60_000,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.MULTIMODAL
    }

    /**
     * WebSocket Step
     *
     * WebSocket 연결 및 메시지 송수신
     */
    @Serializable
    data class WebSocketStep(
        override val stepId: String,
        val action: WebSocketAction,
        val url: String,
        val message: String? = null,
        val timeoutMs: Long = 30_000,
        val headers: Map<String, String> = emptyMap(),
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.WEBSOCKET
    }

    /**
     * Message Queue Step
     *
     * 메시지 큐 송수신 (Kafka, RabbitMQ 등)
     */
    @Serializable
    data class MessageQueueStep(
        override val stepId: String,
        val action: MessageQueueAction,
        val topic: String,
        val message: String? = null,
        val brokerId: String? = null,
        val timeoutMs: Long = 30_000,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.MESSAGE_QUEUE
    }

    /**
     * gRPC Step
     *
     * gRPC 서비스 호출
     */
    @Serializable
    data class GrpcStep(
        override val stepId: String,
        val action: GrpcAction,
        val target: String,
        val service: String,
        val method: String,
        val request: String? = null,
        val metadata: Map<String, String> = emptyMap(),
        val timeoutMs: Long = 30_000,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.GRPC
    }

    /**
     * Noop Step - 테스트/검증용
     */
    @Serializable
    data class NoopStep(
        override val stepId: String,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.CODE_GENERATION  // 임시
    }

    /**
     * RAG Step
     *
     * RAG (Retrieval-Augmented Generation) 작업
     * - INGEST: 문서 수집 및 임베딩
     * - SEARCH: 유사도 검색
     * - DELETE: 문서 삭제
     */
    @Serializable
    data class RagStep(
        override val stepId: String,
        val action: RagAction,
        val content: String? = null,
        val query: String? = null,
        val documentId: String? = null,
        val title: String? = null,
        val topK: Int = 5,
        val minScore: Float = 0.0f,
        override val params: Map<String, String> = emptyMap()
    ) : ExecutionStep() {
        override val type: StepType = StepType.RAG
    }
}

/**
 * File Action
 */
enum class FileAction {
    READ, WRITE, COPY, MOVE, DELETE, MKDIR
}

/**
 * LLM Action
 */
enum class LlmAction {
    COMPLETE, ANALYZE, SUMMARIZE
}

/**
 * Database Mode
 */
enum class DbMode {
    QUERY, MUTATION, DDL
}

/**
 * HTTP Method
 */
enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH
}

/**
 * WebSocket Action
 */
enum class WebSocketAction {
    /** 연결 후 메시지 송신 */
    SEND,
    /** 연결 후 메시지 수신 대기 */
    RECEIVE,
    /** 연결 후 메시지 송신하고 응답 수신 */
    SEND_RECEIVE
}

/**
 * Message Queue Action
 */
enum class MessageQueueAction {
    /** 메시지 발행 */
    PUBLISH,
    /** 메시지 구독 (단일) */
    CONSUME,
    /** 메시지 발행 후 응답 대기 */
    REQUEST_REPLY
}

/**
 * gRPC Action
 */
enum class GrpcAction {
    /** 단항 호출 (Unary) */
    UNARY,
    /** 서버 스트리밍 */
    SERVER_STREAMING,
    /** 클라이언트 스트리밍 */
    CLIENT_STREAMING,
    /** 양방향 스트리밍 */
    BIDIRECTIONAL_STREAMING
}

/**
 * Multimodal Action
 */
enum class MultimodalAction {
    /** 이미지 분석 (설명, 객체 탐지) */
    ANALYZE_IMAGE,
    /** 이미지에서 텍스트 추출 (OCR) */
    EXTRACT_TEXT,
    /** 문서 파싱 (PDF, DOCX 등) */
    PARSE_DOCUMENT,
    /** 오디오 전사 */
    TRANSCRIBE_AUDIO,
    /** 비전 기반 질의응답 */
    VISION_QA
}

/**
 * RAG Action
 */
enum class RagAction {
    /** 문서 수집 및 임베딩 */
    INGEST,
    /** 유사도 검색 */
    SEARCH,
    /** 문서 삭제 */
    DELETE,
    /** 벡터 저장소 초기화 */
    CLEAR,
    /** 저장소 크기 조회 */
    SIZE
}
