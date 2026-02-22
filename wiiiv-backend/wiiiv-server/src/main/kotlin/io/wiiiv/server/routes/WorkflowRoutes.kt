package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import kotlinx.serialization.Serializable

/**
 * Workflow Routes — 저장된 워크플로우 관리 API
 *
 * GET /saved-workflows             저장된 워크플로우 목록
 * GET /saved-workflows/{name}      이름으로 워크플로우 조회
 * DELETE /saved-workflows/{id}     워크플로우 삭제
 */
fun Route.workflowRoutes() {
    route("/saved-workflows") {
        authenticate("auth-jwt", "auth-apikey", strategy = AuthenticationStrategy.FirstSuccessful) {

            // GET /workflows — 목록
            get {
                val governor = WiiivRegistry.conversationalGovernor
                val workflows = governor.listWorkflows()
                call.respond(
                    ApiResponse.success(
                        workflows.map { it.toDto() }
                    )
                )
            }

            // GET /workflows/{name} — 이름으로 조회
            get("/{name}") {
                val name = call.parameters["name"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("BAD_REQUEST", "name parameter required")
                )

                val governor = WiiivRegistry.conversationalGovernor
                val record = governor.loadWorkflow(name)

                if (record == null) {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("NOT_FOUND", "Workflow not found: $name")
                    )
                } else {
                    call.respond(ApiResponse.success(record.toDto()))
                }
            }

            // DELETE /workflows/{id} — 삭제
            delete("/{id}") {
                val id = call.parameters["id"] ?: return@delete call.respond(
                    HttpStatusCode.BadRequest,
                    ApiError("BAD_REQUEST", "id parameter required")
                )

                val deleted = WiiivRegistry.workflowStore?.delete(id) ?: false
                if (deleted) {
                    call.respond(ApiResponse.success(mapOf("deleted" to true)))
                } else {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ApiError("NOT_FOUND", "Workflow not found: $id")
                    )
                }
            }
        }
    }
}

@Serializable
data class WorkflowDto(
    val workflowId: String,
    val name: String,
    val description: String? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val projectId: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val workflowJson: String? = null
)

private fun io.wiiiv.hlx.store.WorkflowRecord.toDto() = WorkflowDto(
    workflowId = workflowId,
    name = name,
    description = description,
    sessionId = sessionId,
    userId = userId,
    projectId = projectId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    workflowJson = workflowJson
)
