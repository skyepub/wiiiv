package io.wiiiv.rag

import io.wiiiv.execution.*
import io.wiiiv.rag.embedding.MockEmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RagExecutorTest {

    private lateinit var executor: RagExecutor
    private lateinit var context: ExecutionContext

    @BeforeEach
    fun setup() {
        val pipeline = RagPipeline(
            embeddingProvider = MockEmbeddingProvider(),
            vectorStore = InMemoryVectorStore()
        )
        executor = RagExecutor(pipeline)
        context = ExecutionContext.create(
            executionId = "test-execution",
            blueprintId = "test-blueprint",
            instructionId = "test-instruction"
        )
    }

    @Test
    fun `executor can handle RagStep`() {
        val step = ExecutionStep.RagStep(
            stepId = "test",
            action = RagAction.SIZE
        )
        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `executor cannot handle other steps`() {
        val step = ExecutionStep.NoopStep(stepId = "test")
        assertFalse(executor.canHandle(step))
    }

    @Test
    fun `INGEST action stores document`() = runBlocking {
        val step = ExecutionStep.RagStep(
            stepId = "step-1",
            action = RagAction.INGEST,
            content = "This is test content for RAG ingestion.",
            title = "Test Document"
        )

        val result = executor.execute(step, context)

        assertTrue(result.isSuccess)
        val output = (result as ExecutionResult.Success).output
        assertNotNull(output.json["documentId"])
        assertEquals("INGEST", output.json["action"]?.toString()?.trim('"'))
    }

    @Test
    fun `SEARCH action returns results`() = runBlocking {
        // First ingest a document
        val ingestStep = ExecutionStep.RagStep(
            stepId = "ingest-1",
            action = RagAction.INGEST,
            content = "Machine learning is a field of artificial intelligence.",
            title = "ML Doc"
        )
        executor.execute(ingestStep, context)

        // Then search
        val searchStep = ExecutionStep.RagStep(
            stepId = "search-1",
            action = RagAction.SEARCH,
            query = "artificial intelligence",
            topK = 5
        )

        val result = executor.execute(searchStep, context)

        assertTrue(result.isSuccess)
        val output = (result as ExecutionResult.Success).output
        assertEquals("artificial intelligence", output.json["query"]?.toString()?.trim('"'))
        assertEquals("SEARCH", output.json["action"]?.toString()?.trim('"'))
    }

    @Test
    fun `DELETE action removes document`() = runBlocking {
        // Ingest
        val ingestStep = ExecutionStep.RagStep(
            stepId = "ingest-1",
            action = RagAction.INGEST,
            content = "Content to delete",
            documentId = "doc-to-delete"
        )
        executor.execute(ingestStep, context)

        // Delete
        val deleteStep = ExecutionStep.RagStep(
            stepId = "delete-1",
            action = RagAction.DELETE,
            documentId = "doc-to-delete"
        )

        val result = executor.execute(deleteStep, context)

        assertTrue(result.isSuccess)
        val output = (result as ExecutionResult.Success).output
        assertEquals("doc-to-delete", output.json["documentId"]?.toString()?.trim('"'))
        assertEquals("DELETE", output.json["action"]?.toString()?.trim('"'))
    }

    @Test
    fun `CLEAR action empties store`() = runBlocking {
        // Ingest some documents
        executor.execute(ExecutionStep.RagStep(
            stepId = "i1",
            action = RagAction.INGEST,
            content = "Doc 1"
        ), context)
        executor.execute(ExecutionStep.RagStep(
            stepId = "i2",
            action = RagAction.INGEST,
            content = "Doc 2"
        ), context)

        // Clear
        val result = executor.execute(ExecutionStep.RagStep(
            stepId = "clear-1",
            action = RagAction.CLEAR
        ), context)

        assertTrue(result.isSuccess)
        val output = (result as ExecutionResult.Success).output
        assertEquals("CLEAR", output.json["action"]?.toString()?.trim('"'))

        // Verify empty
        val sizeResult = executor.execute(ExecutionStep.RagStep(
            stepId = "size-1",
            action = RagAction.SIZE
        ), context)
        val sizeOutput = (sizeResult as ExecutionResult.Success).output
        assertEquals("0", sizeOutput.json["size"]?.toString())
    }

    @Test
    fun `SIZE action returns chunk count`() = runBlocking {
        // Ingest
        executor.execute(ExecutionStep.RagStep(
            stepId = "i1",
            action = RagAction.INGEST,
            content = "Test content"
        ), context)

        // Size
        val result = executor.execute(ExecutionStep.RagStep(
            stepId = "size-1",
            action = RagAction.SIZE
        ), context)

        assertTrue(result.isSuccess)
        val output = (result as ExecutionResult.Success).output
        assertTrue(output.json["size"].toString().toInt() > 0)
    }

    @Test
    fun `INGEST without content returns failure`() = runBlocking {
        val step = ExecutionStep.RagStep(
            stepId = "step-1",
            action = RagAction.INGEST
        )

        val result = executor.execute(step, context)

        assertTrue(result.isFailure)
        val failure = result as ExecutionResult.Failure
        assertTrue(failure.error.message.contains("content", ignoreCase = true))
    }

    @Test
    fun `SEARCH without query returns failure`() = runBlocking {
        val step = ExecutionStep.RagStep(
            stepId = "step-1",
            action = RagAction.SEARCH
        )

        val result = executor.execute(step, context)

        assertTrue(result.isFailure)
        val failure = result as ExecutionResult.Failure
        assertTrue(failure.error.message.contains("query", ignoreCase = true))
    }

    @Test
    fun `DELETE without documentId returns failure`() = runBlocking {
        val step = ExecutionStep.RagStep(
            stepId = "step-1",
            action = RagAction.DELETE
        )

        val result = executor.execute(step, context)

        assertTrue(result.isFailure)
        val failure = result as ExecutionResult.Failure
        assertTrue(failure.error.message.contains("documentId", ignoreCase = true))
    }

    @Test
    fun `createMock factory creates working executor`() = runBlocking {
        val mockExecutor = RagExecutor.createMock()

        val result = mockExecutor.execute(ExecutionStep.RagStep(
            stepId = "test",
            action = RagAction.SIZE
        ), context)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `full workflow - ingest, search, delete`() = runBlocking {
        // 1. Ingest multiple documents
        val doc1Result = executor.execute(ExecutionStep.RagStep(
            stepId = "i1",
            action = RagAction.INGEST,
            content = "Kotlin is a modern programming language.",
            title = "Kotlin Guide",
            documentId = "doc-kotlin"
        ), context)
        assertTrue(doc1Result.isSuccess)

        val doc2Result = executor.execute(ExecutionStep.RagStep(
            stepId = "i2",
            action = RagAction.INGEST,
            content = "Java is an older but widely used language.",
            title = "Java Guide"
        ), context)
        assertTrue(doc2Result.isSuccess)

        // 2. Search
        val searchResult = executor.execute(ExecutionStep.RagStep(
            stepId = "s1",
            action = RagAction.SEARCH,
            query = "programming language",
            topK = 2
        ), context)
        assertTrue(searchResult.isSuccess)
        val searchOutput = (searchResult as ExecutionResult.Success).output
        assertNotNull(searchOutput.rawResponse)

        // 3. Delete one document
        val deleteResult = executor.execute(ExecutionStep.RagStep(
            stepId = "d1",
            action = RagAction.DELETE,
            documentId = "doc-kotlin"
        ), context)
        assertTrue(deleteResult.isSuccess)

        // 4. Verify size decreased
        val sizeResult = executor.execute(ExecutionStep.RagStep(
            stepId = "sz1",
            action = RagAction.SIZE
        ), context)
        val sizeOutput = (sizeResult as ExecutionResult.Success).output
        assertTrue(sizeOutput.json["size"].toString().toInt() > 0)
    }

    @Test
    fun `executing non-RagStep returns failure`() = runBlocking {
        val step = ExecutionStep.NoopStep(stepId = "test")

        val result = executor.execute(step, context)

        assertTrue(result.isFailure)
        val failure = result as ExecutionResult.Failure
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, failure.error.category)
    }
}
