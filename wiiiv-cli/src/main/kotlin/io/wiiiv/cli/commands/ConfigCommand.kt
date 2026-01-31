package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.output.Printer
import kotlinx.serialization.json.*
import java.io.File

/**
 * wiiiv config - CLI 로컬 설정
 *
 * 서버 상태 ❌
 * CLI UX 편의만 담당
 */
class ConfigCommand : CliktCommand(
    name = "config",
    help = "CLI 설정 관리"
) {
    override fun run() = Unit

    init {
        subcommands(
            ConfigShow(),
            ConfigSet(),
            ConfigGet(),
            ConfigReset()
        )
    }
}

private val json = Json { prettyPrint = true }

private fun getConfigFile(): File {
    val wiiivDir = File(System.getProperty("user.home"), ".wiiiv")
    if (!wiiivDir.exists()) wiiivDir.mkdirs()
    return File(wiiivDir, "config.json")
}

private fun loadConfig(): JsonObject {
    val file = getConfigFile()
    return if (file.exists()) {
        try {
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    } else {
        getDefaultConfig()
    }
}

private fun saveConfig(config: JsonObject) {
    getConfigFile().writeText(json.encodeToString(JsonObject.serializer(), config))
}

private fun getDefaultConfig(): JsonObject = buildJsonObject {
    put("api.baseUrl", "http://localhost:8235")
    put("output.color", true)
    put("output.format", "human")
}

/**
 * wiiiv config show
 */
class ConfigShow : CliktCommand(
    name = "show",
    help = "현재 설정 표시"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() {
        val config = loadConfig()

        if (ctx.json) {
            println(json.encodeToString(JsonObject.serializer(), config))
            return
        }

        println("CLI Configuration (~/.wiiiv/config.json):")
        println()

        if (config.isEmpty()) {
            println("  (using defaults)")
            getDefaultConfig().forEach { (key, value) ->
                println("  $key = ${value.jsonPrimitive.content} (default)")
            }
        } else {
            config.forEach { (key, value) ->
                println("  $key = ${value.jsonPrimitive.content}")
            }
        }
    }
}

/**
 * wiiiv config set <key> <value>
 */
class ConfigSet : CliktCommand(
    name = "set",
    help = "설정 값 변경"
) {
    private val ctx by requireObject<CliContext>()
    private val key by argument("key", help = "설정 키 (예: api.baseUrl)")
    private val value by argument("value", help = "설정 값")

    override fun run() {
        val config = loadConfig().toMutableMap()
        config[key] = JsonPrimitive(value)
        saveConfig(JsonObject(config))

        Printer.success(ctx, "Set $key = $value")
    }
}

/**
 * wiiiv config get <key>
 */
class ConfigGet : CliktCommand(
    name = "get",
    help = "설정 값 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val key by argument("key", help = "설정 키")

    override fun run() {
        val config = loadConfig()
        val value = config[key]?.jsonPrimitive?.content

        if (value != null) {
            if (ctx.json) {
                println("""{"$key": "$value"}""")
            } else {
                println(value)
            }
        } else {
            // Check defaults
            val defaultValue = getDefaultConfig()[key]?.jsonPrimitive?.content
            if (defaultValue != null) {
                if (ctx.json) {
                    println("""{"$key": "$defaultValue", "source": "default"}""")
                } else {
                    println("$defaultValue (default)")
                }
            } else {
                Printer.error(ctx, "Unknown config key: $key")
            }
        }
    }
}

/**
 * wiiiv config reset
 */
class ConfigReset : CliktCommand(
    name = "reset",
    help = "설정 초기화"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() {
        val configFile = getConfigFile()
        if (configFile.exists()) {
            configFile.delete()
        }
        Printer.success(ctx, "Configuration reset to defaults")
    }
}
