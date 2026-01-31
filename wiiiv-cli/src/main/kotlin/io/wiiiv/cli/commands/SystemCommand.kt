package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.client.WiiivClient
import io.wiiiv.cli.output.Printer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * wiiiv system - 시스템 정보 (비판단)
 */
class SystemCommand : CliktCommand(
    name = "system",
    help = "시스템 정보 조회"
) {
    override fun run() = Unit

    init {
        subcommands(
            SystemHealth(),
            SystemInfo(),
            SystemExecutors(),
            SystemGates(),
            SystemPersonas()
        )
    }
}

/**
 * wiiiv system health
 */
class SystemHealth : CliktCommand(
    name = "health",
    help = "헬스 체크"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = WiiivClient(ctx.apiUrl)
        try {
            val response = client.health()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val status = data?.get("status")?.jsonPrimitive?.content ?: "unknown"
            val checks = data?.get("checks")?.jsonObject

            println("System Status: $status")

            if (checks != null) {
                println()
                println("Health Checks:")
                checks.forEach { (name, check) ->
                    val checkObj = check.jsonObject
                    val checkStatus = checkObj["status"]?.jsonPrimitive?.content ?: "unknown"
                    val icon = if (checkStatus == "healthy") "✓" else "✗"
                    println("  $icon $name: $checkStatus")
                }
            }

        } catch (e: Exception) {
            Printer.error(ctx, "Server unreachable: ${e.message}")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv system info
 */
class SystemInfo : CliktCommand(
    name = "info",
    help = "시스템 정보"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = WiiivClient(ctx.apiUrl)
        try {
            val response = client.info()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            println("wiiiv Server")
            println("  Version: ${data?.get("version")?.jsonPrimitive?.content ?: "unknown"}")
            println("  Status: ${data?.get("status")?.jsonPrimitive?.content ?: "unknown"}")
            println("  Uptime: ${formatUptime(data?.get("uptime")?.jsonPrimitive?.longOrNull ?: 0)}")
            println("  API URL: ${ctx.apiUrl}")

        } catch (e: Exception) {
            Printer.error(ctx, "Server unreachable: ${e.message}")
        } finally {
            client.close()
        }
    }

    private fun formatUptime(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        val days = hours / 24

        return when {
            days > 0 -> "${days}d ${hours % 24}h"
            hours > 0 -> "${hours}h ${minutes % 60}m"
            minutes > 0 -> "${minutes}m ${seconds % 60}s"
            else -> "${seconds}s"
        }
    }
}

/**
 * wiiiv system executors
 */
class SystemExecutors : CliktCommand(
    name = "executors",
    help = "등록된 Executor 목록"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.listExecutors()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val executors = data?.get("executors")?.jsonArray ?: JsonArray(emptyList())

            println("Registered Executors (${executors.size}):")
            println()

            Printer.table(
                ctx,
                listOf("ID", "TYPE", "STATUS", "SUPPORTED STEPS"),
                executors.map { exec ->
                    val obj = exec.jsonObject
                    val stepTypes = obj["supportedStepTypes"]?.jsonArray
                        ?.joinToString(", ") { it.jsonPrimitive.content }
                        ?: ""
                    listOf(
                        obj["id"]?.jsonPrimitive?.content ?: "",
                        obj["type"]?.jsonPrimitive?.content ?: "",
                        obj["status"]?.jsonPrimitive?.content ?: "",
                        if (stepTypes.length > 40) stepTypes.take(37) + "..." else stepTypes
                    )
                }
            )

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list executors")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv system gates
 */
class SystemGates : CliktCommand(
    name = "gates",
    help = "등록된 Gate 목록"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.listGates()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val gates = data?.get("gates")?.jsonArray ?: JsonArray(emptyList())

            println("Gate Chain (${gates.size} gates):")
            println()

            gates.forEachIndexed { index, gate ->
                val obj = gate.jsonObject
                val id = obj["id"]?.jsonPrimitive?.content ?: ""
                val type = obj["type"]?.jsonPrimitive?.content ?: ""
                val status = obj["status"]?.jsonPrimitive?.content ?: ""
                val statusIcon = if (status == "active") "●" else "○"

                println("  ${index + 1}. $statusIcon $type")
                println("     ID: $id")
            }

            println()
            println("Execution Flow: Request → ${gates.joinToString(" → ") {
                it.jsonObject["type"]?.jsonPrimitive?.content?.split(" ")?.first() ?: "?"
            }} → Executor")

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list gates")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv system personas
 */
class SystemPersonas : CliktCommand(
    name = "personas",
    help = "DACS 페르소나 목록"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.listPersonas()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val personas = data?.get("personas")?.jsonArray ?: JsonArray(emptyList())

            println("DACS Personas (${personas.size}):")
            println()

            personas.forEach { persona ->
                val obj = persona.jsonObject
                println("  ${obj["name"]?.jsonPrimitive?.content}")
                println("    ID: ${obj["id"]?.jsonPrimitive?.content}")
                println("    Role: ${obj["role"]?.jsonPrimitive?.content}")
                println()
            }

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list personas")
        } finally {
            client.close()
        }
    }
}
