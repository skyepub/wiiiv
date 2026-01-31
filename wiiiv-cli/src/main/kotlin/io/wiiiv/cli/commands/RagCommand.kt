package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.float
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.file
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.output.Printer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/**
 * wiiiv rag - RAG (Retrieval-Augmented Generation) 관리
 *
 * 벡터 검색 기반 문서 저장 및 검색
 *
 * ## CLI 헌법
 * - CLI는 판단하지 않는다
 * - CLI는 해석하지 않는다
 * - CLI는 API 리소스를 1:1로 반영한다
 */
class RagCommand : CliktCommand(
    name = "rag",
    help = "RAG (벡터 검색) 관리"
) {
    override fun run() = Unit

    init {
        subcommands(
            RagIngest(),
            RagSearch(),
            RagList(),
            RagDelete(),
            RagClear(),
            RagSize()
        )
    }
}

/**
 * wiiiv rag ingest --content <text> [--title <title>] [--file <path>]
 */
class RagIngest : CliktCommand(
    name = "ingest",
    help = "문서 수집 (벡터화하여 저장)"
) {
    private val ctx by requireObject<CliContext>()
    private val content by option("-c", "--content", help = "수집할 텍스트 내용")
    private val file by option("-f", "--file", help = "수집할 파일 경로").file(mustExist = true, canBeDir = false)
    private val title by option("-t", "--title", help = "문서 제목")
    private val documentId by option("-d", "--document-id", help = "문서 ID (지정하지 않으면 자동 생성)")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            // content 또는 file 중 하나는 필수
            val textContent = when {
                content != null -> content!!
                file != null -> file!!.readText()
                else -> {
                    Printer.error(ctx, "Either --content or --file is required")
                    return@runBlocking
                }
            }

            val docTitle = title ?: file?.name

            Printer.info(ctx, "Ingesting document${if (docTitle != null) ": $docTitle" else ""}...")

            val response = client.ragIngest(textContent, docTitle, documentId)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val success = response["success"]?.jsonPrimitive?.booleanOrNull ?: false
            if (!success) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            println("Document ingested successfully")
            println()
            println("Document ID: ${data?.get("documentId")?.jsonPrimitive?.content}")
            println("Title: ${data?.get("title")?.jsonPrimitive?.contentOrNull ?: "(none)"}")
            println("Chunks: ${data?.get("chunkCount")?.jsonPrimitive?.int}")

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to ingest document")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv rag search <query> [--top-k <n>] [--min-score <score>]
 */
class RagSearch : CliktCommand(
    name = "search",
    help = "유사도 검색"
) {
    private val ctx by requireObject<CliContext>()
    private val query by argument("query", help = "검색 쿼리")
    private val topK by option("-k", "--top-k", help = "반환할 최대 결과 수").int().default(5)
    private val minScore by option("-s", "--min-score", help = "최소 유사도 점수 (0.0~1.0)").float().default(0.0f)

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            Printer.info(ctx, "Searching: \"$query\"...")

            val response = client.ragSearch(query, topK, minScore)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val results = data?.get("results")?.jsonArray ?: JsonArray(emptyList())
            val totalFound = data?.get("totalFound")?.jsonPrimitive?.intOrNull ?: 0

            if (results.isEmpty()) {
                println("No results found")
                return@runBlocking
            }

            println("Found $totalFound result(s):")
            println()

            results.forEachIndexed { index, result ->
                val r = result.jsonObject
                val score = r["score"]?.jsonPrimitive?.floatOrNull ?: 0f
                val content = r["content"]?.jsonPrimitive?.content ?: ""
                val sourceId = r["sourceId"]?.jsonPrimitive?.content ?: ""
                val chunkIndex = r["chunkIndex"]?.jsonPrimitive?.intOrNull ?: 0

                println("─".repeat(60))
                println("[${index + 1}] Score: %.4f | Source: $sourceId (chunk $chunkIndex)".format(score))
                println()
                // 내용이 길면 잘라서 출력
                val displayContent = if (content.length > 200) content.take(200) + "..." else content
                println(displayContent)
            }
            println("─".repeat(60))

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to search")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv rag list
 */
class RagList : CliktCommand(
    name = "list",
    help = "수집된 문서 목록 조회"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.ragDocuments()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val documents = data?.get("documents")?.jsonArray ?: JsonArray(emptyList())
            val total = data?.get("total")?.jsonPrimitive?.intOrNull ?: 0

            if (documents.isEmpty()) {
                println("No documents found")
                return@runBlocking
            }

            println("Documents ($total total):")
            println()

            Printer.table(
                ctx,
                listOf("DOCUMENT ID", "TITLE", "CHUNKS"),
                documents.map { doc ->
                    val d = doc.jsonObject
                    listOf(
                        d["documentId"]?.jsonPrimitive?.content?.take(20) ?: "",
                        d["title"]?.jsonPrimitive?.contentOrNull?.take(30) ?: "(none)",
                        d["chunkCount"]?.jsonPrimitive?.content ?: "0"
                    )
                }
            )

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list documents")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv rag delete <document-id>
 */
class RagDelete : CliktCommand(
    name = "delete",
    help = "문서 삭제"
) {
    private val ctx by requireObject<CliContext>()
    private val documentId by argument("document-id", help = "삭제할 문서 ID")
    private val force by option("-f", "--force", help = "확인 없이 삭제").flag()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            if (!force && !ctx.quiet) {
                print("Delete document '$documentId'? [y/N] ")
                val confirm = readLine()?.trim()?.lowercase()
                if (confirm != "y" && confirm != "yes") {
                    println("Cancelled")
                    return@runBlocking
                }
            }

            val response = client.ragDelete(documentId)

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val deletedChunks = data?.get("deletedChunks")?.jsonPrimitive?.intOrNull ?: 0
            val success = data?.get("success")?.jsonPrimitive?.booleanOrNull ?: false

            if (success) {
                println("Document deleted: $documentId ($deletedChunks chunks removed)")
            } else {
                println("Document not found: $documentId")
            }

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to delete document")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv rag clear
 */
class RagClear : CliktCommand(
    name = "clear",
    help = "모든 문서 삭제 (초기화)"
) {
    private val ctx by requireObject<CliContext>()
    private val force by option("-f", "--force", help = "확인 없이 삭제").flag()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            if (!force && !ctx.quiet) {
                print("Clear ALL documents from vector store? This cannot be undone. [y/N] ")
                val confirm = readLine()?.trim()?.lowercase()
                if (confirm != "y" && confirm != "yes") {
                    println("Cancelled")
                    return@runBlocking
                }
            }

            val response = client.ragClear()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val cleared = data?.get("cleared")?.jsonPrimitive?.booleanOrNull ?: false

            if (cleared) {
                println("Vector store cleared successfully")
            } else {
                println("Failed to clear vector store")
            }

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to clear vector store")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv rag size
 */
class RagSize : CliktCommand(
    name = "size",
    help = "저장소 크기 조회"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.ragSize()

            if (ctx.json) {
                Printer.print(ctx, response)
                return@runBlocking
            }

            val data = response["data"]?.jsonObject
            val size = data?.get("size")?.jsonPrimitive?.intOrNull ?: 0
            val storeId = data?.get("storeId")?.jsonPrimitive?.content ?: "unknown"

            println("Vector Store: $storeId")
            println("Total Chunks: $size")

        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to get store size")
        } finally {
            client.close()
        }
    }
}
