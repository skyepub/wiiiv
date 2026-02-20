package io.wiiiv.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.execution.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import kotlinx.serialization.json.*

/**
 * Dev Routes - Executor 스모크 테스트 (개발 전용)
 *
 * POST /api/v2/dev/executor-test - 단일 Executor 직접 실행
 * GET  /api/v2/dev/executor-status - 활성 Executor 목록
 *
 * Gate/Blueprint/Governor를 우회하여 CompositeExecutor를 직접 호출한다.
 */
fun Route.devRoutes() {
    route("/dev") {

        // 활성 Executor 목록
        get("/executor-status") {
            val probes = mapOf(
                "FILE" to ExecutionStep.FileStep(stepId = "probe", action = FileAction.READ, path = "/dev/null"),
                "COMMAND" to ExecutionStep.CommandStep(stepId = "probe", command = "echo"),
                "API" to ExecutionStep.ApiCallStep(stepId = "probe", method = HttpMethod.GET, url = "http://localhost"),
                "LLM" to ExecutionStep.LlmCallStep(stepId = "probe", action = LlmAction.COMPLETE, prompt = "test"),
                "DB" to ExecutionStep.DbStep(stepId = "probe", sql = "SELECT 1", mode = DbMode.QUERY),
                "MQ" to ExecutionStep.MessageQueueStep(stepId = "probe", action = MessageQueueAction.PUBLISH, topic = "test"),
            )
            val status = probes.mapValues { (_, step) ->
                if (WiiivRegistry.compositeExecutor.canHandle(step)) "ACTIVE" else "DISABLED"
            }
            call.respond(ApiResponse.success(status))
        }

        // 단일 Executor 스모크 테스트
        post("/executor-test") {
            val body = call.receiveText()
            val json = Json.parseToJsonElement(body).jsonObject

            val executor = json["executor"]?.jsonPrimitive?.contentOrNull?.uppercase()
                ?: return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("MISSING_FIELD", "executor field required")))

            val step: ExecutionStep = try {
                when (executor) {
                    "DB" -> {
                        val sql = json["sql"]?.jsonPrimitive?.contentOrNull ?: "SELECT 1"
                        val mode = json["mode"]?.jsonPrimitive?.contentOrNull?.let { DbMode.valueOf(it.uppercase()) } ?: DbMode.QUERY
                        ExecutionStep.DbStep(stepId = "smoke-db", sql = sql, mode = mode)
                    }
                    "LLM" -> {
                        val prompt = json["prompt"]?.jsonPrimitive?.contentOrNull ?: "Say hello in one word."
                        val action = json["action"]?.jsonPrimitive?.contentOrNull?.let { LlmAction.valueOf(it.uppercase()) } ?: LlmAction.COMPLETE
                        ExecutionStep.LlmCallStep(stepId = "smoke-llm", action = action, prompt = prompt)
                    }
                    "MQ" -> {
                        val topic = json["topic"]?.jsonPrimitive?.contentOrNull ?: "smoke-test"
                        val message = json["message"]?.jsonPrimitive?.contentOrNull ?: """{"event":"smoke-test"}"""
                        val action = json["action"]?.jsonPrimitive?.contentOrNull?.let { MessageQueueAction.valueOf(it.uppercase()) } ?: MessageQueueAction.PUBLISH
                        ExecutionStep.MessageQueueStep(stepId = "smoke-mq", action = action, topic = topic, message = message)
                    }
                    "FILE" -> {
                        val path = json["path"]?.jsonPrimitive?.contentOrNull ?: "/tmp/wiiiv-smoke-test.txt"
                        val action = json["action"]?.jsonPrimitive?.contentOrNull?.let { FileAction.valueOf(it.uppercase()) } ?: FileAction.READ
                        val content = json["content"]?.jsonPrimitive?.contentOrNull
                        ExecutionStep.FileStep(stepId = "smoke-file", action = action, path = path, content = content)
                    }
                    else -> return@post call.respond(HttpStatusCode.BadRequest,
                        ApiResponse.error<String>(ApiError("UNKNOWN_EXECUTOR", "Supported: DB, LLM, MQ, FILE")))
                }
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.BadRequest,
                    ApiResponse.error<String>(ApiError("INVALID_PARAMS", e.message ?: "Invalid parameters")))
            }

            // canHandle 확인
            if (!WiiivRegistry.compositeExecutor.canHandle(step)) {
                return@post call.respond(HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<String>(ApiError("EXECUTOR_DISABLED", "$executor executor is not active")))
            }

            // 실행
            val context = ExecutionContext(executionId = "smoke-test", blueprintId = "smoke-test", instructionId = "smoke-test")
            val result = try {
                WiiivRegistry.compositeExecutor.execute(step, context)
            } catch (e: Exception) {
                return@post call.respond(HttpStatusCode.InternalServerError,
                    ApiResponse.error<String>(ApiError("EXECUTION_ERROR", e.message ?: "Unknown error")))
            }

            // 결과 반환
            val response = buildJsonObject {
                put("executor", JsonPrimitive(executor))
                put("stepId", JsonPrimitive(step.stepId))
                put("success", JsonPrimitive(result is ExecutionResult.Success))
                when (result) {
                    is ExecutionResult.Success -> {
                        result.output?.let { output ->
                            put("output", JsonObject(output.json))
                            if (output.artifacts.isNotEmpty()) {
                                put("artifacts", buildJsonObject {
                                    output.artifacts.forEach { (k, v) -> put(k, JsonPrimitive(v)) }
                                })
                            }
                        }
                    }
                    is ExecutionResult.Failure -> {
                        put("error", buildJsonObject {
                            put("category", JsonPrimitive(result.error.category.name))
                            put("code", JsonPrimitive(result.error.code))
                            put("message", JsonPrimitive(result.error.message))
                        })
                    }
                    is ExecutionResult.Cancelled -> {
                        put("cancelled", JsonPrimitive(true))
                        put("reason", JsonPrimitive(result.reason.message))
                    }
                }
                result.meta?.let { meta ->
                    put("durationMs", JsonPrimitive(meta.durationMs))
                }
            }

            call.respond(ApiResponse.success(response))
        }
    }
}
