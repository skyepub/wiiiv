package io.wiiiv.hlx

import io.wiiiv.hlx.model.*
import io.wiiiv.hlx.parser.HlxParseResult
import io.wiiiv.hlx.parser.HlxParser
import kotlin.test.*

/**
 * HLX Parser Tests
 *
 * JSON 파싱 및 직렬화 검증
 */
class HlxParserTest {

    // ==================== 전체 예제 파싱 ====================

    @Test
    fun `should parse full unpaid orders workflow`() {
        val json = """
        {
            "${'$'}schema": "https://hlx.dev/schema/hlx-v1.0.json",
            "version": "1.0",
            "id": "process77",
            "name": "미결제 주문 알림 발송",
            "description": "주문 API에서 미결제 건을 조회하여 고객에게 결제 알림을 발송한다",
            "trigger": {
                "type": "manual"
            },
            "nodes": [
                {
                    "id": "step1",
                    "type": "observe",
                    "description": "주문 시스템에서 최근 7일간 주문 목록을 조회한다",
                    "target": "GET /api/orders?days=7",
                    "output": "orders"
                },
                {
                    "id": "step2",
                    "type": "transform",
                    "hint": "filter",
                    "description": "주문 목록에서 status가 UNPAID이고 생성일이 3일 이상 된 건만 추출한다",
                    "input": "orders",
                    "output": "unpaidOrders"
                },
                {
                    "id": "step3",
                    "type": "decide",
                    "description": "미결제 건이 있으면 알림 발송으로 진행하고, 없으면 워크플로우를 종료한다",
                    "input": "unpaidOrders",
                    "branches": {
                        "hasItems": "step4",
                        "empty": "end"
                    }
                },
                {
                    "id": "step4",
                    "type": "repeat",
                    "description": "각 미결제 주문에 대해 알림을 발송한다",
                    "over": "unpaidOrders",
                    "as": "order",
                    "body": [
                        {
                            "id": "step4a",
                            "type": "act",
                            "description": "해당 고객에게 결제 요청 알림을 발송한다",
                            "target": "POST /api/notifications",
                            "input": "order"
                        }
                    ]
                }
            ]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)

        assertEquals("process77", workflow.id)
        assertEquals("미결제 주문 알림 발송", workflow.name)
        assertEquals("1.0", workflow.version)
        assertEquals(HlxTriggerType.MANUAL, workflow.trigger.type)
        assertEquals(4, workflow.nodes.size)
    }

    // ==================== 노드 타입별 파싱 ====================

    @Test
    fun `should parse Observe node`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "observe",
                "description": "API에서 데이터를 조회한다",
                "target": "GET /api/data",
                "output": "result"
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[0] as HlxNode.Observe

        assertEquals("s1", node.id)
        assertEquals(HlxNodeType.OBSERVE, node.type)
        assertEquals("GET /api/data", node.target)
        assertEquals("result", node.output)
    }

    @Test
    fun `should parse Transform node with hint`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "transform",
                "hint": "filter",
                "description": "조건에 맞는 항목 추출",
                "input": "data",
                "output": "filtered"
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[0] as HlxNode.Transform

        assertEquals(HlxNodeType.TRANSFORM, node.type)
        assertEquals(TransformHint.FILTER, node.hint)
        assertEquals("data", node.input)
        assertEquals("filtered", node.output)
    }

    @Test
    fun `should parse Decide node with branches`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [
                {"id": "s1", "type": "observe", "description": "관찰"},
                {"id": "s2", "type": "decide",
                 "description": "분기 결정",
                 "input": "data",
                 "branches": {"yes": "s1", "no": "end"}}
            ]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[1] as HlxNode.Decide

        assertEquals(HlxNodeType.DECIDE, node.type)
        assertEquals(mapOf("yes" to "s1", "no" to "end"), node.branches)
    }

    @Test
    fun `should parse Act node`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "act",
                "description": "알림을 발송한다",
                "target": "POST /api/notifications",
                "input": "order"
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[0] as HlxNode.Act

        assertEquals(HlxNodeType.ACT, node.type)
        assertEquals("POST /api/notifications", node.target)
        assertEquals("order", node.input)
    }

    @Test
    fun `should parse Repeat node with body`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "repeat",
                "description": "각 항목에 대해 실행",
                "over": "items",
                "as": "item",
                "body": [
                    {"id": "s1a", "type": "act", "description": "처리", "input": "item"}
                ]
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[0] as HlxNode.Repeat

        assertEquals(HlxNodeType.REPEAT, node.type)
        assertEquals("items", node.over)
        assertEquals("item", node.asVar)
        assertEquals(1, node.body.size)
        assertTrue(node.body[0] is HlxNode.Act)
    }

    // ==================== 중첩 Repeat ====================

    @Test
    fun `should parse nested Repeat nodes`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "r1", "type": "repeat",
                "description": "외부 반복",
                "over": "groups", "as": "group",
                "body": [{
                    "id": "r2", "type": "repeat",
                    "description": "내부 반복",
                    "over": "group.items", "as": "item",
                    "body": [
                        {"id": "a1", "type": "act", "description": "항목 처리", "input": "item"}
                    ]
                }]
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val outer = workflow.nodes[0] as HlxNode.Repeat
        val inner = outer.body[0] as HlxNode.Repeat

        assertEquals("r1", outer.id)
        assertEquals("r2", inner.id)
        assertEquals(1, inner.body.size)
    }

    // ==================== 공통 선택 필드 ====================

    @Test
    fun `should parse optional fields`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "observe",
                "description": "관찰",
                "onError": "retry:3",
                "aiRequired": false,
                "determinismLevel": "high"
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[0] as HlxNode.Observe

        assertEquals("retry:3", node.onError)
        assertFalse(node.aiRequired)
        assertEquals(DeterminismLevel.HIGH, node.determinismLevel)
    }

    // ==================== 라운드트립 ====================

    @Test
    fun `should round-trip parse to json to parse`() {
        val original = HlxWorkflow(
            id = "rt-test",
            name = "Round Trip Test",
            description = "라운드트립 테스트",
            nodes = listOf(
                HlxNode.Observe(
                    id = "s1",
                    description = "관찰",
                    target = "GET /api/data",
                    output = "result"
                ),
                HlxNode.Transform(
                    id = "s2",
                    description = "변환",
                    hint = TransformHint.FILTER,
                    input = "result",
                    output = "filtered"
                ),
                HlxNode.Decide(
                    id = "s3",
                    description = "결정",
                    branches = mapOf("yes" to "s4", "no" to "end")
                ),
                HlxNode.Act(
                    id = "s4",
                    description = "실행",
                    target = "POST /api/action"
                )
            )
        )

        val jsonStr = HlxParser.toJson(original)
        val parsed = HlxParser.parse(jsonStr)

        assertEquals(original.id, parsed.id)
        assertEquals(original.name, parsed.name)
        assertEquals(original.nodes.size, parsed.nodes.size)
        assertEquals((original.nodes[0] as HlxNode.Observe).target, (parsed.nodes[0] as HlxNode.Observe).target)
        assertEquals((original.nodes[1] as HlxNode.Transform).hint, (parsed.nodes[1] as HlxNode.Transform).hint)
        assertEquals((original.nodes[2] as HlxNode.Decide).branches, (parsed.nodes[2] as HlxNode.Decide).branches)
    }

    // ==================== 오류 처리 ====================

    @Test
    fun `should throw on unknown node type`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "unknown",
                "description": "알 수 없는 타입"
            }]
        }
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            HlxParser.parse(json)
        }
    }

    @Test
    fun `should return null for invalid JSON via parseOrNull`() {
        val result = HlxParser.parseOrNull("not a json")
        assertNull(result)
    }

    @Test
    fun `should return null for unknown type via parseOrNull`() {
        val json = """{"id":"wf1","name":"t","description":"d","nodes":[{"id":"s1","type":"bad","description":"x"}]}"""
        assertNull(HlxParser.parseOrNull(json))
    }

    // ==================== ignoreUnknownKeys ====================

    @Test
    fun `should ignore unknown keys`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "unknownField": "should be ignored",
            "nodes": [{
                "id": "s1", "type": "observe",
                "description": "관찰",
                "extraField": 42
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        assertEquals("wf1", workflow.id)
        assertEquals(1, workflow.nodes.size)
    }

    // ==================== 기본값 ====================

    @Test
    fun `should use default values`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "s1", "type": "observe",
                "description": "관찰"
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)

        assertEquals("1.0", workflow.version)
        assertEquals(HlxTriggerType.MANUAL, workflow.trigger.type)
        assertNull(workflow.schema)

        val node = workflow.nodes[0] as HlxNode.Observe
        assertTrue(node.aiRequired)
        assertNull(node.onError)
        assertNull(node.determinismLevel)
        assertNull(node.target)
    }

    // ==================== Trigger 파싱 ====================

    @Test
    fun `should parse schedule trigger`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "trigger": {
                "type": "schedule",
                "schedule": "0 9 * * 1-5"
            },
            "nodes": [{"id": "s1", "type": "observe", "description": "관찰"}]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)

        assertEquals(HlxTriggerType.SCHEDULE, workflow.trigger.type)
        assertEquals("0 9 * * 1-5", workflow.trigger.schedule)
    }

    @Test
    fun `should parse webhook trigger`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "trigger": {
                "type": "webhook",
                "webhook": "/hooks/deploy"
            },
            "nodes": [{"id": "s1", "type": "observe", "description": "관찰"}]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)

        assertEquals(HlxTriggerType.WEBHOOK, workflow.trigger.type)
        assertEquals("/hooks/deploy", workflow.trigger.webhook)
    }

    // ==================== parseAndValidate ====================

    @Test
    fun `parseAndValidate should return Success for valid workflow`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{"id": "s1", "type": "observe", "description": "관찰"}]
        }
        """.trimIndent()

        val result = HlxParser.parseAndValidate(json)
        assertTrue(result is HlxParseResult.Success)
        assertEquals("wf1", (result as HlxParseResult.Success).workflow.id)
    }

    @Test
    fun `parseAndValidate should return ParseError for invalid JSON`() {
        val result = HlxParser.parseAndValidate("not json")
        assertTrue(result is HlxParseResult.ParseError)
    }

    @Test
    fun `parseAndValidate should return ValidationError for invalid structure`() {
        val json = """
        {
            "id": "", "name": "test", "description": "test wf",
            "nodes": []
        }
        """.trimIndent()

        val result = HlxParser.parseAndValidate(json)
        assertTrue(result is HlxParseResult.ValidationError)
    }

    // ==================== SubWorkflow 파싱 ====================

    @Test
    fun `should parse SubWorkflow node`() {
        val json = """
        {
            "id": "wf1", "name": "test", "description": "test wf",
            "nodes": [{
                "id": "sub1", "type": "subworkflow",
                "description": "자식 워크플로우 호출",
                "workflowRef": "child-workflow-id",
                "inputMapping": { "parentVar": "childVar" },
                "outputMapping": { "childResult": "parentResult" }
            }]
        }
        """.trimIndent()

        val workflow = HlxParser.parse(json)
        val node = workflow.nodes[0] as HlxNode.SubWorkflow

        assertEquals(HlxNodeType.SUBWORKFLOW, node.type)
        assertEquals("sub1", node.id)
        assertEquals("child-workflow-id", node.workflowRef)
        assertEquals(mapOf("parentVar" to "childVar"), node.inputMapping)
        assertEquals(mapOf("childResult" to "parentResult"), node.outputMapping)
        assertFalse(node.aiRequired)
    }

    @Test
    fun `should round-trip SubWorkflow node`() {
        val original = HlxWorkflow(
            id = "rt-sub",
            name = "Round Trip SubWorkflow",
            description = "서브워크플로우 라운드트립",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub1",
                    description = "서브워크플로우",
                    workflowRef = "child-wf",
                    inputMapping = mapOf("a" to "b"),
                    outputMapping = mapOf("c" to "d"),
                    onError = "retry:2 then skip"
                )
            )
        )

        val jsonStr = HlxParser.toJson(original)
        val parsed = HlxParser.parse(jsonStr)

        assertEquals(1, parsed.nodes.size)
        val node = parsed.nodes[0] as HlxNode.SubWorkflow
        assertEquals("sub1", node.id)
        assertEquals("child-wf", node.workflowRef)
        assertEquals(mapOf("a" to "b"), node.inputMapping)
        assertEquals(mapOf("c" to "d"), node.outputMapping)
        assertEquals("retry:2 then skip", node.onError)
    }

    // ==================== 모든 TransformHint 값 ====================

    @Test
    fun `should parse all TransformHint values`() {
        val hints = listOf("filter", "map", "normalize", "summarize", "extract", "merge")
        val expected = listOf(
            TransformHint.FILTER, TransformHint.MAP, TransformHint.NORMALIZE,
            TransformHint.SUMMARIZE, TransformHint.EXTRACT, TransformHint.MERGE
        )

        for ((hint, expectedEnum) in hints.zip(expected)) {
            val json = """
            {
                "id": "wf1", "name": "test", "description": "test wf",
                "nodes": [{"id": "s1", "type": "transform", "hint": "$hint", "description": "변환"}]
            }
            """.trimIndent()

            val workflow = HlxParser.parse(json)
            val node = workflow.nodes[0] as HlxNode.Transform
            assertEquals(expectedEnum, node.hint, "Failed for hint: $hint")
        }
    }
}
