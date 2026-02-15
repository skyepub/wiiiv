package io.wiiiv.rag

import io.wiiiv.rag.chunk.Chunk
import io.wiiiv.rag.chunk.Chunker
import io.wiiiv.rag.chunk.FixedSizeChunker
import io.wiiiv.rag.embedding.EmbeddingProvider
import io.wiiiv.rag.retrieval.RetrievalResult
import io.wiiiv.rag.retrieval.Retriever
import io.wiiiv.rag.retrieval.SimpleRetriever
import io.wiiiv.rag.vector.InMemoryVectorStore
import io.wiiiv.rag.vector.VectorEntry
import io.wiiiv.rag.vector.VectorStore
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.util.UUID

/**
 * 문서 모델
 */
data class Document(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val title: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * 문서 수집 결과
 */
data class IngestionResult(
    val documentId: String,
    val title: String?,
    val chunkCount: Int,
    val success: Boolean,
    val error: String? = null
)

/**
 * 배치 수집 결과
 */
data class BatchIngestionResult(
    val results: List<IngestionResult>,
    val totalDocuments: Int,
    val successCount: Int,
    val failureCount: Int,
    val totalChunks: Int
)

/**
 * RAG 파이프라인
 *
 * 문서 수집(Ingestion)과 검색(Retrieval)을 통합한다.
 *
 * ## 흐름
 * ```
 * Document → Chunker → EmbeddingProvider → VectorStore
 *                                              ↓
 * Query → EmbeddingProvider → VectorStore → Results
 * ```
 *
 * ## 헌법
 * - Pipeline은 조율만 한다
 * - Pipeline은 판단하지 않는다
 * - 개별 컴포넌트의 책임을 침범하지 않는다
 */
class RagPipeline(
    val embeddingProvider: EmbeddingProvider,
    val vectorStore: VectorStore = InMemoryVectorStore(),
    val chunker: Chunker = FixedSizeChunker(),
    private val batchSize: Int = 100
) {
    private val retriever: Retriever = SimpleRetriever(embeddingProvider, vectorStore)

    /**
     * 단일 문서 수집
     */
    suspend fun ingest(document: Document): IngestionResult {
        return try {
            // 1. 청킹
            val chunks = chunker.chunk(document.content, document.id)

            if (chunks.isEmpty()) {
                return IngestionResult(
                    documentId = document.id,
                    title = document.title,
                    chunkCount = 0,
                    success = true
                )
            }

            // 2. 임베딩 (batchSize 단위로 분할하여 API 토큰 한도 초과 방지)
            val texts = chunks.map { it.content }
            val allEmbeddings = mutableListOf<io.wiiiv.rag.embedding.Embedding>()

            for (batch in texts.chunked(batchSize)) {
                val response = embeddingProvider.embedBatch(batch)
                allEmbeddings.addAll(response.embeddings)
            }

            // 3. 벡터 저장
            val entries = chunks.mapIndexed { index, chunk ->
                VectorEntry(
                    id = chunk.id,
                    vector = allEmbeddings[index].vector,
                    content = chunk.content,
                    sourceId = document.id,
                    chunkIndex = chunk.index,
                    metadata = chunk.metadata + (document.metadata) + mapOf(
                        "title" to (document.title ?: ""),
                        "documentId" to document.id
                    )
                )
            }

            vectorStore.storeBatch(entries)

            IngestionResult(
                documentId = document.id,
                title = document.title,
                chunkCount = chunks.size,
                success = true
            )
        } catch (e: Exception) {
            IngestionResult(
                documentId = document.id,
                title = document.title,
                chunkCount = 0,
                success = false,
                error = e.message
            )
        }
    }

    /**
     * 배치 문서 수집
     */
    suspend fun ingestBatch(documents: List<Document>): BatchIngestionResult {
        val results = documents.map { ingest(it) }

        return BatchIngestionResult(
            results = results,
            totalDocuments = documents.size,
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            totalChunks = results.sumOf { it.chunkCount }
        )
    }

    /**
     * 텍스트에서 문서 생성 및 수집
     */
    suspend fun ingestText(text: String, title: String? = null, metadata: Map<String, String> = emptyMap()): IngestionResult {
        val document = Document(
            content = text,
            title = title,
            metadata = metadata
        )
        return ingest(document)
    }

    /**
     * 파일에서 문서 수집
     */
    suspend fun ingestFile(file: File, metadata: Map<String, String> = emptyMap()): IngestionResult {
        if (!file.exists()) {
            return IngestionResult(
                documentId = "",
                title = file.name,
                chunkCount = 0,
                success = false,
                error = "File not found: ${file.absolutePath}"
            )
        }

        val content = if (file.extension.lowercase() == "pdf") {
            extractPdfText(file)
        } else {
            file.readText()
        }
        val document = Document(
            content = content,
            title = file.name,
            metadata = metadata + mapOf(
                "filePath" to file.absolutePath,
                "fileSize" to file.length().toString()
            )
        )

        return ingest(document)
    }

    /**
     * 디렉토리에서 모든 파일 수집
     */
    suspend fun ingestDirectory(
        directory: File,
        extensions: Set<String> = setOf("txt", "md", "json", "pdf"),
        recursive: Boolean = true
    ): BatchIngestionResult {
        if (!directory.isDirectory) {
            return BatchIngestionResult(
                results = emptyList(),
                totalDocuments = 0,
                successCount = 0,
                failureCount = 1,
                totalChunks = 0
            )
        }

        val files = if (recursive) {
            directory.walkTopDown()
                .filter { it.isFile && it.extension.lowercase() in extensions }
                .toList()
        } else {
            directory.listFiles()
                ?.filter { it.isFile && it.extension.lowercase() in extensions }
                ?: emptyList()
        }

        val results = files.map { file ->
            ingestFile(file, mapOf("directory" to directory.absolutePath))
        }

        return BatchIngestionResult(
            results = results,
            totalDocuments = files.size,
            successCount = results.count { it.success },
            failureCount = results.count { !it.success },
            totalChunks = results.sumOf { it.chunkCount }
        )
    }

    /**
     * 검색
     */
    suspend fun search(query: String, topK: Int = 5, minScore: Float = 0.0f): RetrievalResult {
        return retriever.retrieve(query, topK, minScore)
    }

    /**
     * 다중 쿼리 검색
     */
    suspend fun searchMulti(queries: List<String>, topK: Int = 5, minScore: Float = 0.0f): RetrievalResult {
        return retriever.retrieveMulti(queries, topK, minScore)
    }

    /**
     * 문서 삭제
     */
    suspend fun deleteDocument(documentId: String): Int {
        return vectorStore.deleteBySource(documentId)
    }

    /**
     * 저장소 초기화
     */
    suspend fun clear() {
        vectorStore.clear()
    }

    /**
     * 저장된 문서 수 (청크 기준)
     */
    suspend fun size(): Int {
        return vectorStore.size()
    }

    /**
     * PDF 파일에서 텍스트 추출
     */
    private fun extractPdfText(file: File): String {
        Loader.loadPDF(file).use { document ->
            return PDFTextStripper().getText(document)
        }
    }
}

/**
 * RAG 파이프라인 빌더
 */
class RagPipelineBuilder {
    private var embeddingProvider: EmbeddingProvider? = null
    private var vectorStore: VectorStore? = null
    private var chunker: Chunker? = null
    private var batchSize: Int = 100

    fun withEmbeddingProvider(provider: EmbeddingProvider) = apply {
        this.embeddingProvider = provider
    }

    fun withVectorStore(store: VectorStore) = apply {
        this.vectorStore = store
    }

    fun withChunker(chunker: Chunker) = apply {
        this.chunker = chunker
    }

    fun withFixedSizeChunking(chunkSize: Int = 500, overlap: Int = 50) = apply {
        this.chunker = FixedSizeChunker(chunkSize, overlap)
    }

    fun withBatchSize(size: Int) = apply {
        this.batchSize = size
    }

    fun build(): RagPipeline {
        val provider = embeddingProvider
            ?: throw IllegalStateException("EmbeddingProvider is required")

        return RagPipeline(
            embeddingProvider = provider,
            vectorStore = vectorStore ?: InMemoryVectorStore(),
            chunker = chunker ?: FixedSizeChunker(),
            batchSize = batchSize
        )
    }
}

/**
 * 빌더 DSL
 */
fun ragPipeline(block: RagPipelineBuilder.() -> Unit): RagPipeline {
    return RagPipelineBuilder().apply(block).build()
}
