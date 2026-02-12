package io.wiiiv.cli.commands

import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import io.wiiiv.cli.client.ControlRequest
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * Tier 3+4 — /switch, /cancel, /set
 */
object ControlCommands {

    fun handleSwitch(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val targetId = args.firstOrNull()

        if (targetId == null) {
            println("  ${c.RED}Usage: /switch <task-id>${c.RESET}")
            println("  Use /tasks to see available tasks.")
            return
        }

        runBlocking {
            try {
                val result = ctx.client.controlSession(
                    ctx.sessionId,
                    ControlRequest("switch", targetId = targetId)
                )
                println("  ${result.message}")
            } catch (e: Exception) {
                println("  ${c.RED}${e.message}${c.RESET}")
            }
        }
    }

    fun handleCancel(args: List<String>, ctx: ShellContext) {
        val c = ShellColors

        if (args.firstOrNull()?.lowercase() == "all") {
            if (ctx.confirm("Cancel all tasks and reset session?")) {
                runBlocking {
                    try {
                        val result = ctx.client.controlSession(
                            ctx.sessionId,
                            ControlRequest("cancelAll")
                        )
                        println("  ${result.message}")
                    } catch (e: Exception) {
                        println("  ${c.RED}${e.message}${c.RESET}")
                    }
                }
            } else {
                println("  Cancelled.")
            }
            return
        }

        runBlocking {
            try {
                val result = ctx.client.controlSession(
                    ctx.sessionId,
                    ControlRequest("cancel")
                )
                println("  ${result.message}")
            } catch (e: Exception) {
                println("  ${c.RED}${e.message}${c.RESET}")
            }
        }
    }

    fun handleSet(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val settings = ctx.settings

        if (args.isEmpty()) {
            println()
            println("  ${c.BRIGHT_CYAN}[SETTINGS]${c.RESET}")
            println()
            println("  ${c.WHITE}autocontinue${c.RESET}  ${if (settings.autoContinue) "on" else "off"}")
            println("  ${c.WHITE}maxcontinue${c.RESET}   ${settings.maxContinue}")
            println("  ${c.WHITE}verbose${c.RESET}       ${if (settings.verbose) "on" else "off"}")
            println("  ${c.WHITE}color${c.RESET}         ${if (settings.color) "on" else "off"}")
            println("  ${c.WHITE}workspace${c.RESET}     ${settings.workspace ?: "${c.DIM}(not set)${c.RESET}"}")
            println()
            return
        }

        val key = args[0].lowercase()
        val value = args.getOrNull(1)?.lowercase()

        when (key) {
            "autocontinue" -> {
                when (value) {
                    "on", "true" -> { settings.autoContinue = true; println("  autocontinue = on") }
                    "off", "false" -> { settings.autoContinue = false; println("  autocontinue = off") }
                    else -> println("  ${c.RED}Usage: /set autocontinue <on|off>${c.RESET}")
                }
            }
            "maxcontinue" -> {
                val n = value?.toIntOrNull()
                if (n != null && n in 1..50) {
                    settings.maxContinue = n
                    println("  maxcontinue = $n")
                } else {
                    println("  ${c.RED}Usage: /set maxcontinue <1-50>${c.RESET}")
                }
            }
            "verbose" -> {
                when (value) {
                    "on", "true" -> { settings.verbose = true; println("  verbose = on") }
                    "off", "false" -> { settings.verbose = false; println("  verbose = off") }
                    else -> println("  ${c.RED}Usage: /set verbose <on|off>${c.RESET}")
                }
            }
            "color" -> {
                when (value) {
                    "on", "true" -> {
                        settings.color = true
                        ShellColors.enabled = true
                        println("  color = on")
                    }
                    "off", "false" -> {
                        settings.color = false
                        ShellColors.enabled = false
                        println("  color = off")
                    }
                    else -> println("  ${c.RED}Usage: /set color <on|off>${c.RESET}")
                }
            }
            "workspace" -> {
                val rawPath = args.drop(1).joinToString(" ").trim()
                if (rawPath.isBlank()) {
                    val current = settings.workspace
                    if (current != null) {
                        println("  workspace = $current")
                    } else {
                        println("  ${c.DIM}workspace not set${c.RESET}")
                        println("  ${c.DIM}Usage: /set workspace <path>${c.RESET}")
                    }
                    return
                }

                val resolved = resolvePath(rawPath)
                val dir = File(resolved)

                if (!dir.exists()) {
                    dir.mkdirs()
                    println("  ${c.DIM}Created directory: $resolved${c.RESET}")
                } else if (!dir.isDirectory) {
                    println("  ${c.RED}Not a directory: $resolved${c.RESET}")
                    return
                }

                settings.workspace = resolved

                // 서버에도 전달
                runBlocking {
                    try {
                        ctx.client.controlSession(
                            ctx.sessionId,
                            ControlRequest("setWorkspace", workspace = resolved)
                        )
                    } catch (_: Exception) {}
                }
                println("  workspace = $resolved")
            }
            else -> {
                println("  ${c.RED}Unknown setting: $key${c.RESET}")
                println("  Available: autocontinue, maxcontinue, verbose, color, workspace")
            }
        }
    }
}
