package io.wiiiv.hlx

import io.wiiiv.execution.*
import io.wiiiv.execution.impl.LlmProvider
import io.wiiiv.execution.impl.LlmRequest
import io.wiiiv.execution.impl.LlmResponse
import io.wiiiv.execution.impl.LlmUsage
import io.wiiiv.gate.Gate
import io.wiiiv.gate.GateContext
import io.wiiiv.gate.GateResult
import io.wiiiv.hlx.model.*
import io.wiiiv.hlx.runner.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import kotlin.test.*

// ==================== 테스트용 ScriptedLlmProvider ====================

/**
 * 노드 ID별 응답을 미리 설정하는 테스트용 LLM Provider
 */
class ScriptedLlmProvider(
    private val responses: Map<String, String>,
    override val defaultModel: String = "test-model",
    override val defaultMaxTokens: Int = 1000
) : LlmProvider {

    val callHistory = mutableListOf<LlmRequest>()
    private var failingNodes: Set<String> = emptySet()
    private var failCount: MutableMap<String, Int> = mutableMapOf()
    private var maxFailures: MutableMap<String, Int> = mutableMapOf()

    fun setFailingNode(nodeId: String, maxFailCount: Int = Int.MAX_VALUE) {
        failingNodes = failingNodes + nodeId
        maxFailures[nodeId] = maxFailCount
        failCount[nodeId] = 0
    }

    override suspend fun call(request: LlmRequest): LlmResponse {
        callHistory.add(request)

        // 프롬프트에서 노드 ID 감지
        val nodeId = extractNodeId(request.prompt)

        // 실패 설정된 노드 체크
        if (nodeId != null && nodeId in failingNodes) {
            val count = (failCount[nodeId] ?: 0) + 1
            failCount[nodeId] = count
            val max = maxFailures[nodeId] ?: Int.MAX_VALUE
            if (count <= max) {
                throw RuntimeException("Simulated LLM failure for node: $nodeId")
            }
        }

        // 노드 ID로 응답 매칭
        val content = if (nodeId != null && nodeId in responses) {
            responses[nodeId]!!
        } else {
            // fallback: 기본 응답
            """{ "result": "default response" }"""
        }

        return LlmResponse(
            content = content,
            finishReason = "stop",
            usage = LlmUsage.of(10, 20)
        )
    }

    override suspend fun cancel(executionId: String): Boolean = true

    private fun extractNodeId(prompt: String): String? {
        val regex = Regex("""- ID: (\S+)""")
        return regex.find(prompt)?.groupValues?.get(1)
    }
}

// ==================== 테스트 헬퍼 ====================

private fun simpleWorkflow(vararg nodes: HlxNode) = HlxWorkflow(
    id = "test-workflow",
    name = "Test Workflow",
    description = "Test workflow for HlxRunner",
    nodes = nodes.toList()
)

// ==================== HlxRunnerTest ====================

class HlxRunnerTest {

    // ==================== 기본 노드 실행 ====================

    @Test
    fun `should execute single Observe node`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "result": ["order1", "order2"] }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(
                id = "step1",
                description = "주문 목록 조회",
                target = "GET /api/orders",
                output = "orders"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, result.nodeRecords.size)
        assertEquals(HlxStatus.SUCCESS, result.nodeRecords[0].status)
        assertNotNull(result.context.variables["orders"])
    }

    @Test
    fun `should execute single Transform node with input`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "result": [{"id": "o1", "status": "UNPAID"}] }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Transform(
                id = "step1",
                description = "미결제 건만 필터",
                hint = TransformHint.FILTER,
                input = "orders",
                output = "unpaidOrders"
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "orders" to buildJsonArray {
                add(buildJsonObject { put("id", "o1"); put("status", "UNPAID") })
                add(buildJsonObject { put("id", "o2"); put("status", "PAID") })
            }
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertNotNull(result.context.variables["unpaidOrders"])
    }

    @Test
    fun `should execute single Act node`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "result": { "sent": true, "messageId": "msg-123" } }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Act(
                id = "step1",
                description = "알림 발송",
                target = "POST /api/notifications",
                output = "sendResult"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertNotNull(result.context.variables["sendResult"])
    }

    @Test
    fun `should execute single Decide node`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "branch": "has_items", "reasoning": "미결제 건이 존재함" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Decide(
                id = "step1",
                description = "미결제 건이 있는지 판단",
                input = "unpaidOrders",
                branches = mapOf(
                    "has_items" to "end",
                    "no_items" to "end"
                )
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.ENDED_EARLY, result.status)
        assertEquals("has_items", result.nodeRecords[0].selectedBranch)
    }

    @Test
    fun `should pass input variable to node`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "result": "filtered data" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Transform(
                id = "step1",
                description = "데이터 필터링",
                input = "rawData",
                output = "filteredData"
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "rawData" to JsonPrimitive("some raw data")
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // 프롬프트에 rawData 변수가 포함되어야 함
        assertTrue(provider.callHistory[0].prompt.contains("rawData"))
    }

    // ==================== 순차 실행 ====================

    @Test
    fun `should execute nodes sequentially`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """{ "result": ["order1", "order2"] }""",
                "step2" to """{ "result": ["order1"] }""",
                "step3" to """{ "result": { "notified": true } }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "step1", description = "조회", output = "orders"),
            HlxNode.Transform(id = "step2", description = "필터", input = "orders", output = "filtered"),
            HlxNode.Act(id = "step3", description = "실행", input = "filtered", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(3, result.nodeRecords.size)
        assertTrue(result.nodeRecords.all { it.status == HlxStatus.SUCCESS })
    }

    @Test
    fun `should pass output to input between sequential nodes`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """{ "result": "observed data" }""",
                "step2" to """{ "result": "transformed data" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "step1", description = "관찰", output = "data"),
            HlxNode.Transform(id = "step2", description = "변환", input = "data", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // step2의 프롬프트에 step1의 output 변수(data)가 포함되어야 함
        val step2Prompt = provider.callHistory[1].prompt
        assertTrue(step2Prompt.contains("data"))
    }

    @Test
    fun `should collect execution records for all nodes`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "s1" to """{ "result": "a" }""",
                "s2" to """{ "result": "b" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "s1", description = "1", output = "x"),
            HlxNode.Transform(id = "s2", description = "2", input = "x", output = "y")
        )

        val result = runner.run(workflow)

        assertEquals(2, result.nodeRecords.size)
        assertEquals("s1", result.nodeRecords[0].nodeId)
        assertEquals(HlxNodeType.OBSERVE, result.nodeRecords[0].nodeType)
        assertEquals("s2", result.nodeRecords[1].nodeId)
        assertEquals(HlxNodeType.TRANSFORM, result.nodeRecords[1].nodeType)
    }

    // ==================== Decide 분기 ====================

    @Test
    fun `should jump to target node on decide branch`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """{ "result": "data" }""",
                "decide1" to """{ "branch": "has_items", "reasoning": "items exist" }""",
                "step3" to """{ "result": "acted" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "step1", description = "조회", output = "data"),
            HlxNode.Decide(
                id = "decide1",
                description = "판단",
                input = "data",
                branches = mapOf("has_items" to "step3", "no_items" to "end")
            ),
            HlxNode.Act(id = "skipped", description = "건너뛸 노드", output = "x"),
            HlxNode.Act(id = "step3", description = "실행", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // step1, decide1, step3 실행 (skipped는 건너뜀)
        val executedIds = result.nodeRecords.map { it.nodeId }
        assertTrue("step1" in executedIds)
        assertTrue("decide1" in executedIds)
        assertTrue("step3" in executedIds)
        assertFalse("skipped" in executedIds)
    }

    @Test
    fun `should end workflow when decide selects end branch`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """{ "result": "data" }""",
                "decide1" to """{ "branch": "no_items", "reasoning": "empty list" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "step1", description = "조회", output = "data"),
            HlxNode.Decide(
                id = "decide1",
                description = "판단",
                input = "data",
                branches = mapOf("has_items" to "step3", "no_items" to "end")
            ),
            HlxNode.Act(id = "step3", description = "실행 안 됨", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.ENDED_EARLY, result.status)
        assertNull(result.error)
    }

    @Test
    fun `should continue sequential execution after jump`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "decide1" to """{ "branch": "go", "reasoning": "go" }""",
                "target" to """{ "result": "target executed" }""",
                "after" to """{ "result": "after executed" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Decide(
                id = "decide1",
                description = "판단",
                branches = mapOf("go" to "target")
            ),
            HlxNode.Act(id = "skipped1", description = "건너뜀", output = "x"),
            HlxNode.Act(id = "target", description = "점프 대상", output = "y"),
            HlxNode.Act(id = "after", description = "점프 후 순차", output = "z")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        val executedIds = result.nodeRecords.map { it.nodeId }
        assertTrue("target" in executedIds)
        assertTrue("after" in executedIds)
        assertFalse("skipped1" in executedIds)
    }

    @Test
    fun `should skip middle nodes when jumping forward`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "d1" to """{ "branch": "jump", "reasoning": "skip ahead" }""",
                "n4" to """{ "result": "done" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Decide(
                id = "d1",
                description = "점프",
                branches = mapOf("jump" to "n4")
            ),
            HlxNode.Act(id = "n2", description = "건너뜀", output = "a"),
            HlxNode.Act(id = "n3", description = "건너뜀", output = "b"),
            HlxNode.Act(id = "n4", description = "대상", output = "c")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        val executedIds = result.nodeRecords.map { it.nodeId }
        assertFalse("n2" in executedIds)
        assertFalse("n3" in executedIds)
        assertTrue("n4" in executedIds)
    }

    @Test
    fun `should fail on invalid branch key`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("d1" to """{ "branch": "nonexistent", "reasoning": "oops" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Decide(
                id = "d1",
                description = "판단",
                branches = mapOf("yes" to "end", "no" to "end")
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Invalid branch key"))
    }

    // ==================== Repeat 반복 ====================

    @Test
    fun `should iterate over collection in Repeat node`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("notify" to """{ "result": { "sent": true } }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Repeat(
                id = "repeat1",
                description = "각 주문에 알림 발송",
                over = "orders",
                asVar = "order",
                body = listOf(
                    HlxNode.Act(id = "notify", description = "알림 발송", input = "order", output = "notifyResult")
                )
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "orders" to buildJsonArray {
                add(JsonPrimitive("order1"))
                add(JsonPrimitive("order2"))
                add(JsonPrimitive("order3"))
            }
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // body 노드가 3번 실행 + Repeat 자체 레코드 1개
        assertEquals(4, result.nodeRecords.size)
        // Repeat 레코드의 iterationCount = 3
        val repeatRecord = result.nodeRecords.last()
        assertEquals("repeat1", repeatRecord.nodeId)
        assertEquals(3, repeatRecord.iterationCount)
    }

    @Test
    fun `should set as variable for each iteration`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("body1" to """{ "result": "processed" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Repeat(
                id = "r1",
                description = "반복",
                over = "items",
                asVar = "item",
                body = listOf(
                    HlxNode.Act(id = "body1", description = "처리", input = "item", output = "out")
                )
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "items" to buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            }
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // 각 반복에서 "item" 변수가 프롬프트에 포함되어야 함
        assertTrue(provider.callHistory.size >= 2)
    }

    @Test
    fun `should set iteration metadata during Repeat`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("body1" to """{ "result": "ok" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Repeat(
                id = "r1",
                description = "반복",
                over = "items",
                asVar = "item",
                body = listOf(
                    HlxNode.Act(id = "body1", description = "처리", input = "item")
                )
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "items" to buildJsonArray {
                add(JsonPrimitive("x"))
                add(JsonPrimitive("y"))
            }
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        val repeatRecord = result.nodeRecords.find { it.nodeId == "r1" }
        assertNotNull(repeatRecord)
        assertEquals(2, repeatRecord.iterationCount)
    }

    @Test
    fun `should handle nested Repeat`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("inner-act" to """{ "result": "done" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Repeat(
                id = "outer",
                description = "외부 반복",
                over = "groups",
                asVar = "group",
                body = listOf(
                    HlxNode.Repeat(
                        id = "inner",
                        description = "내부 반복",
                        over = "group",
                        asVar = "item",
                        body = listOf(
                            HlxNode.Act(id = "inner-act", description = "처리", input = "item")
                        )
                    )
                )
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "groups" to buildJsonArray {
                add(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) })
                add(buildJsonArray { add(JsonPrimitive("c")) })
            }
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // inner-act이 3번 실행 (a, b, c)
        val innerActRecords = result.nodeRecords.filter { it.nodeId == "inner-act" }
        assertEquals(3, innerActRecords.size)
    }

    // ==================== 에러 처리 ====================

    @Test
    fun `should retry on failure`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "result": "success after retry" }""")
        )
        // 2번 실패 후 성공
        provider.setFailingNode("step1", maxFailCount = 2)

        val runner = HlxRunner.create(provider, retryDelayMs = 10)

        val workflow = simpleWorkflow(
            HlxNode.Observe(
                id = "step1",
                description = "조회",
                output = "data",
                onError = "retry:3"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // 2번 실패 + 1번 성공 = 3번 호출
        assertEquals(3, provider.callHistory.size)
    }

    @Test
    fun `should skip node on onError skip`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """invalid json""",
                "step2" to """{ "result": "step2 ok" }"""
            )
        )

        val runner = HlxRunner.create(provider, retryDelayMs = 10)

        val workflow = simpleWorkflow(
            HlxNode.Observe(
                id = "step1",
                description = "실패할 노드",
                output = "data",
                onError = "skip"
            ),
            HlxNode.Act(id = "step2", description = "이후 노드", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // step1 FAILED, step2 SUCCESS
        assertEquals(HlxStatus.FAILED, result.nodeRecords[0].status)
        assertEquals(HlxStatus.SUCCESS, result.nodeRecords[1].status)
    }

    @Test
    fun `should abort workflow on onError abort`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """invalid json""")
        )

        val runner = HlxRunner.create(provider, retryDelayMs = 10)

        val workflow = simpleWorkflow(
            HlxNode.Observe(
                id = "step1",
                description = "실패할 노드",
                output = "data",
                onError = "abort"
            ),
            HlxNode.Act(id = "step2", description = "실행 안 됨", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.ABORTED, result.status)
        assertEquals(1, result.nodeRecords.size)
    }

    @Test
    fun `should retry then skip on onError retry then skip`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """invalid json""",
                "step2" to """{ "result": "ok" }"""
            )
        )

        val runner = HlxRunner.create(provider, retryDelayMs = 10)

        val workflow = simpleWorkflow(
            HlxNode.Observe(
                id = "step1",
                description = "실패할 노드",
                output = "data",
                onError = "retry:2 then skip"
            ),
            HlxNode.Act(id = "step2", description = "이후 노드", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // step1이 3번 시도 (초기 1 + retry 2) 후 skip
        assertEquals(3, provider.callHistory.filter { it.prompt.contains("step1") }.size)
        // step2 실행됨
        val step2Record = result.nodeRecords.find { it.nodeId == "step2" }
        assertNotNull(step2Record)
        assertEquals(HlxStatus.SUCCESS, step2Record.status)
    }

    @Test
    fun `should fail after retries exhausted with default fallback`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """invalid json""")
        )

        val runner = HlxRunner.create(provider, retryDelayMs = 10)

        val workflow = simpleWorkflow(
            HlxNode.Observe(
                id = "step1",
                description = "실패할 노드",
                output = "data",
                onError = "retry:2"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        // 3번 시도 (초기 1 + retry 2)
        assertEquals(3, provider.callHistory.size)
    }

    // ==================== onError 파싱 ====================

    @Test
    fun `should parse all onError formats`() {
        // null → default
        val default = HlxRunner.parseOnError(null)
        assertEquals(0, default.retryCount)
        assertEquals(OnErrorFallback.FAIL, default.fallback)

        // "skip"
        val skip = HlxRunner.parseOnError("skip")
        assertEquals(0, skip.retryCount)
        assertEquals(OnErrorFallback.SKIP, skip.fallback)

        // "abort"
        val abort = HlxRunner.parseOnError("abort")
        assertEquals(0, abort.retryCount)
        assertEquals(OnErrorFallback.ABORT, abort.fallback)

        // "retry:3"
        val retry3 = HlxRunner.parseOnError("retry:3")
        assertEquals(3, retry3.retryCount)
        assertEquals(OnErrorFallback.FAIL, retry3.fallback)

        // "retry:3 then skip"
        val retrySkip = HlxRunner.parseOnError("retry:3 then skip")
        assertEquals(3, retrySkip.retryCount)
        assertEquals(OnErrorFallback.SKIP, retrySkip.fallback)

        // "retry:3 then decide"
        val retryDecide = HlxRunner.parseOnError("retry:3 then decide")
        assertEquals(3, retryDecide.retryCount)
        assertEquals(OnErrorFallback.DECIDE, retryDecide.fallback)
    }

    @Test
    fun `should return default for null onError`() {
        val policy = HlxRunner.parseOnError(null)
        assertEquals(OnErrorPolicy(), policy)
    }

    // ==================== 전체 워크플로우 ====================

    @Test
    fun `should execute full unpaid orders workflow - has items branch`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """{ "result": [{"id":"o1","status":"UNPAID"}, {"id":"o2","status":"PAID"}] }""",
                "step2" to """{ "result": [{"id":"o1","status":"UNPAID"}] }""",
                "step3" to """{ "branch": "has_items", "reasoning": "1 unpaid order found" }""",
                "step4-notify" to """{ "result": { "sent": true } }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = HlxWorkflow(
            id = "process77",
            name = "미결제 주문 알림 발송",
            description = "미결제 건 조회 후 알림",
            nodes = listOf(
                HlxNode.Observe(id = "step1", description = "주문 조회", output = "orders"),
                HlxNode.Transform(id = "step2", description = "미결제 필터", input = "orders", output = "unpaidOrders"),
                HlxNode.Decide(
                    id = "step3",
                    description = "미결제 건 여부",
                    input = "unpaidOrders",
                    branches = mapOf("has_items" to "step4", "no_items" to "end")
                ),
                HlxNode.Repeat(
                    id = "step4",
                    description = "각 주문에 알림",
                    over = "unpaidOrders",
                    asVar = "order",
                    body = listOf(
                        HlxNode.Act(id = "step4-notify", description = "알림 발송", input = "order")
                    )
                )
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // step1(observe) + step2(transform) + step3(decide) + step4-notify(act, 1번) + step4(repeat)
        assertTrue(result.nodeRecords.size >= 4)
        assertTrue(result.nodeRecords.any { it.nodeId == "step1" && it.status == HlxStatus.SUCCESS })
        assertTrue(result.nodeRecords.any { it.nodeId == "step2" && it.status == HlxStatus.SUCCESS })
        assertTrue(result.nodeRecords.any { it.nodeId == "step3" && it.selectedBranch == "has_items" })
    }

    @Test
    fun `should execute full unpaid orders workflow - no items branch (end)`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "step1" to """{ "result": [{"id":"o1","status":"PAID"}] }""",
                "step2" to """{ "result": [] }""",
                "step3" to """{ "branch": "no_items", "reasoning": "no unpaid orders" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = HlxWorkflow(
            id = "process77",
            name = "미결제 주문 알림 발송",
            description = "미결제 건 조회 후 알림",
            nodes = listOf(
                HlxNode.Observe(id = "step1", description = "주문 조회", output = "orders"),
                HlxNode.Transform(id = "step2", description = "미결제 필터", input = "orders", output = "unpaidOrders"),
                HlxNode.Decide(
                    id = "step3",
                    description = "미결제 건 여부",
                    input = "unpaidOrders",
                    branches = mapOf("has_items" to "step4", "no_items" to "end")
                ),
                HlxNode.Repeat(
                    id = "step4",
                    description = "각 주문에 알림",
                    over = "unpaidOrders",
                    asVar = "order",
                    body = listOf(
                        HlxNode.Act(id = "step4-notify", description = "알림 발송", input = "order")
                    )
                )
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.ENDED_EARLY, result.status)
        assertNull(result.error)
        assertEquals("no_items", result.nodeRecords.find { it.nodeId == "step3" }?.selectedBranch)
        // step4는 실행되지 않아야 함
        assertFalse(result.nodeRecords.any { it.nodeId == "step4" })
    }

    @Test
    fun `should include audit trail in execution records`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "s1" to """{ "result": "a" }""",
                "s2" to """{ "result": "b" }"""
            )
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "s1", description = "관찰", output = "x"),
            HlxNode.Transform(id = "s2", description = "변환", input = "x", output = "y")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(2, result.nodeRecords.size)

        // 각 레코드의 필수 필드 확인
        result.nodeRecords.forEach { record ->
            assertNotNull(record.nodeId)
            assertNotNull(record.nodeType)
            assertEquals(HlxStatus.SUCCESS, record.status)
            assertTrue(record.durationMs >= 0)
        }

        // workflowId 확인
        assertEquals("test-workflow", result.workflowId)
        assertTrue(result.totalDurationMs >= 0)
    }

    // ==================== 엣지 케이스 ====================

    @Test
    fun `should use initial variables`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("step1" to """{ "result": "processed" }""")
        )
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Transform(
                id = "step1",
                description = "변환",
                input = "existingData",
                output = "result"
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "existingData" to JsonPrimitive("pre-existing value")
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertTrue(provider.callHistory[0].prompt.contains("existingData"))
        assertTrue(provider.callHistory[0].prompt.contains("pre-existing value"))
    }

    @Test
    fun `should handle empty Repeat collection`() = runBlocking {
        val provider = ScriptedLlmProvider(emptyMap())
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Repeat(
                id = "r1",
                description = "빈 반복",
                over = "items",
                asVar = "item",
                body = listOf(
                    HlxNode.Act(id = "body1", description = "실행 안 됨", input = "item")
                )
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "items" to buildJsonArray { }
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // body는 실행되지 않아야 함
        assertFalse(result.nodeRecords.any { it.nodeId == "body1" })
        // Repeat 레코드의 iterationCount = 0
        val repeatRecord = result.nodeRecords.find { it.nodeId == "r1" }
        assertNotNull(repeatRecord)
        assertEquals(0, repeatRecord.iterationCount)
    }

    // ==================== JSON 추출 ====================

    @Test
    fun `should extract JSON from code block`() {
        val response = """
            Here is the result:
            ```json
            { "result": "hello" }
            ```
        """.trimIndent()

        val json = HlxNodeExecutor.extractJson(response)
        assertEquals("hello", json.jsonObject["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should extract raw JSON`() {
        val response = """{ "result": 42 }"""

        val json = HlxNodeExecutor.extractJson(response)
        assertEquals(42, json.jsonObject["result"]?.jsonPrimitive?.int)
    }
}

// ==================== Phase 4: Mock Executor / Gate ====================

/**
 * 테스트용 Mock Executor
 *
 * 모든 step을 수락하고 설정된 결과를 반환한다.
 */
class MockExecutor(
    private val shouldFail: Boolean = false,
    private val failMessage: String = "Mock execution failed"
) : Executor {
    val executedSteps = mutableListOf<ExecutionStep>()

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        executedSteps.add(step)
        return if (shouldFail) {
            ExecutionResult.Failure(
                error = ExecutionError(
                    category = ErrorCategory.UNKNOWN,
                    code = "MOCK_FAIL",
                    message = failMessage
                ),
                meta = ExecutionMeta.now(step.stepId)
            )
        } else {
            ExecutionResult.Success(
                output = StepOutput(
                    stepId = step.stepId,
                    stdout = "mock output for ${step.stepId}",
                    json = mapOf("executed" to JsonPrimitive(true))
                ),
                meta = ExecutionMeta.now(step.stepId)
            )
        }
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = true
    override fun canHandle(step: ExecutionStep): Boolean = true
}

/**
 * 테스트용 Mock Gate
 */
class MockGate(
    private val shouldAllow: Boolean = true,
    private val denyCode: String = "MOCK_DENY"
) : Gate {
    override val id: String = "mock-gate"
    override val name: String = "Mock Gate"
    val checkedContexts = mutableListOf<GateContext>()

    override fun check(context: GateContext): GateResult {
        checkedContexts.add(context)
        return if (shouldAllow) {
            GateResult.Allow(gateId = id)
        } else {
            GateResult.Deny(gateId = id, code = denyCode)
        }
    }
}

// ==================== Phase 4: Act + Executor 테스트 ====================

class HlxRunnerPhase4Test {

    // ==================== 기본 실행 ====================

    @Test
    fun `should execute Act with executor - COMMAND type`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "COMMAND", "params": { "command": "echo", "args": "hello" } } }""")
        )
        val mockExecutor = MockExecutor()
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "셸 명령 실행", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, mockExecutor.executedSteps.size)
        assertTrue(mockExecutor.executedSteps[0] is ExecutionStep.CommandStep)
        assertNotNull(result.context.variables["result"])
    }

    @Test
    fun `should execute Act with executor - FILE_READ type`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "FILE_READ", "params": { "path": "/tmp/test.txt" } } }""")
        )
        val mockExecutor = MockExecutor()
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "파일 읽기", output = "fileContent")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, mockExecutor.executedSteps.size)
        assertTrue(mockExecutor.executedSteps[0] is ExecutionStep.FileStep)
    }

    @Test
    fun `should execute Act with executor - API_CALL type`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "API_CALL", "params": { "url": "https://api.example.com/data", "method": "GET" } } }""")
        )
        val mockExecutor = MockExecutor()
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "API 호출", output = "apiResult")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, mockExecutor.executedSteps.size)
        assertTrue(mockExecutor.executedSteps[0] is ExecutionStep.ApiCallStep)
    }

    // ==================== Gate 통제 ====================

    @Test
    fun `should allow execution when gate allows`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "COMMAND", "params": { "command": "ls" } } }""")
        )
        val mockExecutor = MockExecutor()
        val mockGate = MockGate(shouldAllow = true)
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor, gate = mockGate)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "게이트 통과 테스트", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, mockGate.checkedContexts.size)
        assertEquals("COMMAND", mockGate.checkedContexts[0].action)
        assertEquals(1, mockExecutor.executedSteps.size)
    }

    @Test
    fun `should deny execution when gate denies`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "COMMAND", "params": { "command": "rm -rf /" } } }""")
        )
        val mockExecutor = MockExecutor()
        val mockGate = MockGate(shouldAllow = false, denyCode = "DANGEROUS_COMMAND")
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor, gate = mockGate)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "위험한 명령", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Gate denied"))
        assertTrue(result.error!!.contains("DANGEROUS_COMMAND"))
        // Executor는 호출되지 않아야 함
        assertEquals(0, mockExecutor.executedSteps.size)
    }

    @Test
    fun `should execute without gate when gate is null`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "NOOP", "params": { "action": "test" } } }""")
        )
        val mockExecutor = MockExecutor()
        // gate = null (기본값)
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "게이트 없이 실행", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, mockExecutor.executedSteps.size)
    }

    // ==================== 에러 처리 ====================

    @Test
    fun `should fail on unknown step type`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "UNKNOWN_TYPE", "params": {} } }""")
        )
        val mockExecutor = MockExecutor()
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "잘못된 타입", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Unknown step type"))
        assertEquals(0, mockExecutor.executedSteps.size)
    }

    @Test
    fun `should fail when executor fails`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "step": { "type": "COMMAND", "params": { "command": "fail-cmd" } } }""")
        )
        val mockExecutor = MockExecutor(shouldFail = true, failMessage = "Command not found")
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "실패할 명령", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Executor failed"))
        assertTrue(result.error!!.contains("Command not found"))
    }

    @Test
    fun `should fail when step field is missing`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "result": "no step field" }""")
        )
        val mockExecutor = MockExecutor()
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "step 필드 누락", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("missing 'step' field"))
        assertEquals(0, mockExecutor.executedSteps.size)
    }

    // ==================== 하위 호환 ====================

    @Test
    fun `should use LLM-only path when executor is null`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("act1" to """{ "result": { "sent": true } }""")
        )
        // executor=null → 기존 create() 팩토리
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Act(id = "act1", description = "LLM-only 알림 발송", output = "sendResult")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertNotNull(result.context.variables["sendResult"])
        // LLM-only 경로에서는 "result" 필드를 추출
        val sendResult = result.context.variables["sendResult"]!!.jsonObject
        assertEquals(true, sendResult["sent"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `should mix Observe-Transform with Act-Executor in workflow`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "obs1" to """{ "result": ["item1", "item2"] }""",
                "trans1" to """{ "result": ["item1"] }""",
                "act1" to """{ "step": { "type": "COMMAND", "params": { "command": "notify", "args": "item1" } } }"""
            )
        )
        val mockExecutor = MockExecutor()
        val runner = HlxRunner.createWithExecutor(provider, mockExecutor)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "obs1", description = "관찰", output = "data"),
            HlxNode.Transform(id = "trans1", description = "변환", input = "data", output = "filtered"),
            HlxNode.Act(id = "act1", description = "실행", input = "filtered", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(3, result.nodeRecords.size)
        assertTrue(result.nodeRecords.all { it.status == HlxStatus.SUCCESS })
        // Observe/Transform은 LLM-only, Act만 Executor 사용
        assertEquals(1, mockExecutor.executedSteps.size)
    }
}

// ==================== Phase 5: SubWorkflow 테스트 ====================

class HlxRunnerSubWorkflowTest {

    private val childWorkflow = HlxWorkflow(
        id = "child-workflow",
        name = "Child Workflow",
        description = "자식 워크플로우",
        nodes = listOf(
            HlxNode.Transform(
                id = "child-step1",
                description = "자식 변환",
                input = "childInput",
                output = "childOutput"
            )
        )
    )

    // ==================== 기본 실행 ====================

    @Test
    fun `should execute SubWorkflow basic call`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("child-step1" to """{ "result": "child result" }""")
        )
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "child-workflow") childWorkflow else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "자식 워크플로우 호출",
                workflowRef = "child-workflow"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(1, result.nodeRecords.size)
        assertEquals("sub1", result.nodeRecords[0].nodeId)
        assertEquals(HlxNodeType.SUBWORKFLOW, result.nodeRecords[0].nodeType)
        assertEquals(HlxStatus.SUCCESS, result.nodeRecords[0].status)
    }

    @Test
    fun `should pass inputMapping to child workflow`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("child-step1" to """{ "result": "processed parent data" }""")
        )
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "child-workflow") childWorkflow else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "입력 매핑 테스트",
                workflowRef = "child-workflow",
                inputMapping = mapOf("parentData" to "childInput")
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "parentData" to JsonPrimitive("hello from parent")
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // 프롬프트에 childInput 변수가 포함되어야 함
        assertTrue(provider.callHistory.any { it.prompt.contains("childInput") })
    }

    @Test
    fun `should collect outputMapping from child workflow`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("child-step1" to """{ "result": "child output value" }""")
        )
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "child-workflow") childWorkflow else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "출력 매핑 테스트",
                workflowRef = "child-workflow",
                inputMapping = mapOf("parentData" to "childInput"),
                outputMapping = mapOf("childOutput" to "parentResult")
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "parentData" to JsonPrimitive("input data")
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertNotNull(result.context.variables["parentResult"])
    }

    // ==================== 변수 격리 ====================

    @Test
    fun `should isolate child context from parent`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf("child-step1" to """{ "result": "child modified" }""")
        )
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "child-workflow") childWorkflow else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "격리 테스트",
                workflowRef = "child-workflow",
                inputMapping = mapOf("parentData" to "childInput")
                // outputMapping 없음 → childOutput은 부모에 전달 안 됨
            )
        )

        val initialVars = mapOf<String, JsonElement>(
            "parentData" to JsonPrimitive("parent value"),
            "parentOnly" to JsonPrimitive("should remain")
        )

        val result = runner.run(workflow, initialVars)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        // 부모 변수 유지
        assertEquals("parent value", result.context.variables["parentData"]?.jsonPrimitive?.content)
        assertEquals("should remain", result.context.variables["parentOnly"]?.jsonPrimitive?.content)
        // 자식의 childOutput은 부모에 없어야 함
        assertNull(result.context.variables["childOutput"])
    }

    // ==================== 에러 처리 ====================

    @Test
    fun `should fail when workflowRef not found`() = runBlocking {
        val provider = ScriptedLlmProvider(emptyMap())
        val resolver: (String) -> HlxWorkflow? = { null }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "존재하지 않는 워크플로우",
                workflowRef = "nonexistent-workflow"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("not found"))
    }

    @Test
    fun `should fail when resolver is null`() = runBlocking {
        val provider = ScriptedLlmProvider(emptyMap())
        // workflowResolver = null (기본값)
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "리졸버 없음",
                workflowRef = "any-workflow"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("WorkflowResolver not configured"))
    }

    @Test
    fun `should fail when child workflow fails`() = runBlocking {
        val failingChild = HlxWorkflow(
            id = "failing-child",
            name = "Failing Child",
            description = "실패하는 자식",
            nodes = listOf(
                HlxNode.Observe(
                    id = "fail-step",
                    description = "실패할 노드",
                    output = "data"
                )
            )
        )
        val provider = ScriptedLlmProvider(
            mapOf("fail-step" to """invalid json""")
        )
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "failing-child") failingChild else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "실패하는 자식 호출",
                workflowRef = "failing-child"
            )
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("failed"))
    }

    // ==================== 순환 방지 ====================

    @Test
    fun `should fail on depth limit exceeded`() = runBlocking {
        // 자기 자신을 호출하는 워크플로우
        val selfRefWorkflow = HlxWorkflow(
            id = "self-ref",
            name = "Self Referencing",
            description = "자기 참조",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub-self",
                    description = "자기 자신 호출",
                    workflowRef = "self-ref"
                )
            )
        )
        val provider = ScriptedLlmProvider(emptyMap())
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "self-ref") selfRefWorkflow else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver, maxSubWorkflowDepth = 3)

        val result = runner.run(selfRefWorkflow)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        // 순환 참조 또는 depth 초과로 실패해야 함
        assertTrue(
            result.error!!.contains("Circular") || result.error!!.contains("depth"),
            "Expected circular or depth error but got: ${result.error}"
        )
    }

    @Test
    fun `should detect circular reference`() = runBlocking {
        // A → B → A 순환
        val workflowA = HlxWorkflow(
            id = "wf-a",
            name = "Workflow A",
            description = "A",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub-b",
                    description = "B 호출",
                    workflowRef = "wf-b"
                )
            )
        )
        val workflowB = HlxWorkflow(
            id = "wf-b",
            name = "Workflow B",
            description = "B",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub-a",
                    description = "A 호출",
                    workflowRef = "wf-a"
                )
            )
        )
        val provider = ScriptedLlmProvider(emptyMap())
        val resolver: (String) -> HlxWorkflow? = { id ->
            when (id) {
                "wf-a" -> workflowA
                "wf-b" -> workflowB
                else -> null
            }
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val result = runner.run(workflowA)

        assertEquals(HlxExecutionStatus.FAILED, result.status)
        assertTrue(result.error!!.contains("Circular"))
    }

    // ==================== 하위 호환 ====================

    @Test
    fun `should work without resolver for existing workflows`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "s1" to """{ "result": "observed" }""",
                "s2" to """{ "result": "transformed" }"""
            )
        )
        // resolver 없이 기존 create()
        val runner = HlxRunner.create(provider)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "s1", description = "관찰", output = "data"),
            HlxNode.Transform(id = "s2", description = "변환", input = "data", output = "result")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(2, result.nodeRecords.size)
    }

    // ==================== 복합 ====================

    @Test
    fun `should execute Observe then SubWorkflow then Act mixed workflow`() = runBlocking {
        val provider = ScriptedLlmProvider(
            mapOf(
                "obs1" to """{ "result": ["item1", "item2"] }""",
                "child-step1" to """{ "result": "child processed" }""",
                "act1" to """{ "result": { "sent": true } }"""
            )
        )
        val resolver: (String) -> HlxWorkflow? = { id ->
            if (id == "child-workflow") childWorkflow else null
        }
        val runner = HlxRunner.create(provider, workflowResolver = resolver)

        val workflow = simpleWorkflow(
            HlxNode.Observe(id = "obs1", description = "데이터 조회", output = "data"),
            HlxNode.SubWorkflow(
                id = "sub1",
                description = "서브워크플로우로 처리",
                workflowRef = "child-workflow",
                inputMapping = mapOf("data" to "childInput"),
                outputMapping = mapOf("childOutput" to "processedData")
            ),
            HlxNode.Act(id = "act1", description = "결과 발송", input = "processedData", output = "sendResult")
        )

        val result = runner.run(workflow)

        assertEquals(HlxExecutionStatus.COMPLETED, result.status)
        assertEquals(3, result.nodeRecords.size)
        assertTrue(result.nodeRecords.all { it.status == HlxStatus.SUCCESS })
        assertNotNull(result.context.variables["processedData"])
        assertNotNull(result.context.variables["sendResult"])
    }
}
