package io.wiiiv.cli.commands

import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * HLX 슬래시 명령 — /hlx list, /hlx get, /hlx create, /hlx validate, /hlx run, /hlx delete, /hlx executions, /hlx result
 *
 * 서버 API를 통해 HLX 워크플로우를 관리/실행한다.
 */
object HlxCommands {

    fun handleHlx(args: List<String>, ctx: ShellContext) {
        val c = ShellColors

        val subCmd = args.firstOrNull()?.lowercase()
        val subArgs = args.drop(1)

        when (subCmd) {
            "list", "ls" -> hlxList(ctx, c)
            "get" -> hlxGet(ctx, subArgs, c)
            "create" -> hlxCreate(ctx, subArgs, c)
            "validate" -> hlxValidate(ctx, subArgs, c)
            "run" -> hlxRun(ctx, subArgs, c)
            "delete", "rm" -> hlxDelete(ctx, subArgs, c)
            "executions" -> hlxExecutions(ctx, subArgs, c)
            "result" -> hlxResult(ctx, subArgs, c)
            null -> hlxHelp(c)
            else -> {
                println("  ${c.RED}Unknown: /hlx $subCmd${c.RESET}")
                hlxHelp(c)
            }
        }
    }

    private fun hlxHelp(c: ShellColors) {
        println()
        println("  ${c.BRIGHT_CYAN}HLX workflow commands:${c.RESET}")
        println()
        println("  ${c.WHITE}/hlx list${c.RESET}                     Workflow list")
        println("  ${c.WHITE}/hlx get <id>${c.RESET}                 Workflow details")
        println("  ${c.WHITE}/hlx create <file>${c.RESET}            Create workflow from .hlx file")
        println("  ${c.WHITE}/hlx validate <id>${c.RESET}            Validate workflow")
        println("  ${c.WHITE}/hlx run <id> [--var k=v]${c.RESET}     Execute workflow")
        println("  ${c.WHITE}/hlx executions <id>${c.RESET}          Execution history")
        println("  ${c.WHITE}/hlx result <execution-id>${c.RESET}    Execution result detail")
        println("  ${c.WHITE}/hlx delete <id>${c.RESET}              Delete workflow")
        println()
    }

    private fun hlxList(ctx: ShellContext, c: ShellColors) {
        runBlocking {
            try {
                val result = ctx.client.hlxList()

                println()
                if (result.workflows.isEmpty()) {
                    println("  ${c.DIM}No workflows registered.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[HLX]${c.RESET} ${result.total} workflows")
                println()

                for (wf in result.workflows) {
                    println("  ${c.WHITE}${wf.name}${c.RESET} ${c.DIM}(${wf.id})${c.RESET}")
                    println("    ${wf.description}")
                    println("    ${c.DIM}v${wf.version} · ${wf.nodeCount} nodes · ${wf.createdAt}${c.RESET}")
                    println()
                }
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun hlxGet(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val id = args.firstOrNull()?.trim()
        if (id.isNullOrBlank()) {
            println("  ${c.RED}Usage: /hlx get <workflow-id>${c.RESET}")
            return
        }

        runBlocking {
            try {
                val detail = ctx.client.hlxGet(id)

                println()
                println("  ${c.BRIGHT_CYAN}[HLX]${c.RESET} ${c.WHITE}${detail.name}${c.RESET}")
                println("  ${c.DIM}ID: ${detail.id} · v${detail.version} · trigger: ${detail.trigger ?: "manual"}${c.RESET}")
                println("  ${detail.description}")
                println()

                println("  ${c.BRIGHT_CYAN}Nodes (${detail.nodes.size}):${c.RESET}")
                for ((i, node) in detail.nodes.withIndex()) {
                    println("    ${c.WHITE}${i + 1}.${c.RESET} [${node.type}] ${c.DIM}${node.id}${c.RESET}")
                    println("       ${node.description}")
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun hlxCreate(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val path = args.joinToString(" ").trim()
        if (path.isBlank()) {
            println("  ${c.RED}Usage: /hlx create <file-path>${c.RESET}")
            return
        }

        val resolved = resolvePath(path)
        val file = File(resolved)
        if (!file.exists()) {
            println("  ${c.RED}File not found: $resolved${c.RESET}")
            return
        }

        if (!file.isFile) {
            println("  ${c.RED}Not a file: $path${c.RESET}")
            return
        }

        println("  ${c.DIM}Creating workflow from ${file.name}...${c.RESET}")

        runBlocking {
            try {
                val result = ctx.client.hlxCreate(file.readText())
                println("  ${c.GREEN}OK${c.RESET} Workflow ${c.WHITE}${result.name}${c.RESET} ${c.DIM}(${result.id})${c.RESET} — ${result.nodeCount} nodes")
            } catch (e: Exception) {
                println("  ${c.RED}Failed: ${e.message}${c.RESET}")
            }
        }
    }

    private fun hlxValidate(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val id = args.firstOrNull()?.trim()
        if (id.isNullOrBlank()) {
            println("  ${c.RED}Usage: /hlx validate <workflow-id>${c.RESET}")
            return
        }

        runBlocking {
            try {
                val result = ctx.client.hlxValidate(id)
                if (result.valid) {
                    println("  ${c.GREEN}VALID${c.RESET} Workflow $id is valid.")
                } else {
                    println("  ${c.RED}INVALID${c.RESET} Workflow $id has errors:")
                    for (err in result.errors) {
                        println("    ${c.RED}·${c.RESET} $err")
                    }
                }
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun hlxRun(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val id = args.firstOrNull()?.trim()
        if (id.isNullOrBlank()) {
            println("  ${c.RED}Usage: /hlx run <workflow-id> [--var key=value ...]${c.RESET}")
            return
        }

        // Parse --var key=value pairs
        val variables = mutableMapOf<String, String>()
        var i = 1
        while (i < args.size) {
            if (args[i] == "--var" && i + 1 < args.size) {
                val kv = args[i + 1]
                val eqIdx = kv.indexOf('=')
                if (eqIdx > 0) {
                    variables[kv.substring(0, eqIdx)] = kv.substring(eqIdx + 1)
                }
                i += 2
            } else {
                i++
            }
        }

        println("  ${c.DIM}Executing workflow $id...${c.RESET}")

        runBlocking {
            try {
                val result = ctx.client.hlxExecute(id, variables)

                println()
                val statusColor = when (result.status) {
                    "COMPLETED" -> c.GREEN
                    "FAILED", "ABORTED" -> c.RED
                    else -> c.YELLOW
                }
                println("  ${c.BRIGHT_CYAN}[HLX]${c.RESET} ${statusColor}${result.status}${c.RESET} in ${result.totalDurationMs}ms")

                if (result.error != null) {
                    println("  ${c.RED}Error: ${result.error}${c.RESET}")
                }

                println()
                println("  ${c.BRIGHT_CYAN}Node Records (${result.nodeRecords.size}):${c.RESET}")
                for (record in result.nodeRecords) {
                    val nodeStatus = when (record.status) {
                        "success" -> "${c.GREEN}OK${c.RESET}"
                        "failed" -> "${c.RED}FAIL${c.RESET}"
                        else -> "${c.YELLOW}${record.status}${c.RESET}"
                    }
                    println("    $nodeStatus [${record.nodeType}] ${c.DIM}${record.nodeId}${c.RESET} (${record.durationMs}ms)")
                    if (record.error != null) {
                        println("      ${c.RED}${record.error}${c.RESET}")
                    }
                    if (record.selectedBranch != null) {
                        println("      Branch: ${record.selectedBranch}")
                    }
                }
                println()
                println("  ${c.DIM}Execution ID: ${result.executionId}${c.RESET}")
                println()
            } catch (e: Exception) {
                println("  ${c.RED}Failed: ${e.message}${c.RESET}")
            }
        }
    }

    private fun hlxDelete(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val id = args.firstOrNull()?.trim()
        if (id.isNullOrBlank()) {
            println("  ${c.RED}Usage: /hlx delete <workflow-id>${c.RESET}")
            return
        }

        runBlocking {
            try {
                val result = ctx.client.hlxDelete(id)
                if (result.deleted) {
                    println("  ${c.GREEN}Deleted.${c.RESET} Workflow $id removed.")
                } else {
                    println("  ${c.YELLOW}Not deleted.${c.RESET}")
                }
            } catch (e: Exception) {
                println("  ${c.RED}Failed: ${e.message}${c.RESET}")
            }
        }
    }

    private fun hlxExecutions(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val id = args.firstOrNull()?.trim()
        if (id.isNullOrBlank()) {
            println("  ${c.RED}Usage: /hlx executions <workflow-id>${c.RESET}")
            return
        }

        runBlocking {
            try {
                val result = ctx.client.hlxExecutions(id)

                println()
                if (result.executions.isEmpty()) {
                    println("  ${c.DIM}No executions for workflow $id.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[HLX]${c.RESET} ${result.total} executions for $id")
                println()

                for (exec in result.executions) {
                    val statusColor = when (exec.status) {
                        "COMPLETED" -> c.GREEN
                        "FAILED", "ABORTED" -> c.RED
                        else -> c.YELLOW
                    }
                    println("  ${statusColor}${exec.status}${c.RESET} ${c.DIM}${exec.executionId}${c.RESET}")
                    println("    ${exec.totalDurationMs}ms · ${exec.executedAt}")
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun hlxResult(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val executionId = args.firstOrNull()?.trim()
        if (executionId.isNullOrBlank()) {
            println("  ${c.RED}Usage: /hlx result <execution-id>${c.RESET}")
            return
        }

        runBlocking {
            try {
                val result = ctx.client.hlxExecution(executionId)

                println()
                val statusColor = when (result.status) {
                    "COMPLETED" -> c.GREEN
                    "FAILED", "ABORTED" -> c.RED
                    else -> c.YELLOW
                }
                println("  ${c.BRIGHT_CYAN}[HLX]${c.RESET} Execution ${c.DIM}${result.executionId}${c.RESET}")
                println("  Workflow: ${result.workflowId}")
                println("  Status: ${statusColor}${result.status}${c.RESET}")
                println("  Duration: ${result.totalDurationMs}ms")

                if (result.error != null) {
                    println("  ${c.RED}Error: ${result.error}${c.RESET}")
                }

                println()
                println("  ${c.BRIGHT_CYAN}Node Records (${result.nodeRecords.size}):${c.RESET}")
                for (record in result.nodeRecords) {
                    val nodeStatus = when (record.status) {
                        "success" -> "${c.GREEN}OK${c.RESET}"
                        "failed" -> "${c.RED}FAIL${c.RESET}"
                        else -> "${c.YELLOW}${record.status}${c.RESET}"
                    }
                    println("    $nodeStatus [${record.nodeType}] ${c.DIM}${record.nodeId}${c.RESET} (${record.durationMs}ms)")
                    if (record.error != null) {
                        println("      ${c.RED}${record.error}${c.RESET}")
                    }
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }
}
