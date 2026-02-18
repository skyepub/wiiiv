package io.wiiiv.hlx.runner

import io.wiiiv.hlx.model.HlxContext
import io.wiiiv.hlx.model.HlxNode
import io.wiiiv.hlx.model.TransformHint
import kotlinx.serialization.json.*

/**
 * Code-based EXTRACT Transform — LLM 호출 없이 JSON 필드를 추출한다.
 *
 * Phase 1: hint=EXTRACT이거나 description에 "extract"/"Parse ... as JSON"이 있는
 * Transform 노드를 코드로 처리한다.
 *
 * 처리 케이스:
 * 1. "extract the {field}" → body 파싱 후 필드 추출
 * 2. "Parse the body ... as JSON" (extract 없음) → body 파싱 자체가 목적
 * 3. body 파싱 결과가 배열이면 그대로 반환 (content 필드 없는 API)
 */
object CodeExtractor {

    private val STOP_WORDS = setOf(
        "the", "a", "an", "all", "each", "every",
        "response", "result", "data", "output", "value",
        "json", "body", "field", "object", "array",
        "from", "this", "that", "it"
    )

    private val FIELD_PATTERNS = listOf(
        // "extract the content array" / "extract the accessToken field"
        Regex("""extract\s+the\s+(\w+)\s+(?:array|field|value|object)""", RegexOption.IGNORE_CASE),
        // "extract the accessToken"
        Regex("""extract\s+the\s+(\w+)""", RegexOption.IGNORE_CASE),
        // 한국어
        Regex("""(\w+)를\s*추출"""),
        Regex("""(\w+)을\s*추출"""),
        Regex("""(\w+)\s+추출""")
    )

    /** "Parse the body field ... as JSON" 패턴 (extract 없이 body 파싱만) */
    private val BODY_PARSE_PATTERN =
        Regex("""parse\s+the\s+body\b.*\bas\s+json""", RegexOption.IGNORE_CASE)

    /**
     * EXTRACT Transform 노드를 코드로 실행 시도한다.
     *
     * @return 추출 성공 시 JsonElement, 실패 시 null (LLM 폴백 필요)
     */
    fun tryExtract(node: HlxNode.Transform, context: HlxContext): JsonElement? {
        val inputKey = node.input ?: return null
        val sourceData = context.variables[inputKey] ?: return null

        // 1. description에서 타겟 필드명 추출 시도
        val fieldName = extractFieldName(node.description)

        if (fieldName != null) {
            // 필드명이 있으면 해당 필드 추출
            return extractFromSource(sourceData, fieldName)
        }

        // 2. "Parse the body ... as JSON" 패턴 — body 파싱 자체가 목적
        if (BODY_PARSE_PATTERN.containsMatchIn(node.description)) {
            return parseBody(sourceData)
        }

        return null
    }

    /**
     * description 문자열에서 타겟 필드명을 추출한다.
     */
    internal fun extractFieldName(description: String): String? {
        for (pattern in FIELD_PATTERNS) {
            val match = pattern.find(description)
            if (match != null) {
                val candidate = match.groupValues[1]
                if (candidate.lowercase() !in STOP_WORDS) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * 소스 데이터에서 필드를 추출한다.
     * HTTP 응답 래퍼(body 문자열) 자동 처리.
     * body 파싱 결과가 배열이고 필드를 찾을 수 없으면 배열 자체를 반환.
     */
    private fun extractFromSource(source: JsonElement, fieldName: String): JsonElement? {
        val resolved = resolveBody(source)

        // 해석된 데이터가 JsonObject면 필드 추출
        if (resolved is JsonObject) {
            // 직접 매칭
            resolved[fieldName]?.let { return it }
            // case-insensitive 매칭
            for ((key, value) in resolved) {
                if (key.equals(fieldName, ignoreCase = true)) {
                    return value
                }
            }
        }

        // 배열이면 필드 추출 불가 → 배열 자체 반환
        // (description은 "extract the content array"이지만 API가 직접 배열 반환하는 경우)
        if (resolved is JsonArray) {
            return resolved
        }

        return null
    }

    /**
     * 소스 데이터의 body를 파싱해서 반환한다.
     * "Parse the body ... as JSON" 케이스 — 필드 추출 없이 파싱 자체가 목적.
     */
    private fun parseBody(source: JsonElement): JsonElement? {
        return resolveBody(source)
    }

    /**
     * HTTP 응답 래퍼에서 body를 파싱하여 실제 데이터를 추출한다.
     *
     * 처리 순서:
     * 1. JsonObject + body가 문자열 → body 파싱
     * 2. JsonObject + body가 이미 JsonElement → body 반환
     * 3. 문자열 → JSON 파싱
     * 4. 그 외 → 원본 반환
     */
    private fun resolveBody(element: JsonElement): JsonElement? {
        if (element is JsonObject) {
            val body = element["body"]
            if (body != null) {
                // body가 문자열이면 JSON 파싱
                if (body is JsonPrimitive && body.isString) {
                    return try {
                        Json.parseToJsonElement(body.content)
                    } catch (_: Exception) {
                        // JSON이 아닌 문자열 body
                        body
                    }
                }
                // body가 이미 구조화된 JSON
                return body
            }
            // body 필드 없으면 원본 객체 자체 사용
            return element
        }

        // 문자열이면 JSON 파싱 시도
        if (element is JsonPrimitive && element.isString) {
            return try {
                Json.parseToJsonElement(element.content)
            } catch (_: Exception) {
                null
            }
        }

        // 배열 등은 그대로
        return element
    }

    // ==========================================================
    // Phase 2: AGGREGATE / SORT / FILTER / MAP 코드 변환
    // ==========================================================

    /** Aggregate 패턴: "Aggregate by {groupField} summing {sumField}" */
    private val AGGREGATE_PATTERN =
        Regex("""aggregate\s+by\s+(\w+)\s+summing\s+(\w+)""", RegexOption.IGNORE_CASE)

    /** Sort 패턴: "Sort by {field} (ascending|descending)?" */
    private val SORT_PATTERN =
        Regex("""sort\s+by\s+(\w+)(?:\s+(ascending|descending))?""", RegexOption.IGNORE_CASE)

    /** Filter 패턴: "Filter where {field} = {value}" / "Filter where {field} > {value}" 등 */
    private val FILTER_PATTERN =
        Regex("""filter\s+where\s+(\w+)\s*(=|!=|>|<|>=|<=)\s*(.+)""", RegexOption.IGNORE_CASE)

    /** Map 패턴: "Select {field1}, {field2}, ..." — 쉼표로 구분된 식별자만 매칭 */
    private val MAP_PATTERN =
        Regex("""select\s+(\w+(?:\s*,\s*\w+)+)""", RegexOption.IGNORE_CASE)

    /**
     * AGGREGATE/SORT/FILTER/MAP Transform 노드를 코드로 실행 시도한다.
     *
     * hint 기반 라우팅 우선, 없으면 description 감지.
     * 전체 try-catch → null 폴백 (LLM이 처리).
     *
     * @return 성공 시 JsonElement, 실패 시 null (LLM 폴백)
     */
    fun tryCompute(node: HlxNode.Transform, context: HlxContext): JsonElement? {
        return try {
            val inputKey = node.input ?: return null
            val sourceData = context.variables[inputKey] ?: return null
            val items = flattenToArray(sourceData) ?: return null
            val desc = node.description

            // hint 기반 라우팅
            when (node.hint) {
                TransformHint.AGGREGATE -> return tryAggregate(items, desc)
                TransformHint.SORT -> return trySort(items, desc)
                TransformHint.FILTER -> return tryFilter(items, desc)
                TransformHint.MAP -> return tryMap(items, desc)
                else -> {}
            }

            // hint 없으면 description 키워드 감지
            val descLower = desc.lowercase()
            when {
                descLower.contains("aggregate") -> tryAggregate(items, desc)
                descLower.contains("sort") -> trySort(items, desc)
                descLower.contains("filter where") -> tryFilter(items, desc)
                MAP_PATTERN.containsMatchIn(desc) -> tryMap(items, desc)
                else -> null
            }
        } catch (e: Exception) {
            println("[HLX-CODE] tryCompute failed for '${node.id}': ${e.message}")
            null
        }
    }

    /**
     * 입력 데이터를 JsonArray로 flatten한다.
     *
     * - 이미 JsonArray → 그대로
     * - JsonObject + body(문자열) → 파싱 후 배열 추출
     * - 2D 배열(Repeat body 결과) → flatten
     */
    internal fun flattenToArray(source: JsonElement): JsonArray? {
        val resolved = resolveBody(source) ?: return null

        if (resolved is JsonArray) {
            // 2D 배열 감지: [[...], [...], ...] → flatten
            if (resolved.isNotEmpty() && resolved.all { it is JsonArray }) {
                val flattened = resolved.flatMap { (it as JsonArray).toList() }
                return JsonArray(flattened)
            }
            return resolved
        }

        // JsonObject에서 배열 필드 탐색 (content, items, data, results)
        if (resolved is JsonObject) {
            for (key in listOf("content", "items", "data", "results")) {
                val arr = resolved[key]
                if (arr is JsonArray) return arr
            }
        }

        return null
    }

    /**
     * Aggregate: "Aggregate by {groupField} summing {sumField}"
     *
     * groupBy + sum. 출력 필드: total + capitalize(sumField) → totalQuantity
     * 그룹 첫 아이템의 문자열 필드 보존 (productName 등).
     */
    private fun tryAggregate(items: JsonArray, description: String): JsonElement? {
        val match = AGGREGATE_PATTERN.find(description) ?: return null
        val groupField = match.groupValues[1]
        val sumField = match.groupValues[2]
        val totalFieldName = "total${sumField.replaceFirstChar { it.uppercase() }}"

        // groupField가 최상위에 없으면 중첩 배열 자동 탐색
        val workItems = resolveNestedItems(items, groupField)

        val groups = mutableMapOf<String, MutableList<JsonObject>>()
        for (item in workItems) {
            if (item !is JsonObject) continue
            val key = item[groupField]?.let { extractPrimitiveString(it) } ?: "unknown"
            groups.getOrPut(key) { mutableListOf() }.add(item)
        }

        val result = groups.map { (_, groupItems) ->
            val sum = groupItems.sumOf { obj ->
                obj[sumField]?.jsonPrimitive?.doubleOrNull ?: 0.0
            }
            val first = groupItems.first()
            buildJsonObject {
                // 그룹 첫 아이템의 문자열 필드 보존
                for ((key, value) in first) {
                    if (key == sumField) continue
                    if (value is JsonPrimitive && value.isString) {
                        put(key, value)
                    } else if (value is JsonPrimitive && (value.intOrNull != null || value.longOrNull != null || value.doubleOrNull != null)) {
                        // 숫자 필드도 보존 (ID 등)
                        put(key, value)
                    }
                }
                // 합산 필드
                if (sum == sum.toLong().toDouble()) {
                    put(totalFieldName, JsonPrimitive(sum.toLong()))
                } else {
                    put(totalFieldName, JsonPrimitive(sum))
                }
            }
        }

        println("[HLX-CODE] Aggregate: ${workItems.size} items → ${result.size} groups by '$groupField' summing '$sumField'")
        return JsonArray(result)
    }

    /**
     * 중첩 배열 자동 탐색.
     *
     * targetField가 최상위 아이템에 없으면,
     * 각 아이템의 배열 필드(items, orderItems, details 등)를 탐색하여 flatten한다.
     * 예: orders[].items[].productId → items[]를 flatten
     */
    private fun resolveNestedItems(items: JsonArray, targetField: String): JsonArray {
        if (items.isEmpty()) return items

        // 첫 아이템에 targetField가 있으면 그대로 사용
        val first = items.firstOrNull()
        if (first is JsonObject && first.containsKey(targetField)) {
            return items
        }

        // 중첩 배열 필드 탐색
        val flattened = mutableListOf<JsonElement>()
        for (item in items) {
            if (item !is JsonObject) continue
            for ((_, value) in item) {
                if (value is JsonArray && value.isNotEmpty()) {
                    val innerFirst = value.firstOrNull()
                    if (innerFirst is JsonObject && innerFirst.containsKey(targetField)) {
                        flattened.addAll(value)
                    }
                }
            }
        }

        if (flattened.isNotEmpty()) {
            println("[HLX-CODE] Resolved nested items: ${items.size} parents → ${flattened.size} child items (field: '$targetField')")
            return JsonArray(flattened)
        }

        return items
    }

    /**
     * Sort: "Sort by {field} (ascending|descending)?"
     *
     * 숫자 기준 정렬. 기본 내림차순.
     */
    private fun trySort(items: JsonArray, description: String): JsonElement? {
        val match = SORT_PATTERN.find(description) ?: return null
        val sortField = match.groupValues[1]
        val direction = match.groupValues[2].ifEmpty { "descending" }
        val ascending = direction.equals("ascending", ignoreCase = true)

        val sorted = items.sortedWith(Comparator { a, b ->
            val aVal = (a as? JsonObject)?.get(sortField)?.jsonPrimitive?.doubleOrNull ?: 0.0
            val bVal = (b as? JsonObject)?.get(sortField)?.jsonPrimitive?.doubleOrNull ?: 0.0
            if (ascending) aVal.compareTo(bVal) else bVal.compareTo(aVal)
        })

        println("[HLX-CODE] Sort: ${items.size} items by '$sortField' $direction")
        return JsonArray(sorted)
    }

    /**
     * Filter: "Filter where {field} = {value}" / "> {value}" / "< {value}" 등
     */
    private fun tryFilter(items: JsonArray, description: String): JsonElement? {
        val match = FILTER_PATTERN.find(description) ?: return null
        val field = match.groupValues[1]
        val operator = match.groupValues[2]
        val rawValue = match.groupValues[3].trim().removeSurrounding("\"").removeSurrounding("'")

        val filtered = items.filter { item ->
            if (item !is JsonObject) return@filter false
            val fieldValue = item[field] ?: return@filter false
            compareField(fieldValue, operator, rawValue)
        }

        println("[HLX-CODE] Filter: ${items.size} items → ${filtered.size} where '$field' $operator '$rawValue'")
        return JsonArray(filtered)
    }

    /**
     * Map: "Select {field1}, {field2}, ..."
     *
     * 필드 프로젝션.
     */
    private fun tryMap(items: JsonArray, description: String): JsonElement? {
        val match = MAP_PATTERN.find(description) ?: return null
        val fields = match.groupValues[1].split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (fields.isEmpty()) return null

        val projected = items.map { item ->
            if (item !is JsonObject) return@map item
            buildJsonObject {
                for (f in fields) {
                    item[f]?.let { put(f, it) }
                }
            }
        }

        println("[HLX-CODE] Map: selected ${fields.size} fields (${fields.joinToString()}) from ${items.size} items")
        return JsonArray(projected)
    }

    // ==========================================================
    // 유틸리티
    // ==========================================================

    /** JsonElement에서 문자열 표현 추출 */
    private fun extractPrimitiveString(element: JsonElement): String {
        return when {
            element is JsonPrimitive && element.isString -> element.content
            element is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }

    /** 필드 값과 비교 연산 수행 */
    private fun compareField(fieldValue: JsonElement, operator: String, rawValue: String): Boolean {
        val prim = fieldValue as? JsonPrimitive ?: return false

        // 숫자 비교 시도
        val numField = prim.doubleOrNull
        val numValue = rawValue.toDoubleOrNull()
        if (numField != null && numValue != null) {
            return when (operator) {
                "=" -> numField == numValue
                "!=" -> numField != numValue
                ">" -> numField > numValue
                "<" -> numField < numValue
                ">=" -> numField >= numValue
                "<=" -> numField <= numValue
                else -> false
            }
        }

        // 문자열 비교
        val strField = if (prim.isString) prim.content else prim.content
        return when (operator) {
            "=" -> strField.equals(rawValue, ignoreCase = true)
            "!=" -> !strField.equals(rawValue, ignoreCase = true)
            else -> false
        }
    }
}
