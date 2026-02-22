package io.wiiiv.hlx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * HLX Status - 실행 상태
 *
 * Canonical: HLX Standard v1.0 §4.3
 */
@Serializable
enum class HlxStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("running")
    RUNNING,

    @SerialName("success")
    SUCCESS,

    @SerialName("failed")
    FAILED,

    @SerialName("waiting")
    WAITING,

    @SerialName("suspended")
    SUSPENDED
}

/**
 * HLX Node Result - 노드 실행 결과
 *
 * Canonical: HLX Standard v1.0 §4.1
 */
@Serializable
data class HlxNodeResult(
    val status: HlxStatus = HlxStatus.PENDING,
    val durationMs: Long? = null,
    val output: JsonElement? = null
)

/**
 * HLX Meta - 실행 메타데이터
 *
 * Canonical: HLX Standard v1.0 §4.1
 */
@Serializable
data class HlxMeta(
    val workflowId: String? = null,
    val startedAt: String? = null,
    val currentNode: String? = null,
    val status: HlxStatus = HlxStatus.PENDING,
    val nodeResults: MutableMap<String, HlxNodeResult> = mutableMapOf()
)

/**
 * HLX Iteration - 반복 실행 상태
 *
 * Canonical: HLX Standard v1.0 §4.1
 */
@Serializable
data class HlxIteration(
    val index: Int = 0,
    val total: Int = 0,
    val currentItem: String? = null
)

/**
 * HLX Context - 실행 컨텍스트
 *
 * Canonical: HLX Standard v1.0 §4
 *
 * .hlx는 선언형 구조이고, context는 실행 시점 상태이다.
 * Phase 2에서 HlxRunner가 이 컨텍스트를 사용하여 노드를 실행한다.
 *
 * @property variables 워크플로우 변수 (노드 간 데이터 전달)
 * @property meta 실행 메타데이터
 * @property iteration 반복 실행 상태 (Repeat 노드 내부)
 * @property userId 실행 사용자 ID (Phase D: 거버넌스)
 * @property role 사용자 역할 (Phase D: Role 기반 정책)
 * @property ragContext RAG에서 조회된 API 스펙 컨텍스트 (BUG-003: ACT 노드에 전달)
 */
@Serializable
data class HlxContext(
    val variables: MutableMap<String, JsonElement> = mutableMapOf(),
    val meta: HlxMeta = HlxMeta(),
    val iteration: HlxIteration? = null,
    val userId: String? = null,
    val role: String? = null,
    val ragContext: String? = null
)
