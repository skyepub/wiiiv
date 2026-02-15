package io.wiiiv.server.registry

import io.wiiiv.blueprint.Blueprint
import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.dacs.*
import io.wiiiv.execution.*
import io.wiiiv.execution.impl.*
import io.wiiiv.gate.*
import io.wiiiv.governor.*
import io.wiiiv.hlx.model.HlxWorkflow
import io.wiiiv.hlx.runner.HlxExecutionResult
import io.wiiiv.hlx.runner.HlxRunner
import io.wiiiv.rag.RagPipeline
import io.wiiiv.rag.embedding.MockEmbeddingProvider
import io.wiiiv.rag.embedding.OpenAIEmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import io.wiiiv.runner.*
import io.wiiiv.server.session.SessionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Wiiiv Registry - 컴포넌트 레지스트리
 *
 * API와 Core를 연결하는 싱글톤 레지스트리
 */
object WiiivRegistry {

    // === Executors ===
    private val fileExecutor = FileExecutor()
    private val commandExecutor = CommandExecutor()
    private val apiExecutor = ApiExecutor()
    private val noopExecutor = NoopExecutor(handleAll = false)  // NoopStep만 처리

    val compositeExecutor = CompositeExecutor(
        executors = listOf(fileExecutor, commandExecutor, apiExecutor, noopExecutor)
    )

    val executorRunner = ExecutionRunner.create(compositeExecutor)

    // === LLM Provider ===
    val llmProvider: LlmProvider? = run {
        val key = System.getenv("OPENAI_API_KEY") ?: ""
        if (key.isNotBlank()) OpenAIProvider.fromEnv(model = "gpt-4o-mini") else null
    }

    // === DACS ===
    val dacs: DACS = if (llmProvider != null) {
        HybridDACS(llmProvider, "gpt-4o-mini")
    } else {
        SimpleDACS.DEFAULT  // Degraded Mode (개발 모드 - 느슨한 허용)
    }

    // === Governor (기존 — /decisions, /blueprints, /executions 라우트용) ===
    val governor: Governor = LlmGovernor.create(
        dacs = dacs,
        llmProvider = llmProvider,
        model = if (llmProvider != null) "gpt-4o-mini" else null
    )

    // === Blueprint Runner ===
    val blueprintRunner = BlueprintRunner.create(compositeExecutor)

    // === Gates ===
    val gateLogger = InMemoryGateLogger()

    val dacsGate = DACSGate.INSTANCE
    val userApprovalGate = UserApprovalGate.INSTANCE
    val permissionGate = ExecutionPermissionGate.PERMISSIVE
    val costGate = CostGate.UNLIMITED

    val gateChain = GateChain.standard(gateLogger)

    // === RAG ===
    val ragPipeline = run {
        val embeddingProvider = if (llmProvider != null) {
            try {
                OpenAIEmbeddingProvider.fromEnv()
            } catch (_: Exception) {
                MockEmbeddingProvider()
            }
        } else {
            MockEmbeddingProvider() // 테스트/개발 모드
        }
        RagPipeline(
            embeddingProvider = embeddingProvider,
            vectorStore = InMemoryVectorStore("wiiiv-rag-store")
        )
    }

    // === Conversational Governor (세션 API용) ===
    val conversationalGovernor: ConversationalGovernor = ConversationalGovernor.create(
        id = "gov-server",
        dacs = dacs,
        llmProvider = llmProvider,
        model = if (llmProvider != null) "gpt-4o-mini" else null,
        blueprintRunner = blueprintRunner,
        ragPipeline = ragPipeline
    )

    // === HLX Runner ===
    val hlxRunner: HlxRunner? = llmProvider?.let { provider ->
        HlxRunner.create(provider, model = "gpt-4o-mini")
    }

    // === Session Manager ===
    val sessionManager = SessionManager(conversationalGovernor)

    // === Storage (In-Memory for now) ===
    private val blueprintStore = ConcurrentHashMap<String, Blueprint>()
    private val decisionStore = ConcurrentHashMap<String, DecisionRecord>()
    private val executionStore = ConcurrentHashMap<String, ExecutionRecord>()

    // === HLX Storage ===
    private val hlxWorkflowStore = ConcurrentHashMap<String, HlxWorkflowEntry>()
    private val hlxExecutionStore = ConcurrentHashMap<String, HlxExecutionEntry>()

    // === Blueprint Operations ===
    fun storeBlueprint(blueprint: Blueprint) {
        blueprintStore[blueprint.id] = blueprint
    }

    fun getBlueprint(id: String): Blueprint? = blueprintStore[id]

    fun listBlueprints(): List<Blueprint> = blueprintStore.values.toList()

    // === Decision Operations ===
    fun storeDecision(record: DecisionRecord) {
        decisionStore[record.decisionId] = record
    }

    fun getDecision(id: String): DecisionRecord? = decisionStore[id]

    fun getDecisionByBlueprintId(blueprintId: String): DecisionRecord? =
        decisionStore.values.find { it.blueprintId == blueprintId }

    fun updateDecision(id: String, update: (DecisionRecord) -> DecisionRecord) {
        decisionStore.computeIfPresent(id) { _, old -> update(old) }
    }

    // === Execution Operations ===
    fun storeExecution(record: ExecutionRecord) {
        executionStore[record.executionId] = record
    }

    fun getExecution(id: String): ExecutionRecord? = executionStore[id]

    fun updateExecution(id: String, update: (ExecutionRecord) -> ExecutionRecord) {
        executionStore.computeIfPresent(id) { _, old -> update(old) }
    }

    fun listExecutions(): List<ExecutionRecord> = executionStore.values.toList()

    // === HLX Workflow Operations ===
    fun storeHlxWorkflow(entry: HlxWorkflowEntry) { hlxWorkflowStore[entry.id] = entry }
    fun getHlxWorkflow(id: String): HlxWorkflowEntry? = hlxWorkflowStore[id]
    fun listHlxWorkflows(): List<HlxWorkflowEntry> = hlxWorkflowStore.values.toList()
    fun deleteHlxWorkflow(id: String): Boolean = hlxWorkflowStore.remove(id) != null

    // === HLX Execution Operations ===
    fun storeHlxExecution(entry: HlxExecutionEntry) { hlxExecutionStore[entry.executionId] = entry }
    fun getHlxExecution(id: String): HlxExecutionEntry? = hlxExecutionStore[id]
    fun listHlxExecutions(workflowId: String): List<HlxExecutionEntry> =
        hlxExecutionStore.values.filter { it.workflowId == workflowId }

    // === Executor Info ===
    fun getExecutorInfos(): List<ExecutorInfo> = listOf(
        ExecutorInfo("file-executor", "FileExecutor", listOf("FILE")),
        ExecutorInfo("command-executor", "CommandExecutor", listOf("COMMAND")),
        ExecutorInfo("noop-executor", "NoopExecutor", listOf("NOOP")),
        ExecutorInfo("api-executor", "ApiExecutor", listOf("API")),
        ExecutorInfo("llm-executor", "LlmExecutor", listOf("LLM")),
        ExecutorInfo("db-executor", "DbExecutor", listOf("DB")),
        ExecutorInfo("websocket-executor", "WebSocketExecutor", listOf("WEBSOCKET")),
        ExecutorInfo("mq-executor", "MessageQueueExecutor", listOf("MESSAGE_QUEUE")),
        ExecutorInfo("grpc-executor", "GrpcExecutor", listOf("GRPC")),
        ExecutorInfo("multimodal-executor", "MultimodalExecutor", listOf("MULTIMODAL")),
        ExecutorInfo("rag-executor", "RagExecutor", listOf("RAG"))
    )

    // === Gate Info ===
    fun getGateInfos(): List<GateInfo> = listOf(
        GateInfo(dacsGate.id, dacsGate.name, 100),
        GateInfo(userApprovalGate.id, userApprovalGate.name, 90),
        GateInfo(permissionGate.id, permissionGate.name, 80),
        GateInfo(costGate.id, costGate.name, 70)
    )

    // === Persona Info ===
    fun getPersonaInfos(): List<PersonaInfo> {
        val providerType = if (llmProvider != null) "hybrid" else "rule-based"
        return listOf(
            PersonaInfo("architect", "Architect", "Technical feasibility", providerType),
            PersonaInfo("reviewer", "Reviewer", "Requirements validation", providerType),
            PersonaInfo("adversary", "Adversary", "Security analysis", providerType)
        )
    }
}

// === Data Classes ===

data class ExecutorInfo(
    val id: String,
    val type: String,
    val supportedStepTypes: List<String>,
    val status: String = "available"
)

data class GateInfo(
    val id: String,
    val name: String,
    val priority: Int,
    val status: String = "active"
)

data class PersonaInfo(
    val id: String,
    val name: String,
    val role: String,
    val provider: String?
)

data class DecisionRecord(
    val decisionId: String,
    val specInput: SpecInput,
    val dacsResult: DACSResult,
    val blueprintId: String?,
    val status: String,
    val createdAt: String,
    val userApproved: Boolean = false  // 사용자 승인 여부
)

data class SpecInput(
    val intent: String,
    val constraints: List<String>? = null
)

data class ExecutionRecord(
    val executionId: String,
    val blueprintId: String,
    val status: String,
    val startedAt: String,
    val completedAt: String? = null,
    val runnerResult: RunnerResult? = null
)

data class HlxWorkflowEntry(
    val id: String,
    val workflow: HlxWorkflow,
    val rawJson: String,
    val createdAt: String
)

data class HlxExecutionEntry(
    val executionId: String,
    val workflowId: String,
    val result: HlxExecutionResult,
    val executedAt: String
)
