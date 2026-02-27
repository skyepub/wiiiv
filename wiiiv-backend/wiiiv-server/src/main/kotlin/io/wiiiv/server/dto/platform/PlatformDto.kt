package io.wiiiv.server.dto.platform

import io.wiiiv.platform.model.*
import kotlinx.serialization.Serializable

// ════════════════════════════════════════════
//  Request DTOs
// ════════════════════════════════════════════

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class CreateProjectRequest(
    val name: String,
    val description: String? = null,
    val defaultModel: String = "gpt-4o-mini"
)

@Serializable
data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val defaultModel: String? = null
)

@Serializable
data class AddMemberRequest(
    val email: String,
    val role: String = "MEMBER"
)

@Serializable
data class UpdateMemberRoleRequest(
    val role: String
)

@Serializable
data class CreateApiKeyRequest(
    val label: String? = null,
    val expiresAt: String? = null
)

@Serializable
data class UpdatePolicyRequest(
    val allowedStepTypes: String? = null,
    val allowedPlugins: String? = null,
    val maxRequestsPerDay: Int? = null,
    val llmProvider: String? = null,
    val llmBaseUrl: String? = null,
    val governorModel: String? = null,
    val generatorModel: String? = null,
    val embeddingModel: String? = null
)

// ════════════════════════════════════════════
//  Response DTOs
// ════════════════════════════════════════════

@Serializable
data class AuthResponse(
    val accessToken: String,
    val userId: Long,
    val email: String,
    val displayName: String
)

@Serializable
data class UserDto(
    val userId: Long,
    val email: String,
    val displayName: String,
    val isActive: Boolean,
    val createdAt: String
)

@Serializable
data class ProjectDto(
    val projectId: Long,
    val name: String,
    val description: String? = null,
    val ownerUserId: Long,
    val defaultModel: String,
    val createdAt: String,
    val updatedAt: String? = null
)

@Serializable
data class ProjectListResponse(
    val projects: List<ProjectDto>,
    val total: Int
)

@Serializable
data class MemberDto(
    val userId: Long,
    val email: String,
    val displayName: String,
    val role: String,
    val joinedAt: String
)

@Serializable
data class MemberListResponse(
    val members: List<MemberDto>,
    val total: Int
)

@Serializable
data class ApiKeyResponse(
    val keyId: Long,
    val apiKey: String?,
    val prefix: String,
    val label: String? = null,
    val expiresAt: String? = null,
    val createdAt: String
)

@Serializable
data class ApiKeyListResponse(
    val keys: List<ApiKeyResponse>,
    val total: Int
)

@Serializable
data class ProjectPolicyDto(
    val projectId: Long,
    val allowedStepTypes: String,
    val allowedPlugins: String,
    val maxRequestsPerDay: Int? = null,
    val llmProvider: String? = null,
    val llmBaseUrl: String? = null,
    val governorModel: String? = null,
    val generatorModel: String? = null,
    val embeddingModel: String? = null,
    val createdAt: String,
    val updatedAt: String? = null
)

@Serializable
data class ProjectUsageResponse(
    val projectId: Long,
    val todayCount: Long,
    val monthCount: Long,
    val maxRequestsPerDay: Int?,
    val periodStart: String,
    val periodEnd: String
)

// ════════════════════════════════════════════
//  Extension: domain → DTO
// ════════════════════════════════════════════

fun User.toDto(): UserDto = UserDto(
    userId = userId,
    email = email,
    displayName = displayName,
    isActive = isActive,
    createdAt = createdAt
)

fun Project.toDto(): ProjectDto = ProjectDto(
    projectId = projectId,
    name = name,
    description = description,
    ownerUserId = ownerUserId,
    defaultModel = defaultModel,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun ApiKeyRecord.toDto(rawKey: String? = null): ApiKeyResponse = ApiKeyResponse(
    keyId = keyId,
    apiKey = rawKey,
    prefix = apiKeyPrefix,
    label = label,
    expiresAt = expiresAt,
    createdAt = createdAt
)

fun ProjectPolicy.toDto(): ProjectPolicyDto = ProjectPolicyDto(
    projectId = projectId,
    allowedStepTypes = allowedStepTypes,
    allowedPlugins = allowedPlugins,
    maxRequestsPerDay = maxRequestsPerDay,
    llmProvider = llmProvider,
    llmBaseUrl = llmBaseUrl,
    governorModel = governorModel,
    generatorModel = generatorModel,
    embeddingModel = embeddingModel,
    createdAt = createdAt,
    updatedAt = updatedAt
)
