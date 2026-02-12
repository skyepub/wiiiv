package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.rag.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.rag.Document

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
                val request = call.receive<IngestRequest>()

                val document = Document(
                    id = request.documentId ?: java.util.UUID.randomUUID().toString(),
                    content = request.content,
                    title = request.title,
                    metadata = request.metadata
                )

                val result = WiiivRegistry.ragPipeline.ingest(document)

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

            // Batch ingest documents
            post("/ingest/batch") {
                val request = call.receive<BatchIngestRequest>()

                val documents = request.documents.map { doc ->
                    Document(
                        id = doc.documentId ?: java.util.UUID.randomUUID().toString(),
                        content = doc.content,
                        title = doc.title,
                        metadata = doc.metadata
                    )
                }

                val result = WiiivRegistry.ragPipeline.ingestBatch(documents)

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
                val request = call.receive<SearchRequest>()

                val result = WiiivRegistry.ragPipeline.search(
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
                val size = WiiivRegistry.ragPipeline.size()
                val storeId = WiiivRegistry.ragPipeline.vectorStore.storeId

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
                val store = WiiivRegistry.ragPipeline.vectorStore

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
                val documentId = call.parameters["documentId"]
                    ?: throw IllegalArgumentException("Document ID required")

                val deletedCount = WiiivRegistry.ragPipeline.deleteDocument(documentId)

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
                WiiivRegistry.ragPipeline.clear()

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
