package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.output.Printer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * wiiiv blueprint - 설계 결과 (inspect 가능한 자산)
 *
 * ❗ blueprint는 실행 전 마지막 검토 지점
 * CLI는 여기서도 판단하지 않음
 */
class BlueprintCommand : CliktCommand(
    name = "blueprint",
    help = "Blueprint (실행 계획) 관리"
) {
    override fun run() = Unit

    init {
        subcommands(
            BlueprintGet(),
            BlueprintList(),
            BlueprintInspect(),
            BlueprintValidate(),
            BlueprintExport()
        )
    }
}

/**
 * wiiiv blueprint get <blueprint-id>
 */
class BlueprintGet : CliktCommand(
    name = "get",
    help = "Blueprint 상세 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("blueprint-id", help = "Blueprint ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.getBlueprint(id)
            Printer.print(ctx, response)
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to get blueprint")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv blueprint list
 */
class BlueprintList : CliktCommand(
    name = "list",
    help = "Blueprint 목록 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val page by option("-p", "--page", help = "페이지 번호").int().default(1)
    private val pageSize by option("-s", "--size", help = "페이지 크기").int().default(20)

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.listBlueprints(page, pageSize)

            if (ctx.json) {
                Printer.print(ctx, response)
            } else {
                val data = response["data"]?.jsonObject
                val blueprints = data?.get("blueprints")?.jsonArray ?: JsonArray(emptyList())
                val total = data?.get("total")?.jsonPrimitive?.intOrNull ?: 0

                if (blueprints.isEmpty()) {
                    println("No blueprints found")
                    return@runBlocking
                }

                println("Blueprints ($total total):")
                println()

                Printer.table(
                    ctx,
                    listOf("ID", "STATUS", "NODES", "CREATED"),
                    blueprints.map { bp ->
                        val obj = bp.jsonObject
                        listOf(
                            obj["id"]?.jsonPrimitive?.content?.take(12) ?: "",
                            obj["status"]?.jsonPrimitive?.content ?: "",
                            obj["nodeCount"]?.jsonPrimitive?.content ?: "0",
                            obj["createdAt"]?.jsonPrimitive?.content?.take(19) ?: ""
                        )
                    }
                )
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list blueprints")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv blueprint inspect <blueprint-id>
 *
 * 상세 구조 출력:
 * - 실행 단계
 * - Executor 종류
 * - 파라미터
 * - 정책 정보
 */
class BlueprintInspect : CliktCommand(
    name = "inspect",
    help = "Blueprint 구조 상세 분석"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("blueprint-id", help = "Blueprint ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.getBlueprint(id)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            if (data == null) {
                Printer.error(ctx, "Blueprint not found")
                return@runBlocking
            }

            println("Blueprint: ${data["id"]?.jsonPrimitive?.content}")
            println("Status: ${data["status"]?.jsonPrimitive?.content}")
            println("Created: ${data["createdAt"]?.jsonPrimitive?.content}")
            println()

            val structure = data["structure"]?.jsonObject
            val nodesElement = structure?.get("nodes")
            val nodes = if (nodesElement is JsonArray) nodesElement else JsonArray(emptyList())
            val edgesElement = structure?.get("edges")
            val edges = if (edgesElement is JsonArray) edgesElement else JsonArray(emptyList())

            println("Execution Plan (${nodes.size} steps):")
            println("─".repeat(50))

            nodes.forEachIndexed { index, node ->
                val nodeObj = node.jsonObject
                val nodeId = nodeObj["id"]?.jsonPrimitive?.content ?: ""
                val nodeType = nodeObj["type"]?.jsonPrimitive?.content ?: ""
                val configElement = nodeObj["config"]
                val config = if (configElement is JsonObject) configElement else null
                val dependsOnElement = nodeObj["dependsOn"]
                val dependsOn = if (dependsOnElement is JsonArray) dependsOnElement else null

                println()
                println("Step ${index + 1}: $nodeId")
                println("  Type: $nodeType")

                if (config != null && config.isNotEmpty()) {
                    println("  Config:")
                    config.forEach { (key, value) ->
                        val valueStr = when (value) {
                            is JsonPrimitive -> value.content
                            else -> value.toString()
                        }
                        println("    $key: $valueStr")
                    }
                }

                if (dependsOn != null && dependsOn.isNotEmpty()) {
                    println("  Depends on: ${dependsOn.joinToString { it.jsonPrimitive.content }}")
                }
            }

            if (edges.isNotEmpty()) {
                println()
                println("Edges:")
                edges.forEach { edge ->
                    val edgeObj = edge.jsonObject
                    val from = edgeObj["from"]?.jsonPrimitive?.content ?: ""
                    val to = edgeObj["to"]?.jsonPrimitive?.content ?: ""
                    println("  $from → $to")
                }
            }

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to inspect blueprint")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv blueprint validate <blueprint-id>
 */
class BlueprintValidate : CliktCommand(
    name = "validate",
    help = "Blueprint 유효성 검증"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("blueprint-id", help = "Blueprint ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.validateBlueprint(id)
            Printer.print(ctx, response)

            if (!ctx.json && !ctx.quiet) {
                val data = response["data"]?.jsonObject
                val valid = data?.get("valid")?.jsonPrimitive?.booleanOrNull ?: false
                val errors = data?.get("errors")?.jsonArray ?: JsonArray(emptyList())
                val warnings = data?.get("warnings")?.jsonArray ?: JsonArray(emptyList())

                println()
                if (valid) {
                    println("✓ Blueprint is valid")
                } else {
                    println("✗ Blueprint has ${errors.size} error(s)")
                }

                if (warnings.isNotEmpty()) {
                    println("⚠ ${warnings.size} warning(s)")
                }
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to validate blueprint")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv blueprint export <blueprint-id>
 */
class BlueprintExport : CliktCommand(
    name = "export",
    help = "Blueprint를 JSON 파일로 내보내기"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("blueprint-id", help = "Blueprint ID")
    private val output by option("-o", "--output", help = "출력 파일 경로")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.getBlueprint(id)
            val json = Json { prettyPrint = true }
            val jsonStr = json.encodeToString(JsonObject.serializer(), response)

            if (output != null) {
                java.io.File(output!!).writeText(jsonStr)
                Printer.success(ctx, "Exported to $output")
            } else {
                println(jsonStr)
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to export blueprint")
        } finally {
            client.close()
        }
    }
}
