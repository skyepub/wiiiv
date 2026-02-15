package io.wiiiv.hlx.runner

import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.hlx.model.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*

// ==================== 결과 모델 ====================

/**
 * 워크플로우 실행 최종 상태
 */
enum class HlxExecutionStatus {
    COMPLETED, FAILED, ABORTED, ENDED_EARLY
}

/**
 * 개별 노드 실행 기록 (감사 추적용)
 */
data class HlxNodeExecutionRecord(
    val nodeId: String,
    val nodeType: HlxNodeType,
    val status: HlxStatus,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    val llmPrompt: String? = null,
    val llmResponse: String? = null,
    val durationMs: Long = 0,
    val error: String? = null,
    val retryCount: Int = 0,
    val selectedBranch: String? = null,
    val iterationCount: Int? = null
)

/**
 * 워크플로우 실행 결과
 */
data class HlxExecutionResult(
    val workflowId: String,
    val status: HlxExecutionStatus,
    val context: HlxContext,
    val nodeRecords: List<HlxNodeExecutionRecord>,
    val totalDurationMs: Long,
    val error: String? = null
)

// ==================== 흐름 제어 ====================

/**
 * 노드 실행 후 흐름 제어 신호
 */
sealed class FlowControl {
    /** 다음 노드로 계속 */
    data object Continue : FlowControl()

    /** 워크플로우 조기 종료 (Decide가 "end" 선택) */
    data object EndWorkflow : FlowControl()

    /** 특정 노드로 점프 (Decide 분기) */
    data class JumpTo(val nodeId: String) : FlowControl()

    /** 노드 실패 */
    data class Failed(val error: String) : FlowControl()

    /** 워크플로우 중단 (onError abort) */
    data class Aborted(val error: String) : FlowControl()

    /** 노드 건너뛰기 (onError skip) */
    data object Skip : FlowControl()
}

// ==================== onError 정책 ====================

/**
 * onError fallback 전략
 */
enum class OnErrorFallback {
    FAIL, SKIP, ABORT, DECIDE
}

/**
 * onError 정책 (파싱 결과)
 */
data class OnErrorPolicy(
    val retryCount: Int = 0,
    val fallback: OnErrorFallback = OnErrorFallback.FAIL
)

// ==================== HlxRunner ====================

/**
 * HLX Runner - 워크플로우 실행 엔진
 *
 * HLX 워크플로우를 LLM으로 실행하는 엔진.
 * 기존 Executor/Blueprint 경로를 사용하지 않고 LlmProvider를 직접 사용한다.
 *
 * Executor는 "판단 없이 실행"이 원칙이지만,
 * HLX 노드는 LLM이 description을 해석하고 판단하며 실행한다.
 * 따라서 별개의 실행 경로가 필요하다.
 */
class HlxRunner(
    private val nodeExecutor: HlxNodeExecutor,
    private val maxRetries: Int = 3,
    private val retryDelayMs: Long = 1000
) {

    /**
     * 워크플로우 실행
     */
    suspend fun run(
        workflow: HlxWorkflow,
        initialVariables: Map<String, JsonElement> = emptyMap()
    ): HlxExecutionResult {
        val startTime = System.currentTimeMillis()
        val records = mutableListOf<HlxNodeExecutionRecord>()

        // 1. HlxContext 초기화
        val context = HlxContext(
            variables = initialVariables.toMutableMap(),
            meta = HlxMeta(
                workflowId = workflow.id,
                status = HlxStatus.RUNNING
            )
        )

        // 2. 노드 인덱스 맵 구성
        val nodeIndexMap = buildNodeIndexMap(workflow.nodes)

        // 3. 노드 순차 실행
        var currentIndex = 0
        while (currentIndex < workflow.nodes.size) {
            val node = workflow.nodes[currentIndex]
            context.meta.copy(currentNode = node.id).let { /* meta is data class, need mutable approach */ }

            val flowControl = executeNodeWithPolicy(node, context, records)

            when (flowControl) {
                is FlowControl.Continue, is FlowControl.Skip -> {
                    currentIndex++
                }
                is FlowControl.EndWorkflow -> {
                    return HlxExecutionResult(
                        workflowId = workflow.id,
                        status = HlxExecutionStatus.ENDED_EARLY,
                        context = context,
                        nodeRecords = records,
                        totalDurationMs = System.currentTimeMillis() - startTime
                    )
                }
                is FlowControl.JumpTo -> {
                    val targetIndex = nodeIndexMap[flowControl.nodeId]
                    if (targetIndex != null) {
                        currentIndex = targetIndex
                    } else {
                        return HlxExecutionResult(
                            workflowId = workflow.id,
                            status = HlxExecutionStatus.FAILED,
                            context = context,
                            nodeRecords = records,
                            totalDurationMs = System.currentTimeMillis() - startTime,
                            error = "Jump target not found: ${flowControl.nodeId}"
                        )
                    }
                }
                is FlowControl.Failed -> {
                    return HlxExecutionResult(
                        workflowId = workflow.id,
                        status = HlxExecutionStatus.FAILED,
                        context = context,
                        nodeRecords = records,
                        totalDurationMs = System.currentTimeMillis() - startTime,
                        error = flowControl.error
                    )
                }
                is FlowControl.Aborted -> {
                    return HlxExecutionResult(
                        workflowId = workflow.id,
                        status = HlxExecutionStatus.ABORTED,
                        context = context,
                        nodeRecords = records,
                        totalDurationMs = System.currentTimeMillis() - startTime,
                        error = flowControl.error
                    )
                }
            }
        }

        return HlxExecutionResult(
            workflowId = workflow.id,
            status = HlxExecutionStatus.COMPLETED,
            context = context,
            nodeRecords = records,
            totalDurationMs = System.currentTimeMillis() - startTime
        )
    }

    /**
     * onError 정책을 적용하여 노드 실행
     */
    private suspend fun executeNodeWithPolicy(
        node: HlxNode,
        context: HlxContext,
        records: MutableList<HlxNodeExecutionRecord>
    ): FlowControl {
        val policy = parseOnError(node.onError)
        var lastError: String? = null
        var retryCount = 0

        // 최초 시도 + retry
        for (attempt in 0..policy.retryCount) {
            if (attempt > 0) {
                delay(retryDelayMs)
                retryCount = attempt
            }

            val flowControl = executeNode(node, context, records, retryCount)

            when (flowControl) {
                is FlowControl.Failed -> {
                    lastError = flowControl.error
                    // 재시도 남았으면 계속
                    if (attempt < policy.retryCount) continue
                    // 재시도 소진 → fallback 적용
                    return applyFallback(policy.fallback, lastError, node, records, retryCount)
                }
                else -> return flowControl
            }
        }

        // 여기 도달하면 안 되지만 안전장치
        return FlowControl.Failed(lastError ?: "Unknown error")
    }

    /**
     * fallback 전략 적용
     */
    private fun applyFallback(
        fallback: OnErrorFallback,
        error: String,
        @Suppress("UNUSED_PARAMETER") node: HlxNode,
        @Suppress("UNUSED_PARAMETER") records: MutableList<HlxNodeExecutionRecord>,
        @Suppress("UNUSED_PARAMETER") retryCount: Int
    ): FlowControl {
        return when (fallback) {
            OnErrorFallback.FAIL -> FlowControl.Failed(error)
            OnErrorFallback.SKIP -> {
                // 마지막 레코드의 상태를 FAILED로 유지하되 Skip 반환
                FlowControl.Skip
            }
            OnErrorFallback.ABORT -> FlowControl.Aborted(error)
            OnErrorFallback.DECIDE -> {
                // DECIDE fallback: 상위에서 처리할 수 있도록 Skip으로 처리
                FlowControl.Skip
            }
        }
    }

    /**
     * 개별 노드 실행
     */
    private suspend fun executeNode(
        node: HlxNode,
        context: HlxContext,
        records: MutableList<HlxNodeExecutionRecord>,
        retryCount: Int
    ): FlowControl {
        val nodeStart = System.currentTimeMillis()
        val inputValue = node.input?.let { context.variables[it] }

        return when (node) {
            is HlxNode.Observe -> {
                val result = nodeExecutor.executeObserve(node, context)
                handleNodeResult(node, result, context, records, nodeStart, inputValue, retryCount)
            }
            is HlxNode.Transform -> {
                val result = nodeExecutor.executeTransform(node, context)
                handleNodeResult(node, result, context, records, nodeStart, inputValue, retryCount)
            }
            is HlxNode.Act -> {
                val result = nodeExecutor.executeAct(node, context)
                handleNodeResult(node, result, context, records, nodeStart, inputValue, retryCount)
            }
            is HlxNode.Decide -> {
                executeDecideNode(node, context, records, nodeStart, inputValue, retryCount)
            }
            is HlxNode.Repeat -> {
                executeRepeatNode(node, context, records, nodeStart, retryCount)
            }
        }
    }

    /**
     * 일반 노드 결과 처리 (Observe, Transform, Act)
     */
    private fun handleNodeResult(
        node: HlxNode,
        result: NodeExecutionResult,
        context: HlxContext,
        records: MutableList<HlxNodeExecutionRecord>,
        nodeStart: Long,
        inputValue: JsonElement?,
        retryCount: Int
    ): FlowControl {
        val durationMs = System.currentTimeMillis() - nodeStart

        return when (result) {
            is NodeExecutionResult.Success -> {
                // output 변수에 저장
                node.output?.let { context.variables[it] = result.output }

                records.add(
                    HlxNodeExecutionRecord(
                        nodeId = node.id,
                        nodeType = node.type,
                        status = HlxStatus.SUCCESS,
                        input = inputValue,
                        output = result.output,
                        durationMs = durationMs,
                        retryCount = retryCount
                    )
                )
                FlowControl.Continue
            }
            is NodeExecutionResult.Failure -> {
                records.add(
                    HlxNodeExecutionRecord(
                        nodeId = node.id,
                        nodeType = node.type,
                        status = HlxStatus.FAILED,
                        input = inputValue,
                        durationMs = durationMs,
                        error = result.error,
                        retryCount = retryCount
                    )
                )
                FlowControl.Failed(result.error)
            }
        }
    }

    /**
     * Decide 노드 실행
     */
    private suspend fun executeDecideNode(
        node: HlxNode.Decide,
        context: HlxContext,
        records: MutableList<HlxNodeExecutionRecord>,
        nodeStart: Long,
        inputValue: JsonElement?,
        retryCount: Int
    ): FlowControl {
        val result = nodeExecutor.executeDecide(node, context)
        val durationMs = System.currentTimeMillis() - nodeStart

        return when (result) {
            is DecideResult.BranchSelected -> {
                val branchKey = result.branchKey
                val target = node.branches[branchKey]

                if (target == null) {
                    records.add(
                        HlxNodeExecutionRecord(
                            nodeId = node.id,
                            nodeType = HlxNodeType.DECIDE,
                            status = HlxStatus.FAILED,
                            input = inputValue,
                            durationMs = durationMs,
                            error = "Invalid branch key: $branchKey",
                            retryCount = retryCount,
                            selectedBranch = branchKey
                        )
                    )
                    return FlowControl.Failed("Invalid branch key: $branchKey. Available: ${node.branches.keys}")
                }

                records.add(
                    HlxNodeExecutionRecord(
                        nodeId = node.id,
                        nodeType = HlxNodeType.DECIDE,
                        status = HlxStatus.SUCCESS,
                        input = inputValue,
                        output = JsonPrimitive(branchKey),
                        durationMs = durationMs,
                        retryCount = retryCount,
                        selectedBranch = branchKey
                    )
                )

                if (target == "end") {
                    FlowControl.EndWorkflow
                } else {
                    FlowControl.JumpTo(target)
                }
            }
            is DecideResult.Failure -> {
                records.add(
                    HlxNodeExecutionRecord(
                        nodeId = node.id,
                        nodeType = HlxNodeType.DECIDE,
                        status = HlxStatus.FAILED,
                        input = inputValue,
                        durationMs = durationMs,
                        error = result.error,
                        retryCount = retryCount
                    )
                )
                FlowControl.Failed(result.error)
            }
        }
    }

    /**
     * Repeat 노드 실행
     */
    private suspend fun executeRepeatNode(
        node: HlxNode.Repeat,
        context: HlxContext,
        records: MutableList<HlxNodeExecutionRecord>,
        nodeStart: Long,
        retryCount: Int
    ): FlowControl {
        val overVar = node.over ?: return FlowControl.Failed("Repeat node '${node.id}' missing 'over'")
        val asVar = node.asVar ?: return FlowControl.Failed("Repeat node '${node.id}' missing 'as'")

        val collection = context.variables[overVar]
        if (collection == null || collection !is JsonArray) {
            // 빈 컬렉션 또는 존재하지 않는 경우 → 스킵
            records.add(
                HlxNodeExecutionRecord(
                    nodeId = node.id,
                    nodeType = HlxNodeType.REPEAT,
                    status = HlxStatus.SUCCESS,
                    durationMs = System.currentTimeMillis() - nodeStart,
                    retryCount = retryCount,
                    iterationCount = 0
                )
            )
            return FlowControl.Continue
        }

        val items = collection.jsonArray

        for ((index, item) in items.withIndex()) {
            // iteration 메타 설정
            val iteration = HlxIteration(
                index = index,
                total = items.size,
                currentItem = item.toString()
            )
            // context.iteration은 val이므로 새 context를 만들지 않고 변수로 전달
            // HlxContext의 iteration은 val이지만 body 실행 시 새로운 context를 전달해야 함
            // 대신 context.variables에 iteration 정보를 저장
            context.variables[asVar] = item

            // body 노드들 순차 실행
            for (bodyNode in node.body) {
                val flowControl = executeNodeWithPolicy(bodyNode, context.copy(iteration = iteration), records)
                when (flowControl) {
                    is FlowControl.Continue, is FlowControl.Skip -> continue
                    is FlowControl.Failed -> {
                        records.add(
                            HlxNodeExecutionRecord(
                                nodeId = node.id,
                                nodeType = HlxNodeType.REPEAT,
                                status = HlxStatus.FAILED,
                                durationMs = System.currentTimeMillis() - nodeStart,
                                error = flowControl.error,
                                retryCount = retryCount,
                                iterationCount = index + 1
                            )
                        )
                        return FlowControl.Failed(flowControl.error)
                    }
                    is FlowControl.Aborted -> {
                        return FlowControl.Aborted(flowControl.error)
                    }
                    is FlowControl.EndWorkflow -> return FlowControl.EndWorkflow
                    is FlowControl.JumpTo -> {
                        // Repeat body 내 JumpTo는 외부 노드로 점프
                        return flowControl
                    }
                }
            }
        }

        records.add(
            HlxNodeExecutionRecord(
                nodeId = node.id,
                nodeType = HlxNodeType.REPEAT,
                status = HlxStatus.SUCCESS,
                durationMs = System.currentTimeMillis() - nodeStart,
                retryCount = retryCount,
                iterationCount = items.size
            )
        )

        return FlowControl.Continue
    }

    /**
     * 최상위 노드의 인덱스 맵 구성
     */
    private fun buildNodeIndexMap(nodes: List<HlxNode>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        nodes.forEachIndexed { index, node -> map[node.id] = index }
        return map
    }

    companion object {
        /**
         * onError 문자열 파싱
         *
         * 형식:
         * - null → retryCount=0, fallback=FAIL
         * - "skip" → retryCount=0, fallback=SKIP
         * - "abort" → retryCount=0, fallback=ABORT
         * - "retry:3" → retryCount=3, fallback=FAIL
         * - "retry:3 then skip" → retryCount=3, fallback=SKIP
         * - "retry:3 then decide" → retryCount=3, fallback=DECIDE
         */
        fun parseOnError(onError: String?): OnErrorPolicy {
            if (onError == null) return OnErrorPolicy()

            val trimmed = onError.trim()

            return when {
                trimmed == "skip" -> OnErrorPolicy(retryCount = 0, fallback = OnErrorFallback.SKIP)
                trimmed == "abort" -> OnErrorPolicy(retryCount = 0, fallback = OnErrorFallback.ABORT)
                trimmed.startsWith("retry:") -> {
                    val retryRegex = Regex("""retry:(\d+)(\s+then\s+(skip|decide|abort))?""")
                    val match = retryRegex.matchEntire(trimmed)
                    if (match != null) {
                        val count = match.groupValues[1].toInt()
                        val fallback = when (match.groupValues[3]) {
                            "skip" -> OnErrorFallback.SKIP
                            "decide" -> OnErrorFallback.DECIDE
                            "abort" -> OnErrorFallback.ABORT
                            else -> OnErrorFallback.FAIL
                        }
                        OnErrorPolicy(retryCount = count, fallback = fallback)
                    } else {
                        OnErrorPolicy()
                    }
                }
                else -> OnErrorPolicy()
            }
        }

        /**
         * HlxRunner 생성 팩토리
         */
        fun create(
            llmProvider: LlmProvider,
            model: String? = null,
            maxRetries: Int = 3,
            retryDelayMs: Long = 1000
        ): HlxRunner {
            val nodeExecutor = HlxNodeExecutor(llmProvider, model)
            return HlxRunner(nodeExecutor, maxRetries, retryDelayMs)
        }
    }
}
