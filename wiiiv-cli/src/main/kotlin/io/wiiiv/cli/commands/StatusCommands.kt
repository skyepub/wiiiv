package io.wiiiv.cli.commands

import io.wiiiv.governor.MessageRole
import io.wiiiv.governor.TaskStatus
import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Tier 1+2 — /status, /history, /tasks
 */
object StatusCommands {

    fun handleStatus(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val session = ctx.session
        val context = session.context

        // Uptime
        val uptimeMs = System.currentTimeMillis() - session.createdAt
        val uptimeStr = formatUptime(uptimeMs)

        println()
        println("  ${c.BRIGHT_CYAN}[SESSION]${c.RESET} gov-shell / ${session.sessionId}")
        println("  Uptime: $uptimeStr | Turns: ${session.history.size}")
        println()

        // LLM
        if (ctx.llmProviderPresent) {
            println("  ${c.BRIGHT_CYAN}[LLM]${c.RESET} ${ctx.modelName ?: "unknown"}")
        } else {
            println("  ${c.BRIGHT_CYAN}[LLM]${c.RESET} ${c.DIM}none (basic mode)${c.RESET}")
        }

        // DACS
        val dacsDetail = when (ctx.dacsTypeName) {
            "HybridDACS" -> "HybridDACS (3 rule + 3 LLM)"
            "SimpleDACS" -> "SimpleDACS (3 rule-based)"
            else -> ctx.dacsTypeName
        }
        println("  ${c.BRIGHT_CYAN}[DACS]${c.RESET} $dacsDetail")
        println()

        // Active task
        val activeTask = context.activeTask
        if (activeTask != null) {
            val spec = activeTask.draftSpec
            val filled = spec.getFilledSlots().size
            val required = spec.getRequiredSlots().size
            val complete = if (spec.isComplete()) "complete" else "incomplete"
            val risky = if (spec.isRisky()) "${c.YELLOW}Yes${c.RESET}" else "No"

            println("  ${c.BRIGHT_CYAN}[TASK]${c.RESET} #${activeTask.id} \"${activeTask.label}\" (${c.CYAN}ACTIVE${c.RESET})")
            spec.taskType?.let { println("    Type: ${it.displayName}") }
            spec.targetPath?.let { println("    Path: $it") }
            println("    Spec: $filled/$required slots filled ($complete)")
            println("    Turns: ${activeTask.context.executionHistory.size} | Risky: $risky")
            context.declaredWriteIntent?.let { println("    Write intent: ${if (it) "declared" else "none"}") }
        } else {
            println("  ${c.DIM}[TASK] No active task${c.RESET}")
        }
        println()

        // Task summary
        val tasks = context.tasks.values
        val activeCount = tasks.count { it.status == TaskStatus.ACTIVE }
        val suspendedCount = tasks.count { it.status == TaskStatus.SUSPENDED }
        val completedCount = tasks.count { it.status == TaskStatus.COMPLETED }
        println("  ${c.BRIGHT_CYAN}[TASKS]${c.RESET} $activeCount active, $suspendedCount suspended, $completedCount completed")

        // Settings summary
        println()
        println("  ${c.BRIGHT_CYAN}[SETTINGS]${c.RESET} autocontinue=${if (ctx.settings.autoContinue) "on" else "off"} maxcontinue=${ctx.settings.maxContinue} verbose=${if (ctx.settings.verbose) "on" else "off"} color=${if (ctx.settings.color) "on" else "off"}")
        println()
    }

    fun handleHistory(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val count = args.firstOrNull()?.toIntOrNull() ?: 10
        val messages = ctx.session.getRecentHistory(count)

        println()
        if (messages.isEmpty()) {
            println("  ${c.DIM}No conversation history.${c.RESET}")
            println()
            return
        }

        println("  ${c.BRIGHT_CYAN}[HISTORY]${c.RESET} Last ${messages.size} messages")
        println()

        val timeFormat = SimpleDateFormat("HH:mm:ss")
        for (msg in messages) {
            // SYSTEM messages only in verbose mode
            if (msg.role == MessageRole.SYSTEM && !ctx.settings.verbose) continue

            val ts = timeFormat.format(Date(msg.timestamp))
            val roleColor = when (msg.role) {
                MessageRole.USER -> c.WHITE
                MessageRole.GOVERNOR -> c.CYAN
                MessageRole.SYSTEM -> c.DIM
            }
            val roleName = msg.role.name.padEnd(10)
            val content = msg.content.let {
                if (it.length > 80) it.take(77) + "..." else it
            }.replace("\n", " ")

            println("  ${c.DIM}$ts${c.RESET}  $roleColor$roleName${c.RESET}$content")
        }
        println()
    }

    fun handleTasks(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val tasks = ctx.session.context.tasks.values.toList()
        val activeTaskId = ctx.session.context.activeTaskId

        println()
        if (tasks.isEmpty()) {
            println("  ${c.DIM}No tasks.${c.RESET}")
            println()
            return
        }

        println("  ${c.BRIGHT_CYAN}[TASKS]${c.RESET} ${tasks.size} total")
        println()

        val timeFormat = SimpleDateFormat("HH:mm")
        for (task in tasks) {
            val marker = if (task.id == activeTaskId) "${c.CYAN}*${c.RESET}" else " "
            val statusColor = when (task.status) {
                TaskStatus.ACTIVE -> c.CYAN
                TaskStatus.SUSPENDED -> c.YELLOW
                TaskStatus.COMPLETED -> c.DIM
            }
            val statusStr = "$statusColor${task.status.name.padEnd(10)}${c.RESET}"
            val typeStr = task.draftSpec.taskType?.displayName ?: ""
            val ts = timeFormat.format(Date(task.createdAt))

            println("  $marker #${task.id.padEnd(14)} \"${task.label.take(25)}\"  $statusStr ${typeStr.padEnd(12)} ${c.DIM}$ts${c.RESET}")
        }
        println()
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
