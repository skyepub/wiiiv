package io.wiiiv.cli.commands

import io.wiiiv.cli.CommandDispatcher
import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext

/**
 * Tier 1 — /help, /clear, /reset
 */
object HelpCommands {

    fun handleHelp(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        println()
        println("  ${c.BRIGHT_CYAN}Available commands:${c.RESET}")
        println()
        for ((name, desc) in CommandDispatcher.getDescriptions()) {
            println("  ${c.WHITE}${name.padEnd(22)}${c.RESET}$desc")
        }
        println()
    }

    fun handleClear(args: List<String>, ctx: ShellContext) {
        print("\u001B[2J\u001B[H")
        System.out.flush()
    }

    fun handleReset(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        ctx.session.resetSpec()
        println("  ${c.BRIGHT_CYAN}Current spec cleared.${c.RESET} Session preserved.")
    }
}
