package io.wiiiv.api.dto.rag

import kotlinx.serialization.Serializable

/**
 * RAG Request/Response DTOs
 *
 * RAG (Retrieval-Augmented Generation) API 요청/응답 모델
 */

// ==================== Ingest ====================

/**
 * 문서 수집 요청
 */
@Serializable
data class IngestRequest(
    val content: String,
    val title: String? = null,
    val documentId: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 배치 수집 요청
 */
@Serializable
data class BatchIngestRequest(
    val documents: List<IngestRequest>
)

/**
 * 수집 응답
 */
@Serializable
data class IngestResponse(
    val documentId: String,
    val title: String?,
    val chunkCount: Int,
    val success: Boolean,
    val error: String? = null
)

/**
 * 배치 수집 응답
 */
@Serializable
data class BatchIngestResponse(
    val totalDocuments: Int,
    val successCount: Int,
    val failureCount: Int,
    val results: List<IngestResponse>
)

// ==================== Search ====================

/**
 * 검색 요청
 */
@Serializable
data class SearchRequest(
    val query: String,
    val topK: Int = 5,
    val minScore: Float = 0.0f,
    val filter: Map<String, String>? = null
)

/**
 * 검색 응답
 */
@Serializable
data class SearchResponse(
    val query: String,
    val results: List<SearchResultItem>,
    val totalFound: Int
)

/**
 * 검색 결과 항목
 */
@Serializable
data class SearchResultItem(
    val id: String,
    val content: String,
    val score: Float,
    val sourceId: String,
    val chunkIndex: Int,
    val metadata: Map<String, String> = emptyMap()
)

// ==================== Delete ====================

/**
 * 삭제 응답
 */
@Serializable
data class DeleteResponse(
    val documentId: String,
    val deletedChunks: Int,
    val success: Boolean
)

// ==================== Size ====================

/**
 * 크기 응답
 */
@Serializable
data class SizeResponse(
    val size: Int,
    val storeId: String
)

// ==================== Clear ====================

/**
 * 초기화 응답
 */
@Serializable
data class ClearResponse(
    val cleared: Boolean,
    val message: String
)

// ==================== Document List ====================

/**
 * 문서 목록 응답
 */
@Serializable
data class DocumentListResponse(
    val documents: List<DocumentSummary>,
    val total: Int
)

/**
 * 문서 요약
 */
@Serializable
data class DocumentSummary(
    val documentId: String,
    val title: String?,
    val chunkCount: Int
)
