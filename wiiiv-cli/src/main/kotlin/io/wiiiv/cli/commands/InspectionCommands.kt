package io.wiiiv.cli.commands

import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import io.wiiiv.cli.ShellRenderer
import kotlinx.coroutines.runBlocking

/**
 * Tier 2 — /spec, /blueprint, /result, /artifacts
 */
object InspectionCommands {

    fun handleSpec(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        runBlocking {
            try {
                val state = ctx.client.getSessionState(ctx.sessionId)
                val spec = state.spec

                println()
                if (spec == null) {
                    println("  ${c.DIM}No spec available.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[SPEC]${c.RESET} Draft #${spec.id}")
                println()

                val taskType = spec.taskTypeDisplayName ?: "${c.DIM}(not set)${c.RESET}"
                val intent = spec.intent ?: "${c.DIM}(not set)${c.RESET}"
                val complete = if (spec.isComplete) "${c.GREEN}Yes${c.RESET}" else "No"
                val risky = if (spec.isRisky) "${c.YELLOW}Yes${c.RESET}" else "No"
                val filled = spec.filledSlots
                val required = spec.requiredSlots

                println("  Task type:  $taskType")
                println("  Intent:     $intent")
                println("  Complete:   $complete (${filled.size}/${required.size} required)")
                println("  Risky:      $risky")
                println()

                // Slot details
                println("  Slots:")
                val allSlots = listOf(
                    "intent" to spec.intent,
                    "taskType" to spec.taskType,
                    "domain" to spec.domain,
                    "techStack" to spec.techStack?.joinToString(", "),
                    "targetPath" to spec.targetPath,
                    "content" to spec.content?.let { if (it.length > 40) it.take(37) + "..." else it },
                    "scale" to spec.scale,
                    "constraints" to spec.constraints?.joinToString(", ")
                )

                for ((name, value) in allSlots) {
                    val isFilled = value != null
                    val mark = if (isFilled) "${c.GREEN}v${c.RESET}" else " "
                    val display = if (isFilled) "\"$value\"" else "${c.DIM}(missing)${c.RESET}"
                    val requiredMark = if (name in required) "" else "${c.DIM} (optional)${c.RESET}"
                    println("    [$mark] ${name.padEnd(14)} $display$requiredMark")
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    fun handleBlueprint(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val summary = ctx.lastExecutionSummary

        println()
        if (summary == null) {
            println("  ${c.DIM}No blueprint available. Use /result to see last execution.${c.RESET}")
            println()
            return
        }

        println("  ${c.BRIGHT_CYAN}[BLUEPRINT]${c.RESET} ${summary.blueprintId}")
        println("  Steps: ${summary.steps.size}")
        println()

        summary.steps.forEachIndexed { i, step ->
            val paramStr = step.params.entries.joinToString("  ") { (k, v) ->
                val display = if (v.length > 40) "${v.take(37)}..." else v
                "$k=$display"
            }
            println("    ${i + 1}. ${step.type.padEnd(14)} $paramStr")
        }
        println()
    }

    fun handleResult(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val summary = ctx.lastExecutionSummary

        if (summary == null) {
            println()
            println("  ${c.DIM}No execution result.${c.RESET}")
            println()
            return
        }

        println()
        println("  ${c.BRIGHT_CYAN}[RESULT]${c.RESET} Blueprint: ${summary.blueprintId}")
        println()

        val successStr = if (summary.failureCount == 0) "${c.GREEN}Yes${c.RESET}" else "${c.RED}No${c.RESET}"
        println("  Success:    $successStr (${summary.successCount}/${summary.successCount + summary.failureCount} steps)")
        println()

        println("  Steps:")
        summary.steps.forEachIndexed { i, step ->
            val status = if (step.success) "${c.GREEN}OK${c.RESET}" else "${c.RED}FAIL${c.RESET}"
            val duration = if (step.durationMs > 0) "${step.durationMs}ms" else ""
            val artifacts = step.artifacts.keys.takeIf { it.isNotEmpty() }?.joinToString(", ")?.let { " artifacts: $it" } ?: ""

            println("    ${i + 1}. ${step.type.padEnd(14)} $status  ${c.DIM}$duration${c.RESET}$artifacts")

            // stdout/stderr in verbose mode (level 2+) or on failure
            if (ctx.settings.verbose >= 2 || !step.success) {
                step.stdout?.takeIf { it.isNotBlank() }?.let { out ->
                    val display = if (ctx.settings.verbose >= 3) out else out.take(200)
                    println("       ${c.DIM}stdout: \"$display\"${c.RESET}")
                }
                step.stderr?.takeIf { it.isNotBlank() }?.let { err ->
                    println("       ${c.RED}stderr: \"$err\"${c.RESET}")
                }
                step.error?.takeIf { it.isNotBlank() }?.let { err ->
                    println("       ${c.RED}error: \"$err\"${c.RESET}")
                }
            }
        }
        println()
    }

    fun handleArtifacts(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        runBlocking {
            try {
                val state = ctx.client.getSessionState(ctx.sessionId)
                var totalCount = 0

                println()

                for (task in state.tasks) {
                    if (task.artifacts.isNotEmpty()) {
                        totalCount += task.artifacts.size
                        println("  Task #${task.id} \"${task.label}\":")
                        for ((name, value) in task.artifacts) {
                            println("    ${c.WHITE}$name${c.RESET}     $value")
                        }
                        println()
                    }
                }

                if (totalCount == 0) {
                    println("  ${c.DIM}No artifacts.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[ARTIFACTS]${c.RESET} $totalCount items")
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }
}
