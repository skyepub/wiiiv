package io.wiiiv.plugins.pdf

import com.github.mustachejava.DefaultMustacheFactory
import java.io.File
import java.io.StringReader
import java.io.StringWriter

/**
 * Mustache 템플릿 엔진 래핑
 *
 * HTML 템플릿 + JSON 데이터 → 렌더링된 HTML 문자열
 */
object TemplateEngine {

    private val factory = DefaultMustacheFactory()

    /**
     * 인라인 HTML 템플릿 렌더링
     *
     * @param template HTML 템플릿 문자열 (Mustache 구문)
     * @param data JSON 데이터 (Map 형태)
     * @return 렌더링된 HTML 문자열
     */
    fun render(template: String, data: Map<String, Any?>): String {
        val mustache = factory.compile(StringReader(template), "inline")
        val writer = StringWriter()
        mustache.execute(writer, data)
        writer.flush()
        return writer.toString()
    }

    /**
     * 파일 기반 HTML 템플릿 렌더링
     *
     * @param templatePath 템플릿 파일 경로
     * @param data JSON 데이터 (Map 형태)
     * @return 렌더링된 HTML 문자열
     */
    fun renderFile(templatePath: String, data: Map<String, Any?>): String {
        val file = File(templatePath)
        if (!file.exists()) throw IllegalArgumentException("Template file not found: $templatePath")
        return render(file.readText(Charsets.UTF_8), data)
    }

    /**
     * JSON 문자열을 Map으로 변환 (Mustache 바인딩용)
     */
    fun jsonToMap(json: String): Map<String, Any?> {
        val element = kotlinx.serialization.json.Json.parseToJsonElement(json)
        return jsonElementToMap(element)
    }

    private fun jsonElementToMap(element: kotlinx.serialization.json.JsonElement): Map<String, Any?> {
        return when (element) {
            is kotlinx.serialization.json.JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
            else -> emptyMap()
        }
    }

    private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? {
        return when (element) {
            is kotlinx.serialization.json.JsonNull -> null
            is kotlinx.serialization.json.JsonPrimitive -> {
                if (element.isString) element.content
                else element.content.toDoubleOrNull() ?: element.content.toBooleanStrictOrNull() ?: element.content
            }
            is kotlinx.serialization.json.JsonArray -> element.map { jsonElementToAny(it) }
            is kotlinx.serialization.json.JsonObject -> element.mapValues { (_, v) -> jsonElementToAny(v) }
        }
    }
}
