package io.wiiiv.cli.commands

import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import io.wiiiv.cli.ShellSettings
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
            println("  ${c.WHITE}verbose${c.RESET}       ${settings.verbose} (${ShellSettings.verboseName(settings.verbose)})")
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
                if (value == null) {
                    // 값 없이 실행: 현재 레벨 + 전체 레벨 설명 표시
                    println()
                    println("  verbose = ${settings.verbose} (${ShellSettings.verboseName(settings.verbose)})")
                    println()
                    for (i in 0..3) {
                        val marker = if (i == settings.verbose) " ${c.BRIGHT_CYAN}← current${c.RESET}" else ""
                        val desc = when (i) {
                            0 -> "최종 답변만"
                            1 -> "진행 상태 + 최종 답변"
                            2 -> "중간 결과 + 실행 상세"
                            3 -> "raw 데이터 전체"
                            else -> ""
                        }
                        println("  ${c.WHITE}$i${c.RESET}  ${ShellSettings.VERBOSE_NAMES[i].padEnd(10)} $desc$marker")
                    }
                    println()
                    return
                }

                // 숫자, 이름, on/off로 설정
                val level = when (value) {
                    "off" -> 0
                    "on" -> 2
                    else -> value.toIntOrNull()
                        ?: ShellSettings.verboseFromName(value)
                }

                if (level != null && level in 0..3) {
                    settings.verbose = level
                    println("  verbose = $level (${ShellSettings.verboseName(level)})")
                } else {
                    println("  ${c.RED}Usage: /set verbose <0-3|on|off|quiet|normal|detailed|debug>${c.RESET}")
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
