package io.wiiiv.rag.vector

import io.wiiiv.rag.embedding.CosineSimilarity
import java.util.concurrent.ConcurrentHashMap

/**
 * 인메모리 벡터 저장소
 *
 * 개발 및 테스트용 벡터 저장소.
 * ConcurrentHashMap 기반으로 스레드 안전하다.
 *
 * ## 특징
 * - 선형 검색 O(n)
 * - 메모리 기반 (재시작 시 데이터 손실)
 * - 소규모~중규모 데이터셋에 적합
 *
 * ## 제한
 * - 대규모 데이터셋에는 부적합 (> 100K 엔트리)
 * - 영속성 없음
 */
class InMemoryVectorStore(
    override val storeId: String = "in-memory-vector-store"
) : VectorStore {

    private val entries = ConcurrentHashMap<String, VectorEntry>()
    private val sourceIndex = ConcurrentHashMap<String, MutableSet<String>>()

    override suspend fun store(entry: VectorEntry): String {
        entries[entry.id] = entry

        // 소스 인덱스 업데이트
        sourceIndex.computeIfAbsent(entry.sourceId) { ConcurrentHashMap.newKeySet() }
            .add(entry.id)

        return entry.id
    }

    override suspend fun storeBatch(entries: List<VectorEntry>): List<String> {
        return entries.map { store(it) }
    }

    override suspend fun search(
        queryVector: FloatArray,
        topK: Int,
        minScore: Float,
        filter: Map<String, String>?
    ): List<SearchResult> {
        return entries.values
            .asSequence()
            .filter { entry ->
                // 메타데이터 필터 적용
                if (filter != null) {
                    filter.all { (key, value) -> entry.metadata[key] == value }
                } else {
                    true
                }
            }
            .map { entry ->
                val score = CosineSimilarity.calculate(queryVector, entry.vector)
                SearchResult(
                    id = entry.id,
                    content = entry.content,
                    score = score,
                    sourceId = entry.sourceId,
                    chunkIndex = entry.chunkIndex,
                    metadata = entry.metadata
                )
            }
            .filter { it.score >= minScore }
            .sorted()  // SearchResult.compareTo is already descending
            .take(topK)
            .toList()
    }

    override suspend fun get(id: String): VectorEntry? {
        return entries[id]
    }

    override suspend fun delete(id: String): Boolean {
        val entry = entries.remove(id)
        if (entry != null) {
            sourceIndex[entry.sourceId]?.remove(id)
            return true
        }
        return false
    }

    override suspend fun deleteBySource(sourceId: String): Int {
        val ids = sourceIndex.remove(sourceId) ?: return 0
        ids.forEach { entries.remove(it) }
        return ids.size
    }

    override suspend fun size(): Int = entries.size

    override suspend fun clear() {
        entries.clear()
        sourceIndex.clear()
    }

    override suspend fun isHealthy(): Boolean = true

    // 디버그/테스트용 메서드

    /**
     * 저장된 모든 소스 ID 목록
     */
    fun listSources(): Set<String> = sourceIndex.keys.toSet()

    /**
     * 특정 소스의 엔트리 수
     */
    fun countBySource(sourceId: String): Int = sourceIndex[sourceId]?.size ?: 0

    /**
     * 모든 엔트리 조회 (테스트용)
     */
    fun getAllEntries(): List<VectorEntry> = entries.values.toList()
}

/**
 * 필터링 벡터 저장소 데코레이터
 *
 * 기존 VectorStore에 메타데이터 필터링 기능을 추가한다.
 */
class FilteredVectorStore(
    private val delegate: VectorStore
) : VectorStore by delegate {

    /**
     * 소스 ID로 필터링된 검색
     */
    suspend fun searchBySource(
        queryVector: FloatArray,
        sourceId: String,
        topK: Int = 10,
        minScore: Float = 0.0f
    ): List<SearchResult> {
        return delegate.search(
            queryVector = queryVector,
            topK = topK,
            minScore = minScore,
            filter = mapOf("sourceId" to sourceId)
        )
    }

    /**
     * 커스텀 필터 함수로 검색
     */
    suspend fun searchWithFilter(
        queryVector: FloatArray,
        topK: Int = 10,
        minScore: Float = 0.0f,
        predicate: (SearchResult) -> Boolean
    ): List<SearchResult> {
        // 더 많이 가져와서 필터링 후 topK 반환
        val candidates = delegate.search(
            queryVector = queryVector,
            topK = topK * 3,
            minScore = minScore
        )

        return candidates.filter(predicate).take(topK)
    }
}
