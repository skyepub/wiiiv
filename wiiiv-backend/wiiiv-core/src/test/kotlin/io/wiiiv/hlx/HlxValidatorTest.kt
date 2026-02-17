package io.wiiiv.hlx

import io.wiiiv.hlx.model.*
import io.wiiiv.hlx.validation.HlxValidator
import kotlin.test.*

/**
 * HLX Validator Tests
 *
 * 구조 검증 규칙 검증
 */
class HlxValidatorTest {

    // ==================== 유효한 워크플로우 ====================

    @Test
    fun `should pass validation for valid workflow`() {
        val workflow = HlxWorkflow(
            id = "wf1",
            name = "Valid Workflow",
            description = "A valid workflow",
            nodes = listOf(
                HlxNode.Observe(id = "s1", description = "관찰", output = "data"),
                HlxNode.Transform(id = "s2", description = "변환", input = "data", output = "result"),
                HlxNode.Decide(
                    id = "s3", description = "결정",
                    branches = mapOf("yes" to "s4", "no" to "end")
                ),
                HlxNode.Act(id = "s4", description = "실행")
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
        assertTrue(HlxValidator.isValid(workflow))
    }

    // ==================== 워크플로우 수준 검증 ====================

    @Test
    fun `should fail for blank workflow id`() {
        val workflow = HlxWorkflow(
            id = "", name = "test", description = "test",
            nodes = listOf(HlxNode.Observe(id = "s1", description = "관찰"))
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.path == "workflow.id" })
    }

    @Test
    fun `should fail for blank workflow name`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "  ", description = "test",
            nodes = listOf(HlxNode.Observe(id = "s1", description = "관찰"))
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.path == "workflow.name" })
    }

    @Test
    fun `should fail for empty nodes`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = emptyList()
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.path == "workflow.nodes" })
    }

    // ==================== 노드 ID 유일성 ====================

    @Test
    fun `should fail for duplicate node ids`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Observe(id = "dup", description = "첫 번째"),
                HlxNode.Act(id = "dup", description = "두 번째")
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.path == "node.id" && it.message.contains("dup") })
    }

    @Test
    fun `should detect duplicate ids in nested Repeat body`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Observe(id = "s1", description = "관찰"),
                HlxNode.Repeat(
                    id = "r1", description = "반복",
                    over = "items", asVar = "item",
                    body = listOf(
                        HlxNode.Act(id = "s1", description = "중복 ID")
                    )
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.path == "node.id" && it.message.contains("s1") })
    }

    // ==================== 노드 description 검증 ====================

    @Test
    fun `should fail for blank node description`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Observe(id = "s1", description = "")
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("description") })
    }

    // ==================== Decide 검증 ====================

    @Test
    fun `should fail for Decide with empty branches`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Decide(id = "d1", description = "빈 분기", branches = emptyMap())
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("branch") })
    }

    @Test
    fun `should fail for Decide referencing non-existent node`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Decide(
                    id = "d1", description = "잘못된 참조",
                    branches = mapOf("yes" to "nonexistent")
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("nonexistent") })
    }

    @Test
    fun `should allow 'end' as branch target`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Decide(
                    id = "d1", description = "end 분기",
                    branches = mapOf("done" to "end")
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertFalse(errors.any { it.message.contains("end") })
    }

    // ==================== Repeat 검증 ====================

    @Test
    fun `should fail for Repeat with empty body`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Repeat(
                    id = "r1", description = "빈 body",
                    over = "items", asVar = "item",
                    body = emptyList()
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("body") })
    }

    @Test
    fun `should fail for Repeat with blank over`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Repeat(
                    id = "r1", description = "빈 over",
                    over = "", asVar = "item",
                    body = listOf(HlxNode.Act(id = "a1", description = "실행"))
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("over") })
    }

    @Test
    fun `should fail for Repeat with blank as`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Repeat(
                    id = "r1", description = "빈 as",
                    over = "items", asVar = "",
                    body = listOf(HlxNode.Act(id = "a1", description = "실행"))
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("as") })
    }

    // ==================== onError 형식 검증 ====================

    @Test
    fun `should accept valid onError formats`() {
        val validFormats = listOf("retry:3", "retry:1 then skip", "retry:5 then decide", "skip", "abort")

        for (format in validFormats) {
            val workflow = HlxWorkflow(
                id = "wf1", name = "test", description = "test",
                nodes = listOf(
                    HlxNode.Observe(id = "s1", description = "관찰", onError = format)
                )
            )

            val errors = HlxValidator.validate(workflow)
            assertFalse(
                errors.any { it.message.contains("onError") },
                "Expected '$format' to be valid but got errors: $errors"
            )
        }
    }

    @Test
    fun `should reject invalid onError formats`() {
        val invalidFormats = listOf("retry", "retry:abc", "ignore", "retry:3 then", "retry:3 then fail", "")

        for (format in invalidFormats) {
            val workflow = HlxWorkflow(
                id = "wf1", name = "test", description = "test",
                nodes = listOf(
                    HlxNode.Observe(id = "s1", description = "관찰", onError = format)
                )
            )

            val errors = HlxValidator.validate(workflow)
            assertTrue(
                errors.any { it.message.contains("onError") },
                "Expected '$format' to be invalid but no errors found"
            )
        }
    }

    // ==================== 중첩 검증 ====================

    @Test
    fun `should validate nodes inside Repeat body`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.Repeat(
                    id = "r1", description = "반복",
                    over = "items", asVar = "item",
                    body = listOf(
                        HlxNode.Act(id = "a1", description = ""),  // blank description
                        HlxNode.Decide(id = "d1", description = "결정", branches = emptyMap())  // empty branches
                    )
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("description") })
        assertTrue(errors.any { it.message.contains("branch") })
    }

    // ==================== SubWorkflow 검증 ====================

    @Test
    fun `should fail for SubWorkflow with blank workflowRef`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub1", description = "빈 워크플로우 참조",
                    workflowRef = ""
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("workflowRef") })
    }

    @Test
    fun `should pass validation for valid SubWorkflow`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub1", description = "유효한 서브워크플로우",
                    workflowRef = "child-workflow",
                    inputMapping = mapOf("a" to "b"),
                    outputMapping = mapOf("c" to "d")
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.isEmpty(), "Expected no errors but got: $errors")
    }

    @Test
    fun `should validate SubWorkflow onError`() {
        val workflow = HlxWorkflow(
            id = "wf1", name = "test", description = "test",
            nodes = listOf(
                HlxNode.SubWorkflow(
                    id = "sub1", description = "onError 검증",
                    workflowRef = "child-workflow",
                    onError = "invalid_format"
                )
            )
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.any { it.message.contains("onError") })
    }

    // ==================== 복합 오류 ====================

    @Test
    fun `should collect multiple errors`() {
        val workflow = HlxWorkflow(
            id = "", name = "", description = "test",
            nodes = emptyList()
        )

        val errors = HlxValidator.validate(workflow)
        assertTrue(errors.size >= 3, "Expected at least 3 errors but got ${errors.size}: $errors")
        assertFalse(HlxValidator.isValid(workflow))
    }
}
