package io.wiiiv.hlx.parser

import io.wiiiv.hlx.model.HlxWorkflow
import io.wiiiv.hlx.validation.HlxValidator
import io.wiiiv.hlx.validation.HlxValidationError
import kotlinx.serialization.json.Json

/**
 * HLX Parse Result - 파싱 + 검증 결과
 */
sealed class HlxParseResult {
    data class Success(val workflow: HlxWorkflow) : HlxParseResult()
    data class ParseError(val message: String, val cause: Throwable? = null) : HlxParseResult()
    data class ValidationError(val errors: List<HlxValidationError>) : HlxParseResult()
}

/**
 * HLX Parser - JSON 파서
 *
 * .hlx JSON 파일을 HlxWorkflow 모델로 파싱한다.
 */
object HlxParser {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        coerceInputValues = true
        encodeDefaults = true
    }

    /**
     * JSON 문자열을 HlxWorkflow로 파싱
     *
     * @throws kotlinx.serialization.SerializationException 파싱 실패 시
     * @throws IllegalArgumentException 알 수 없는 노드 타입
     */
    fun parse(jsonString: String): HlxWorkflow {
        return json.decodeFromString(HlxWorkflow.serializer(), jsonString)
    }

    /**
     * JSON 문자열을 HlxWorkflow로 파싱 (실패 시 null)
     */
    fun parseOrNull(jsonString: String): HlxWorkflow? {
        return try {
            parse(jsonString)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * JSON 문자열을 파싱하고 구조 검증까지 수행
     *
     * @return Success, ParseError, 또는 ValidationError
     */
    fun parseAndValidate(jsonString: String): HlxParseResult {
        val workflow = try {
            parse(jsonString)
        } catch (e: Exception) {
            return HlxParseResult.ParseError(
                message = e.message ?: "Unknown parse error",
                cause = e
            )
        }

        val errors = HlxValidator.validate(workflow)
        return if (errors.isEmpty()) {
            HlxParseResult.Success(workflow)
        } else {
            HlxParseResult.ValidationError(errors)
        }
    }

    /**
     * HlxWorkflow를 JSON 문자열로 변환
     */
    fun toJson(workflow: HlxWorkflow): String {
        return json.encodeToString(HlxWorkflow.serializer(), workflow)
    }
}
