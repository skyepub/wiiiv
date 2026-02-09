package io.wiiiv.shell.commands

import io.wiiiv.shell.ShellColors
import io.wiiiv.shell.ShellContext

/**
 * Tier 2 — /spec, /blueprint, /result, /artifacts
 */
object InspectionCommands {

    fun handleSpec(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val spec = ctx.session.draftSpec

        println()
        println("  ${c.BRIGHT_CYAN}[SPEC]${c.RESET} Draft #${spec.id}")
        println()

        val taskType = spec.taskType?.displayName ?: "${c.DIM}(not set)${c.RESET}"
        val intent = spec.intent ?: "${c.DIM}(not set)${c.RESET}"
        val complete = if (spec.isComplete()) "${c.GREEN}Yes${c.RESET}" else "No"
        val risky = if (spec.isRisky()) "${c.YELLOW}Yes${c.RESET}" else "No"
        val filled = spec.getFilledSlots()
        val required = spec.getRequiredSlots()

        println("  Task type:  $taskType")
        println("  Intent:     $intent")
        println("  Complete:   $complete (${filled.size}/${required.size + filled.size - (filled intersect required).size} slots)")
        println("  Risky:      $risky")
        println()

        // Slot details
        println("  Slots:")
        val allSlots = listOf(
            "intent" to spec.intent,
            "taskType" to spec.taskType?.name,
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
    }

    fun handleBlueprint(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val history = ctx.session.context.executionHistory
        val lastExec = history.lastOrNull()

        println()
        if (lastExec?.blueprint == null) {
            println("  ${c.DIM}No blueprint available.${c.RESET}")
            println()
            return
        }

        val bp = lastExec.blueprint!!
        println("  ${c.BRIGHT_CYAN}[BLUEPRINT]${c.RESET} ${bp.id}")
        println()
        println("  Spec:   ${bp.specSnapshot.specId} (v${bp.specSnapshot.specVersion ?: "1.0"})")

        val dacsStr = bp.specSnapshot.dacsResult?.let { "YES" } ?: "N/A"
        println("  DACS:   $dacsStr")
        println("  Steps:  ${bp.steps.size}")
        println()

        bp.steps.forEachIndexed { i, step ->
            val paramStr = step.params.entries.joinToString("  ") { (k, v) ->
                val display = if (v.length > 40) "${v.take(37)}..." else v
                "$k=$display"
            }
            println("    ${i + 1}. ${step.type.name.padEnd(14)} $paramStr")
        }
        println()
    }

    fun handleResult(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val history = ctx.session.context.executionHistory

        if (history.isEmpty()) {
            println()
            println("  ${c.DIM}No execution history.${c.RESET}")
            println()
            return
        }

        val turnIndex = args.firstOrNull()?.toIntOrNull()
        val exec = if (turnIndex != null) {
            history.find { it.turnIndex == turnIndex }
        } else {
            history.lastOrNull()
        }

        if (exec == null) {
            println()
            println("  ${c.RED}Turn #$turnIndex not found.${c.RESET} Available: ${history.map { it.turnIndex }.joinToString(", ")}")
            println()
            return
        }

        println()
        println("  ${c.BRIGHT_CYAN}[RESULT]${c.RESET} Turn #${exec.turnIndex}")
        println()

        val bp = exec.blueprint
        if (bp != null) {
            println("  Blueprint:  ${bp.id}")
        }

        val result = exec.result
        if (result != null) {
            val successStr = if (result.isSuccess) "${c.GREEN}Yes${c.RESET}" else "${c.RED}No${c.RESET}"
            println("  Success:    $successStr (${result.successCount}/${result.successCount + result.failureCount} steps)")
            println()

            // Step details
            println("  Steps:")
            bp?.steps?.forEachIndexed { i, step ->
                val output = result.getStepOutput(step.stepId)
                val status = if (output != null) {
                    if (output.exitCode == null || output.exitCode == 0) "${c.GREEN}OK${c.RESET}" else "${c.RED}FAIL${c.RESET}"
                } else {
                    "${c.DIM}--${c.RESET}"
                }
                val duration = output?.durationMs?.let { "${it}ms" } ?: ""
                val artifacts = output?.artifacts?.keys?.joinToString(", ")?.let { " artifacts: $it" } ?: ""

                println("    ${i + 1}. ${step.type.name.padEnd(14)} $status  ${c.DIM}$duration${c.RESET}$artifacts")

                // stdout/stderr in verbose mode or on failure
                if (ctx.settings.verbose || (output?.exitCode != null && output.exitCode != 0)) {
                    output?.stdout?.takeIf { it.isNotBlank() }?.let { out ->
                        val display = if (ctx.settings.verbose) out else out.take(200)
                        println("       ${c.DIM}stdout: \"$display\"${c.RESET}")
                    }
                    output?.stderr?.takeIf { it.isNotBlank() }?.let { err ->
                        println("       ${c.RED}stderr: \"$err\"${c.RESET}")
                    }
                }
            }
        } else {
            println("  Summary: ${exec.summary}")
        }
        println()
    }

    fun handleArtifacts(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val context = ctx.session.context
        var totalCount = 0

        println()

        // Per-task artifacts
        for (task in context.tasks.values) {
            val taskArtifacts = task.context.artifacts
            if (taskArtifacts.isNotEmpty()) {
                totalCount += taskArtifacts.size
                println("  Task #${task.id} \"${task.label}\":")
                for ((name, value) in taskArtifacts) {
                    println("    ${c.WHITE}$name${c.RESET}     $value")
                }
                println()
            }
        }

        // Session-level artifacts
        val sessionArtifacts = context.artifacts
        if (sessionArtifacts.isNotEmpty()) {
            totalCount += sessionArtifacts.size
            println("  Session:")
            for ((name, value) in sessionArtifacts) {
                println("    ${c.WHITE}$name${c.RESET}     $value")
            }
            println()
        }

        if (totalCount == 0) {
            println("  ${c.DIM}No artifacts.${c.RESET}")
            println()
            return
        }

        println("  ${c.BRIGHT_CYAN}[ARTIFACTS]${c.RESET} $totalCount items")
        println()
    }
}
