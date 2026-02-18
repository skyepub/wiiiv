package io.wiiiv.hlx.validation

import io.wiiiv.hlx.model.HlxNode
import io.wiiiv.hlx.model.HlxWorkflow
import java.net.URI

/**
 * HLX Validation Error - 검증 오류
 */
data class HlxValidationError(
    val path: String,
    val message: String
)

/**
 * HLX Validator - 구조 검증기
 *
 * HlxWorkflow의 구조적 유효성을 검증한다.
 *
 * 검증 규칙:
 * 1. 워크플로우 id/name 비어있지 않은지
 * 2. nodes가 최소 1개 이상
 * 3. 모든 노드 ID 유일성 (중첩 Repeat body 포함)
 * 4. 모든 노드 description 비어있지 않은지
 * 5. Decide: branches 최소 1개, 참조 노드 ID 존재 여부 ("end" 특별 허용)
 * 6. Repeat: over/as 비어있지 않은지, body 최소 1개
 * 7. onError 형식 검증
 * 8. Decide: branch key에 동적 값 차단 (공백 포함 또는 30자 초과)
 * 9. Act: description URL의 unknown query param 차단
 */
object HlxValidator {

    private val ON_ERROR_PATTERN = Regex(
        """^(retry:\d+(\s+then\s+(skip|decide))?|skip|abort)$"""
    )

    /** 허용된 query param 이름 (소문자) */
    private val ALLOWED_QUERY_PARAMS = setOf(
        "page", "size", "threshold", "keyword", "categoryid", "status", "level"
    )

    /** URL 추출 패턴 (description에서 http(s)://... 추출) */
    private val URL_PATTERN = Regex("""https?://[^\s"',}]+""")

    /**
     * 워크플로우 검증
     *
     * @return 검증 오류 목록 (비어있으면 유효)
     */
    fun validate(workflow: HlxWorkflow): List<HlxValidationError> {
        val errors = mutableListOf<HlxValidationError>()

        // 1. 워크플로우 id/name 비어있지 않은지
        if (workflow.id.isBlank()) {
            errors.add(HlxValidationError("workflow.id", "Workflow id must not be blank"))
        }
        if (workflow.name.isBlank()) {
            errors.add(HlxValidationError("workflow.name", "Workflow name must not be blank"))
        }

        // 2. nodes가 최소 1개 이상
        if (workflow.nodes.isEmpty()) {
            errors.add(HlxValidationError("workflow.nodes", "Workflow must have at least one node"))
        }

        // 3. 모든 노드 ID 유일성 (중첩 Repeat body 포함)
        val allIds = mutableListOf<String>()
        collectNodeIds(workflow.nodes, allIds)
        val duplicates = allIds.groupBy { it }.filter { it.value.size > 1 }.keys
        for (dup in duplicates) {
            errors.add(HlxValidationError("node.id", "Duplicate node id: '$dup'"))
        }

        // 모든 노드 ID의 Set (Decide branches 참조 검증용)
        val allIdSet = allIds.toSet()

        // 4-7. 노드별 검증
        validateNodes(workflow.nodes, allIdSet, "nodes", errors)

        return errors
    }

    /**
     * 워크플로우가 유효한지 확인
     */
    fun isValid(workflow: HlxWorkflow): Boolean {
        return validate(workflow).isEmpty()
    }

    private fun collectNodeIds(nodes: List<HlxNode>, ids: MutableList<String>) {
        for (node in nodes) {
            ids.add(node.id)
            if (node is HlxNode.Repeat) {
                collectNodeIds(node.body, ids)
            }
            // SubWorkflow는 body가 없으므로 추가 수집 불필요
        }
    }

    private fun validateNodes(
        nodes: List<HlxNode>,
        allIds: Set<String>,
        path: String,
        errors: MutableList<HlxValidationError>
    ) {
        for ((index, node) in nodes.withIndex()) {
            val nodePath = "$path[$index](${node.id})"

            // 4. 모든 노드 description 비어있지 않은지
            if (node.description.isBlank()) {
                errors.add(HlxValidationError("$nodePath.description", "Node description must not be blank"))
            }

            // 7. onError 형식 검증
            node.onError?.let { onError ->
                if (!ON_ERROR_PATTERN.matches(onError)) {
                    errors.add(
                        HlxValidationError(
                            "$nodePath.onError",
                            "Invalid onError format: '$onError'. Expected: retry:<n>, retry:<n> then skip|decide, skip, abort"
                        )
                    )
                }
            }

            // 타입별 검증
            when (node) {
                is HlxNode.Decide -> {
                    // 5. Decide: branches 최소 1개
                    if (node.branches.isEmpty()) {
                        errors.add(HlxValidationError("$nodePath.branches", "Decide node must have at least one branch"))
                    }
                    // 5. 참조 노드 ID 존재 여부 ("end"는 특별 허용)
                    for ((condition, targetId) in node.branches) {
                        if (targetId != "end" && targetId !in allIds) {
                            errors.add(
                                HlxValidationError(
                                    "$nodePath.branches.$condition",
                                    "Branch '$condition' references non-existent node: '$targetId'"
                                )
                            )
                        }
                    }
                    // 8. branch key 패턴 검증 — 동적 값 차단
                    for ((condition, _) in node.branches) {
                        if (condition.contains(' ')) {
                            errors.add(
                                HlxValidationError(
                                    "$nodePath.branches.$condition",
                                    "Branch key contains spaces (likely a dynamic value): '$condition'"
                                )
                            )
                        }
                        if (condition.length > 30) {
                            errors.add(
                                HlxValidationError(
                                    "$nodePath.branches.$condition",
                                    "Branch key exceeds 30 chars (likely a dynamic value): '${condition.take(30)}...'"
                                )
                            )
                        }
                    }
                }
                is HlxNode.Repeat -> {
                    // 6. Repeat: over/as 비어있지 않은지
                    if (node.over.isNullOrBlank()) {
                        errors.add(HlxValidationError("$nodePath.over", "Repeat node 'over' must not be blank"))
                    }
                    if (node.asVar.isNullOrBlank()) {
                        errors.add(HlxValidationError("$nodePath.as", "Repeat node 'as' must not be blank"))
                    }
                    // 6. body 최소 1개
                    if (node.body.isEmpty()) {
                        errors.add(HlxValidationError("$nodePath.body", "Repeat node must have at least one body node"))
                    }
                    // 재귀 검증
                    validateNodes(node.body, allIds, "$nodePath.body", errors)
                }
                is HlxNode.SubWorkflow -> {
                    if (node.workflowRef.isBlank()) {
                        errors.add(HlxValidationError("$nodePath.workflowRef", "SubWorkflow node 'workflowRef' must not be blank"))
                    }
                }
                is HlxNode.Act -> {
                    // 9. ACT 노드 URL의 unknown query param 차단
                    validateActUrlParams(node.description, nodePath, errors)
                }
                is HlxNode.Observe, is HlxNode.Transform -> {
                    // 추가 타입별 검증 없음
                }
            }
        }
    }

    /**
     * ACT 노드 description에서 URL을 추출하고,
     * query param이 허용 목록에 없으면 오류 추가
     */
    private fun validateActUrlParams(
        description: String,
        nodePath: String,
        errors: MutableList<HlxValidationError>
    ) {
        val urls = URL_PATTERN.findAll(description)
        for (urlMatch in urls) {
            val urlStr = urlMatch.value
            val queryString = try {
                URI(urlStr).query
            } catch (_: Exception) {
                continue
            } ?: continue

            val paramNames = queryString.split('&').mapNotNull { part ->
                part.split('=').firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
            }

            for (param in paramNames) {
                // 템플릿 변수 ({variable}) 는 허용
                if (param.startsWith("{")) continue
                if (param !in ALLOWED_QUERY_PARAMS) {
                    errors.add(
                        HlxValidationError(
                            "$nodePath.url",
                            "Unknown query param '$param' in URL: $urlStr. Allowed: $ALLOWED_QUERY_PARAMS"
                        )
                    )
                }
            }
        }
    }
}
