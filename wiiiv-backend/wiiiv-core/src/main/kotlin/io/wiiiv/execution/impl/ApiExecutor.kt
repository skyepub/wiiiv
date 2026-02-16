package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant

/**
 * API Executor - HTTP API 호출 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: URL이 안전한지, 적절한지 판단하지 않음
 * - 해석하지 않는다: 응답의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - HTTP 메서드: GET, POST, PUT, DELETE, PATCH
 * - 요청 헤더 설정
 * - 요청 바디 전송
 * - 타임아웃 지원
 * - 응답 상태 코드, 헤더, 바디 캡처
 *
 * ## 오류 처리
 *
 * - 잘못된 URL → Failure (CONTRACT_VIOLATION)
 * - 연결 실패 → Failure (EXTERNAL_SERVICE_ERROR)
 * - 타임아웃 → Failure (TIMEOUT)
 * - 4xx/5xx 응답 → Success (HTTP 오류는 Executor가 판단하지 않음)
 *
 * ## 보안 주의사항
 *
 * ApiExecutor는 판단하지 않는다. 보안 검증은 Governor와 Gate의 책임이다.
 * Blueprint에 포함된 API 호출은 이미 검증되었다고 가정한다.
 */
class ApiExecutor(
    private val httpClient: HttpClient = DEFAULT_CLIENT
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        // Type check
        if (step !is ExecutionStep.ApiCallStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "ApiExecutor can only handle ApiCallStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val result = executeApiCall(step)

            val endedAt = Instant.now()

            when (result) {
                is ApiResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.url)
                    )

                    // Add to context
                    context.addStepOutput(step.stepId, output)

                    ExecutionResult.Success(output = output, meta = meta)
                }
                is ApiResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.url)
                    )

                    ExecutionResult.Failure(
                        error = result.error,
                        partialOutput = result.partialOutput,
                        meta = meta
                    )
                }
            }
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.url)
                )
            )
        }
    }

    /**
     * API 호출 실행 (빈 body 시 1회 재시도)
     */
    private fun executeApiCall(step: ExecutionStep.ApiCallStep): ApiResult {
        val result = executeApiCallOnce(step)

        // 성공이지만 body가 비어있고 GET 요청인 경우 1회 재시도
        if (result is ApiResult.Success) {
            val body = result.output.artifacts["body"] ?: ""
            val statusCode = result.output.json["statusCode"]?.toString()?.toIntOrNull() ?: 0
            if (body.isBlank() && statusCode in 200..299 && step.method == HttpMethod.GET) {
                System.err.println("[ApiExecutor] Empty body on ${step.method} ${step.url} (HTTP $statusCode), retrying...")
                Thread.sleep(500)
                val retry = executeApiCallOnce(step)
                if (retry is ApiResult.Success) {
                    val retryBody = retry.output.artifacts["body"] ?: ""
                    if (retryBody.isNotBlank()) return retry
                    // 재시도도 비어있으면 경고 삽입
                    System.err.println("[ApiExecutor] Retry also returned empty body for ${step.url}")
                    return markEmptyBody(retry, step)
                }
                return retry
            }
        }

        return result
    }

    /**
     * 빈 body 응답에 명시적 경고를 삽입하여 LLM 환각을 방지한다
     */
    private fun markEmptyBody(result: ApiResult.Success, step: ExecutionStep.ApiCallStep): ApiResult {
        val emptyWarning = "[EMPTY_RESPONSE] 서버가 빈 응답을 반환했습니다. 실제 데이터를 가져오지 못했습니다."
        val output = StepOutput(
            stepId = result.output.stepId,
            json = buildJsonObject {
                put("method", JsonPrimitive(step.method.name))
                put("url", JsonPrimitive(step.url))
                put("statusCode", JsonPrimitive(200))
                put("body", JsonPrimitive(emptyWarning))
                put("truncated", JsonPrimitive(false))
                put("contentLength", JsonPrimitive(0))
                put("emptyResponse", JsonPrimitive(true))
            },
            artifacts = buildMap {
                put("body", emptyWarning)
                result.output.artifacts.forEach { (key, value) ->
                    if (key != "body") put(key, value)
                }
            }
        )
        return ApiResult.Success(output)
    }

    /**
     * 단일 API 호출 실행
     */
    private fun executeApiCallOnce(step: ExecutionStep.ApiCallStep): ApiResult {
        // Validate URL
        val uri = try {
            URI.create(step.url)
        } catch (e: IllegalArgumentException) {
            return ApiResult.Error(
                ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "INVALID_URL",
                    message = "Invalid URL: ${step.url}"
                )
            )
        }

        // Build request
        val requestBuilder = HttpRequest.newBuilder()
            .uri(uri)
            .timeout(Duration.ofMillis(step.timeoutMs))

        // 기본 User-Agent 설정 (일부 서버는 UA 없으면 빈 응답 반환)
        if (step.headers.none { it.key.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", DEFAULT_USER_AGENT)
        }

        // Set headers
        step.headers.forEach { (key, value) ->
            requestBuilder.header(key, value)
        }

        // Set method and body
        val bodyPublisher = step.body?.let { HttpRequest.BodyPublishers.ofString(it) }
            ?: HttpRequest.BodyPublishers.noBody()

        when (step.method) {
            HttpMethod.GET -> requestBuilder.GET()
            HttpMethod.POST -> requestBuilder.POST(bodyPublisher)
            HttpMethod.PUT -> requestBuilder.PUT(bodyPublisher)
            HttpMethod.DELETE -> requestBuilder.DELETE()
            HttpMethod.PATCH -> requestBuilder.method("PATCH", bodyPublisher)
        }

        val request = requestBuilder.build()

        // Execute request
        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: java.net.http.HttpConnectTimeoutException) {
            return ApiResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "CONNECTION_TIMEOUT",
                    message = "Connection timeout: ${e.message}"
                )
            )
        } catch (e: java.net.http.HttpTimeoutException) {
            return ApiResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "REQUEST_TIMEOUT",
                    message = "Request timeout after ${step.timeoutMs}ms"
                )
            )
        } catch (e: java.net.ConnectException) {
            return ApiResult.Error(
                ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "CONNECTION_FAILED",
                    message = "Connection failed: ${e.message}"
                )
            )
        } catch (e: java.io.IOException) {
            return ApiResult.Error(
                ExecutionError(
                    category = ErrorCategory.IO_ERROR,
                    code = "IO_ERROR",
                    message = "IO error during API call: ${e.message}"
                )
            )
        }

        // Build response headers map
        val responseHeaders = buildMap {
            response.headers().map().forEach { (key, values) ->
                put(key, values.joinToString(", "))
            }
        }

        // Truncate body if too large
        val responseBody = response.body() ?: ""
        val truncated = responseBody.length > MAX_RESPONSE_LENGTH
        val body = if (truncated) responseBody.take(MAX_RESPONSE_LENGTH) else responseBody

        // Build output - Executor는 상태 코드를 판단하지 않음
        // 4xx, 5xx도 Success로 반환 (해석은 상위 계층의 책임)
        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("method", JsonPrimitive(step.method.name))
                put("url", JsonPrimitive(step.url))
                put("statusCode", JsonPrimitive(response.statusCode()))
                put("body", JsonPrimitive(body))
                put("truncated", JsonPrimitive(truncated))
                put("contentLength", JsonPrimitive(responseBody.length))
            },
            artifacts = buildMap {
                put("body", body)
                responseHeaders.forEach { (key, value) ->
                    put("header:$key", value)
                }
            }
        )

        return ApiResult.Success(output)
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // HTTP 요청 취소는 복잡한 상태 관리 필요
        // v1.0에서는 간단히 true 반환 (실제 취소는 타임아웃에 의존)
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.ApiCallStep
    }

    /**
     * Internal result type for API operations
     */
    private sealed class ApiResult {
        data class Success(val output: StepOutput) : ApiResult()
        data class Error(
            val error: ExecutionError,
            val partialOutput: StepOutput? = null
        ) : ApiResult()
    }

    companion object {
        /**
         * 최대 응답 길이 (1MB)
         */
        const val MAX_RESPONSE_LENGTH = 1024 * 1024

        /**
         * 기본 User-Agent (일부 서버는 UA 없이 빈 응답 반환)
         */
        const val DEFAULT_USER_AGENT = "wiiiv-api-executor/2.2"

        /**
         * 기본 HTTP 클라이언트
         * - HTTP/1.1 강제: 일부 서버(wttr.in 등)가 HTTP/2에서 빈 응답 반환
         * - 리다이렉트 자동 추적
         */
        private val DEFAULT_CLIENT: HttpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

        /**
         * 기본 인스턴스
         */
        val INSTANCE = ApiExecutor()
    }
}
