package io.wiiiv.rag.embedding

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.*
import java.util.UUID

/**
 * OpenAI Embedding Provider
 *
 * 지원 모델:
 * - text-embedding-3-small (1536 dims, 권장)
 * - text-embedding-3-large (3072 dims, 고정밀)
 * - text-embedding-ada-002 (1536 dims, legacy)
 */
class OpenAIEmbeddingProvider(
    private val apiKey: String,
    private val baseUrl: String = "https://api.openai.com/v1",
    override val defaultModel: String = OpenAIEmbeddingModels.TEXT_EMBEDDING_3_SMALL
) : EmbeddingProvider {

    override val providerId: String = "openai"

    override val supportedModels: List<String> = listOf(
        OpenAIEmbeddingModels.TEXT_EMBEDDING_3_SMALL,
        OpenAIEmbeddingModels.TEXT_EMBEDDING_3_LARGE,
        OpenAIEmbeddingModels.TEXT_EMBEDDING_ADA_002
    )

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json { ignoreUnknownKeys = true })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 10_000
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    override fun getDimension(model: String?): Int {
        val m = model ?: defaultModel
        return OpenAIEmbeddingModels.DIMENSIONS[m]
            ?: throw IllegalArgumentException("Unknown model: $m")
    }

    override suspend fun embed(text: String, model: String?): Embedding {
        val response = embedBatch(listOf(text), model)
        return response.embeddings.first()
    }

    override suspend fun embedBatch(texts: List<String>, model: String?): EmbeddingResponse {
        val modelName = model ?: defaultModel

        val requestBody = buildJsonObject {
            put("model", modelName)
            putJsonArray("input") {
                texts.forEach { add(it) }
            }
        }

        val response = client.post("$baseUrl/embeddings") {
            contentType(ContentType.Application.Json)
            header("Authorization", "Bearer $apiKey")
            setBody(requestBody.toString())
        }

        if (response.status != HttpStatusCode.OK) {
            val errorBody = response.bodyAsText()
            throw OpenAIEmbeddingException("OpenAI API error: ${response.status} - $errorBody")
        }

        val responseJson = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val data = responseJson["data"]?.jsonArray
            ?: throw OpenAIEmbeddingException("Invalid response: missing 'data' field")

        val embeddings = data.mapIndexed { index, element ->
            val obj = element.jsonObject
            val embeddingArray = obj["embedding"]?.jsonArray
                ?: throw OpenAIEmbeddingException("Invalid response: missing 'embedding' field")

            val vector = FloatArray(embeddingArray.size) {
                embeddingArray[it].jsonPrimitive.float
            }

            Embedding(
                id = UUID.randomUUID().toString(),
                vector = vector,
                text = texts.getOrNull(index),
                metadata = mapOf("model" to modelName)
            )
        }

        val usage = responseJson["usage"]?.jsonObject?.let {
            TokenUsage(
                promptTokens = it["prompt_tokens"]?.jsonPrimitive?.int ?: 0,
                totalTokens = it["total_tokens"]?.jsonPrimitive?.int ?: 0
            )
        }

        return EmbeddingResponse(
            embeddings = embeddings,
            model = modelName,
            usage = usage
        )
    }

    override suspend fun isAvailable(): Boolean {
        return try {
            // 간단한 텍스트로 테스트
            embed("test")
            true
        } catch (e: Exception) {
            false
        }
    }

    fun close() {
        client.close()
    }

    companion object {
        /**
         * 환경변수에서 API 키를 읽어 Provider 생성
         */
        fun fromEnv(
            envKey: String = "OPENAI_API_KEY",
            model: String = OpenAIEmbeddingModels.TEXT_EMBEDDING_3_SMALL
        ): OpenAIEmbeddingProvider {
            val apiKey = System.getenv(envKey)
                ?: throw IllegalStateException("Environment variable $envKey not set")
            return OpenAIEmbeddingProvider(apiKey, defaultModel = model)
        }
    }
}

/**
 * OpenAI Embedding 예외
 */
class OpenAIEmbeddingException(message: String, cause: Throwable? = null) : Exception(message, cause)
