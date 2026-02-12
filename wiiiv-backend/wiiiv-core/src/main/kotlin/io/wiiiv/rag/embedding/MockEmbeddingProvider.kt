package io.wiiiv.rag.embedding

import java.util.UUID
import kotlin.random.Random

/**
 * Mock Embedding Provider
 *
 * 테스트 및 개발용 임베딩 Provider.
 * 실제 API 호출 없이 결정론적 벡터를 생성한다.
 *
 * ## 특징
 * - 동일 텍스트 → 동일 벡터 (해시 기반 시드)
 * - L2 정규화된 벡터 출력
 * - API 키 불필요
 */
class MockEmbeddingProvider(
    private val dimension: Int = 1536,
    override val defaultModel: String = "mock-embedding-v1"
) : EmbeddingProvider {

    override val providerId: String = "mock"

    override val supportedModels: List<String> = listOf(
        "mock-embedding-v1",
        "mock-embedding-small",
        "mock-embedding-large"
    )

    override fun getDimension(model: String?): Int = dimension

    override suspend fun embed(text: String, model: String?): Embedding {
        val vector = generateDeterministicVector(text)
        return Embedding(
            id = UUID.randomUUID().toString(),
            vector = vector,
            text = text,
            metadata = mapOf("model" to (model ?: defaultModel), "provider" to "mock")
        )
    }

    override suspend fun embedBatch(texts: List<String>, model: String?): EmbeddingResponse {
        val embeddings = texts.map { text ->
            Embedding(
                id = UUID.randomUUID().toString(),
                vector = generateDeterministicVector(text),
                text = text,
                metadata = mapOf("model" to (model ?: defaultModel), "provider" to "mock")
            )
        }

        return EmbeddingResponse(
            embeddings = embeddings,
            model = model ?: defaultModel,
            usage = TokenUsage(
                promptTokens = texts.sumOf { it.length / 4 },  // 대략적인 토큰 추정
                totalTokens = texts.sumOf { it.length / 4 }
            )
        )
    }

    override suspend fun isAvailable(): Boolean = true

    /**
     * 텍스트 해시를 시드로 사용하여 결정론적 벡터 생성
     */
    private fun generateDeterministicVector(text: String): FloatArray {
        val seed = text.hashCode().toLong()
        val random = Random(seed)

        val vector = FloatArray(dimension) { random.nextFloat() * 2 - 1 }
        return CosineSimilarity.normalize(vector)
    }
}

/**
 * 유사도 인식 Mock Provider
 *
 * 유사한 단어를 포함한 텍스트는 유사한 벡터를 생성한다.
 * 시맨틱 유사도 테스트에 유용하다.
 */
class SimilarityAwareMockProvider(
    private val dimension: Int = 1536,
    override val defaultModel: String = "similarity-aware-mock-v1"
) : EmbeddingProvider {

    override val providerId: String = "similarity-mock"

    override val supportedModels: List<String> = listOf(defaultModel)

    override fun getDimension(model: String?): Int = dimension

    // 단어 → 벡터 위치 매핑 (캐시)
    private val wordPositions = mutableMapOf<String, Int>()
    private var nextPosition = 0

    override suspend fun embed(text: String, model: String?): Embedding {
        val vector = generateSimilarityAwareVector(text)
        return Embedding(
            id = UUID.randomUUID().toString(),
            vector = vector,
            text = text,
            metadata = mapOf("model" to (model ?: defaultModel), "provider" to "similarity-mock")
        )
    }

    override suspend fun embedBatch(texts: List<String>, model: String?): EmbeddingResponse {
        val embeddings = texts.map { text ->
            Embedding(
                id = UUID.randomUUID().toString(),
                vector = generateSimilarityAwareVector(text),
                text = text,
                metadata = mapOf("model" to (model ?: defaultModel))
            )
        }

        return EmbeddingResponse(
            embeddings = embeddings,
            model = model ?: defaultModel,
            usage = null
        )
    }

    override suspend fun isAvailable(): Boolean = true

    /**
     * 단어 기반 유사도 인식 벡터 생성
     *
     * 같은 단어를 공유하는 텍스트는 벡터 공간에서 가깝게 위치한다.
     */
    private fun generateSimilarityAwareVector(text: String): FloatArray {
        val words = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

        val vector = FloatArray(dimension) { 0f }

        for (word in words) {
            val position = wordPositions.getOrPut(word) {
                (nextPosition++ % dimension)
            }

            // 단어가 있으면 해당 위치 값 증가
            vector[position] += 1f

            // 인접 위치에도 약간의 값 추가 (smoothing)
            if (position > 0) vector[position - 1] += 0.3f
            if (position < dimension - 1) vector[position + 1] += 0.3f
        }

        // 기본 노이즈 추가 (완전 0 벡터 방지)
        val random = Random(text.hashCode().toLong())
        for (i in vector.indices) {
            vector[i] += random.nextFloat() * 0.1f
        }

        return CosineSimilarity.normalize(vector)
    }
}
