package io.wiiiv.execution

import io.wiiiv.execution.impl.ApiExecutor
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.*

/**
 * ApiExecutor Tests
 *
 * HTTP API 호출 테스트
 *
 * 참고: 실제 네트워크 호출 테스트는 외부 의존성이 있으므로
 * Mock 기반 테스트와 실제 호출 테스트를 분리함
 */
class ApiExecutorTest {

    private lateinit var executor: ApiExecutor
    private lateinit var context: ExecutionContext

    @BeforeTest
    fun setup() {
        executor = ApiExecutor.INSTANCE
        context = ExecutionContext.create(
            executionId = "test-exec",
            blueprintId = "test-bp",
            instructionId = "test-inst"
        )
    }

    // ==================== Basic Tests ====================

    @Test
    fun `should handle ApiCallStep`() {
        val step = ExecutionStep.ApiCallStep(
            stepId = "api-1",
            method = HttpMethod.GET,
            url = "https://httpbin.org/get"
        )
        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `should not handle other step types`() {
        val step = ExecutionStep.FileStep(
            stepId = "file-1",
            action = FileAction.READ,
            path = "/tmp/test.txt"
        )
        assertFalse(executor.canHandle(step))
    }

    @Test
    fun `should return error for invalid step type`() = runBlocking {
        val step = ExecutionStep.CommandStep(
            stepId = "cmd-1",
            command = "echo"
        )
        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_STEP_TYPE", result.error.code)
    }

    // ==================== URL Validation Tests ====================

    @Test
    fun `should return error for invalid URL`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "api-invalid",
            method = HttpMethod.GET,
            url = "not a valid url"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_URL", result.error.code)
    }

    @Test
    fun `should return error for malformed URL`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "api-malformed",
            method = HttpMethod.GET,
            url = "http://[invalid"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
    }

    // ==================== Connection Error Tests ====================

    @Test
    fun `should return error for unreachable host`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "api-unreachable",
            method = HttpMethod.GET,
            url = "http://192.0.2.1:12345/test",  // TEST-NET-1, guaranteed unreachable
            timeoutMs = 1000
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        val error = result.error
        assertTrue(
            error.category == ErrorCategory.TIMEOUT ||
            error.category == ErrorCategory.EXTERNAL_SERVICE_ERROR ||
            error.category == ErrorCategory.IO_ERROR,
            "Expected TIMEOUT, EXTERNAL_SERVICE_ERROR, or IO_ERROR but got ${error.category}"
        )
    }

    @Test
    fun `should return error for connection refused`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "api-refused",
            method = HttpMethod.GET,
            url = "http://localhost:59999/test",  // Unlikely to be in use
            timeoutMs = 1000
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        val error = result.error
        assertTrue(
            error.category == ErrorCategory.EXTERNAL_SERVICE_ERROR ||
            error.category == ErrorCategory.IO_ERROR,
            "Expected EXTERNAL_SERVICE_ERROR or IO_ERROR but got ${error.category}"
        )
    }

    // ==================== Real HTTP Tests (httpbin.org) ====================

    @Test
    fun `should execute GET request successfully`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "get-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/get",
            headers = mapOf("X-Test-Header" to "test-value")
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success, "Expected Success but got $result")
        val output = result.output

        assertEquals(200, output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
        assertEquals("GET", output.json["method"]?.jsonPrimitive?.content)
        assertTrue(output.json["body"]?.jsonPrimitive?.content?.contains("httpbin") == true)
    }

    @Test
    fun `should execute POST request with body`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "post-test",
            method = HttpMethod.POST,
            url = "https://httpbin.org/post",
            headers = mapOf("Content-Type" to "application/json"),
            body = """{"key": "value"}"""
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success, "Expected Success but got $result")
        val output = result.output

        assertEquals(200, output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
        assertTrue(output.json["body"]?.jsonPrimitive?.content?.contains("value") == true)
    }

    @Test
    fun `should execute PUT request`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "put-test",
            method = HttpMethod.PUT,
            url = "https://httpbin.org/put",
            body = "update data"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals(200, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `should execute DELETE request`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "delete-test",
            method = HttpMethod.DELETE,
            url = "https://httpbin.org/delete"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals(200, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `should execute PATCH request`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "patch-test",
            method = HttpMethod.PATCH,
            url = "https://httpbin.org/patch",
            body = """{"patch": "data"}"""
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals(200, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }

    // ==================== HTTP Error Response Tests ====================

    @Test
    fun `should return Success for 404 response - Executor does not judge`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "404-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/status/404"
        )

        val result = executor.execute(step, context)

        // Executor는 HTTP 상태 코드를 판단하지 않음
        // 4xx/5xx도 Success로 반환 (해석은 상위 계층의 책임)
        assertTrue(result is ExecutionResult.Success, "Expected Success but got $result")
        assertEquals(404, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `should return Success for 500 response - Executor does not judge`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "500-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/status/500"
        )

        val result = executor.execute(step, context)

        // Executor는 HTTP 상태 코드를 판단하지 않음
        assertTrue(result is ExecutionResult.Success)
        assertEquals(500, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `should return Success for 401 response`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "401-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/status/401"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals(401, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }

    // ==================== Headers Tests ====================

    @Test
    fun `should send custom headers`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "headers-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/headers",
            headers = mapOf(
                "X-Custom-Header" to "custom-value",
                "Authorization" to "Bearer test-token"
            )
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val body = result.output.json["body"]?.jsonPrimitive?.content ?: ""
        assertTrue(body.contains("X-Custom-Header") || body.contains("x-custom-header"))
        assertTrue(body.contains("custom-value"))
    }

    // ==================== Output Tests ====================

    @Test
    fun `output should contain all required fields`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "output-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/get"
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        // Check JSON fields
        assertNotNull(output.json["method"])
        assertNotNull(output.json["url"])
        assertNotNull(output.json["statusCode"])
        assertNotNull(output.json["body"])
        assertNotNull(output.json["truncated"])
        assertNotNull(output.json["contentLength"])

        // Check artifacts
        assertNotNull(output.artifacts["body"])
    }

    @Test
    fun `output should be added to context`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "context-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/get"
        )

        executor.execute(step, context)

        val storedOutput = context.getStepOutput("context-test")
        assertNotNull(storedOutput)
        assertNotNull(storedOutput.json["statusCode"])
    }

    // ==================== Integration with Runner ====================

    @Test
    fun `Runner should execute multiple API steps`() = runBlocking {
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.ApiCallStep(
                stepId = "api-1",
                method = HttpMethod.GET,
                url = "https://httpbin.org/get"
            ),
            ExecutionStep.ApiCallStep(
                stepId = "api-2",
                method = HttpMethod.POST,
                url = "https://httpbin.org/post",
                body = "test"
            )
        )

        val runnerContext = ExecutionContext.create(
            executionId = "workflow-exec",
            blueprintId = "workflow-bp",
            instructionId = "workflow-inst"
        )

        val result = runner.execute(steps, runnerContext)

        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(2, result.successCount)
        assertTrue(result.isAllSuccess)
    }

    @Test
    fun `Runner should handle mixed success and HTTP errors`() = runBlocking {
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.ApiCallStep(
                stepId = "success-api",
                method = HttpMethod.GET,
                url = "https://httpbin.org/get"
            ),
            ExecutionStep.ApiCallStep(
                stepId = "error-api",
                method = HttpMethod.GET,
                url = "https://httpbin.org/status/500"
            )
        )

        val runnerContext = ExecutionContext.create(
            executionId = "workflow-exec",
            blueprintId = "workflow-bp",
            instructionId = "workflow-inst"
        )

        val result = runner.execute(steps, runnerContext)

        // Both should be Success (Executor doesn't judge HTTP status)
        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(2, result.successCount)
    }

    // ==================== Timeout Tests ====================

    @Test
    fun `should timeout slow request`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "timeout-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/delay/10",  // 10 second delay
            timeoutMs = 500  // 500ms timeout
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.TIMEOUT, result.error.category)
    }

    // ==================== Redirect Tests ====================

    @Test
    fun `should follow redirects`() = runBlocking {
        val step = ExecutionStep.ApiCallStep(
            stepId = "redirect-test",
            method = HttpMethod.GET,
            url = "https://httpbin.org/redirect/1"  // Redirects once
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        assertEquals(200, result.output.json["statusCode"]?.jsonPrimitive?.content?.toInt())
    }
}
