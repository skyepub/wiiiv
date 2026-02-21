package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import io.wiiiv.audit.AuditFilter
import io.wiiiv.audit.AuditRecordFactory
import io.wiiiv.audit.ExecutionPath
import io.wiiiv.config.AuthType
import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.hlx.parser.HlxParseResult
import io.wiiiv.hlx.parser.HlxParser
import io.wiiiv.platform.model.ProjectRole
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.audit.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.hlx.*
import io.wiiiv.server.dto.session.*
import io.wiiiv.server.registry.HlxExecutionEntry
import io.wiiiv.server.registry.HlxWorkflowEntry
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.server.dto.platform.ProjectUsageResponse
import io.wiiiv.server.dto.platform.toDto
import io.wiiiv.server.policy.ProjectPolicyChecker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/**
 * Project-Scoped Routes — F-4 핵심
 *
 * /api/v2/projects/{projectId}/sessions    프로젝트 스코프 세션
 * /api/v2/projects/{projectId}/workflows   프로젝트 스코프 워크플로우
 * /api/v2/projects/{projectId}/audit       프로젝트 스코프 감사 로그
 *
 * 모든 핸들러는 requireProjectAccess()로 멤버십 검증 후 실행.
 */
fun Route.projectScopedRoutes() {
    route("/projects/{projectId}") {
        authenticate("auth-jwt", "auth-apikey", strategy = AuthenticationStrategy.FirstSuccessful) {
            projectSessionRoutes()
            projectHlxRoutes()
            projectAuditRoutes()
        }
    }
}

// ── 멤버십 검증 ──

data class ProjectAccessContext(
    val principal: UserPrincipal,
    val projectId: Long,
    val projectRole: ProjectRole
)

private fun errorResponse(code: String, message: String): ApiResponse<Unit> =
    ApiResponse.error(ApiError(code = code, message = message))

/**
 * 프로젝트 접근 검증
 *
 * 1. URL에서 projectId 추출
 * 2. API_KEY면 key.projectId == URL projectId 교차 검증
 * 3. PlatformStore에서 멤버십 조회
 * 4. 요구 능력(canExecute/canView) 확인
 *
 * @param requireExecute true면 VIEWER 거부 (POST/실행용)
 */
private suspend fun PipelineContext<Unit, ApplicationCall>.requireProjectAccess(
    requireExecute: Boolean = false
): ProjectAccessContext? {
    val principal = call.principal<UserPrincipal>()!!
    val projectId = call.parameters["projectId"]?.toLongOrNull()
    if (projectId == null) {
        call.respond(HttpStatusCode.BadRequest, errorResponse("INVALID_PROJECT_ID", "projectId must be a number"))
        return null
    }

    // API_KEY 교차 검증: key의 projectId ≠ URL의 projectId → 403
    if (principal.authType == AuthType.API_KEY && principal.projectId != projectId) {
        call.respond(HttpStatusCode.Forbidden, errorResponse("PROJECT_MISMATCH", "API key does not belong to this project"))
        return null
    }

    val store = WiiivRegistry.platformStore
    if (store == null) {
        call.respond(HttpStatusCode.ServiceUnavailable, errorResponse("PLATFORM_UNAVAILABLE", "Platform store not initialized"))
        return null
    }

    val userId = principal.userId.toLongOrNull()
    if (userId == null) {
        call.respond(HttpStatusCode.Forbidden, errorResponse("INVALID_USER", "User ID is not numeric"))
        return null
    }

    val member = store.findMember(projectId, userId)
    if (member == null) {
        call.respond(HttpStatusCode.Forbidden, errorResponse("NOT_MEMBER", "Not a member of this project"))
        return null
    }

    if (requireExecute && !member.role.canExecute()) {
        call.respond(HttpStatusCode.Forbidden, errorResponse("INSUFFICIENT_ROLE", "VIEWER cannot execute"))
        return null
    }

    return ProjectAccessContext(principal, projectId, member.role)
}

// ── 프로젝트 스코프 세션 ──

private fun Route.projectSessionRoutes() {
    route("/sessions") {
        // POST — 프로젝트 스코프 세션 생성
        post {
            val ctx = requireProjectAccess(requireExecute = true) ?: return@post
            val request = try { call.receive<CreateSessionRequest>() } catch (_: Exception) { CreateSessionRequest() }

            val session = WiiivRegistry.sessionManager.createSession(
                ctx.principal.userId,
                ctx.projectRole.name,
                ctx.projectId
            )
            request.workspace?.let { session.context.workspace = it }
            val info = WiiivRegistry.sessionManager.getSessionInfo(session.sessionId)!!

            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(
                    SessionResponse(
                        sessionId = info.sessionId,
                        userId = info.userId,
                        createdAt = info.createdAt,
                        projectId = info.projectId
                    )
                )
            )
        }

        // GET — 프로젝트 내 세션 목록
        get {
            val ctx = requireProjectAccess() ?: return@get
            val sessions = WiiivRegistry.sessionManager.listUserSessions(ctx.principal.userId, ctx.projectId)

            call.respond(
                ApiResponse.success(
                    SessionListResponse(
                        sessions = sessions.map {
                            SessionResponse(it.sessionId, it.userId, it.createdAt, projectId = it.projectId)
                        },
                        total = sessions.size
                    )
                )
            )
        }

        // POST /{id}/chat — 프로젝트 스코프 채팅
        post("/{id}/chat") {
            val ctx = requireProjectAccess(requireExecute = true) ?: return@post

            // F5-F6: 일일 요청 한도 체크
            val policyCheck = ProjectPolicyChecker.checkDailyLimit(
                ctx.projectId, WiiivRegistry.platformStore, WiiivRegistry.auditStore
            )
            if (!policyCheck.allowed) {
                call.respond(HttpStatusCode.TooManyRequests,
                    errorResponse("POLICY_LIMIT_EXCEEDED", policyCheck.message ?: "Request denied"))
                return@post
            }

            val sessionId = call.parameters["id"]
                ?: throw IllegalArgumentException("Session ID required")

            // 세션 소유권 + 프로젝트 소속 검증
            val sessionInfo = WiiivRegistry.sessionManager.getSessionInfo(sessionId)
            if (sessionInfo == null || sessionInfo.userId != ctx.principal.userId
                || sessionInfo.projectId != ctx.projectId
            ) {
                call.respond(HttpStatusCode.Forbidden, errorResponse("SESSION_NOT_IN_PROJECT", "Session does not belong to this project"))
                return@post
            }
            WiiivRegistry.sessionManager.getSession(sessionId)
                ?: throw NoSuchElementException("Session not found: $sessionId")

            val request = try {
                call.receive<ChatRequest>()
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid chat request: ${e.message}")
            }

            val images = request.images?.map { img ->
                LlmImage(
                    data = Base64.getDecoder().decode(img.base64),
                    mimeType = img.mimeType
                )
            } ?: emptyList()
            val maxContinue = request.maxContinue.coerceIn(1, 20)

            handleChatSse(call, sessionId, ctx.principal, request, images, maxContinue)
        }
    }
}

// ── 프로젝트 스코프 HLX 워크플로우 ──

private fun Route.projectHlxRoutes() {
    route("/workflows") {
        // POST — 프로젝트 스코프 워크플로우 등록
        post {
            val ctx = requireProjectAccess(requireExecute = true) ?: return@post
            val request = call.receive<HlxWorkflowCreateRequest>()
            val result = HlxParser.parseAndValidate(request.workflow)

            when (result) {
                is HlxParseResult.ParseError -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<HlxWorkflowResponse>(
                            ApiError(code = "PARSE_ERROR", message = result.message)
                        )
                    )
                }
                is HlxParseResult.ValidationError -> {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<HlxWorkflowResponse>(
                            ApiError(code = "VALIDATION_ERROR", message = result.errors.joinToString("; ") { it.message })
                        )
                    )
                }
                is HlxParseResult.Success -> {
                    val workflow = result.workflow
                    val entry = HlxWorkflowEntry(
                        id = workflow.id,
                        workflow = workflow,
                        rawJson = request.workflow,
                        createdAt = Instant.now().toString(),
                        projectId = ctx.projectId
                    )
                    WiiivRegistry.storeHlxWorkflow(entry)

                    call.respond(
                        HttpStatusCode.Created,
                        ApiResponse.success(
                            HlxWorkflowResponse(
                                id = workflow.id,
                                name = workflow.name,
                                description = workflow.description,
                                version = workflow.version,
                                nodeCount = workflow.nodes.size,
                                createdAt = entry.createdAt
                            )
                        )
                    )
                }
            }
        }

        // GET — 프로젝트 내 워크플로우 목록
        get {
            val ctx = requireProjectAccess() ?: return@get
            val entries = WiiivRegistry.listHlxWorkflows(ctx.projectId)

            call.respond(
                ApiResponse.success(
                    HlxWorkflowListResponse(
                        workflows = entries.map { entry ->
                            HlxWorkflowResponse(
                                id = entry.id,
                                name = entry.workflow.name,
                                description = entry.workflow.description,
                                version = entry.workflow.version,
                                nodeCount = entry.workflow.nodes.size,
                                createdAt = entry.createdAt
                            )
                        },
                        total = entries.size
                    )
                )
            )
        }

        // POST /{id}/execute — 프로젝트 스코프 워크플로우 실행
        post("/{id}/execute") {
            val ctx = requireProjectAccess(requireExecute = true) ?: return@post

            // F5-F6: 일일 요청 한도 체크
            val policyCheck = ProjectPolicyChecker.checkDailyLimit(
                ctx.projectId, WiiivRegistry.platformStore, WiiivRegistry.auditStore
            )
            if (!policyCheck.allowed) {
                call.respond(HttpStatusCode.TooManyRequests,
                    errorResponse("POLICY_LIMIT_EXCEEDED", policyCheck.message ?: "Request denied"))
                return@post
            }

            val id = call.parameters["id"]!!

            val entry = WiiivRegistry.getHlxWorkflowScoped(id, ctx.projectId)
            if (entry == null) {
                call.respond(
                    HttpStatusCode.NotFound,
                    ApiResponse.error<HlxExecutionResponse>(
                        ApiError(code = "NOT_FOUND", message = "Workflow not found in this project: $id")
                    )
                )
                return@post
            }

            val runner = WiiivRegistry.hlxRunner
            if (runner == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<HlxExecutionResponse>(
                        ApiError(code = "LLM_UNAVAILABLE", message = "HLX Runner not available (no LLM provider)")
                    )
                )
                return@post
            }

            val request = call.receive<HlxExecuteRequest>()
            val role = request.role ?: ctx.projectRole.name

            val hlxResult = runner.run(
                workflow = entry.workflow,
                initialVariables = request.variables,
                userId = ctx.principal.userId,
                role = role
            )

            val executionId = UUID.randomUUID().toString()
            val execEntry = HlxExecutionEntry(
                executionId = executionId,
                workflowId = id,
                result = hlxResult,
                executedAt = Instant.now().toString(),
                projectId = ctx.projectId
            )
            WiiivRegistry.storeHlxExecution(execEntry)

            // Audit hook
            try {
                WiiivRegistry.auditStore?.insert(
                    AuditRecordFactory.fromHlxResult(
                        hlxResult = hlxResult,
                        workflow = entry.workflow,
                        executionPath = ExecutionPath.DIRECT_HLX_API,
                        userId = ctx.principal.userId,
                        role = role,
                        taskType = "DIRECT_HLX",
                        projectId = ctx.projectId
                    )
                )
            } catch (e: Exception) {
                println("[AUDIT] Failed to record project-scoped DIRECT_HLX_API: ${e.message}")
            }

            call.respond(
                ApiResponse.success(toProjectExecutionResponse(execEntry))
            )
        }
    }
}

private fun toProjectExecutionResponse(entry: HlxExecutionEntry): HlxExecutionResponse {
    val result = entry.result
    return HlxExecutionResponse(
        executionId = entry.executionId,
        workflowId = entry.workflowId,
        status = result.status.name,
        nodeRecords = result.nodeRecords.map { record ->
            HlxNodeRecordDto(
                nodeId = record.nodeId,
                nodeType = record.nodeType.name.lowercase(),
                status = record.status.name.lowercase(),
                durationMs = record.durationMs,
                error = record.error,
                selectedBranch = record.selectedBranch,
                iterationCount = record.iterationCount,
                gate = record.gate
            )
        },
        totalDurationMs = result.totalDurationMs,
        variables = result.context.variables,
        error = result.error
    )
}

// ── 프로젝트 스코프 감사 로그 ──

private fun Route.projectAuditRoutes() {
    route("/audit") {
        // GET — 프로젝트 감사 로그 (projectId 자동 필터링)
        get {
            val ctx = requireProjectAccess() ?: return@get

            val store = WiiivRegistry.auditStore
            if (store == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<AuditListResponse>(
                        ApiError(code = "AUDIT_UNAVAILABLE", message = "Audit store not initialized")
                    )
                )
                return@get
            }

            val filter = AuditFilter(
                projectId = ctx.projectId,       // 강제 필터 — 프로젝트 스코프
                userId = call.parameters["userId"],
                role = call.parameters["role"],
                status = call.parameters["status"],
                executionPath = call.parameters["executionPath"],
                sessionId = call.parameters["sessionId"],
                workflowId = call.parameters["workflowId"],
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

        // GET /usage — 프로젝트 사용량 (F5-F6)
        get("/usage") {
            val ctx = requireProjectAccess() ?: return@get
            val store = WiiivRegistry.auditStore
            if (store == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<ProjectUsageResponse>(
                        ApiError(code = "AUDIT_UNAVAILABLE", message = "Audit store not initialized")
                    )
                )
                return@get
            }
            val policy = WiiivRegistry.platformStore?.getPolicy(ctx.projectId)
            val todayStart = LocalDate.now().atStartOfDay(ZoneOffset.UTC).toInstant()
            val monthStart = LocalDate.now().withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant()
            call.respond(ApiResponse.success(ProjectUsageResponse(
                projectId = ctx.projectId,
                todayCount = store.countByProject(ctx.projectId, todayStart),
                monthCount = store.countByProject(ctx.projectId, monthStart),
                maxRequestsPerDay = policy?.maxRequestsPerDay,
                periodStart = todayStart.toString(),
                periodEnd = Instant.now().toString()
            )))
        }

        // GET /stats — 프로젝트 감사 통계
        get("/stats") {
            requireProjectAccess() ?: return@get

            val store = WiiivRegistry.auditStore
            if (store == null) {
                call.respond(
                    HttpStatusCode.ServiceUnavailable,
                    ApiResponse.error<AuditStatsResponse>(
                        ApiError(code = "AUDIT_UNAVAILABLE", message = "Audit store not initialized")
                    )
                )
                return@get
            }

            val stats = store.stats()
            call.respond(ApiResponse.success(stats.toDto()))
        }
    }
}
