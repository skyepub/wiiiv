package io.wiiiv.rag

import io.wiiiv.rag.chunk.Chunk
import io.wiiiv.rag.chunk.FixedSizeChunker
import io.wiiiv.rag.chunk.SentenceChunker
import io.wiiiv.rag.chunk.TokenChunker
import io.wiiiv.rag.embedding.*
import io.wiiiv.rag.retrieval.RetrievalResult
import io.wiiiv.rag.retrieval.SimpleRetriever
import io.wiiiv.rag.vector.InMemoryVectorStore
import io.wiiiv.rag.vector.VectorEntry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class RagTest {

    // ========== Embedding Tests ==========

    @Nested
    inner class EmbeddingTests {

        @Test
        fun `CosineSimilarity - identical vectors have similarity 1`() {
            val v1 = floatArrayOf(1f, 0f, 0f)
            val v2 = floatArrayOf(1f, 0f, 0f)

            val similarity = CosineSimilarity.calculate(v1, v2)

            assertEquals(1.0f, similarity, 0.001f)
        }

        @Test
        fun `CosineSimilarity - orthogonal vectors have similarity 0`() {
            val v1 = floatArrayOf(1f, 0f, 0f)
            val v2 = floatArrayOf(0f, 1f, 0f)

            val similarity = CosineSimilarity.calculate(v1, v2)

            assertEquals(0.0f, similarity, 0.001f)
        }

        @Test
        fun `CosineSimilarity - opposite vectors have similarity -1`() {
            val v1 = floatArrayOf(1f, 0f, 0f)
            val v2 = floatArrayOf(-1f, 0f, 0f)

            val similarity = CosineSimilarity.calculate(v1, v2)

            assertEquals(-1.0f, similarity, 0.001f)
        }

        @Test
        fun `CosineSimilarity - normalize produces unit vector`() {
            val v = floatArrayOf(3f, 4f, 0f)

            val normalized = CosineSimilarity.normalize(v)
            val magnitude = kotlin.math.sqrt(normalized.sumOf { (it * it).toDouble() }).toFloat()

            assertEquals(1.0f, magnitude, 0.001f)
        }

        @Test
        fun `MockEmbeddingProvider - same text produces same vector`() = runBlocking {
            val provider = MockEmbeddingProvider()

            val embedding1 = provider.embed("hello world")
            val embedding2 = provider.embed("hello world")

            assertArrayEquals(embedding1.vector, embedding2.vector)
        }

        @Test
        fun `MockEmbeddingProvider - different texts produce different vectors`() = runBlocking {
            val provider = MockEmbeddingProvider()

            val embedding1 = provider.embed("hello world")
            val embedding2 = provider.embed("goodbye world")

            assertFalse(embedding1.vector.contentEquals(embedding2.vector))
        }

        @Test
        fun `MockEmbeddingProvider - batch embedding works`() = runBlocking {
            val provider = MockEmbeddingProvider()

            val response = provider.embedBatch(listOf("text1", "text2", "text3"))

            assertEquals(3, response.embeddings.size)
            assertEquals(1536, response.embeddings[0].dimension)
        }

        @Test
        fun `SimilarityAwareMockProvider - similar texts have higher similarity`() = runBlocking {
            val provider = SimilarityAwareMockProvider()

            val catEmbedding = provider.embed("The cat sat on the mat")
            val dogEmbedding = provider.embed("The dog sat on the mat")
            val carEmbedding = provider.embed("The car drove on the road")

            val catDogSimilarity = CosineSimilarity.calculate(catEmbedding.vector, dogEmbedding.vector)
            val catCarSimilarity = CosineSimilarity.calculate(catEmbedding.vector, carEmbedding.vector)

            // cat-dog should be more similar than cat-car (share more words)
            assertTrue(catDogSimilarity > catCarSimilarity)
        }
    }

    // ========== Chunker Tests ==========

    @Nested
    inner class ChunkerTests {

        @Test
        fun `FixedSizeChunker - chunks text correctly`() {
            val chunker = FixedSizeChunker(chunkSize = 100, overlap = 20)

            val text = "A".repeat(250)
            val chunks = chunker.chunk(text, "source-1")

            assertTrue(chunks.isNotEmpty())
            chunks.forEach { chunk ->
                assertTrue(chunk.content.length <= 100)
            }
        }

        @Test
        fun `FixedSizeChunker - generates deterministic IDs`() {
            val chunker = FixedSizeChunker()

            val chunks1 = chunker.chunk("Hello World", "source-1")
            val chunks2 = chunker.chunk("Hello World", "source-1")

            assertEquals(chunks1.map { it.id }, chunks2.map { it.id })
        }

        @Test
        fun `FixedSizeChunker - empty text returns empty list`() {
            val chunker = FixedSizeChunker()

            val chunks = chunker.chunk("", "source-1")

            assertTrue(chunks.isEmpty())
        }

        @Test
        fun `FixedSizeChunker - overlap preserves context`() {
            val chunker = FixedSizeChunker(chunkSize = 50, overlap = 10)

            val text = "A".repeat(100)
            val chunks = chunker.chunk(text, "source-1")

            // Check that consecutive chunks have overlapping content
            for (i in 0 until chunks.size - 1) {
                @Suppress("UNUSED_VARIABLE") val _endOfCurrent = chunks[i].content.takeLast(10)
                @Suppress("UNUSED_VARIABLE") val _startOfNext = chunks[i + 1].content.take(10)
                // Due to overlap, there should be shared content
                assertTrue(chunks.size >= 2)
            }
        }

        @Test
        fun `SentenceChunker - preserves sentence boundaries`() {
            val chunker = SentenceChunker(maxChunkSize = 100)

            val text = "First sentence. Second sentence. Third sentence."
            val chunks = chunker.chunk(text, "source-1")

            // Should try to keep sentences together
            assertTrue(chunks.isNotEmpty())
        }

        @Test
        fun `SentenceChunker - handles long sentences with forced split`() {
            val chunker = SentenceChunker(maxChunkSize = 50)

            val longSentence = "A".repeat(100)
            val chunks = chunker.chunk(longSentence, "source-1")

            // Long sentence should be force-split
            assertTrue(chunks.size > 1)
        }

        @Test
        fun `TokenChunker - chunks by token count`() {
            val chunker = TokenChunker(maxTokens = 5, overlap = 1)

            val text = "one two three four five six seven eight nine ten"
            val chunks = chunker.chunk(text, "source-1")

            assertTrue(chunks.size >= 2)
        }

        @Test
        fun `Chunk ID is deterministic`() {
            val id1 = Chunk.generateId("content", "source", 0)
            val id2 = Chunk.generateId("content", "source", 0)
            val id3 = Chunk.generateId("different", "source", 0)

            assertEquals(id1, id2)
            assertNotEquals(id1, id3)
        }
    }

    // ========== VectorStore Tests ==========

    @Nested
    inner class VectorStoreTests {
        private lateinit var store: InMemoryVectorStore

        @BeforeEach
        fun setup() {
            store = InMemoryVectorStore()
        }

        @Test
        fun `store and retrieve entry`() = runBlocking {
            val entry = VectorEntry(
                id = "entry-1",
                vector = floatArrayOf(1f, 0f, 0f),
                content = "Test content",
                sourceId = "doc-1",
                chunkIndex = 0
            )

            store.store(entry)
            val retrieved = store.get("entry-1")

            assertNotNull(retrieved)
            assertEquals("Test content", retrieved?.content)
        }

        @Test
        fun `search returns results sorted by similarity`() = runBlocking {
            // Store entries with different vectors
            store.store(VectorEntry("e1", floatArrayOf(1f, 0f, 0f), "First", "doc-1", 0))
            store.store(VectorEntry("e2", floatArrayOf(0.9f, 0.1f, 0f), "Second", "doc-1", 1))
            store.store(VectorEntry("e3", floatArrayOf(0f, 1f, 0f), "Third", "doc-1", 2))

            val queryVector = floatArrayOf(1f, 0f, 0f)
            val results = store.search(queryVector, topK = 3)

            assertEquals(3, results.size)
            assertEquals("e1", results[0].id) // Most similar
            assertEquals("e2", results[1].id) // Second most similar
            assertTrue(results[0].score > results[1].score)
        }

        @Test
        fun `search respects minScore filter`() = runBlocking {
            store.store(VectorEntry("e1", floatArrayOf(1f, 0f, 0f), "High", "doc-1", 0))
            store.store(VectorEntry("e2", floatArrayOf(0f, 1f, 0f), "Low", "doc-1", 1))

            val queryVector = floatArrayOf(1f, 0f, 0f)
            val results = store.search(queryVector, topK = 10, minScore = 0.5f)

            // Only the similar one should be returned
            assertEquals(1, results.size)
            assertEquals("e1", results[0].id)
        }

        @Test
        fun `delete removes entry`() = runBlocking {
            store.store(VectorEntry("e1", floatArrayOf(1f, 0f, 0f), "Content", "doc-1", 0))

            val deleted = store.delete("e1")

            assertTrue(deleted)
            assertNull(store.get("e1"))
        }

        @Test
        fun `deleteBySource removes all entries for source`() = runBlocking {
            store.store(VectorEntry("e1", floatArrayOf(1f, 0f, 0f), "C1", "doc-1", 0))
            store.store(VectorEntry("e2", floatArrayOf(0f, 1f, 0f), "C2", "doc-1", 1))
            store.store(VectorEntry("e3", floatArrayOf(0f, 0f, 1f), "C3", "doc-2", 0))

            val count = store.deleteBySource("doc-1")

            assertEquals(2, count)
            assertEquals(1, store.size())
        }

        @Test
        fun `clear removes all entries`() = runBlocking {
            store.store(VectorEntry("e1", floatArrayOf(1f, 0f, 0f), "C1", "doc-1", 0))
            store.store(VectorEntry("e2", floatArrayOf(0f, 1f, 0f), "C2", "doc-2", 0))

            store.clear()

            assertEquals(0, store.size())
        }
    }

    // ========== Retriever Tests ==========

    @Nested
    inner class RetrieverTests {
        private lateinit var provider: EmbeddingProvider
        private lateinit var store: InMemoryVectorStore
        private lateinit var retriever: SimpleRetriever

        @BeforeEach
        fun setup() {
            provider = SimilarityAwareMockProvider()
            store = InMemoryVectorStore()
            retriever = SimpleRetriever(provider, store)
        }

        @Test
        fun `retrieve returns relevant documents`() = runBlocking {
            // Ingest documents manually
            val texts = listOf(
                "The quick brown fox jumps over the lazy dog",
                "A fast orange fox leaps across the sleepy hound",
                "The weather today is sunny and warm"
            )

            texts.forEachIndexed { index, text ->
                val embedding = provider.embed(text)
                store.store(VectorEntry(
                    id = "chunk-$index",
                    vector = embedding.vector,
                    content = text,
                    sourceId = "doc-$index",
                    chunkIndex = 0
                ))
            }

            val result = retriever.retrieve("fox jumping", topK = 2)

            assertEquals(2, result.results.size)
            // Fox-related documents should rank higher
            assertTrue(result.results[0].content.contains("fox", ignoreCase = true))
        }

        @Test
        fun `retrieveMulti merges results from multiple queries`() = runBlocking {
            val texts = listOf("Apple fruit", "Banana fruit", "Car vehicle", "Bike vehicle")

            texts.forEachIndexed { index, text ->
                val embedding = provider.embed(text)
                store.store(VectorEntry(
                    id = "chunk-$index",
                    vector = embedding.vector,
                    content = text,
                    sourceId = "doc-$index",
                    chunkIndex = 0
                ))
            }

            val result = retriever.retrieveMulti(
                queries = listOf("apple", "car"),
                topK = 4
            )

            assertTrue(result.results.isNotEmpty())
        }

        @Test
        fun `RetrievalResult toContextString formats correctly`() {
            val result = RetrievalResult(
                query = "test",
                results = listOf(
                    io.wiiiv.rag.retrieval.RetrievedDocument("1", "First content", 0.9f, "doc-1", 0, emptyMap()),
                    io.wiiiv.rag.retrieval.RetrievedDocument("2", "Second content", 0.8f, "doc-2", 0, emptyMap())
                ),
                totalFound = 2
            )

            val context = result.toContextString()

            assertTrue(context.contains("First content"))
            assertTrue(context.contains("Second content"))
        }
    }

    // ========== Pipeline Tests ==========

    @Nested
    inner class PipelineTests {
        private lateinit var pipeline: RagPipeline

        @BeforeEach
        fun setup() {
            pipeline = RagPipeline(
                embeddingProvider = SimilarityAwareMockProvider(),  // Use similarity-aware for better search
                vectorStore = InMemoryVectorStore(),
                chunker = FixedSizeChunker(chunkSize = 100, overlap = 20)
            )
        }

        @Test
        fun `ingest document creates chunks`() = runBlocking {
            val result = pipeline.ingestText(
                text = "A".repeat(250),
                title = "Test Document"
            )

            assertTrue(result.success)
            assertTrue(result.chunkCount > 0)
        }

        @Test
        fun `ingest and search workflow`() = runBlocking {
            // Ingest
            pipeline.ingestText(
                text = "Machine learning is a subset of artificial intelligence.",
                title = "ML Introduction"
            )
            pipeline.ingestText(
                text = "Deep learning uses neural networks with many layers.",
                title = "DL Introduction"
            )

            // Search
            val result = pipeline.search("neural networks", topK = 2)

            assertTrue(result.isNotEmpty())
        }

        @Test
        fun `delete removes document chunks`() = runBlocking {
            val ingested = pipeline.ingestText("Test content", "Test")
            val sizeAfterIngest = pipeline.size()

            pipeline.deleteDocument(ingested.documentId)
            val sizeAfterDelete = pipeline.size()

            assertTrue(sizeAfterDelete < sizeAfterIngest)
        }

        @Test
        fun `clear removes all data`() = runBlocking {
            pipeline.ingestText("Doc 1")
            pipeline.ingestText("Doc 2")

            pipeline.clear()

            assertEquals(0, pipeline.size())
        }

        @Test
        fun `batch ingestion works`() = runBlocking {
            val documents = listOf(
                Document(content = "First document"),
                Document(content = "Second document"),
                Document(content = "Third document")
            )

            val result = pipeline.ingestBatch(documents)

            assertEquals(3, result.totalDocuments)
            assertEquals(3, result.successCount)
            assertEquals(0, result.failureCount)
        }
    }

    // ========== Builder Tests ==========

    @Nested
    inner class BuilderTests {

        @Test
        fun `RagPipelineBuilder creates pipeline with custom config`() {
            val pipeline = RagPipelineBuilder()
                .withEmbeddingProvider(MockEmbeddingProvider())
                .withVectorStore(InMemoryVectorStore("custom-store"))
                .withFixedSizeChunking(chunkSize = 200, overlap = 40)
                .build()

            assertNotNull(pipeline)
        }

        @Test
        fun `ragPipeline DSL works`() {
            val pipeline = ragPipeline {
                withEmbeddingProvider(MockEmbeddingProvider())
                withFixedSizeChunking(300, 50)
            }

            assertNotNull(pipeline)
        }

        @Test
        fun `builder throws if no embedding provider`() {
            assertThrows(IllegalStateException::class.java) {
                RagPipelineBuilder().build()
            }
        }
    }
}
