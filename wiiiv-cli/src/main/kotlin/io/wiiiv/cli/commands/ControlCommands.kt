package io.wiiiv.cli.commands

import io.wiiiv.governor.TaskStatus
import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
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

        val context = ctx.session.context
        val targetTask = context.tasks[targetId]

        if (targetTask == null) {
            println("  ${c.RED}Task #$targetId not found.${c.RESET} Use /tasks to see available tasks.")
            return
        }

        if (targetTask.id == context.activeTaskId) {
            println("  Task #${targetTask.id} is already active.")
            return
        }

        // Suspend current work
        val suspended = ctx.session.suspendCurrentWork()
        if (suspended != null) {
            println("  Task #${suspended.id} \"${suspended.label}\" suspended.")
        }

        // Activate target
        targetTask.status = TaskStatus.ACTIVE
        context.activeTaskId = targetTask.id
        println("  Switched to Task #${targetTask.id} \"${targetTask.label}\".")
    }

    fun handleCancel(args: List<String>, ctx: ShellContext) {
        val c = ShellColors

        if (args.firstOrNull()?.lowercase() == "all") {
            // /cancel all — requires confirmation
            if (ctx.confirm("Cancel all tasks and reset session?")) {
                ctx.session.resetAll()
                println("  All tasks cancelled. Session reset.")
            } else {
                println("  Cancelled.")
            }
            return
        }

        // /cancel — current task only
        val activeTask = ctx.session.context.activeTask
        if (activeTask == null) {
            println("  ${c.DIM}No active task to cancel.${c.RESET}")
            return
        }

        val label = activeTask.label
        val id = activeTask.id
        ctx.session.cancelCurrentTask()
        println("  Task #$id \"$label\" cancelled.")
    }

    fun handleSet(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val settings = ctx.settings

        if (args.isEmpty()) {
            // /set — show all settings
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
                ctx.session.context.workspace = resolved
                println("  workspace = $resolved")
            }
            else -> {
                println("  ${c.RED}Unknown setting: $key${c.RESET}")
                println("  Available: autocontinue, maxcontinue, verbose, color, workspace")
            }
        }
    }
}
