package io.wiiiv.rag.embedding

import kotlinx.serialization.Serializable

/**
 * 임베딩 벡터 표현
 *
 * 텍스트를 고차원 벡터 공간에 매핑한 결과
 */
@Serializable
data class Embedding(
    val id: String,
    val vector: FloatArray,
    val dimension: Int = vector.size,
    val text: String? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Embedding) return false
        return id == other.id && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + vector.contentHashCode()
        return result
    }
}

/**
 * 임베딩 요청
 */
@Serializable
data class EmbeddingRequest(
    val texts: List<String>,
    val model: String? = null
) {
    constructor(text: String, model: String? = null) : this(listOf(text), model)
}

/**
 * 임베딩 응답
 */
@Serializable
data class EmbeddingResponse(
    val embeddings: List<Embedding>,
    val model: String,
    val usage: TokenUsage? = null
)

/**
 * 토큰 사용량
 */
@Serializable
data class TokenUsage(
    val promptTokens: Int,
    val totalTokens: Int
)

/**
 * 코사인 유사도 계산
 *
 * cosine(A, B) = (A · B) / (||A|| × ||B||)
 * 범위: -1.0 ~ 1.0 (정규화된 벡터의 경우 0.0 ~ 1.0)
 */
object CosineSimilarity {

    fun calculate(a: FloatArray, b: FloatArray): Float {
        require(a.size == b.size) { "Vectors must have same dimension: ${a.size} vs ${b.size}" }

        var dotProduct = 0f
        var normA = 0f
        var normB = 0f

        for (i in a.indices) {
            dotProduct += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }

        val denominator = kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)
        return if (denominator == 0f) 0f else dotProduct / denominator
    }

    /**
     * 벡터 정규화 (L2 norm)
     */
    fun normalize(vector: FloatArray): FloatArray {
        val norm = kotlin.math.sqrt(vector.sumOf { (it * it).toDouble() }).toFloat()
        return if (norm == 0f) vector else FloatArray(vector.size) { vector[it] / norm }
    }
}
