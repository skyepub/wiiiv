package io.wiiiv.plugins.webhook

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginConfig
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * Webhook Executor — HTTP Webhook 전송 및 헬스체크
 *
 * 액션:
 * - ping: GET 요청으로 URL 헬스체크 (riskLevel=LOW)
 * - send: JSON body를 URL에 POST (riskLevel=MEDIUM)
 * - send_form: form-encoded body를 URL에 POST (riskLevel=MEDIUM)
 *
 * ## HTTP 상태코드 처리 규칙 (GPT 리뷰 반영)
 * - 네트워크/타임아웃 → Failure(IO_ERROR/TIMEOUT, retryable)
 * - HTTP 429 → Failure(EXTERNAL_SERVICE_ERROR, retryable)
 * - HTTP 5xx → Failure(EXTERNAL_SERVICE_ERROR, retryable)
 * - HTTP 4xx (429 제외) → Failure(EXTERNAL_SERVICE_ERROR, not retryable)
 * - HTTP 2xx/3xx → Success
 */
class WebhookExecutor(config: PluginConfig) : Executor {

    private val ssrfGuard = SsrfGuard(
        allowPrivateIp = config.env["ALLOW_PRIVATE_IP"] == "true",
        allowLocalhost = config.env["ALLOW_LOCALHOST"] != "false" // 기본 true
    )
    private val defaultTimeoutMs = config.env["DEFAULT_TIMEOUT_MS"]?.toLongOrNull() ?: 30_000

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = defaultTimeoutMs
        }
        expectSuccess = false // 4xx/5xx에서 예외 던지지 않음
    }

    override fun canHandle(step: ExecutionStep): Boolean =
        step is ExecutionStep.PluginStep && step.pluginId == "webhook"

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val ps = step as ExecutionStep.PluginStep
        val startedAt = Instant.now()

        // SSRF 검사
        val url = ps.params["url"] ?: return contractViolation(ps.stepId, "MISSING_URL", "url param required")
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

        return try {
            when (ps.action) {
                "ping" -> executePing(url, ps, startedAt)
                "send" -> executeSend(url, ps, startedAt)
                "send_form" -> executeSendForm(url, ps, startedAt)
                else -> contractViolation(ps.stepId, "UNKNOWN_ACTION", "Unknown webhook action: ${ps.action}")
            }
        } catch (e: io.ktor.client.network.sockets.ConnectTimeoutException) {
            ExecutionResult.failure(
                error = ExecutionError.timeout("CONNECT_TIMEOUT", "Connection timeout: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        } catch (e: io.ktor.client.plugins.HttpRequestTimeoutException) {
            ExecutionResult.failure(
                error = ExecutionError.timeout("REQUEST_TIMEOUT", "Request timeout: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        } catch (e: java.net.ConnectException) {
            ExecutionResult.failure(
                error = ExecutionError.ioError("CONNECTION_REFUSED", "Connection refused: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.ioError("NETWORK_ERROR", "Network error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(url))
            )
        }
    }

    private suspend fun executePing(url: String, ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val response = client.get(url) {
            applyHeaders(ps)
        }
        return classifyResponse(response, ps.stepId, url, startedAt)
    }

    private suspend fun executeSend(url: String, ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val body = ps.params["body"] ?: return contractViolation(ps.stepId, "MISSING_BODY", "send action requires 'body' param")
        val contentType = ps.params["content_type"] ?: "application/json"

        val response = client.post(url) {
            applyHeaders(ps)
            contentType(ContentType.parse(contentType))
            setBody(body)
        }
        return classifyResponse(response, ps.stepId, url, startedAt)
    }

    private suspend fun executeSendForm(url: String, ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val formData = ps.params["form_data"]
            ?: return contractViolation(ps.stepId, "MISSING_FORM_DATA", "send_form action requires 'form_data' param")

        val response = client.post(url) {
            applyHeaders(ps)
            contentType(ContentType.Application.FormUrlEncoded)
            setBody(formData)
        }
        return classifyResponse(response, ps.stepId, url, startedAt)
    }

    /**
     * HTTP 상태코드 → ExecutionResult 기계적 분류
     *
     * Executor는 판단하지 않되, 재시도 가능성은 보존한다.
     */
    private suspend fun classifyResponse(
        response: HttpResponse,
        stepId: String,
        url: String,
        startedAt: Instant
    ): ExecutionResult {
        val endedAt = Instant.now()
        val meta = ExecutionMeta.of(stepId, startedAt, endedAt, listOf(url))
        val statusCode = response.status.value
        val responseBody = response.bodyAsText()

        return when {
            statusCode in 200..399 -> {
                // 성공
                val output = StepOutput.json(
                    stepId = stepId,
                    data = mapOf(
                        "statusCode" to JsonPrimitive(statusCode),
                        "body" to JsonPrimitive(responseBody.take(10_000)),
                        "url" to JsonPrimitive(url)
                    ),
                    durationMs = meta.durationMs
                )
                ExecutionResult.success(output, meta)
            }
            statusCode == 429 -> {
                // Rate limit — retryable
                ExecutionResult.failure(
                    error = ExecutionError.externalServiceError(
                        "HTTP_429_RATE_LIMIT",
                        "Rate limited (HTTP 429) from $url"
                    ),
                    meta = meta
                )
            }
            statusCode in 500..599 -> {
                // Server error — retryable
                ExecutionResult.failure(
                    error = ExecutionError.externalServiceError(
                        "HTTP_${statusCode}_SERVER_ERROR",
                        "Server error (HTTP $statusCode) from $url: ${responseBody.take(500)}"
                    ),
                    meta = meta
                )
            }
            statusCode in 400..499 -> {
                // Client error — not retryable
                ExecutionResult.failure(
                    error = ExecutionError(
                        category = ErrorCategory.CONTRACT_VIOLATION,
                        code = "HTTP_${statusCode}_CLIENT_ERROR",
                        message = "Client error (HTTP $statusCode) from $url: ${responseBody.take(500)}"
                    ),
                    meta = meta
                )
            }
            else -> {
                // 기타 (1xx 등)
                ExecutionResult.failure(
                    error = ExecutionError.unknown("Unexpected HTTP status $statusCode from $url"),
                    meta = meta
                )
            }
        }
    }

    private fun HttpRequestBuilder.applyHeaders(ps: ExecutionStep.PluginStep) {
        ps.params.forEach { (k, v) ->
            if (k.startsWith("header:")) {
                header(k.removePrefix("header:"), v)
            }
        }
    }

    private fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
        ExecutionResult.contractViolation(stepId = stepId, code = code, message = message)

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = false
}
