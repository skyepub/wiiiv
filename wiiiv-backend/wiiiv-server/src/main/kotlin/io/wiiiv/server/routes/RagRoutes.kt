package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.rag.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.rag.Document
import io.wiiiv.rag.RagPipeline
import java.io.File

/**
 * RAG Routes - 벡터 검색 기반 문서 검색
 *
 * POST /api/v2/rag/ingest        - 문서 수집
 * POST /api/v2/rag/ingest/batch  - 배치 문서 수집
 * POST /api/v2/rag/search        - 유사도 검색
 * GET  /api/v2/rag/size          - 저장소 크기 조회
 * GET  /api/v2/rag/documents     - 문서 목록 조회
 * DELETE /api/v2/rag/{documentId} - 문서 삭제
 * DELETE /api/v2/rag             - 저장소 초기화
 *
 * ## 헌법
 * - RAG는 검색만 한다
 * - RAG는 판단하지 않는다
 * - 결과는 그대로 반환한다
 */
fun Route.ragRoutes() {
    route("/rag") {
        authenticate("auth-jwt") {
            // Ingest single document
            post("/ingest") {
                val rag = requireRag() ?: return@post
                val request = call.receive<IngestRequest>()

                val document = Document(
                    id = request.documentId ?: java.util.UUID.randomUUID().toString(),
                    content = request.content,
                    title = request.title,
                    metadata = request.metadata
                )

                val result = rag.ingest(document)

                if (result.success) {
                    call.respond(
                        HttpStatusCode.Created,
                        ApiResponse.success(
                            IngestResponse(
                                documentId = result.documentId,
                                title = result.title,
                                chunkCount = result.chunkCount,
                                success = true
                            )
                        )
                    )
                } else {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ApiResponse.error<IngestResponse>(
                            ApiError(
                                code = "INGEST_FAILED",
                                message = result.error ?: "Ingestion failed"
                            )
                        )
                    )
                }
            }

            // Ingest file (multipart upload — PDF, txt, md, json)
            post("/ingest/file") {
                val rag = requireRag() ?: return@post
                val multipart = call.receiveMultipart()
                var fileName: String? = null
                var fileBytes: ByteArray? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FileItem -> {
                            fileName = part.originalFileName
                            fileBytes = part.streamProvider().readBytes()
                        }
                        else -> {}
                    }
                    part.dispose()
                }

                if (fileBytes == null || fileName == null) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<IngestResponse>(
                            ApiError(code = "NO_FILE", message = "File is required")
                        )
                    )
                    return@post
                }

                val tempFile = File.createTempFile("rag-", "-$fileName")
                try {
                    tempFile.writeBytes(fileBytes!!)
                    val result = rag.ingestFile(tempFile, mapOf("originalName" to fileName!!))

                    if (result.success) {
                        call.respond(
                            HttpStatusCode.Created,
                            ApiResponse.success(
                                IngestResponse(
                                    documentId = result.documentId,
                                    title = result.title,
                                    chunkCount = result.chunkCount,
                                    success = true
                                )
                            )
                        )
                    } else {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ApiResponse.error<IngestResponse>(
                                ApiError(code = "INGEST_FAILED", message = result.error ?: "Ingestion failed")
                            )
                        )
                    }
                } finally {
                    tempFile.delete()
                }
            }

            // Batch ingest documents
            post("/ingest/batch") {
                val rag = requireRag() ?: return@post
                val request = call.receive<BatchIngestRequest>()

                val documents = request.documents.map { doc ->
                    Document(
                        id = doc.documentId ?: java.util.UUID.randomUUID().toString(),
                        content = doc.content,
                        title = doc.title,
                        metadata = doc.metadata
                    )
                }

                val result = rag.ingestBatch(documents)

                call.respond(
                    HttpStatusCode.Created,
                    ApiResponse.success(
                        BatchIngestResponse(
                            totalDocuments = result.totalDocuments,
                            successCount = result.successCount,
                            failureCount = result.failureCount,
                            results = result.results.map { r ->
                                IngestResponse(
                                    documentId = r.documentId,
                                    title = r.title,
                                    chunkCount = r.chunkCount,
                                    success = r.success,
                                    error = r.error
                                )
                            }
                        )
                    )
                )
            }

            // Search documents
            post("/search") {
                val rag = requireRag() ?: return@post
                val request = call.receive<SearchRequest>()

                val result = rag.search(
                    query = request.query,
                    topK = request.topK,
                    minScore = request.minScore
                )

                call.respond(
                    ApiResponse.success(
                        SearchResponse(
                            query = result.query,
                            results = result.results.map { r ->
                                SearchResultItem(
                                    id = r.id,
                                    content = r.content,
                                    score = r.score,
                                    sourceId = r.sourceId,
                                    chunkIndex = r.chunkIndex,
                                    metadata = r.metadata
                                )
                            },
                            totalFound = result.totalFound
                        )
                    )
                )
            }

            // Get store size
            get("/size") {
                val rag = requireRag() ?: return@get
                val size = rag.size()
                val storeId = rag.vectorStore.storeId

                call.respond(
                    ApiResponse.success(
                        SizeResponse(
                            size = size,
                            storeId = storeId
                        )
                    )
                )
            }

            // List documents (source IDs)
            get("/documents") {
                val rag = requireRag() ?: return@get
                val store = rag.vectorStore

                // InMemoryVectorStore의 listSources 활용
                val sources = if (store is io.wiiiv.rag.vector.InMemoryVectorStore) {
                    store.listSources().map { sourceId ->
                        DocumentSummary(
                            documentId = sourceId,
                            title = null,  // 메타데이터에서 가져올 수 있으면 좋겠지만 현재는 null
                            chunkCount = store.countBySource(sourceId)
                        )
                    }
                } else {
                    emptyList()
                }

                call.respond(
                    ApiResponse.success(
                        DocumentListResponse(
                            documents = sources,
                            total = sources.size
                        )
                    )
                )
            }

            // Delete document by ID
            delete("/{documentId}") {
                val rag = requireRag() ?: return@delete
                val documentId = call.parameters["documentId"]
                    ?: throw IllegalArgumentException("Document ID required")

                val deletedCount = rag.deleteDocument(documentId)

                call.respond(
                    ApiResponse.success(
                        DeleteResponse(
                            documentId = documentId,
                            deletedChunks = deletedCount,
                            success = deletedCount > 0
                        )
                    )
                )
            }

            // Clear all documents
            delete {
                val rag = requireRag() ?: return@delete
                rag.clear()

                call.respond(
                    ApiResponse.success(
                        ClearResponse(
                            cleared = true,
                            message = "Vector store cleared"
                        )
                    )
                )
            }
        }
    }
}

/**
 * RAG pipeline null 체크 헬퍼.
 * null이면 503 응답 후 null 반환 — caller는 return@handler.
 */
private suspend fun PipelineContext<Unit, ApplicationCall>.requireRag(): RagPipeline? {
    val rag = WiiivRegistry.ragPipeline
    if (rag == null) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiResponse.error<Unit>(
                ApiError(code = "RAG_UNAVAILABLE", message = "RAG pipeline is not configured")
            )
        )
    }
    return rag
}
