package io.wiiiv.hlx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HLX Workflow - 최상위 워크플로우 구조
 *
 * Canonical: HLX Standard v1.0 §5.2
 *
 * .hlx 파일의 루트 구조. JSON 데이터 포맷으로 정의되며,
 * 인간이 읽고 LLM이 실행하는 오픈 표준 워크플로우이다.
 *
 * @property schema JSON Schema URL (선택)
 * @property version HLX 표준 버전
 * @property id 워크플로우 고유 식별자
 * @property name 워크플로우 이름
 * @property description 워크플로우 설명
 * @property trigger 트리거 정보
 * @property nodes 실행 노드 목록
 */
@Serializable
data class HlxWorkflow(
    @SerialName("\$schema") val schema: String? = null,
    val version: String = "1.0",
    val id: String,
    val name: String,
    val description: String,
    val trigger: HlxTrigger = HlxTrigger(),
    val nodes: List<HlxNode>
)
