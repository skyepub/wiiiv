package io.wiiiv.rag.retrieval

import io.wiiiv.rag.embedding.EmbeddingProvider
import io.wiiiv.rag.vector.SearchResult
import io.wiiiv.rag.vector.VectorStore

/**
 * RAG 검색기
 *
 * 쿼리를 임베딩하고 벡터 저장소에서 유사한 문서를 검색한다.
 *
 * ## 헌법
 * - Retriever는 검색만 한다
 * - Retriever는 판단하지 않는다
 * - 결과 없음은 실패가 아니다
 * - 결과는 유사도 순으로 정렬된다
 */
interface Retriever {

    /**
     * 텍스트 쿼리로 검색
     *
     * @param query 검색 쿼리
     * @param topK 반환할 최대 결과 수
     * @param minScore 최소 유사도 점수
     * @return 검색 결과
     */
    suspend fun retrieve(
        query: String,
        topK: Int = 5,
        minScore: Float = 0.0f
    ): RetrievalResult

    /**
     * 다중 쿼리 검색 (결과 병합)
     */
    suspend fun retrieveMulti(
        queries: List<String>,
        topK: Int = 5,
        minScore: Float = 0.0f
    ): RetrievalResult
}

/**
 * 검색 결과
 */
data class RetrievalResult(
    val query: String,
    val results: List<RetrievedDocument>,
    val totalFound: Int
) {
    /**
     * 결과를 컨텍스트 문자열로 변환
     */
    fun toContextString(separator: String = "\n\n---\n\n"): String {
        return results.joinToString(separator) { it.content }
    }

    /**
     * 번호가 붙은 컨텍스트 문자열
     */
    fun toNumberedContext(): String {
        return results.mapIndexed { index, doc ->
            "[${index + 1}] ${doc.content}"
        }.joinToString("\n\n")
    }

    /**
     * 소스 ID 목록
     */
    fun getSources(): List<String> = results.map { it.sourceId }.distinct()

    /**
     * 결과가 비어있는지 확인
     */
    fun isEmpty(): Boolean = results.isEmpty()

    /**
     * 결과가 있는지 확인
     */
    fun isNotEmpty(): Boolean = results.isNotEmpty()
}

/**
 * 검색된 문서
 */
data class RetrievedDocument(
    val id: String,
    val content: String,
    val score: Float,
    val sourceId: String,
    val chunkIndex: Int,
    val metadata: Map<String, String>
) {
    companion object {
        fun fromSearchResult(result: SearchResult): RetrievedDocument {
            return RetrievedDocument(
                id = result.id,
                content = result.content,
                score = result.score,
                sourceId = result.sourceId,
                chunkIndex = result.chunkIndex,
                metadata = result.metadata
            )
        }
    }
}

/**
 * 기본 검색기 구현
 */
class SimpleRetriever(
    private val embeddingProvider: EmbeddingProvider,
    private val vectorStore: VectorStore
) : Retriever {

    override suspend fun retrieve(
        query: String,
        topK: Int,
        minScore: Float
    ): RetrievalResult {
        // 1. 쿼리 임베딩
        val queryEmbedding = embeddingProvider.embed(query)

        // 2. 벡터 검색
        val searchResults = vectorStore.search(
            queryVector = queryEmbedding.vector,
            topK = topK,
            minScore = minScore
        )

        // 3. 결과 변환
        return RetrievalResult(
            query = query,
            results = searchResults.map { RetrievedDocument.fromSearchResult(it) },
            totalFound = searchResults.size
        )
    }

    override suspend fun retrieveMulti(
        queries: List<String>,
        topK: Int,
        minScore: Float
    ): RetrievalResult {
        if (queries.isEmpty()) {
            return RetrievalResult(
                query = "",
                results = emptyList(),
                totalFound = 0
            )
        }

        // 모든 쿼리에 대해 검색
        val allResults = mutableMapOf<String, RetrievedDocument>()

        for (query in queries) {
            val result = retrieve(query, topK * 2, minScore)
            for (doc in result.results) {
                // 중복 제거: 더 높은 점수 유지
                val existing = allResults[doc.id]
                if (existing == null || doc.score > existing.score) {
                    allResults[doc.id] = doc
                }
            }
        }

        // 점수 순 정렬 후 topK 반환
        val sortedResults = allResults.values
            .sortedByDescending { it.score }
            .take(topK)

        return RetrievalResult(
            query = queries.joinToString(" | "),
            results = sortedResults,
            totalFound = sortedResults.size
        )
    }
}

/**
 * Reranking 검색기
 *
 * 초기 검색 후 재순위화를 적용한다.
 * 더 정확한 검색 결과를 위해 사용한다.
 */
class RerankingRetriever(
    private val baseRetriever: Retriever,
    private val reranker: Reranker
) : Retriever {

    override suspend fun retrieve(
        query: String,
        topK: Int,
        minScore: Float
    ): RetrievalResult {
        // 1. 더 많은 후보 검색
        val candidates = baseRetriever.retrieve(query, topK * 3, minScore)

        // 2. 재순위화
        val reranked = reranker.rerank(query, candidates.results)

        // 3. topK 반환
        return RetrievalResult(
            query = query,
            results = reranked.take(topK),
            totalFound = reranked.size
        )
    }

    override suspend fun retrieveMulti(
        queries: List<String>,
        topK: Int,
        minScore: Float
    ): RetrievalResult {
        val candidates = baseRetriever.retrieveMulti(queries, topK * 3, minScore)
        val combinedQuery = queries.joinToString(" ")
        val reranked = reranker.rerank(combinedQuery, candidates.results)

        return RetrievalResult(
            query = candidates.query,
            results = reranked.take(topK),
            totalFound = reranked.size
        )
    }
}

/**
 * 재순위화 인터페이스
 */
interface Reranker {
    suspend fun rerank(query: String, documents: List<RetrievedDocument>): List<RetrievedDocument>
}

/**
 * Cross-Encoder 기반 재순위화 (향후 구현)
 */
class CrossEncoderReranker : Reranker {
    override suspend fun rerank(query: String, documents: List<RetrievedDocument>): List<RetrievedDocument> {
        // TODO: Cross-encoder 모델을 사용한 재순위화
        // 현재는 원본 순서 유지
        return documents
    }
}
