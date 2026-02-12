package io.wiiiv.cli.commands

import io.wiiiv.cli.CommandDispatcher
import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import io.wiiiv.cli.client.ControlRequest
import kotlinx.coroutines.runBlocking

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
        runBlocking {
            try {
                val result = ctx.client.controlSession(
                    ctx.sessionId,
                    ControlRequest("resetSpec")
                )
                println("  ${c.BRIGHT_CYAN}${result.message}${c.RESET}")
            } catch (e: Exception) {
                println("  ${c.RED}${e.message}${c.RESET}")
            }
        }
    }
}
