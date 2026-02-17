package io.wiiiv.rag.vector

import io.wiiiv.rag.embedding.Embedding

/**
 * 벡터 저장소 인터페이스
 *
 * 임베딩 벡터의 저장 및 유사도 검색을 담당한다.
 *
 * ## 헌법
 * - VectorStore는 저장/검색만 한다
 * - VectorStore는 판단하지 않는다
 * - 검색 결과는 유사도 순으로 정렬된다 (결정론적)
 *
 * ## 구현체
 * - InMemoryVectorStore: 메모리 기반 (개발/테스트)
 * - 향후: PineconeVectorStore, PgVectorStore 등
 */
interface VectorStore {

    /**
     * 저장소 식별자
     */
    val storeId: String

    /**
     * 단일 엔트리 저장
     */
    suspend fun store(entry: VectorEntry): String

    /**
     * 배치 저장
     */
    suspend fun storeBatch(entries: List<VectorEntry>): List<String>

    /**
     * 유사도 검색
     *
     * @param queryVector 검색 벡터
     * @param topK 반환할 최대 결과 수
     * @param minScore 최소 유사도 점수 (0.0 ~ 1.0)
     * @param filter 메타데이터 필터
     * @return 유사도 순으로 정렬된 검색 결과
     */
    suspend fun search(
        queryVector: FloatArray,
        topK: Int = 10,
        minScore: Float = 0.0f,
        filter: Map<String, String>? = null
    ): List<SearchResult>

    /**
     * ID로 엔트리 조회
     */
    suspend fun get(id: String): VectorEntry?

    /**
     * ID로 엔트리 삭제
     */
    suspend fun delete(id: String): Boolean

    /**
     * 소스 ID로 모든 관련 엔트리 삭제
     */
    suspend fun deleteBySource(sourceId: String): Int

    /**
     * 저장된 엔트리 수
     */
    suspend fun size(): Int

    /**
     * 모든 엔트리 삭제
     */
    suspend fun clear()

    /**
     * 저장소 상태 확인
     */
    suspend fun isHealthy(): Boolean
}

/**
 * 벡터 저장소 엔트리
 */
data class VectorEntry(
    val id: String,
    val vector: FloatArray,
    val content: String,
    val sourceId: String,
    val chunkIndex: Int = 0,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VectorEntry) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /**
         * Embedding에서 VectorEntry 생성
         */
        fun fromEmbedding(
            embedding: Embedding,
            content: String,
            sourceId: String,
            chunkIndex: Int = 0,
            metadata: Map<String, String> = emptyMap()
        ): VectorEntry {
            return VectorEntry(
                id = embedding.id,
                vector = embedding.vector,
                content = content,
                sourceId = sourceId,
                chunkIndex = chunkIndex,
                metadata = embedding.metadata + metadata
            )
        }
    }
}

/**
 * 검색 결과
 */
data class SearchResult(
    val id: String,
    val content: String,
    val score: Float,
    val sourceId: String,
    val chunkIndex: Int,
    val metadata: Map<String, String>
) : Comparable<SearchResult> {

    override fun compareTo(other: SearchResult): Int {
        // 점수 내림차순 정렬
        return other.score.compareTo(this.score)
    }
}

/**
 * 검색 요청
 */
data class SearchRequest(
    val query: String,
    val topK: Int = 10,
    val minScore: Float = 0.0f,
    val filter: Map<String, String>? = null
)

/**
 * 검색 응답
 */
data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val totalFound: Int
) {
    /**
     * 결과를 문자열로 변환 (컨텍스트 주입용)
     */
    fun toContextString(separator: String = "\n\n---\n\n"): String {
        return results.joinToString(separator) { it.content }
    }

    /**
     * 번호가 붙은 컨텍스트 문자열
     */
    fun toNumberedContextString(): String {
        return results.mapIndexed { index, result ->
            "[${index + 1}] ${result.content}"
        }.joinToString("\n\n")
    }
}
