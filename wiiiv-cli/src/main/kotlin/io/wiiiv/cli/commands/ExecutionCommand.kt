package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.output.Printer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * wiiiv execution - 실행 "의뢰"
 *
 * job / run / task ❌
 * execution = 요청 + 진행 상태
 *
 * Gate 정책 통과 여부는 서버 책임
 * CLI는 결과 상태만 출력
 */
class ExecutionCommand : CliktCommand(
    name = "execution",
    help = "Blueprint 실행 관리"
) {
    override fun run() = Unit

    init {
        subcommands(
            ExecutionCreate(),
            ExecutionGet(),
            ExecutionList(),
            ExecutionCancel(),
            ExecutionLogs()
        )
    }
}

/**
 * wiiiv execution create --blueprint <id>
 */
class ExecutionCreate : CliktCommand(
    name = "create",
    help = "새 실행 시작"
) {
    private val ctx by requireObject<CliContext>()
    private val blueprintId by option("-b", "--blueprint", help = "Blueprint ID").required()
    private val dryRun by option("--dry-run", help = "시뮬레이션 모드 (실제 실행 안 함)").flag()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            Printer.info(ctx, "Creating execution${if (dryRun) " (dry-run)" else ""}...")

            val response = client.createExecution(blueprintId, dryRun)

            // Gate 거부 확인
            val success = response["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!success) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            Printer.print(ctx, response)

            if (!ctx.json && !ctx.quiet) {
                val data = response["data"]?.jsonObject
                val executionId = data?.get("executionId")?.jsonPrimitive?.content
                val status = data?.get("status")?.jsonPrimitive?.content

                println()
                println("─".repeat(50))
                println("Execution ID: $executionId")
                println("Status: $status")
                println()
                println("Monitor with:")
                println("  wiiiv execution get $executionId")
                println("  wiiiv execution logs $executionId")
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to create execution")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv execution get <execution-id>
 */
class ExecutionGet : CliktCommand(
    name = "get",
    help = "실행 상태 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("execution-id", help = "Execution ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.getExecution(id)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            if (data == null) {
                Printer.error(ctx, "Execution not found")
                return@runBlocking
            }

            println("Execution: ${data["executionId"]?.jsonPrimitive?.content}")
            println("Blueprint: ${data["blueprintId"]?.jsonPrimitive?.content}")
            println("Status: ${data["status"]?.jsonPrimitive?.content}")
            println("Started: ${data["startedAt"]?.jsonPrimitive?.content}")

            val completedAt = data["completedAt"]?.jsonPrimitive?.contentOrNull
            if (completedAt != null) {
                println("Completed: $completedAt")
            }

            val results = data["results"]?.jsonArray
            if (results != null && results.isNotEmpty()) {
                println()
                println("Step Results:")
                Printer.table(
                    ctx,
                    listOf("NODE", "STATUS", "DURATION", "OUTPUT/ERROR"),
                    results.map { result ->
                        val r = result.jsonObject
                        listOf(
                            r["nodeId"]?.jsonPrimitive?.content ?: "",
                            r["status"]?.jsonPrimitive?.content ?: "",
                            "${r["duration"]?.jsonPrimitive?.longOrNull ?: 0}ms",
                            r["output"]?.jsonPrimitive?.content?.take(30)
                                ?: r["error"]?.jsonPrimitive?.content?.take(30)
                                ?: ""
                        )
                    }
                )
            }

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to get execution")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv execution list
 */
class ExecutionList : CliktCommand(
    name = "list",
    help = "실행 목록 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val blueprintId by option("-b", "--blueprint", help = "Blueprint ID로 필터링")
    private val page by option("-p", "--page", help = "페이지 번호").int().default(1)
    private val pageSize by option("-s", "--size", help = "페이지 크기").int().default(20)

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.listExecutions(blueprintId, page, pageSize)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val executions = data?.get("executions")?.jsonArray ?: JsonArray(emptyList())
            val total = data?.get("total")?.jsonPrimitive?.intOrNull ?: 0

            if (executions.isEmpty()) {
                println("No executions found")
                return@runBlocking
            }

            println("Executions ($total total):")
            println()

            Printer.table(
                ctx,
                listOf("ID", "BLUEPRINT", "STATUS", "STARTED"),
                executions.map { exec ->
                    val obj = exec.jsonObject
                    listOf(
                        obj["executionId"]?.jsonPrimitive?.content?.take(12) ?: "",
                        obj["blueprintId"]?.jsonPrimitive?.content?.take(12) ?: "",
                        obj["status"]?.jsonPrimitive?.content ?: "",
                        obj["startedAt"]?.jsonPrimitive?.content?.take(19) ?: ""
                    )
                }
            )

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list executions")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv execution cancel <execution-id>
 */
class ExecutionCancel : CliktCommand(
    name = "cancel",
    help = "실행 취소"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("execution-id", help = "Execution ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.cancelExecution(id)
            Printer.print(ctx, response)
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to cancel execution")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv execution logs <execution-id>
 */
class ExecutionLogs : CliktCommand(
    name = "logs",
    help = "실행 로그 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("execution-id", help = "Execution ID")
    private val follow by option("-f", "--follow", help = "실시간 로그 추적 (미구현)").flag()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.getExecutionLogs(id)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val logs = data?.get("logs")?.jsonArray ?: JsonArray(emptyList())

            if (logs.isEmpty()) {
                println("No logs available")
                return@runBlocking
            }

            logs.forEach { log ->
                println(log.jsonPrimitive.content)
            }

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to get execution logs")
        } finally {
            client.close()
        }
    }
}
