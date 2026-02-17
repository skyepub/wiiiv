package io.wiiiv.execution

import io.wiiiv.rag.Document
import io.wiiiv.rag.RagPipeline
import io.wiiiv.rag.embedding.EmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import io.wiiiv.rag.vector.VectorStore
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant

/**
 * RAG Executor
 *
 * Blueprint에서 RAG 작업을 실행한다.
 *
 * ## 지원 작업
 * - INGEST: 문서 수집
 * - SEARCH: 유사도 검색
 * - DELETE: 문서 삭제
 * - CLEAR: 저장소 초기화
 * - SIZE: 저장소 크기 조회
 *
 * ## 헌법
 * - Executor는 실행만 한다
 * - Executor는 판단하지 않는다
 * - 결과는 그대로 반환한다
 */
class RagExecutor(
    private val ragPipeline: RagPipeline
) : Executor {

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val startedAt = Instant.now()

        return try {
            when (step) {
                is ExecutionStep.RagStep -> executeRagStep(step, startedAt)
                else -> ExecutionResult.failure(
                    error = ExecutionError.contractViolation(
                        "INVALID_STEP_TYPE",
                        "RagExecutor can only execute RagStep, got: ${step::class.simpleName}"
                    ),
                    meta = ExecutionMeta.now(step.stepId)
                )
            }
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.unknown(e.message ?: "RAG execution failed"),
                meta = ExecutionMeta.of(step.stepId, startedAt, Instant.now())
            )
        }
    }

    private suspend fun executeRagStep(step: ExecutionStep.RagStep, startedAt: Instant): ExecutionResult {
        return when (step.action) {
            RagAction.INGEST -> executeIngest(step, startedAt)
            RagAction.SEARCH -> executeSearch(step, startedAt)
            RagAction.DELETE -> executeDelete(step, startedAt)
            RagAction.CLEAR -> executeClear(step, startedAt)
            RagAction.SIZE -> executeSize(step, startedAt)
        }
    }

    private suspend fun executeIngest(step: ExecutionStep.RagStep, startedAt: Instant): ExecutionResult {
        val content = step.content
            ?: return ExecutionResult.failure(
                error = ExecutionError.contractViolation("MISSING_CONTENT", "Missing 'content' for INGEST"),
                meta = ExecutionMeta.now(step.stepId)
            )

        val document = Document(
            id = step.documentId ?: java.util.UUID.randomUUID().toString(),
            content = content,
            title = step.title,
            metadata = step.params
        )

        val result = ragPipeline.ingest(document)
        val endedAt = Instant.now()

        return if (result.success) {
            ExecutionResult.success(
                output = StepOutput.json(
                    stepId = step.stepId,
                    data = mapOf(
                        "documentId" to JsonPrimitive(result.documentId),
                        "title" to JsonPrimitive(result.title ?: ""),
                        "chunkCount" to JsonPrimitive(result.chunkCount),
                        "action" to JsonPrimitive("INGEST")
                    ),
                    durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
                ),
                meta = ExecutionMeta.of(step.stepId, startedAt, endedAt)
            )
        } else {
            ExecutionResult.failure(
                error = ExecutionError.ioError("INGEST_FAILED", result.error ?: "Ingestion failed"),
                meta = ExecutionMeta.of(step.stepId, startedAt, endedAt)
            )
        }
    }

    private suspend fun executeSearch(step: ExecutionStep.RagStep, startedAt: Instant): ExecutionResult {
        val query = step.query
            ?: return ExecutionResult.failure(
                error = ExecutionError.contractViolation("MISSING_QUERY", "Missing 'query' for SEARCH"),
                meta = ExecutionMeta.now(step.stepId)
            )

        val result = ragPipeline.search(query, step.topK, step.minScore)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput(
                stepId = step.stepId,
                json = mapOf(
                    "query" to JsonPrimitive(query),
                    "totalFound" to JsonPrimitive(result.totalFound),
                    "action" to JsonPrimitive("SEARCH")
                ),
                rawResponse = result.toContextString(),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(step.stepId, startedAt, endedAt)
        )
    }

    private suspend fun executeDelete(step: ExecutionStep.RagStep, startedAt: Instant): ExecutionResult {
        val documentId = step.documentId
            ?: return ExecutionResult.failure(
                error = ExecutionError.contractViolation("MISSING_DOCUMENT_ID", "Missing 'documentId' for DELETE"),
                meta = ExecutionMeta.now(step.stepId)
            )

        val deletedCount = ragPipeline.deleteDocument(documentId)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = step.stepId,
                data = mapOf(
                    "documentId" to JsonPrimitive(documentId),
                    "deletedChunks" to JsonPrimitive(deletedCount),
                    "action" to JsonPrimitive("DELETE")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(step.stepId, startedAt, endedAt)
        )
    }

    private suspend fun executeClear(step: ExecutionStep.RagStep, startedAt: Instant): ExecutionResult {
        ragPipeline.clear()
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = step.stepId,
                data = mapOf(
                    "message" to JsonPrimitive("Vector store cleared"),
                    "action" to JsonPrimitive("CLEAR")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(step.stepId, startedAt, endedAt)
        )
    }

    private suspend fun executeSize(step: ExecutionStep.RagStep, startedAt: Instant): ExecutionResult {
        val size = ragPipeline.size()
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = step.stepId,
                data = mapOf(
                    "size" to JsonPrimitive(size),
                    "action" to JsonPrimitive("SIZE")
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(step.stepId, startedAt, endedAt)
        )
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // RAG 작업은 일반적으로 짧으므로 취소 지원 안 함
        return false
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.RagStep
    }

    companion object {
        /**
         * 기본 설정으로 RagExecutor 생성
         */
        fun create(
            embeddingProvider: EmbeddingProvider,
            vectorStore: VectorStore = InMemoryVectorStore()
        ): RagExecutor {
            val pipeline = RagPipeline(
                embeddingProvider = embeddingProvider,
                vectorStore = vectorStore
            )
            return RagExecutor(pipeline)
        }

    }
}
