package io.wiiiv.cli.commands

import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Tier 1+2 — /status, /history, /tasks
 */
object StatusCommands {

    fun handleStatus(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        runBlocking {
            try {
                val state = ctx.client.getSessionState(ctx.sessionId)

                // Uptime
                val uptimeMs = System.currentTimeMillis() - state.createdAt
                val uptimeStr = formatUptime(uptimeMs)

                println()
                println("  ${c.BRIGHT_CYAN}[SESSION]${c.RESET} ${state.sessionId}")
                println("  Uptime: $uptimeStr | Turns: ${state.turnCount}")
                println()

                // LLM
                val info = state.serverInfo
                if (info.llmAvailable) {
                    println("  ${c.BRIGHT_CYAN}[LLM]${c.RESET} ${info.modelName ?: "unknown"}")
                } else {
                    println("  ${c.BRIGHT_CYAN}[LLM]${c.RESET} ${c.DIM}none (basic mode)${c.RESET}")
                }

                // DACS
                val dacsDetail = when (info.dacsTypeName) {
                    "HybridDACS" -> "HybridDACS (3 rule + 3 LLM)"
                    "SimpleDACS" -> "SimpleDACS (3 rule-based)"
                    else -> info.dacsTypeName
                }
                println("  ${c.BRIGHT_CYAN}[DACS]${c.RESET} $dacsDetail")
                println()

                // Active task
                val activeTask = state.activeTask
                if (activeTask != null) {
                    val complete = if (activeTask.specComplete) "complete" else "incomplete"
                    val risky = if (activeTask.specRisky) "${c.YELLOW}Yes${c.RESET}" else "No"

                    println("  ${c.BRIGHT_CYAN}[TASK]${c.RESET} #${activeTask.id} \"${activeTask.label}\" (${c.CYAN}ACTIVE${c.RESET})")
                    activeTask.taskTypeDisplayName?.let { println("    Type: $it") }
                    activeTask.targetPath?.let { println("    Path: $it") }
                    println("    Spec: ${activeTask.filledSlotCount}/${activeTask.requiredSlotCount} slots filled ($complete)")
                    println("    Executions: ${activeTask.executionCount} | Risky: $risky")
                    state.declaredWriteIntent?.let { println("    Write intent: ${if (it) "declared" else "none"}") }
                } else {
                    println("  ${c.DIM}[TASK] No active task${c.RESET}")
                }
                println()

                // Task summary
                val activeCount = state.tasks.count { it.status == "ACTIVE" }
                val suspendedCount = state.tasks.count { it.status == "SUSPENDED" }
                val completedCount = state.tasks.count { it.status == "COMPLETED" }
                println("  ${c.BRIGHT_CYAN}[TASKS]${c.RESET} $activeCount active, $suspendedCount suspended, $completedCount completed")

                // Settings summary
                println()
                println("  ${c.BRIGHT_CYAN}[SETTINGS]${c.RESET} autocontinue=${if (ctx.settings.autoContinue) "on" else "off"} maxcontinue=${ctx.settings.maxContinue} verbose=${if (ctx.settings.verbose) "on" else "off"} color=${if (ctx.settings.color) "on" else "off"}")
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    fun handleHistory(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val count = args.firstOrNull()?.toIntOrNull() ?: 10

        runBlocking {
            try {
                val history = ctx.client.getHistory(ctx.sessionId, count)

                println()
                if (history.messages.isEmpty()) {
                    println("  ${c.DIM}No conversation history.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[HISTORY]${c.RESET} Last ${history.messages.size} messages (total: ${history.total})")
                println()

                val timeFormat = SimpleDateFormat("HH:mm:ss")
                for (msg in history.messages) {
                    // SYSTEM messages only in verbose mode
                    if (msg.role == "SYSTEM" && !ctx.settings.verbose) continue

                    val ts = timeFormat.format(Date(msg.timestamp))
                    val roleColor = when (msg.role) {
                        "USER" -> c.WHITE
                        "GOVERNOR" -> c.CYAN
                        "SYSTEM" -> c.DIM
                        else -> c.RESET
                    }
                    val roleName = msg.role.padEnd(10)
                    val content = msg.content.let {
                        if (it.length > 80) it.take(77) + "..." else it
                    }.replace("\n", " ")

                    println("  ${c.DIM}$ts${c.RESET}  $roleColor$roleName${c.RESET}$content")
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    fun handleTasks(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        runBlocking {
            try {
                val state = ctx.client.getSessionState(ctx.sessionId)
                val tasks = state.tasks

                println()
                if (tasks.isEmpty()) {
                    println("  ${c.DIM}No tasks.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[TASKS]${c.RESET} ${tasks.size} total")
                println()

                val timeFormat = SimpleDateFormat("HH:mm")
                val activeTaskId = state.activeTask?.id

                for (task in tasks) {
                    val marker = if (task.id == activeTaskId) "${c.CYAN}*${c.RESET}" else " "
                    val statusColor = when (task.status) {
                        "ACTIVE" -> c.CYAN
                        "SUSPENDED" -> c.YELLOW
                        "COMPLETED" -> c.DIM
                        else -> c.RESET
                    }
                    val statusStr = "$statusColor${task.status.padEnd(10)}${c.RESET}"
                    val typeStr = task.taskTypeDisplayName ?: ""
                    val ts = timeFormat.format(Date(task.createdAt))

                    println("  $marker #${task.id.padEnd(14)} \"${task.label.take(25)}\"  $statusStr ${typeStr.padEnd(12)} ${c.DIM}$ts${c.RESET}")
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun formatUptime(ms: Long): String {
        val totalSec = ms / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) "${hours}h ${minutes}m ${seconds}s"
        else if (minutes > 0) "${minutes}m ${seconds}s"
        else "${seconds}s"
    }
}
