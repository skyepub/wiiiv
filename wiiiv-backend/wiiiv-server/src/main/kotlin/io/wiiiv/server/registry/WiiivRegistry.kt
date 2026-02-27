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
import io.wiiiv.rag.embedding.OpenAIEmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import io.wiiiv.runner.*
import io.wiiiv.audit.AuditStore
import io.wiiiv.audit.JdbcAuditStore
import io.wiiiv.hlx.store.WorkflowStore
import io.wiiiv.hlx.store.JdbcWorkflowStore
import io.wiiiv.platform.store.PlatformStore
import io.wiiiv.platform.store.JdbcPlatformStore
import io.wiiiv.plugin.PluginLoader
import io.wiiiv.plugin.PluginRegistry
import io.wiiiv.server.session.SessionManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Wiiiv Registry - 컴포넌트 레지스트리
 *
 * API와 Core를 연결하는 싱글톤 레지스트리
 */
object WiiivRegistry {

    // === Default Model (환경변수로 오버라이드 가능) ===
    val defaultModel: String = System.getenv("WIIIV_DEFAULT_MODEL") ?: "gpt-4o-mini"

    // === LLM Provider (Executor보다 먼저 초기화 — LlmExecutor가 참조) ===
    val llmProvider: LlmProvider? = run {
        val key = System.getenv("OPENAI_API_KEY") ?: ""
        if (key.isNotBlank()) OpenAIProvider.fromEnv(model = defaultModel) else null
    }

    // === DB Provider (환경변수 기반 — WIIIV_DB_URL 없으면 비활성화) ===
    private val dbConnectionProvider: DbConnectionProvider? = run {
        val url = System.getenv("WIIIV_DB_URL")
        if (url.isNullOrBlank()) null
        else SimpleConnectionProvider(url, System.getenv("WIIIV_DB_USER"), System.getenv("WIIIV_DB_PASSWORD"))
    }

    // === Executors ===
    private val fileExecutor = FileExecutor()
    private val commandExecutor = CommandExecutor()
    private val apiExecutor = ApiExecutor()
    private val noopExecutor = NoopExecutor(handleAll = false)  // NoopStep만 처리
    private val llmExecutor: LlmExecutor? = llmProvider?.let { LlmExecutor.create(it) }
    private val mqExecutor = MessageQueueExecutor(DefaultProviderRegistry(InMemoryMessageQueueProvider()))
    private val dbExecutor: DbExecutor? = dbConnectionProvider?.let { DbExecutor.create(it) }

    val compositeExecutor = CompositeExecutor(
        executors = listOfNotNull(fileExecutor, commandExecutor, apiExecutor, noopExecutor, llmExecutor, mqExecutor, dbExecutor)
    )

    val executorRunner = ExecutionRunner.create(compositeExecutor)

    // === DACS ===
    val dacs: DACS = if (llmProvider != null) {
        HybridDACS(llmProvider, defaultModel)
    } else {
        SimpleDACS.DEFAULT  // Degraded Mode (개발 모드 - 느슨한 허용)
    }

    // === Governor (기존 — /decisions, /blueprints, /executions 라우트용) ===
    val governor: Governor = LlmGovernor.create(
        dacs = dacs,
        llmProvider = llmProvider,
        model = if (llmProvider != null) defaultModel else null
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
    val ragPipeline: RagPipeline? = run {
        try {
            val embeddingProvider = OpenAIEmbeddingProvider.fromEnv()
            RagPipeline(
                embeddingProvider = embeddingProvider,
                vectorStore = InMemoryVectorStore("wiiiv-rag-store")
            )
        } catch (_: Exception) {
            null // OPENAI_API_KEY가 없으면 RAG 비활성화
        }
    }

    // === Executor Meta Registry (거버넌스 메타데이터 — HlxRunner보다 먼저 초기화) ===
    val executorMetaRegistry = ExecutorMetaRegistry().apply {
        register(ExecutorMeta(
            scheme = "file",
            name = "FileExecutor",
            capabilities = setOf(Capability.READ, Capability.WRITE, Capability.DELETE),
            idempotent = false,
            riskLevel = RiskLevel.MEDIUM,
            stepType = StepType.FILE_OPERATION,
            description = "파일 읽기/쓰기/복사/이동/삭제"
        ))
        register(ExecutorMeta(
            scheme = "os",
            name = "CommandExecutor",
            capabilities = setOf(Capability.EXECUTE),
            idempotent = false,
            riskLevel = RiskLevel.HIGH,
            stepType = StepType.COMMAND,
            description = "셸 명령 실행"
        ))
        register(ExecutorMeta(
            scheme = "http",
            name = "ApiExecutor",
            capabilities = setOf(Capability.READ, Capability.WRITE, Capability.SEND),
            idempotent = false,
            riskLevel = RiskLevel.MEDIUM,
            stepType = StepType.API_CALL,
            description = "HTTP API 호출"
        ))
        register(ExecutorMeta(
            scheme = "db",
            name = "DbExecutor",
            capabilities = setOf(Capability.READ, Capability.WRITE, Capability.DELETE),
            idempotent = false,
            riskLevel = RiskLevel.HIGH,
            stepType = StepType.DB_OPERATION,
            description = "데이터베이스 쿼리/변경/DDL"
        ))
        register(ExecutorMeta(
            scheme = "llm",
            name = "LlmExecutor",
            capabilities = setOf(Capability.READ),
            idempotent = true,
            riskLevel = RiskLevel.LOW,
            stepType = StepType.LLM_CALL,
            description = "LLM 텍스트 생성/분석/요약"
        ))
        register(ExecutorMeta(
            scheme = "ws",
            name = "WebSocketExecutor",
            capabilities = setOf(Capability.READ, Capability.SEND),
            idempotent = false,
            riskLevel = RiskLevel.MEDIUM,
            stepType = StepType.WEBSOCKET,
            description = "WebSocket 연결 및 메시지 송수신"
        ))
        register(ExecutorMeta(
            scheme = "mq",
            name = "MessageQueueExecutor",
            capabilities = setOf(Capability.READ, Capability.SEND),
            idempotent = false,
            riskLevel = RiskLevel.MEDIUM,
            stepType = StepType.MESSAGE_QUEUE,
            description = "메시지 큐 발행/구독"
        ))
        register(ExecutorMeta(
            scheme = "grpc",
            name = "GrpcExecutor",
            capabilities = setOf(Capability.READ, Capability.WRITE),
            idempotent = false,
            riskLevel = RiskLevel.MEDIUM,
            stepType = StepType.GRPC,
            description = "gRPC 서비스 호출"
        ))
        register(ExecutorMeta(
            scheme = "multimodal",
            name = "MultimodalExecutor",
            capabilities = setOf(Capability.READ),
            idempotent = true,
            riskLevel = RiskLevel.LOW,
            stepType = StepType.MULTIMODAL,
            description = "이미지/문서/오디오 분석"
        ))
        register(ExecutorMeta(
            scheme = "rag",
            name = "RagExecutor",
            capabilities = setOf(Capability.READ, Capability.WRITE, Capability.DELETE),
            idempotent = false,
            riskLevel = RiskLevel.LOW,
            stepType = StepType.RAG,
            description = "RAG 문서 수집/검색/삭제"
        ))
    }

    // === Plugin Registry (Phase D-1: Executor 플러그인 인프라) ===
    val pluginRegistry: PluginRegistry = run {
        val loader = PluginLoader()
        val loaded = loader.loadAll()
        loaded.forEach { lp ->
            compositeExecutor.addExecutor(lp.executor)
            executorMetaRegistry.register(lp.meta)
            println("[PLUGIN] Loaded: ${lp.plugin.pluginId} v${lp.plugin.version} " +
                    "(actions=${lp.actions.map { it.name }}, jar=${lp.jarPath})")
        }
        PluginRegistry(loaded)
    }

    // === HLX Runner (Phase D: Governed Execution — GateChain + ExecutorMeta 연동) ===
    val hlxRunner: HlxRunner? = llmProvider?.let { provider ->
        HlxRunner.createWithExecutor(
            llmProvider = provider,
            executor = compositeExecutor,
            gate = permissionGate,
            gateChain = gateChain,
            executorMetaRegistry = executorMetaRegistry,
            model = defaultModel,
            workflowResolver = { id -> getHlxWorkflow(id)?.workflow }
        )
    }

    // === Audit Store (감사 DB — H2 파일 기본, MySQL 전환 가능) ===
    val auditStore: AuditStore? = run {
        // H2 JDBC 드라이버 명시적 로드 (shadow JAR에서 ServiceLoader 누락 방지)
        try { Class.forName("org.h2.Driver") } catch (_: Exception) {}
        val auditUrl = System.getenv("WIIIV_AUDIT_DB_URL")
            ?: System.getenv("WIIIV_DB_URL")

        val provider = if (!auditUrl.isNullOrBlank()) {
            println("[AUDIT] Using external DB: ${auditUrl.take(40)}...")
            SimpleConnectionProvider(auditUrl, System.getenv("WIIIV_DB_USER"), System.getenv("WIIIV_DB_PASSWORD"))
        } else {
            val h2Path = "${System.getProperty("user.home")}/.wiiiv/data/wiiiv-audit"
            println("[AUDIT] Using H2 file mode: $h2Path")
            SimpleConnectionProvider("jdbc:h2:file:$h2Path;AUTO_SERVER=TRUE")
        }

        try {
            JdbcAuditStore(provider)
        } catch (e: Exception) {
            println("[AUDIT] Failed to initialize: ${e.message}")
            null
        }
    }

    // === Workflow Store (HLX 워크플로우 영구 저장) ===
    val workflowStore: WorkflowStore? = run {
        val wfUrl = System.getenv("WIIIV_AUDIT_DB_URL")
            ?: System.getenv("WIIIV_DB_URL")

        val provider = if (!wfUrl.isNullOrBlank()) {
            SimpleConnectionProvider(wfUrl, System.getenv("WIIIV_DB_USER"), System.getenv("WIIIV_DB_PASSWORD"))
        } else {
            SimpleConnectionProvider("jdbc:h2:file:${System.getProperty("user.home")}/.wiiiv/data/wiiiv-audit;AUTO_SERVER=TRUE")
        }

        try {
            JdbcWorkflowStore(provider)
        } catch (e: Exception) {
            println("[WORKFLOW-STORE] Failed to initialize: ${e.message}")
            null
        }
    }

    // === Platform Store (멀티유저 — User, Project, Membership, API Key) ===
    val platformStore: PlatformStore? = run {
        val platformUrl = System.getenv("WIIIV_PLATFORM_DB_URL")
            ?: System.getenv("WIIIV_DB_URL")

        val provider = if (!platformUrl.isNullOrBlank()) {
            println("[PLATFORM] Using external DB: ${platformUrl.take(40)}...")
            SimpleConnectionProvider(platformUrl, System.getenv("WIIIV_DB_USER"), System.getenv("WIIIV_DB_PASSWORD"))
        } else {
            val h2Path = "${System.getProperty("user.home")}/.wiiiv/data/wiiiv-platform"
            println("[PLATFORM] Using H2 file mode: $h2Path")
            SimpleConnectionProvider("jdbc:h2:file:$h2Path;AUTO_SERVER=TRUE")
        }

        try {
            JdbcPlatformStore(provider)
        } catch (e: Exception) {
            println("[PLATFORM] Failed to initialize: ${e.message}")
            null
        }
    }

    // === RAG 자동 재수집 (서버 시작 시 DB에서 문서 복원) ===
    init {
        if (ragPipeline != null && platformStore != null) {
            try {
                val docs = platformStore.listRagDocuments()
                if (docs.isNotEmpty()) {
                    println("[RAG-REINDEX] Found ${docs.size} documents in DB, re-vectorizing...")
                    var reindexed = 0
                    kotlinx.coroutines.runBlocking {
                        for (doc in docs) {
                            val content = doc.content ?: doc.filePath?.let {
                                try { java.io.File(it).readText() } catch (_: Exception) { null }
                            } ?: continue

                            ragPipeline.ingestText(
                                text = content,
                                title = doc.title,
                                metadata = mapOf("scope" to doc.scope, "documentId" to doc.documentId)
                            )
                            reindexed++
                        }
                    }
                    println("[RAG-REINDEX] Completed: $reindexed/${docs.size} documents re-vectorized")
                }
            } catch (e: Exception) {
                println("[RAG-REINDEX] Failed: ${e.message}")
            }
        }
    }

    // === Conversational Governor (세션 API용) ===
    val conversationalGovernor: ConversationalGovernor = ConversationalGovernor.create(
        id = "gov-server",
        dacs = dacs,
        llmProvider = llmProvider,
        model = if (llmProvider != null) defaultModel else null,
        blueprintRunner = blueprintRunner,
        ragPipeline = ragPipeline,
        hlxRunner = hlxRunner,
        auditStore = auditStore,
        workflowStore = workflowStore,
        platformStore = platformStore
    )

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
    fun listHlxWorkflows(projectId: Long? = null): List<HlxWorkflowEntry> {
        val all = hlxWorkflowStore.values.toList()
        return if (projectId != null) all.filter { it.projectId == projectId } else all
    }
    fun getHlxWorkflowScoped(id: String, projectId: Long): HlxWorkflowEntry? {
        val entry = hlxWorkflowStore[id] ?: return null
        return if (entry.projectId == projectId) entry else null
    }
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
    val createdAt: String,
    val projectId: Long? = null          // F-4
)

data class HlxExecutionEntry(
    val executionId: String,
    val workflowId: String,
    val result: HlxExecutionResult,
    val executedAt: String,
    val projectId: Long? = null          // F-4
)
