package io.wiiiv.shell

import io.wiiiv.shell.commands.ControlCommands
import io.wiiiv.shell.commands.HelpCommands
import io.wiiiv.shell.commands.InspectionCommands
import io.wiiiv.shell.commands.RagCommands
import io.wiiiv.shell.commands.StatusCommands

/**
 * 슬래시 명령 디스패처
 *
 * `register(name, desc, handler)` 방식으로 명령을 등록하고,
 * `/`로 시작하는 입력을 파싱하여 해당 핸들러에 전달한다.
 */
object CommandDispatcher {

    private data class CommandEntry(
        val name: String,
        val description: String,
        val handler: (List<String>, ShellContext) -> Unit
    )

    private val commands = mutableListOf<CommandEntry>()

    init {
        // Tier 1 — 필수
        register("/help", "Available commands") { args, ctx -> HelpCommands.handleHelp(args, ctx) }
        register("/status", "Session status overview") { args, ctx -> StatusCommands.handleStatus(args, ctx) }
        register("/history [N]", "Recent N messages (default 10)") { args, ctx -> StatusCommands.handleHistory(args, ctx) }
        register("/tasks", "Task list") { args, ctx -> StatusCommands.handleTasks(args, ctx) }
        register("/clear", "Clear screen") { args, ctx -> HelpCommands.handleClear(args, ctx) }
        register("/reset", "Reset current spec") { args, ctx -> HelpCommands.handleReset(args, ctx) }

        // Tier 2 — 검사
        register("/spec", "Current DraftSpec status") { args, ctx -> InspectionCommands.handleSpec(args, ctx) }
        register("/blueprint", "Last Blueprint details") { args, ctx -> InspectionCommands.handleBlueprint(args, ctx) }
        register("/result [N]", "Execution result (default: last)") { args, ctx -> InspectionCommands.handleResult(args, ctx) }
        register("/artifacts", "Artifact list") { args, ctx -> InspectionCommands.handleArtifacts(args, ctx) }

        // Tier 3 — 제어
        register("/switch <id>", "Switch to task") { args, ctx -> ControlCommands.handleSwitch(args, ctx) }
        register("/cancel [all]", "Cancel task") { args, ctx -> ControlCommands.handleCancel(args, ctx) }

        // Tier 4 — 설정
        register("/set [key] [value]", "View/change settings") { args, ctx -> ControlCommands.handleSet(args, ctx) }

        // RAG
        register("/rag <cmd>", "RAG vector store (size/list/search/ingest/remove)") { args, ctx -> RagCommands.handleRag(args, ctx) }
    }

    private fun register(name: String, description: String, handler: (List<String>, ShellContext) -> Unit) {
        commands.add(CommandEntry(name, description, handler))
    }

    /**
     * 입력이 슬래시 명령인지 확인
     */
    fun isCommand(input: String): Boolean = input.startsWith("/")

    /**
     * 슬래시 명령 파싱 및 디스패치
     */
    fun dispatch(input: String, ctx: ShellContext) {
        val parts = input.removePrefix("/").trim().split("\\s+".toRegex(), limit = 3)
        val cmd = parts[0].lowercase()
        val args = parts.drop(1).filter { it.isNotBlank() }

        val entry = commands.find { entry ->
            // Match by first word of the registered name
            entry.name.removePrefix("/").split(" ")[0].lowercase() == cmd
        }

        if (entry != null) {
            entry.handler(args, ctx)
        } else {
            println("  Unknown command: /$cmd. Type /help for available commands.")
        }
    }

    /**
     * 등록된 명령 목록 (name→description)
     */
    fun getDescriptions(): List<Pair<String, String>> {
        return commands.map { it.name to it.description }
    }
}
