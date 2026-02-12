package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.dto.blueprint.*
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.blueprint.Blueprint
import java.time.Instant

/**
 * Blueprint Routes - 실행 계획 조회
 *
 * GET  /api/v2/blueprints/{id} - Blueprint 상세 조회
 * GET  /api/v2/blueprints - Blueprint 목록 조회
 *
 * Blueprint는 Governor가 생성한다.
 * 여기서는 조회만 한다.
 */
fun Route.blueprintRoutes() {
    route("/blueprints") {
        authenticate("auth-jwt") {
            // List blueprints
            get {
                val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 1
                val pageSize = call.request.queryParameters["pageSize"]?.toIntOrNull() ?: 20

                val allBlueprints = WiiivRegistry.listBlueprints()
                val startIndex = (page - 1) * pageSize
                val endIndex = minOf(startIndex + pageSize, allBlueprints.size)
                val pagedBlueprints = if (startIndex < allBlueprints.size) {
                    allBlueprints.subList(startIndex, endIndex)
                } else {
                    emptyList()
                }

                call.respond(
                    ApiResponse.success(
                        BlueprintListResponse(
                            blueprints = pagedBlueprints.map { it.toSummary() },
                            total = allBlueprints.size,
                            page = page,
                            pageSize = pageSize
                        )
                    )
                )
            }

            // Get blueprint by ID
            get("/{id}") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Blueprint ID required")

                val blueprint = WiiivRegistry.getBlueprint(id)
                    ?: throw NoSuchElementException("Blueprint not found: $id")

                call.respond(
                    ApiResponse.success(blueprint.toResponse())
                )
            }

            // Validate blueprint (dry-run check)
            post("/{id}/validate") {
                val id = call.parameters["id"]
                    ?: throw IllegalArgumentException("Blueprint ID required")

                val blueprint = WiiivRegistry.getBlueprint(id)
                    ?: throw NoSuchElementException("Blueprint not found: $id")

                // 기본적인 유효성 검사
                val errors = mutableListOf<String>()
                val warnings = mutableListOf<String>()

                if (blueprint.steps.isEmpty()) {
                    errors.add("Blueprint has no steps")
                }

                blueprint.steps.forEach { step ->
                    if (step.stepId.isBlank()) {
                        errors.add("Step has empty ID")
                    }
                }

                call.respond(
                    ApiResponse.success(
                        ValidationResponse(
                            blueprintId = id,
                            valid = errors.isEmpty(),
                            errors = errors,
                            warnings = warnings
                        )
                    )
                )
            }
        }
    }
}

// Extension functions for conversion
private fun Blueprint.toSummary() = BlueprintSummary(
    id = id,
    decisionId = specSnapshot.specId,
    status = BlueprintStatus.APPROVED,
    nodeCount = steps.size,
    createdAt = metadata.createdAt
)

private fun Blueprint.toResponse() = BlueprintResponse(
    id = id,
    decisionId = specSnapshot.specId,
    status = BlueprintStatus.APPROVED,
    structure = BlueprintStructure(
        nodes = steps.mapIndexed { index, step ->
            BlueprintNode(
                id = step.stepId,
                type = step.type.name,
                config = step.params,
                dependsOn = if (index > 0) listOf(steps[index - 1].stepId) else null
            )
        },
        edges = steps.zipWithNext().map { (from, to) ->
            BlueprintEdge(from = from.stepId, to = to.stepId)
        }
    ),
    createdAt = metadata.createdAt,
    updatedAt = null
)
