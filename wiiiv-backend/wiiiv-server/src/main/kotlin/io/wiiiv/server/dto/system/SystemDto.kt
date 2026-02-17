package io.wiiiv.server.dto.system

import kotlinx.serialization.Serializable

/**
 * System Introspection DTOs
 *
 * 시스템 상태 및 등록된 컴포넌트 조회용
 */

@Serializable
data class SystemInfo(
    val version: String,
    val uptime: Long,
    val status: String
)

@Serializable
data class ExecutorInfo(
    val id: String,
    val type: String,
    val supportedStepTypes: List<String>,
    val status: String = "available"
)

@Serializable
data class ExecutorsResponse(
    val executors: List<ExecutorInfo>
)

@Serializable
data class GateInfo(
    val id: String,
    val type: String,
    val priority: Int,
    val status: String = "active"
)

@Serializable
data class GatesResponse(
    val gates: List<GateInfo>
)

@Serializable
data class PersonaInfo(
    val id: String,
    val name: String,
    val role: String,
    val provider: String? = null
)

@Serializable
data class PersonasResponse(
    val personas: List<PersonaInfo>
)

@Serializable
data class HealthResponse(
    val status: String,
    val checks: Map<String, HealthCheck>
)

@Serializable
data class HealthCheck(
    val status: String,
    val message: String? = null
)
