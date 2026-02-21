package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.audit.AuditFilter
import io.wiiiv.server.dto.audit.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import java.time.Instant

/**
 * Audit Routes - 감사 기록 조회 API
 *
 * GET  /api/v2/audit          목록 (필터: userId, role, status, executionPath, from/to, limit/offset)
 * GET  /api/v2/audit/stats    통계 (total, completed, failed, 경로별)
 * GET  /api/v2/audit/{auditId} 상세 (JSON trace 포함)
 */
fun Route.auditRoutes() {
    route("/audit") {
        authenticate("auth-jwt", "auth-apikey", strategy = AuthenticationStrategy.FirstSuccessful) {
            // GET /audit — 목록
            get {
                val store = WiiivRegistry.auditStore
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<AuditListResponse>(
                            ApiError(code = "AUDIT_UNAVAILABLE", message = "Audit store not initialized")
                        )
                    )

                val filter = AuditFilter(
                    userId = call.parameters["userId"],
                    role = call.parameters["role"],
                    status = call.parameters["status"],
                    executionPath = call.parameters["executionPath"],
                    sessionId = call.parameters["sessionId"],
                    workflowId = call.parameters["workflowId"],
                    projectId = call.parameters["projectId"]?.toLongOrNull(),
                    from = call.parameters["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    to = call.parameters["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() },
                    limit = call.parameters["limit"]?.toIntOrNull() ?: 50,
                    offset = call.parameters["offset"]?.toIntOrNull() ?: 0
                )

                val records = store.findAll(filter)
                call.respond(
                    ApiResponse.success(
                        AuditListResponse(
                            records = records.map { it.toSummaryDto() },
                            total = records.size
                        )
                    )
                )
            }

            // GET /audit/stats — 통계
            get("/stats") {
                val store = WiiivRegistry.auditStore
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<AuditStatsResponse>(
                            ApiError(code = "AUDIT_UNAVAILABLE", message = "Audit store not initialized")
                        )
                    )

                val stats = store.stats()
                call.respond(ApiResponse.success(stats.toDto()))
            }

            // GET /audit/{auditId} — 상세
            get("/{auditId}") {
                val store = WiiivRegistry.auditStore
                    ?: return@get call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        ApiResponse.error<AuditDetailDto>(
                            ApiError(code = "AUDIT_UNAVAILABLE", message = "Audit store not initialized")
                        )
                    )

                val auditId = call.parameters["auditId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<AuditDetailDto>(
                            ApiError(code = "MISSING_PARAM", message = "auditId required")
                        )
                    )

                val record = store.findById(auditId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<AuditDetailDto>(
                            ApiError(code = "NOT_FOUND", message = "Audit record not found: $auditId")
                        )
                    )

                call.respond(ApiResponse.success(record.toDetailDto()))
            }
        }
    }
}
