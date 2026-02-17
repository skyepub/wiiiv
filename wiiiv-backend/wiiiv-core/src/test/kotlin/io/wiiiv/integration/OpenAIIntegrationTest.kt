package io.wiiiv.integration

import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.rag.RagPipeline
import io.wiiiv.rag.Document
import io.wiiiv.rag.embedding.OpenAIEmbeddingProvider
import io.wiiiv.rag.embedding.CosineSimilarity
import io.wiiiv.rag.vector.InMemoryVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import kotlin.test.assertTrue
import kotlin.test.assertNotNull
import kotlin.test.assertEquals

/**
 * OpenAI 실제 API 통합 테스트
 *
 * 환경변수 OPENAI_API_KEY가 설정된 경우에만 실행
 *
 * 테스트 항목:
 * 1. LlmExecutor - GPT-4o-mini 호출
 * 2. RAG - 실제 OpenAI Embedding
 * 3. 의미론적 검색 품질
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAIIntegrationTest {

    companion object {
        // 테스트용 API 키 (실행 시 환경변수로 주입)
        private val API_KEY = System.getenv("OPENAI_API_KEY") ?: ""
        private const val MODEL = "gpt-4o-mini"
    }

    private lateinit var llmProvider: OpenAIProvider
    private lateinit var embeddingProvider: OpenAIEmbeddingProvider

    @BeforeAll
    fun setup() {
        if (API_KEY.isNotBlank()) {
            llmProvider = OpenAIProvider(
                apiKey = API_KEY,
                defaultModel = MODEL,
                defaultMaxTokens = 500
            )
            embeddingProvider = OpenAIEmbeddingProvider(
                apiKey = API_KEY
            )
        }
    }

    // ==================== LLM Executor Tests ====================

    @Test
    fun `LLM - simple completion with GPT-4o-mini`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val executor = LlmExecutor(llmProvider)
        val context = ExecutionContext.create(
            executionId = "test-llm-1",
            blueprintId = "bp-test",
            instructionId = "instr-test"
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-step-1",
            action = LlmAction.COMPLETE,
            prompt = "What is 2 + 2? Answer with just the number.",
            model = MODEL,
            maxTokens = 50
        )

        val result = executor.execute(step, context)

        println("LLM Result: $result")

        assertTrue(result.isSuccess, "LLM call should succeed")
        val output = (result as ExecutionResult.Success).output
        val content = output.artifacts["content"]
        assertNotNull(content, "Should have content")
        assertTrue(content.contains("4"), "Should contain answer '4', got: $content")

        println("✅ LLM Completion: $content")
    }

    @Test
    fun `LLM - analyze text with GPT-4o-mini`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val executor = LlmExecutor(llmProvider)
        val context = ExecutionContext.create(
            executionId = "test-llm-2",
            blueprintId = "bp-test",
            instructionId = "instr-test"
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-step-2",
            action = LlmAction.ANALYZE,
            prompt = """
                Analyze the sentiment of this text and respond with exactly one word: POSITIVE, NEGATIVE, or NEUTRAL.

                Text: "I love this product! It works amazingly well."
            """.trimIndent(),
            model = MODEL,
            maxTokens = 20
        )

        val result = executor.execute(step, context)

        assertTrue(result.isSuccess, "LLM call should succeed")
        val output = (result as ExecutionResult.Success).output
        val content = output.artifacts["content"]
        assertNotNull(content)
        assertTrue(
            content.uppercase().contains("POSITIVE"),
            "Should be POSITIVE sentiment, got: $content"
        )

        println("✅ LLM Analyze: $content")
    }

    @Test
    fun `LLM - summarize text with GPT-4o-mini`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val executor = LlmExecutor(llmProvider)
        val context = ExecutionContext.create(
            executionId = "test-llm-3",
            blueprintId = "bp-test",
            instructionId = "instr-test"
        )

        val longText = """
            Machine learning is a subset of artificial intelligence that focuses on building systems
            that learn from data. Instead of being explicitly programmed, these systems improve their
            performance on tasks through experience. There are three main types of machine learning:
            supervised learning, unsupervised learning, and reinforcement learning. Supervised learning
            uses labeled data, unsupervised learning finds patterns in unlabeled data, and reinforcement
            learning learns through trial and error with rewards.
        """.trimIndent()

        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-step-3",
            action = LlmAction.SUMMARIZE,
            prompt = "Summarize this text in one sentence:\n\n$longText",
            model = MODEL,
            maxTokens = 100
        )

        val result = executor.execute(step, context)

        assertTrue(result.isSuccess, "LLM call should succeed")
        val output = (result as ExecutionResult.Success).output
        val content = output.artifacts["content"]
        assertNotNull(content)
        assertTrue(content.length < longText.length, "Summary should be shorter than original")
        assertTrue(
            content.lowercase().contains("machine learning") || content.lowercase().contains("ai"),
            "Summary should mention the topic"
        )

        println("✅ LLM Summarize: $content")
    }

    // ==================== RAG with Real Embeddings ====================

    @Test
    fun `RAG - ingest and search with real OpenAI embeddings`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val pipeline = RagPipeline(
            embeddingProvider = embeddingProvider,
            vectorStore = InMemoryVectorStore("openai-test-store")
        )

        // 문서 수집
        val docs = listOf(
            Document(content = "Kotlin is a modern programming language for the JVM.", title = "Kotlin"),
            Document(content = "Python is great for machine learning and data science.", title = "Python"),
            Document(content = "Java is a widely used enterprise programming language.", title = "Java"),
            Document(content = "Chocolate cake is a delicious dessert made with cocoa.", title = "Cake")
        )

        docs.forEach { doc ->
            val result = pipeline.ingest(doc)
            assertTrue(result.success, "Ingestion should succeed for ${doc.title}")
            println("✅ Ingested: ${doc.title}")
        }

        // 검색 테스트 1: 프로그래밍 관련
        val result1 = pipeline.search("programming language for backend development", topK = 3)
        println("\nSearch: 'programming language for backend development'")
        result1.results.forEach { r ->
            println("  - ${r.content.take(50)}... (score: ${r.score})")
        }

        assertTrue(result1.results.isNotEmpty(), "Should find results")
        // 프로그래밍 관련 문서가 케이크보다 상위에 있어야 함
        val topContent = result1.results.first().content.lowercase()
        assertTrue(
            topContent.contains("kotlin") || topContent.contains("java") || topContent.contains("python"),
            "Top result should be about programming, got: $topContent"
        )

        // 검색 테스트 2: 머신러닝 관련
        val result2 = pipeline.search("AI and data science", topK = 2)
        println("\nSearch: 'AI and data science'")
        result2.results.forEach { r ->
            println("  - ${r.content.take(50)}... (score: ${r.score})")
        }

        assertTrue(
            result2.results.first().content.lowercase().contains("python"),
            "Python should rank highest for ML query"
        )

        // 검색 테스트 3: 관련 없는 쿼리
        val result3 = pipeline.search("sweet food dessert", topK = 2)
        println("\nSearch: 'sweet food dessert'")
        result3.results.forEach { r ->
            println("  - ${r.content.take(50)}... (score: ${r.score})")
        }

        assertTrue(
            result3.results.first().content.lowercase().contains("chocolate") ||
            result3.results.first().content.lowercase().contains("cake"),
            "Cake should rank highest for dessert query"
        )

        println("\n✅ RAG semantic search working correctly!")
    }

    @Test
    fun `RAG - embedding similarity comparison`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        // 유사한 문장들
        val sentence1 = "The cat sat on the mat."
        val sentence2 = "A cat was sitting on a rug."
        val sentence3 = "The stock market crashed yesterday."

        val emb1 = embeddingProvider.embed(sentence1)
        val emb2 = embeddingProvider.embed(sentence2)
        val emb3 = embeddingProvider.embed(sentence3)

        val sim12 = CosineSimilarity.calculate(emb1.vector, emb2.vector)
        val sim13 = CosineSimilarity.calculate(emb1.vector, emb3.vector)
        val sim23 = CosineSimilarity.calculate(emb2.vector, emb3.vector)

        println("Embedding Similarity Test:")
        println("  '$sentence1'")
        println("  '$sentence2'")
        println("  '$sentence3'")
        println()
        println("  Similarity (1-2, similar): $sim12")
        println("  Similarity (1-3, different): $sim13")
        println("  Similarity (2-3, different): $sim23")

        // 유사한 문장이 더 높은 유사도를 가져야 함
        assertTrue(sim12 > sim13, "Similar sentences should have higher similarity: $sim12 > $sim13")
        assertTrue(sim12 > sim23, "Similar sentences should have higher similarity: $sim12 > $sim23")

        println("\n✅ Embedding similarity correctly reflects semantic meaning!")
    }

    // ==================== Token Usage Tracking ====================

    @Test
    fun `LLM - verify token usage is tracked`() = runBlocking {
        if (API_KEY.isBlank()) {
            println("SKIP: OPENAI_API_KEY not set")
            return@runBlocking
        }

        val executor = LlmExecutor(llmProvider)
        val context = ExecutionContext.create(
            executionId = "test-llm-tokens",
            blueprintId = "bp-test",
            instructionId = "instr-test"
        )

        val step = ExecutionStep.LlmCallStep(
            stepId = "llm-token-test",
            action = LlmAction.COMPLETE,
            prompt = "Say hello in Korean.",
            model = MODEL,
            maxTokens = 50
        )

        val result = executor.execute(step, context)

        assertTrue(result.isSuccess)
        val output = (result as ExecutionResult.Success).output

        val promptTokens = output.json["promptTokens"]?.toString()?.toIntOrNull() ?: 0
        val completionTokens = output.json["completionTokens"]?.toString()?.toIntOrNull() ?: 0
        val totalTokens = output.json["totalTokens"]?.toString()?.toIntOrNull() ?: 0

        println("Token Usage:")
        println("  Prompt tokens: $promptTokens")
        println("  Completion tokens: $completionTokens")
        println("  Total tokens: $totalTokens")

        assertTrue(promptTokens > 0, "Should have prompt tokens")
        assertTrue(completionTokens > 0, "Should have completion tokens")
        assertEquals(promptTokens + completionTokens, totalTokens, "Total should equal sum")

        println("\n✅ Token usage tracking working!")
    }
}
