package io.wiiiv.plugins.webfetch

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginConfig
import kotlinx.serialization.json.JsonPrimitive
import org.jsoup.Jsoup
import java.time.Instant

/**
 * WebFetch Executor — URL 텍스트/JSON 추출 및 웹 검색
 *
 * 액션:
 * - fetch: URL → 텍스트/HTML 추출 (jsoup CSS selector 지원)
 * - fetch_json: URL → JSON 파싱 (점 표기법 경로 추출)
 * - search: 웹 검색 API 호출
 */
class WebFetchExecutor(config: PluginConfig) : Executor {

    private val ssrfGuard = SsrfGuard(
        allowPrivateIp = config.env["ALLOW_PRIVATE_IP"] == "true",
        allowLocalhost = config.env["ALLOW_LOCALHOST"] != "false"
    )
    private val maxLength = config.env["MAX_LENGTH"]?.toIntOrNull() ?: 51_200
    private val searchApiKey = config.env["SEARCH_API_KEY"]
    private val searchEngine = config.env["SEARCH_ENGINE"] ?: "serpapi"

    private val client = HttpClient(CIO) {
        engine { requestTimeout = 30_000 }
        expectSuccess = false
    }

    override fun canHandle(step: ExecutionStep): Boolean =
        step is ExecutionStep.PluginStep && step.pluginId == "webfetch"

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val ps = step as ExecutionStep.PluginStep
        val startedAt = Instant.now()

        return try {
            when (ps.action) {
                "fetch" -> executeFetch(ps, startedAt)
                "fetch_json" -> executeFetchJson(ps, startedAt)
                "search" -> executeSearch(ps, startedAt)
                else -> contractViolation(ps.stepId, "UNKNOWN_ACTION", "Unknown webfetch action: ${ps.action}")
            }
        } catch (e: io.ktor.client.network.sockets.ConnectTimeoutException) {
            ExecutionResult.failure(
                error = ExecutionError.timeout("CONNECT_TIMEOUT", "Connection timeout: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            ExecutionResult.failure(
                error = ExecutionError.timeout("REQUEST_TIMEOUT", "Request timeout: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.ioError("FETCH_ERROR", "WebFetch error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }
    }

    private suspend fun executeFetch(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val url = ps.params["url"]
            ?: return contractViolation(ps.stepId, "MISSING_URL", "fetch action requires 'url' param")
        val selector = ps.params["selector"]
        val limit = ps.params["max_length"]?.toIntOrNull() ?: maxLength

        val ssrfError = ssrfGuard.validate(url)
        if (ssrfError != null) {
            return ExecutionResult.failure(
                error = ExecutionError(
                    category = ErrorCategory.PERMISSION_DENIED,
                    code = "SSRF_BLOCKED",
                    message = "SSRF guard blocked: $ssrfError (url=$url)"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        }

        val response = client.get(url) {
            header("User-Agent", "wiiiv-webfetch/1.0")
            ps.params.forEach { (k, v) ->
                if (k.startsWith("header:")) header(k.removePrefix("header:"), v)
            }
        }

        val body = response.bodyAsText()
        val statusCode = response.status.value

        if (statusCode !in 200..399) {
            return ExecutionResult.failure(
                error = ExecutionError.externalServiceError(
                    "HTTP_$statusCode",
                    "HTTP $statusCode from $url: ${body.take(500)}"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        }

        val text = if (selector != null) {
            val doc = Jsoup.parse(body)
            doc.select(selector).text()
        } else {
            val doc = Jsoup.parse(body)
            doc.text()
        }

        val truncated = text.take(limit)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("fetch"),
                    "url" to JsonPrimitive(url),
                    "statusCode" to JsonPrimitive(statusCode),
                    "length" to JsonPrimitive(truncated.length),
                    "text" to JsonPrimitive(truncated)
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(url))
        )
    }

    private suspend fun executeFetchJson(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val url = ps.params["url"]
            ?: return contractViolation(ps.stepId, "MISSING_URL", "fetch_json action requires 'url' param")
        val jqPath = ps.params["jq_path"]

        val ssrfError = ssrfGuard.validate(url)
        if (ssrfError != null) {
            return ExecutionResult.failure(
                error = ExecutionError(
                    category = ErrorCategory.PERMISSION_DENIED,
                    code = "SSRF_BLOCKED",
                    message = "SSRF guard blocked: $ssrfError (url=$url)"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        }

        val response = client.get(url) {
            header("User-Agent", "wiiiv-webfetch/1.0")
            header("Accept", "application/json")
            ps.params.forEach { (k, v) ->
                if (k.startsWith("header:")) header(k.removePrefix("header:"), v)
            }
        }

        val body = response.bodyAsText()
        val statusCode = response.status.value

        if (statusCode !in 200..399) {
            return ExecutionResult.failure(
                error = ExecutionError.externalServiceError(
                    "HTTP_$statusCode",
                    "HTTP $statusCode from $url: ${body.take(500)}"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        }

        // 간단한 점 표기법 경로 추출
        val extracted = if (jqPath != null) {
            extractJsonPath(body, jqPath)
        } else {
            body.take(maxLength)
        }

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("fetch_json"),
                    "url" to JsonPrimitive(url),
                    "statusCode" to JsonPrimitive(statusCode),
                    "result" to JsonPrimitive(extracted)
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(url))
        )
    }

    private suspend fun executeSearch(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val query = ps.params["query"]
            ?: return contractViolation(ps.stepId, "MISSING_QUERY", "search action requires 'query' param")

        if (searchApiKey.isNullOrBlank()) {
            return contractViolation(ps.stepId, "SEARCH_NOT_CONFIGURED", "SEARCH_API_KEY not configured")
        }

        val limit = ps.params["limit"]?.toIntOrNull() ?: 5
        val searchUrl = when (searchEngine) {
            "serpapi" -> "https://serpapi.com/search.json?q=${java.net.URLEncoder.encode(query, "UTF-8")}&num=$limit&api_key=$searchApiKey"
            else -> return contractViolation(ps.stepId, "UNSUPPORTED_ENGINE", "Unsupported search engine: $searchEngine")
        }

        val response = client.get(searchUrl) {
            header("User-Agent", "wiiiv-webfetch/1.0")
        }

        val body = response.bodyAsText()
        val statusCode = response.status.value

        if (statusCode !in 200..399) {
            return ExecutionResult.failure(
                error = ExecutionError.externalServiceError(
                    "SEARCH_HTTP_$statusCode",
                    "Search API returned HTTP $statusCode"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("search"),
                    "query" to JsonPrimitive(query),
                    "engine" to JsonPrimitive(searchEngine),
                    "result" to JsonPrimitive(body.take(maxLength))
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
        )
    }

    /**
     * 간단한 점 표기법 JSON 경로 추출
     * 예: "data.items.0.name" → JSON에서 해당 값 추출
     */
    private fun extractJsonPath(json: String, path: String): String {
        try {
            val parsed = kotlinx.serialization.json.Json.parseToJsonElement(json)
            var current: kotlinx.serialization.json.JsonElement = parsed
            for (key in path.split(".")) {
                current = when (current) {
                    is kotlinx.serialization.json.JsonObject ->
                        (current as kotlinx.serialization.json.JsonObject)[key] ?: return "null"
                    is kotlinx.serialization.json.JsonArray -> {
                        val idx = key.toIntOrNull() ?: return "null"
                        (current as kotlinx.serialization.json.JsonArray).getOrNull(idx) ?: return "null"
                    }
                    else -> return current.toString()
                }
            }
            return current.toString()
        } catch (e: Exception) {
            return "ERROR: ${e.message}"
        }
    }

    private fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
        ExecutionResult.contractViolation(stepId = stepId, code = code, message = message)

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = false
}
