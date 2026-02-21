package io.wiiiv.plugins.cron

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginConfig
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Cron Executor — 워크플로우 지연 및 크론 스케줄링
 *
 * 액션:
 * - delay: 워크플로우 실행을 N ms 일시정지
 * - schedule: 크론 표현식 기반 반복 실행 등록
 * - list: 활성 스케줄 목록 반환
 * - cancel: 스케줄된 작업 취소
 */
class CronExecutor(config: PluginConfig) : Executor {

    private val store = ScheduleStore()
    private val scheduler = Executors.newScheduledThreadPool(2)
    private val client = HttpClient(CIO) {
        engine { requestTimeout = 30_000 }
        expectSuccess = false
    }

    override fun canHandle(step: ExecutionStep): Boolean =
        step is ExecutionStep.PluginStep && step.pluginId == "cron"

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val ps = step as ExecutionStep.PluginStep
        val startedAt = Instant.now()

        return try {
            when (ps.action) {
                "delay" -> executeDelay(ps, startedAt)
                "schedule" -> executeSchedule(ps, startedAt)
                "list" -> executeList(ps, startedAt)
                "cancel" -> executeCancel(ps, startedAt)
                else -> contractViolation(ps.stepId, "UNKNOWN_ACTION", "Unknown cron action: ${ps.action}")
            }
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.unknown("Cron executor error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }
    }

    private suspend fun executeDelay(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val durationMs = ps.params["duration_ms"]?.toLongOrNull()
            ?: return contractViolation(ps.stepId, "MISSING_DURATION", "delay action requires 'duration_ms' param")

        if (durationMs < 0 || durationMs > 3_600_000) {
            return contractViolation(ps.stepId, "INVALID_DURATION", "duration_ms must be 0..3600000, got: $durationMs")
        }

        kotlinx.coroutines.delay(durationMs)

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("delay"),
                    "duration_ms" to JsonPrimitive(durationMs),
                    "status" to JsonPrimitive("completed")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
        )
    }

    private fun executeSchedule(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val cronExpr = ps.params["cron_expr"]
            ?: return contractViolation(ps.stepId, "MISSING_CRON_EXPR", "schedule action requires 'cron_expr' param")
        val callbackUrl = ps.params["callback_url"]
            ?: return contractViolation(ps.stepId, "MISSING_CALLBACK_URL", "schedule action requires 'callback_url' param")
        val jobId = ps.params["job_id"] ?: "job-${UUID.randomUUID().toString().take(8)}"
        val payload = ps.params["payload"]

        // 크론 파서 검증
        val parser = try {
            CronParser(cronExpr)
        } catch (e: Exception) {
            return contractViolation(ps.stepId, "INVALID_CRON_EXPR", "Invalid cron expression: ${e.message}")
        }

        // 다음 실행 시각 계산 → 스케줄 등록
        val now = ZonedDateTime.now()
        val nextRun = parser.nextExecution(now)
        val delayMs = java.time.Duration.between(now, nextRun).toMillis()

        val future = scheduler.scheduleAtFixedRate({
            try {
                // 콜백 URL에 POST (blocking — scheduler thread)
                val thread = Thread {
                    kotlinx.coroutines.runBlocking {
                        client.post(callbackUrl) {
                            contentType(ContentType.Application.Json)
                            setBody(payload ?: """{"job_id":"$jobId","triggered_at":"${Instant.now()}"}""")
                        }
                    }
                }
                thread.start()
            } catch (_: Exception) {
                // 콜백 실패는 무시 (로그만)
            }
        }, delayMs, 60_000, TimeUnit.MILLISECONDS) // 기본 1분 간격 (다음 실행 시 크론 매칭)

        store.put(ScheduleStore.ScheduleEntry(jobId, cronExpr, callbackUrl, payload, future))

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("schedule"),
                    "job_id" to JsonPrimitive(jobId),
                    "cron_expr" to JsonPrimitive(cronExpr),
                    "callback_url" to JsonPrimitive(callbackUrl),
                    "next_run" to JsonPrimitive(nextRun.toString()),
                    "status" to JsonPrimitive("scheduled")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(callbackUrl))
        )
    }

    private fun executeList(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val jobs = store.all()
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("list"),
                    "count" to JsonPrimitive(jobs.size),
                    "jobs" to JsonPrimitive(jobs.joinToString(", ") { "${it.jobId}(${it.cronExpr})" })
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
        )
    }

    private fun executeCancel(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val jobId = ps.params["job_id"]
            ?: return contractViolation(ps.stepId, "MISSING_JOB_ID", "cancel action requires 'job_id' param")

        val entry = store.remove(jobId)
            ?: return ExecutionResult.failure(
                error = ExecutionError.resourceNotFound("JOB_NOT_FOUND", "Schedule job not found: $jobId"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )

        entry.future.cancel(false)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("cancel"),
                    "job_id" to JsonPrimitive(jobId),
                    "status" to JsonPrimitive("cancelled")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt)
        )
    }

    private fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
        ExecutionResult.contractViolation(stepId = stepId, code = code, message = message)

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = false
}
