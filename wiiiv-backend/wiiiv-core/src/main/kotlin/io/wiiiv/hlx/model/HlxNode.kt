package io.wiiiv.hlx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HLX Node Type - 5가지 노드 타입
 *
 * Canonical: HLX Standard v1.0 §2
 */
@Serializable
enum class HlxNodeType {
    @SerialName("observe")
    OBSERVE,

    @SerialName("transform")
    TRANSFORM,

    @SerialName("decide")
    DECIDE,

    @SerialName("act")
    ACT,

    @SerialName("repeat")
    REPEAT,

    @SerialName("subworkflow")
    SUBWORKFLOW
}

/**
 * Transform Hint - Transform 노드의 의도 힌트
 *
 * Canonical: HLX Standard v1.0 §2.3
 */
@Serializable
enum class TransformHint {
    @SerialName("filter")
    FILTER,

    @SerialName("map")
    MAP,

    @SerialName("normalize")
    NORMALIZE,

    @SerialName("summarize")
    SUMMARIZE,

    @SerialName("extract")
    EXTRACT,

    @SerialName("merge")
    MERGE
}

/**
 * Determinism Level - 판단 결정론 수준
 *
 * Canonical: HLX Standard v1.0 §3.1
 */
@Serializable
enum class DeterminismLevel {
    @SerialName("high")
    HIGH,

    @SerialName("medium")
    MEDIUM,

    @SerialName("low")
    LOW
}

/**
 * HLX Node - 5가지 노드 타입의 sealed class
 *
 * Canonical: HLX Standard v1.0 §2, §3
 *
 * 인간 사고의 최소 완전 집합:
 * - Observe: 인지 (외부에서 정보를 얻음)
 * - Transform: 이해 (데이터를 해석/가공)
 * - Decide: 판단 (흐름 선택)
 * - Act: 행동 (외부에 영향)
 * - Repeat: 반복 (여러 대상에 동일 구조 적용)
 */
@Serializable(with = HlxNodeSerializer::class)
sealed class HlxNode {
    abstract val id: String
    abstract val type: HlxNodeType
    abstract val description: String
    abstract val input: String?
    abstract val output: String?
    abstract val onError: String?
    abstract val aiRequired: Boolean
    abstract val determinismLevel: DeterminismLevel?

    /**
     * Observe - 외부 세계에서 정보를 얻는다
     *
     * Canonical: HLX Standard v1.0 §2.2
     * 읽기 전용 (외부 상태 변경 없음)
     */
    @Serializable
    data class Observe(
        override val id: String,
        override val description: String,
        val target: String? = null,
        override val input: String? = null,
        override val output: String? = null,
        override val onError: String? = null,
        override val aiRequired: Boolean = true,
        override val determinismLevel: DeterminismLevel? = null,
        override val type: HlxNodeType = HlxNodeType.OBSERVE
    ) : HlxNode()

    /**
     * Transform - 데이터를 해석/가공/정규화한다
     *
     * Canonical: HLX Standard v1.0 §2.3
     * 외부와 상호작용하지 않음 (데이터 내부 처리)
     */
    @Serializable
    data class Transform(
        override val id: String,
        override val description: String,
        val hint: TransformHint? = null,
        override val input: String? = null,
        override val output: String? = null,
        override val onError: String? = null,
        override val aiRequired: Boolean = true,
        override val determinismLevel: DeterminismLevel? = null,
        override val type: HlxNodeType = HlxNodeType.TRANSFORM
    ) : HlxNode()

    /**
     * Decide - 상황에 따라 다음 흐름을 선택한다
     *
     * Canonical: HLX Standard v1.0 §2.4
     * 순서는 고정, 경로는 유동
     */
    @Serializable
    data class Decide(
        override val id: String,
        override val description: String,
        val branches: Map<String, String> = emptyMap(),
        override val input: String? = null,
        override val output: String? = null,
        override val onError: String? = null,
        override val aiRequired: Boolean = true,
        override val determinismLevel: DeterminismLevel? = null,
        override val type: HlxNodeType = HlxNodeType.DECIDE
    ) : HlxNode()

    /**
     * Act - 외부 세계에 영향을 준다
     *
     * Canonical: HLX Standard v1.0 §2.5
     * 외부 상태를 변경한다 (쓰기)
     */
    @Serializable
    data class Act(
        override val id: String,
        override val description: String,
        val target: String? = null,
        override val input: String? = null,
        override val output: String? = null,
        override val onError: String? = null,
        override val aiRequired: Boolean = true,
        override val determinismLevel: DeterminismLevel? = null,
        override val type: HlxNodeType = HlxNodeType.ACT
    ) : HlxNode()

    /**
     * Repeat - 여러 대상에 동일 구조를 적용한다
     *
     * Canonical: HLX Standard v1.0 §2.6
     * body 안에 모든 노드 타입을 중첩할 수 있다
     */
    @Serializable
    data class Repeat(
        override val id: String,
        override val description: String,
        val over: String? = null,
        @SerialName("as") val asVar: String? = null,
        val body: List<HlxNode> = emptyList(),
        override val input: String? = null,
        override val output: String? = null,
        override val onError: String? = null,
        override val aiRequired: Boolean = true,
        override val determinismLevel: DeterminismLevel? = null,
        override val type: HlxNodeType = HlxNodeType.REPEAT
    ) : HlxNode()

    /**
     * SubWorkflow - 다른 등록된 워크플로우를 호출한다
     *
     * HLX 모듈화: 워크플로우 안에서 다른 워크플로우를 서브 워크플로우로 호출.
     * 자식 워크플로우는 독립 context에서 실행되며,
     * inputMapping/outputMapping으로 명시적 변수 전달.
     */
    @Serializable
    data class SubWorkflow(
        override val id: String,
        override val description: String,
        val workflowRef: String,
        val inputMapping: Map<String, String> = emptyMap(),
        val outputMapping: Map<String, String> = emptyMap(),
        override val input: String? = null,
        override val output: String? = null,
        override val onError: String? = null,
        override val aiRequired: Boolean = false,
        override val determinismLevel: DeterminismLevel? = null,
        override val type: HlxNodeType = HlxNodeType.SUBWORKFLOW
    ) : HlxNode()
}
