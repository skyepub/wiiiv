package io.wiiiv.rag.embedding

/**
 * 임베딩 Provider 인터페이스
 *
 * 다양한 임베딩 모델/서비스를 추상화:
 * - OpenAI (text-embedding-3-small, text-embedding-3-large, ada-002)
 * - Anthropic (향후)
 * - Local models (sentence-transformers 등)
 *
 * ## 헌법
 * - Provider는 텍스트를 벡터로 변환만 한다
 * - Provider는 판단하지 않는다
 * - 동일 입력은 동일 출력을 보장한다 (결정론적)
 */
interface EmbeddingProvider {

    /**
     * Provider 식별자
     */
    val providerId: String

    /**
     * 지원 모델 목록
     */
    val supportedModels: List<String>

    /**
     * 기본 모델
     */
    val defaultModel: String

    /**
     * 출력 벡터 차원
     */
    fun getDimension(model: String? = null): Int

    /**
     * 단일 텍스트 임베딩
     */
    suspend fun embed(text: String, model: String? = null): Embedding

    /**
     * 배치 임베딩
     */
    suspend fun embedBatch(texts: List<String>, model: String? = null): EmbeddingResponse

    /**
     * Provider 상태 확인
     */
    suspend fun isAvailable(): Boolean
}

/**
 * 임베딩 모델 정보
 */
data class EmbeddingModelInfo(
    val modelId: String,
    val dimension: Int,
    val maxTokens: Int,
    val provider: String
)

/**
 * OpenAI 임베딩 모델 상수
 */
object OpenAIEmbeddingModels {
    const val TEXT_EMBEDDING_3_SMALL = "text-embedding-3-small"
    const val TEXT_EMBEDDING_3_LARGE = "text-embedding-3-large"
    const val TEXT_EMBEDDING_ADA_002 = "text-embedding-ada-002"

    val DIMENSIONS = mapOf(
        TEXT_EMBEDDING_3_SMALL to 1536,
        TEXT_EMBEDDING_3_LARGE to 3072,
        TEXT_EMBEDDING_ADA_002 to 1536
    )

    val MAX_TOKENS = mapOf(
        TEXT_EMBEDDING_3_SMALL to 8191,
        TEXT_EMBEDDING_3_LARGE to 8191,
        TEXT_EMBEDDING_ADA_002 to 8191
    )
}
