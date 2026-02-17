package io.wiiiv.cli.commands

import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import io.wiiiv.cli.client.ControlRequest
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * RAG 슬래시 명령 — /rag size, /rag list, /rag search, /rag ingest, /rag remove
 *
 * 서버 API를 통해 RAG 파이프라인을 조작한다.
 */
object RagCommands {

    fun handleRag(args: List<String>, ctx: ShellContext) {
        val c = ShellColors

        val subCmd = args.firstOrNull()?.lowercase()
        val subArgs = args.drop(1)

        when (subCmd) {
            "size" -> ragSize(ctx, c)
            "list", "ls" -> ragList(ctx, c)
            "search" -> ragSearch(ctx, subArgs, c)
            "ingest" -> ragIngest(ctx, subArgs, c)
            "remove", "rm", "delete" -> ragRemove(ctx, subArgs, c)
            null -> ragHelp(c)
            else -> {
                println("  ${c.RED}Unknown: /rag $subCmd${c.RESET}")
                ragHelp(c)
            }
        }
    }

    private fun ragHelp(c: ShellColors) {
        println()
        println("  ${c.BRIGHT_CYAN}RAG commands:${c.RESET}")
        println()
        println("  ${c.WHITE}/rag size${c.RESET}              Store size (total chunks)")
        println("  ${c.WHITE}/rag list${c.RESET}              Document list")
        println("  ${c.WHITE}/rag search <query>${c.RESET}    Similarity search (top 5)")
        println("  ${c.WHITE}/rag ingest <path>${c.RESET}     Ingest file")
        println("  ${c.WHITE}/rag remove <id>${c.RESET}       Remove document")
        println()
    }

    private fun ragSize(ctx: ShellContext, c: ShellColors) {
        runBlocking {
            try {
                val size = ctx.client.ragSize()
                println()
                println("  ${c.BRIGHT_CYAN}[RAG]${c.RESET} $size chunks")
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun ragList(ctx: ShellContext, c: ShellColors) {
        runBlocking {
            try {
                val result = ctx.client.ragDocuments()

                println()
                if (result.documents.isEmpty()) {
                    println("  ${c.DIM}No documents in store.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[RAG]${c.RESET} ${result.total} documents")
                println()

                for (doc in result.documents) {
                    val title = doc.metadata["title"] ?: doc.documentId.take(20)
                    println("  ${c.WHITE}$title${c.RESET}")
                    println("    ID: ${c.DIM}${doc.documentId}${c.RESET}  Chunks: ${doc.chunkCount}")
                    println()
                }
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun ragSearch(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val query = args.joinToString(" ").trim()
        if (query.isBlank()) {
            println("  ${c.RED}Usage: /rag search <query>${c.RESET}")
            return
        }

        println()
        println("  ${c.DIM}Searching...${c.RESET}")

        runBlocking {
            try {
                val result = ctx.client.ragSearch(query, topK = 5)

                if (result.results.isEmpty()) {
                    println("  ${c.DIM}No results found.${c.RESET}")
                    println()
                    return@runBlocking
                }

                println("  ${c.BRIGHT_CYAN}[RAG SEARCH]${c.RESET} \"$query\" — ${result.totalFound} found")
                println()

                for ((i, doc) in result.results.withIndex()) {
                    val score = "%.3f".format(doc.score)
                    val content = doc.content
                        .replace("\n", " ")
                        .trim()
                        .let { if (it.length > 70) it.take(67) + "..." else it }
                    val src = doc.sourceId.take(16)

                    println("  ${c.WHITE}${i + 1}.${c.RESET} ${c.DIM}[$score]${c.RESET} $content")
                    println("     ${c.DIM}source: $src  chunk: ${doc.chunkIndex}${c.RESET}")
                }
                println()
            } catch (e: Exception) {
                println("  ${c.RED}[ERROR]${c.RESET} ${e.message}")
            }
        }
    }

    private fun ragIngest(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val path = args.joinToString(" ").trim()
        if (path.isBlank()) {
            println("  ${c.RED}Usage: /rag ingest <file-path>${c.RESET}")
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

        println("  ${c.DIM}Ingesting ${file.name} (${file.length()} bytes)...${c.RESET}")

        val binaryExtensions = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx")
        val isBinary = file.extension.lowercase() in binaryExtensions

        try {
            val result = runBlocking {
                if (isBinary) {
                    ctx.client.ragIngestFile(file)
                } else {
                    ctx.client.ragIngest(file.readText(), file.name)
                }
            }
            println("  ${c.GREEN}OK${c.RESET} Document ${c.DIM}${result.documentId}${c.RESET} — ${result.chunkCount} chunks")
        } catch (e: Exception) {
            println("  ${c.RED}Failed: ${e.message}${c.RESET}")
        }
    }

    private fun ragRemove(ctx: ShellContext, args: List<String>, c: ShellColors) {
        val targetId = args.firstOrNull()?.trim()
        if (targetId.isNullOrBlank()) {
            println("  ${c.RED}Usage: /rag remove <document-id>${c.RESET}")
            println("  Use /rag list to see document IDs.")
            return
        }

        try {
            val deleted = runBlocking { ctx.client.ragDelete(targetId) }
            println("  ${c.GREEN}Removed.${c.RESET} $deleted chunks deleted.")
        } catch (e: Exception) {
            println("  ${c.RED}Failed: ${e.message}${c.RESET}")
        }
    }
}
