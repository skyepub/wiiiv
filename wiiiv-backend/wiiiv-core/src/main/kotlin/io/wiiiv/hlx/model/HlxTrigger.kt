package io.wiiiv.hlx.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HLX Trigger Type
 *
 * Canonical: HLX Standard v1.0 §5.3
 */
@Serializable
enum class HlxTriggerType {
    @SerialName("manual")
    MANUAL,

    @SerialName("schedule")
    SCHEDULE,

    @SerialName("webhook")
    WEBHOOK,

    @SerialName("event")
    EVENT
}

/**
 * HLX Trigger - 워크플로우 트리거 모델
 *
 * Canonical: HLX Standard v1.0 §5.3
 *
 * Trigger는 워크플로우 내부 노드가 아니라 메타데이터 레벨에서 정의한다.
 */
@Serializable
data class HlxTrigger(
    val type: HlxTriggerType = HlxTriggerType.MANUAL,
    val schedule: String? = null,
    val webhook: String? = null
)
