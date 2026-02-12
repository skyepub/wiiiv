package io.wiiiv.cli.commands

import io.wiiiv.rag.RagPipeline
import io.wiiiv.rag.vector.InMemoryVectorStore
import io.wiiiv.cli.ShellColors
import io.wiiiv.cli.ShellContext
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * RAG 슬래시 명령 — /rag size, /rag list, /rag search, /rag ingest, /rag remove
 */
object RagCommands {

    fun handleRag(args: List<String>, ctx: ShellContext) {
        val c = ShellColors
        val pipeline = ctx.ragPipeline

        if (pipeline == null) {
            println("  ${c.YELLOW}[WARN]${c.RESET} RAG not available (no LLM provider)")
            return
        }

        val subCmd = args.firstOrNull()?.lowercase()
        val subArgs = args.drop(1)

        when (subCmd) {
            "size" -> ragSize(pipeline, c)
            "list", "ls" -> ragList(pipeline, c)
            "search" -> ragSearch(pipeline, subArgs, c)
            "ingest" -> ragIngest(pipeline, subArgs, c)
            "remove", "rm", "delete" -> ragRemove(pipeline, subArgs, ctx, c)
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

    private fun ragSize(pipeline: RagPipeline, c: ShellColors) {
        val size = runBlocking { pipeline.size() }
        val store = pipeline.vectorStore
        val docCount = if (store is InMemoryVectorStore) store.listSources().size else "?"
        println()
        println("  ${c.BRIGHT_CYAN}[RAG]${c.RESET} $docCount documents, $size chunks")
        println()
    }

    private fun ragList(pipeline: RagPipeline, c: ShellColors) {
        val store = pipeline.vectorStore
        if (store !is InMemoryVectorStore) {
            println("  ${c.DIM}List not supported for this store type.${c.RESET}")
            return
        }

        val sources = store.listSources()
        println()
        if (sources.isEmpty()) {
            println("  ${c.DIM}No documents in store.${c.RESET}")
            println()
            return
        }

        println("  ${c.BRIGHT_CYAN}[RAG]${c.RESET} ${sources.size} documents")
        println()

        for (sourceId in sources) {
            val chunkCount = store.countBySource(sourceId)
            // Get first chunk's content as preview
            val preview = store.getAllEntries()
                .filter { it.sourceId == sourceId }
                .minByOrNull { it.chunkIndex }
                ?.content
                ?.replace("\n", " ")
                ?.trim()
                ?.let { if (it.length > 60) it.take(57) + "..." else it }
                ?: "${c.DIM}(empty)${c.RESET}"

            val title = store.getAllEntries()
                .firstOrNull { it.sourceId == sourceId }
                ?.metadata?.get("title")

            val label = title ?: sourceId.take(20)
            println("  ${c.WHITE}$label${c.RESET}")
            println("    ID: ${c.DIM}$sourceId${c.RESET}  Chunks: $chunkCount")
            println("    $preview")
            println()
        }
    }

    private fun ragSearch(pipeline: RagPipeline, args: List<String>, c: ShellColors) {
        val query = args.joinToString(" ").trim()
        if (query.isBlank()) {
            println("  ${c.RED}Usage: /rag search <query>${c.RESET}")
            return
        }

        println()
        println("  ${c.DIM}Searching...${c.RESET}")

        val result = runBlocking { pipeline.search(query, topK = 5) }

        if (result.results.isEmpty()) {
            println("  ${c.DIM}No results found.${c.RESET}")
            println()
            return
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
    }

    private fun ragIngest(pipeline: RagPipeline, args: List<String>, c: ShellColors) {
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

        try {
            val result = runBlocking {
                pipeline.ingestFile(file, mapOf("title" to file.name))
            }
            println("  ${c.GREEN}OK${c.RESET} Document ${c.DIM}${result.documentId}${c.RESET} — ${result.chunkCount} chunks")
        } catch (e: Exception) {
            println("  ${c.RED}Failed: ${e.message}${c.RESET}")
        }
    }

    private fun ragRemove(pipeline: RagPipeline, args: List<String>, ctx: ShellContext, c: ShellColors) {
        val targetId = args.firstOrNull()?.trim()
        if (targetId.isNullOrBlank()) {
            println("  ${c.RED}Usage: /rag remove <document-id>${c.RESET}")
            println("  Use /rag list to see document IDs.")
            return
        }

        val store = pipeline.vectorStore

        // Find matching document
        val matchedId = if (store is InMemoryVectorStore) {
            val sources = store.listSources()
            // Exact match first, then prefix match
            sources.find { it == targetId }
                ?: sources.find { it.startsWith(targetId) }
        } else {
            targetId
        }

        if (matchedId == null) {
            println("  ${c.RED}Document not found: $targetId${c.RESET}")
            println("  Use /rag list to see document IDs.")
            return
        }

        // Show what will be removed
        val chunkCount = if (store is InMemoryVectorStore) store.countBySource(matchedId) else "?"
        val preview = if (store is InMemoryVectorStore) {
            store.getAllEntries()
                .filter { it.sourceId == matchedId }
                .minByOrNull { it.chunkIndex }
                ?.content
                ?.replace("\n", " ")
                ?.trim()
                ?.let { if (it.length > 50) it.take(47) + "..." else it }
                ?: ""
        } else ""

        println()
        println("  ${c.WHITE}$matchedId${c.RESET}")
        println("  Chunks: $chunkCount")
        if (preview.isNotBlank()) println("  $preview")
        println()

        if (!ctx.confirm("Remove this document?")) {
            println("  Cancelled.")
            return
        }

        val deleted = runBlocking { pipeline.deleteDocument(matchedId) }
        println("  ${c.GREEN}Removed.${c.RESET} $deleted chunks deleted.")
    }

    // Path resolution delegated to shared resolvePath() in PathUtils.kt
}
