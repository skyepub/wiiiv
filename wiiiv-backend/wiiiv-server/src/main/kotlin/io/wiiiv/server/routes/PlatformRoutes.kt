package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.util.pipeline.*
import io.wiiiv.platform.model.ProjectRole
import io.wiiiv.server.config.JwtConfig
import io.wiiiv.server.config.UserPrincipal
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.platform.*
import io.wiiiv.server.registry.WiiivRegistry
import org.mindrot.jbcrypt.BCrypt

/**
 * Platform Routes — User, Project, Membership, API Key 관리
 *
 * POST   /platform/register                     회원가입
 * POST   /platform/login                        로그인 → JWT
 * GET    /platform/me                           내 정보
 *
 * POST   /platform/projects                     프로젝트 생성
 * GET    /platform/projects                     내 프로젝트 목록
 * GET    /platform/projects/{id}                프로젝트 상세
 * PUT    /platform/projects/{id}                프로젝트 수정 (OWNER)
 * DELETE /platform/projects/{id}                프로젝트 삭제 (원 owner만)
 *
 * POST   /platform/projects/{id}/members        멤버 추가 (OWNER)
 * GET    /platform/projects/{id}/members        멤버 목록
 * PUT    /platform/projects/{id}/members/{uid}  역할 변경 (OWNER)
 * DELETE /platform/projects/{id}/members/{uid}  멤버 제거 (OWNER)
 *
 * POST   /platform/projects/{id}/api-keys       API Key 생성
 * GET    /platform/projects/{id}/api-keys       API Key 목록
 * DELETE /platform/api-keys/{keyId}             API Key 취소
 */
fun Route.platformRoutes() {
    route("/platform") {

        // ════════════════════════════════════════
        //  Auth (비인증)
        // ════════════════════════════════════════

        post("/register") {
            val store = requirePlatformStore() ?: return@post
            val req = call.receive<RegisterRequest>()

            if (req.email.isBlank() || req.password.length < 8 || req.displayName.isBlank()) {
                return@post call.respond(
                    HttpStatusCode.BadRequest,
                    ApiResponse.error<Unit>(ApiError("INVALID_INPUT", "email, password(8+), displayName required"))
                )
            }

            if (store.findUserByEmail(req.email) != null) {
                return@post call.respond(
                    HttpStatusCode.Conflict,
                    ApiResponse.error<Unit>(ApiError("EMAIL_EXISTS", "Email already registered"))
                )
            }

            val hash = BCrypt.hashpw(req.password, BCrypt.gensalt(12))
            val user = store.createUser(req.email, req.displayName, hash)
            val token = JwtConfig.generateToken(user.userId.toString(), listOf("user"))

            call.respond(
                HttpStatusCode.Created,
                ApiResponse.success(AuthResponse(token, user.userId, user.email, user.displayName))
            )
        }

        post("/login") {
            val store = requirePlatformStore() ?: return@post
            val req = call.receive<LoginRequest>()

            val user = store.findUserByEmail(req.email)
            if (user == null || !user.isActive || !BCrypt.checkpw(req.password, user.passwordHash)) {
                return@post call.respond(
                    HttpStatusCode.Unauthorized,
                    ApiResponse.error<Unit>(ApiError("INVALID_CREDENTIALS", "Invalid email or password"))
                )
            }

            val token = JwtConfig.generateToken(user.userId.toString(), listOf("user"))
            call.respond(
                ApiResponse.success(AuthResponse(token, user.userId, user.email, user.displayName))
            )
        }

        // ════════════════════════════════════════
        //  인증 필요 구간
        // ════════════════════════════════════════

        authenticate("auth-jwt", "auth-apikey", strategy = AuthenticationStrategy.FirstSuccessful) {

            // ── 내 정보 ──
            get("/me") {
                val store = requirePlatformStore() ?: return@get
                val userId = currentUserId() ?: return@get
                val user = store.findUserById(userId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<Unit>(ApiError("USER_NOT_FOUND", "User not found"))
                    )
                call.respond(ApiResponse.success(user.toDto()))
            }

            // ════════════════════════════════════
            //  Project CRUD
            // ════════════════════════════════════

            route("/projects") {

                // POST /projects — 생성
                post {
                    val store = requirePlatformStore() ?: return@post
                    val userId = currentUserId() ?: return@post
                    val req = call.receive<CreateProjectRequest>()

                    if (req.name.isBlank()) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ApiResponse.error<Unit>(ApiError("INVALID_INPUT", "Project name required"))
                        )
                    }

                    val project = store.createProject(req.name, req.description, userId, req.defaultModel)
                    call.respond(HttpStatusCode.Created, ApiResponse.success(project.toDto()))
                }

                // GET /projects — 내 프로젝트 목록
                get {
                    val store = requirePlatformStore() ?: return@get
                    val userId = currentUserId() ?: return@get
                    val projects = store.listProjectsForUser(userId)
                    call.respond(
                        ApiResponse.success(ProjectListResponse(projects.map { it.toDto() }, projects.size))
                    )
                }

                // ── 개별 프로젝트 ──
                route("/{projectId}") {

                    // GET /projects/{id}
                    get {
                        val store = requirePlatformStore() ?: return@get
                        val userId = currentUserId() ?: return@get
                        val projectId = projectIdParam() ?: return@get

                        if (!store.isMember(projectId, userId)) {
                            return@get call.respond(
                                HttpStatusCode.Forbidden,
                                ApiResponse.error<Unit>(ApiError("NOT_MEMBER", "You are not a member of this project"))
                            )
                        }

                        val project = store.findProjectById(projectId)
                            ?: return@get call.respond(
                                HttpStatusCode.NotFound,
                                ApiResponse.error<Unit>(ApiError("NOT_FOUND", "Project not found"))
                            )
                        call.respond(ApiResponse.success(project.toDto()))
                    }

                    // PUT /projects/{id} — 수정 (OWNER만)
                    put {
                        val store = requirePlatformStore() ?: return@put
                        val userId = currentUserId() ?: return@put
                        val projectId = projectIdParam() ?: return@put
                        requireRole(store, projectId, userId, ProjectRole.OWNER) ?: return@put

                        val req = call.receive<UpdateProjectRequest>()
                        store.updateProject(projectId, req.name, req.description, req.defaultModel)
                        val updated = store.findProjectById(projectId)!!
                        call.respond(ApiResponse.success(updated.toDto()))
                    }

                    // DELETE /projects/{id} — 삭제 (원 owner만)
                    delete {
                        val store = requirePlatformStore() ?: return@delete
                        val userId = currentUserId() ?: return@delete
                        val projectId = projectIdParam() ?: return@delete

                        if (!store.deleteProject(projectId, userId)) {
                            return@delete call.respond(
                                HttpStatusCode.Forbidden,
                                ApiResponse.error<Unit>(ApiError("FORBIDDEN", "Only the original owner can delete this project"))
                            )
                        }
                        call.respond(ApiResponse.success(mapOf("deleted" to true)))
                    }

                    // ════════════════════════════
                    //  Members
                    // ════════════════════════════

                    route("/members") {

                        // GET /projects/{id}/members
                        get {
                            val store = requirePlatformStore() ?: return@get
                            val userId = currentUserId() ?: return@get
                            val projectId = projectIdParam() ?: return@get

                            if (!store.isMember(projectId, userId)) {
                                return@get call.respond(
                                    HttpStatusCode.Forbidden,
                                    ApiResponse.error<Unit>(ApiError("NOT_MEMBER", "Not a member"))
                                )
                            }

                            val members = store.listMembers(projectId)
                            val dtos = members.mapNotNull { m ->
                                val user = store.findUserById(m.userId) ?: return@mapNotNull null
                                MemberDto(m.userId, user.email, user.displayName, m.role.name, m.joinedAt)
                            }
                            call.respond(ApiResponse.success(MemberListResponse(dtos, dtos.size)))
                        }

                        // POST /projects/{id}/members — 멤버 추가 (OWNER)
                        post {
                            val store = requirePlatformStore() ?: return@post
                            val userId = currentUserId() ?: return@post
                            val projectId = projectIdParam() ?: return@post
                            requireRole(store, projectId, userId, ProjectRole.OWNER) ?: return@post

                            val req = call.receive<AddMemberRequest>()
                            val role = runCatching { ProjectRole.valueOf(req.role.uppercase()) }.getOrNull()
                                ?: return@post call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiResponse.error<Unit>(ApiError("INVALID_ROLE", "Role must be OWNER, MEMBER, or VIEWER"))
                                )

                            val targetUser = store.findUserByEmail(req.email)
                                ?: return@post call.respond(
                                    HttpStatusCode.NotFound,
                                    ApiResponse.error<Unit>(ApiError("USER_NOT_FOUND", "User not found: ${req.email}"))
                                )

                            if (store.isMember(projectId, targetUser.userId)) {
                                return@post call.respond(
                                    HttpStatusCode.Conflict,
                                    ApiResponse.error<Unit>(ApiError("ALREADY_MEMBER", "User is already a member"))
                                )
                            }

                            val member = store.addMember(projectId, targetUser.userId, role)
                            call.respond(
                                HttpStatusCode.Created,
                                ApiResponse.success(
                                    MemberDto(member.userId, targetUser.email, targetUser.displayName, member.role.name, member.joinedAt)
                                )
                            )
                        }

                        // PUT /projects/{id}/members/{uid} — 역할 변경 (OWNER)
                        put("/{targetUserId}") {
                            val store = requirePlatformStore() ?: return@put
                            val userId = currentUserId() ?: return@put
                            val projectId = projectIdParam() ?: return@put
                            requireRole(store, projectId, userId, ProjectRole.OWNER) ?: return@put

                            val targetUserId = call.parameters["targetUserId"]?.toLongOrNull()
                                ?: return@put call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiResponse.error<Unit>(ApiError("INVALID_PARAM", "Invalid targetUserId"))
                                )

                            val req = call.receive<UpdateMemberRoleRequest>()
                            val role = runCatching { ProjectRole.valueOf(req.role.uppercase()) }.getOrNull()
                                ?: return@put call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiResponse.error<Unit>(ApiError("INVALID_ROLE", "Role must be OWNER, MEMBER, or VIEWER"))
                                )

                            store.updateMemberRole(projectId, targetUserId, role)
                            call.respond(ApiResponse.success(mapOf("updated" to true)))
                        }

                        // DELETE /projects/{id}/members/{uid} — 멤버 제거 (OWNER)
                        delete("/{targetUserId}") {
                            val store = requirePlatformStore() ?: return@delete
                            val userId = currentUserId() ?: return@delete
                            val projectId = projectIdParam() ?: return@delete
                            requireRole(store, projectId, userId, ProjectRole.OWNER) ?: return@delete

                            val targetUserId = call.parameters["targetUserId"]?.toLongOrNull()
                                ?: return@delete call.respond(
                                    HttpStatusCode.BadRequest,
                                    ApiResponse.error<Unit>(ApiError("INVALID_PARAM", "Invalid targetUserId"))
                                )

                            if (!store.removeMember(projectId, targetUserId)) {
                                return@delete call.respond(
                                    HttpStatusCode.Forbidden,
                                    ApiResponse.error<Unit>(ApiError("CANNOT_REMOVE", "Cannot remove the original project owner"))
                                )
                            }
                            call.respond(ApiResponse.success(mapOf("removed" to true)))
                        }
                    }

                    // ════════════════════════════
                    //  Policy (F5-F6)
                    // ════════════════════════════

                    get("/policy") {
                        val store = requirePlatformStore() ?: return@get
                        val userId = currentUserId() ?: return@get
                        val projectId = projectIdParam() ?: return@get
                        if (!store.isMember(projectId, userId)) {
                            return@get call.respond(HttpStatusCode.Forbidden,
                                ApiResponse.error<Unit>(ApiError("NOT_MEMBER", "Not a member")))
                        }
                        val policy = store.getPolicy(projectId)
                            ?: return@get call.respond(HttpStatusCode.NotFound,
                                ApiResponse.error<Unit>(ApiError("NOT_FOUND", "Policy not found")))
                        call.respond(ApiResponse.success(policy.toDto()))
                    }

                    put("/policy") {
                        val store = requirePlatformStore() ?: return@put
                        val userId = currentUserId() ?: return@put
                        val projectId = projectIdParam() ?: return@put
                        requireRole(store, projectId, userId, ProjectRole.OWNER) ?: return@put
                        val req = call.receive<UpdatePolicyRequest>()
                        val current = store.getPolicy(projectId)
                            ?: return@put call.respond(HttpStatusCode.NotFound,
                                ApiResponse.error<Unit>(ApiError("NOT_FOUND", "Policy not found")))
                        val updated = current.copy(
                            allowedStepTypes = req.allowedStepTypes ?: current.allowedStepTypes,
                            allowedPlugins = req.allowedPlugins ?: current.allowedPlugins,
                            maxRequestsPerDay = req.maxRequestsPerDay,
                            updatedAt = java.time.Instant.now().toString()
                        )
                        store.upsertPolicy(updated)
                        call.respond(ApiResponse.success(updated.toDto()))
                    }

                    // ════════════════════════════
                    //  API Keys
                    // ════════════════════════════

                    route("/api-keys") {

                        // POST /projects/{id}/api-keys — 키 생성
                        post {
                            val store = requirePlatformStore() ?: return@post
                            val userId = currentUserId() ?: return@post
                            val projectId = projectIdParam() ?: return@post

                            if (!store.isMember(projectId, userId)) {
                                return@post call.respond(
                                    HttpStatusCode.Forbidden,
                                    ApiResponse.error<Unit>(ApiError("NOT_MEMBER", "Not a member"))
                                )
                            }

                            val req = call.receive<CreateApiKeyRequest>()
                            val (rawKey, record) = store.createApiKey(userId, projectId, req.label, req.expiresAt)
                            call.respond(HttpStatusCode.Created, ApiResponse.success(record.toDto(rawKey)))
                        }

                        // GET /projects/{id}/api-keys — 키 목록
                        get {
                            val store = requirePlatformStore() ?: return@get
                            val userId = currentUserId() ?: return@get
                            val projectId = projectIdParam() ?: return@get

                            if (!store.isMember(projectId, userId)) {
                                return@get call.respond(
                                    HttpStatusCode.Forbidden,
                                    ApiResponse.error<Unit>(ApiError("NOT_MEMBER", "Not a member"))
                                )
                            }

                            val keys = store.listApiKeys(projectId)
                            call.respond(
                                ApiResponse.success(
                                    ApiKeyListResponse(keys.map { it.toDto() }, keys.size)
                                )
                            )
                        }
                    }
                }
            }

            // DELETE /platform/api-keys/{keyId} — 키 취소
            delete("/api-keys/{keyId}") {
                val store = requirePlatformStore() ?: return@delete
                val keyId = call.parameters["keyId"]?.toLongOrNull()
                    ?: return@delete call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<Unit>(ApiError("INVALID_PARAM", "Invalid keyId"))
                    )

                store.revokeApiKey(keyId)
                call.respond(ApiResponse.success(mapOf("revoked" to true)))
            }
        }
    }
}

// ════════════════════════════════════════════
//  Helper extensions
// ════════════════════════════════════════════

private suspend fun PipelineContext<Unit, ApplicationCall>.requirePlatformStore(): io.wiiiv.platform.store.PlatformStore? {
    val store = WiiivRegistry.platformStore
    if (store == null) {
        call.respond(
            HttpStatusCode.ServiceUnavailable,
            ApiResponse.error<Unit>(ApiError("PLATFORM_UNAVAILABLE", "Platform store not initialized"))
        )
    }
    return store
}

private suspend fun PipelineContext<Unit, ApplicationCall>.currentUserId(): Long? {
    val principal = call.principal<UserPrincipal>()
    val userId = principal?.userId?.toLongOrNull()
    if (userId == null) {
        call.respond(
            HttpStatusCode.Unauthorized,
            ApiResponse.error<Unit>(ApiError("INVALID_TOKEN", "Cannot extract userId from token"))
        )
    }
    return userId
}

private suspend fun PipelineContext<Unit, ApplicationCall>.projectIdParam(): Long? {
    val id = call.parameters["projectId"]?.toLongOrNull()
    if (id == null) {
        call.respond(
            HttpStatusCode.BadRequest,
            ApiResponse.error<Unit>(ApiError("INVALID_PARAM", "Invalid projectId"))
        )
    }
    return id
}

private suspend fun PipelineContext<Unit, ApplicationCall>.requireRole(
    store: io.wiiiv.platform.store.PlatformStore,
    projectId: Long,
    userId: Long,
    requiredRole: ProjectRole
): ProjectRole? {
    val member = store.findMember(projectId, userId)
    if (member == null) {
        call.respond(
            HttpStatusCode.Forbidden,
            ApiResponse.error<Unit>(ApiError("NOT_MEMBER", "Not a member of this project"))
        )
        return null
    }
    if (requiredRole == ProjectRole.OWNER && !member.role.canManage()) {
        call.respond(
            HttpStatusCode.Forbidden,
            ApiResponse.error<Unit>(ApiError("INSUFFICIENT_ROLE", "OWNER role required"))
        )
        return null
    }
    return member.role
}
