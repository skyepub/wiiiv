package io.wiiiv.hlx.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HLX Node Serializer - 커스텀 다형성 직렬화
 *
 * JSON "type" 필드 값으로 서브클래스를 선택한다.
 */
object HlxNodeSerializer : JsonContentPolymorphicSerializer<HlxNode>(HlxNode::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<HlxNode> =
        when (element.jsonObject["type"]?.jsonPrimitive?.content) {
            "observe" -> HlxNode.Observe.serializer()
            "transform" -> HlxNode.Transform.serializer()
            "decide" -> HlxNode.Decide.serializer()
            "act" -> HlxNode.Act.serializer()
            "repeat" -> HlxNode.Repeat.serializer()
            else -> throw IllegalArgumentException(
                "Unknown HLX node type: ${element.jsonObject["type"]}"
            )
        }
}
