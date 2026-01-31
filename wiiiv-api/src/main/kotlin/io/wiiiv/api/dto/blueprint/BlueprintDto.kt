package io.wiiiv.api.dto.blueprint

import kotlinx.serialization.Serializable

/**
 * Blueprint - 판단의 고정
 *
 * Blueprint는 Governor가 생성한 실행 계획이다.
 * 실행 전에 조회하고 검증할 수 있다.
 */
@Serializable
data class BlueprintResponse(
    val id: String,
    val decisionId: String,
    val status: BlueprintStatus,
    val structure: BlueprintStructure,
    val createdAt: String,
    val updatedAt: String? = null
)

@Serializable
enum class BlueprintStatus {
    DRAFT,
    APPROVED,
    EXECUTED,
    FAILED,
    CANCELLED
}

@Serializable
data class BlueprintStructure(
    val nodes: List<BlueprintNode>,
    val edges: List<BlueprintEdge>
)

@Serializable
data class BlueprintNode(
    val id: String,
    val type: String,
    val config: Map<String, String>? = null,
    val dependsOn: List<String>? = null
)

@Serializable
data class BlueprintEdge(
    val from: String,
    val to: String,
    val condition: String? = null
)

/**
 * Blueprint List Response
 */
@Serializable
data class BlueprintListResponse(
    val blueprints: List<BlueprintSummary>,
    val total: Int,
    val page: Int,
    val pageSize: Int
)

@Serializable
data class BlueprintSummary(
    val id: String,
    val decisionId: String,
    val status: BlueprintStatus,
    val nodeCount: Int,
    val createdAt: String
)

/**
 * Blueprint Validation Response
 */
@Serializable
data class ValidationResponse(
    val blueprintId: String,
    val valid: Boolean,
    val errors: List<String>,
    val warnings: List<String>
)
