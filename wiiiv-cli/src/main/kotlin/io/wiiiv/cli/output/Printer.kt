package io.wiiiv.cli.output

import io.wiiiv.cli.CliContext
import kotlinx.serialization.json.*

/**
 * CLI 출력 담당
 *
 * - json 모드: JSON 그대로 출력 (자동화용)
 * - 기본 모드: 인간 친화적 포맷
 *
 * 출력은 판단을 포함하지 않는다
 */
object Printer {
    private val json = Json { prettyPrint = true }

    fun print(ctx: CliContext, data: JsonObject) {
        if (ctx.quiet) return

        if (ctx.json) {
            println(json.encodeToString(JsonObject.serializer(), data))
        } else {
            printHumanReadable(data, ctx.trace)
        }
    }

    fun success(ctx: CliContext, message: String) {
        if (ctx.quiet) return
        if (ctx.json) {
            println("""{"status": "success", "message": "$message"}""")
        } else {
            println("✓ $message")
        }
    }

    fun error(ctx: CliContext, message: String) {
        if (ctx.json) {
            System.err.println("""{"status": "error", "message": "$message"}""")
        } else {
            System.err.println("✗ Error: $message")
        }
    }

    fun info(ctx: CliContext, message: String) {
        if (ctx.quiet) return
        if (!ctx.json) {
            println("→ $message")
        }
    }

    private fun printHumanReadable(obj: JsonObject, trace: Boolean, indent: Int = 0) {
        val prefix = "  ".repeat(indent)

        // success 필드 확인
        val success = obj["success"]?.jsonPrimitive?.booleanOrNull
        if (success == false) {
            val error = obj["error"]?.jsonObject
            if (error != null) {
                println("${prefix}Error: ${error["message"]?.jsonPrimitive?.content ?: "Unknown error"}")
                return
            }
        }

        // data 필드가 있으면 그것을 출력
        val data = obj["data"]
        if (data != null) {
            printJsonElement(data, indent, trace)
        } else {
            printJsonElement(obj, indent, trace)
        }
    }

    private fun printJsonElement(element: JsonElement, indent: Int, trace: Boolean) {
        val prefix = "  ".repeat(indent)

        when (element) {
            is JsonObject -> {
                element.forEach { (key, value) ->
                    when (value) {
                        is JsonPrimitive -> {
                            println("$prefix$key: ${value.content}")
                        }
                        is JsonArray -> {
                            println("$prefix$key:")
                            value.forEachIndexed { index, item ->
                                when (item) {
                                    is JsonPrimitive -> println("$prefix  - ${item.content}")
                                    is JsonObject -> {
                                        println("$prefix  [$index]")
                                        printJsonElement(item, indent + 2, trace)
                                    }
                                    else -> println("$prefix  - $item")
                                }
                            }
                        }
                        is JsonObject -> {
                            println("$prefix$key:")
                            printJsonElement(value, indent + 1, trace)
                        }
                        else -> println("$prefix$key: $value")
                    }
                }
            }
            is JsonArray -> {
                element.forEachIndexed { index, item ->
                    println("$prefix[$index]")
                    printJsonElement(item, indent + 1, trace)
                }
            }
            is JsonPrimitive -> {
                println("$prefix${element.content}")
            }
            else -> println("$prefix$element")
        }
    }

    // 테이블 출력
    fun table(ctx: CliContext, headers: List<String>, rows: List<List<String>>) {
        if (ctx.quiet) return
        if (ctx.json) {
            val jsonRows = rows.map { row ->
                headers.zip(row).toMap()
            }
            println(Json { prettyPrint = true }.encodeToString(
                JsonArray.serializer(),
                JsonArray(jsonRows.map { row ->
                    JsonObject(row.mapValues { JsonPrimitive(it.value) })
                })
            ))
            return
        }

        // 컬럼 너비 계산
        val widths = headers.indices.map { col ->
            maxOf(
                headers[col].length,
                rows.maxOfOrNull { it.getOrElse(col) { "" }.length } ?: 0
            )
        }

        // 헤더 출력
        val headerLine = headers.mapIndexed { i, h -> h.padEnd(widths[i]) }.joinToString(" │ ")
        val separator = widths.joinToString("─┼─") { "─".repeat(it) }

        println(headerLine)
        println(separator)

        // 행 출력
        rows.forEach { row ->
            val line = row.mapIndexed { i, cell -> cell.padEnd(widths[i]) }.joinToString(" │ ")
            println(line)
        }
    }
}
