package io.wiiiv.server.routes

import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.dto.system.*
import io.wiiiv.server.registry.WiiivRegistry
import io.wiiiv.BuildInfo

/**
 * System Introspection Routes
 *
 * GET /api/v2/system/health - Health check
 * GET /api/v2/system/info - System info
 * GET /api/v2/system/executors - Registered Executors
 * GET /api/v2/system/gates - Registered Gates
 * GET /api/v2/system/personas - Registered DACS Personas
 */
fun Route.systemRoutes() {
    route("/system") {
        // Health check (public)
        get("/health") {
            call.respond(
                ApiResponse.success(
                    HealthResponse(
                        status = "healthy",
                        checks = mapOf(
                            "core" to HealthCheck("healthy", "wiiiv-core loaded"),
                            "executors" to HealthCheck("healthy", "${WiiivRegistry.getExecutorInfos().size} registered"),
                            "gates" to HealthCheck("healthy", "${WiiivRegistry.getGateInfos().size} registered"),
                            "dacs" to HealthCheck("healthy", "${WiiivRegistry.getPersonaInfos().size} personas")
                        )
                    )
                )
            )
        }

        // System info (public)
        get("/info") {
            val startTime = System.getProperty("wiiiv.startTime")?.toLongOrNull()
                ?: System.currentTimeMillis()
            val uptime = System.currentTimeMillis() - startTime

            call.respond(
                ApiResponse.success(
                    SystemInfo(
                        version = BuildInfo.FULL_VERSION,
                        uptime = uptime,
                        status = "running"
                    )
                )
            )
        }

        // Protected introspection endpoints
        authenticate("auth-jwt") {
            // List registered executors
            get("/executors") {
                val executors = WiiivRegistry.getExecutorInfos().map {
                    ExecutorInfo(
                        id = it.id,
                        type = it.type,
                        supportedStepTypes = it.supportedStepTypes,
                        status = it.status
                    )
                }

                call.respond(
                    ApiResponse.success(
                        ExecutorsResponse(executors = executors)
                    )
                )
            }

            // List registered gates
            get("/gates") {
                val gates = WiiivRegistry.getGateInfos().map {
                    GateInfo(
                        id = it.id,
                        type = it.name,
                        priority = it.priority,
                        status = it.status
                    )
                }

                call.respond(
                    ApiResponse.success(
                        GatesResponse(gates = gates)
                    )
                )
            }

            // List registered DACS personas
            get("/personas") {
                val personas = WiiivRegistry.getPersonaInfos().map {
                    PersonaInfo(
                        id = it.id,
                        name = it.name,
                        role = it.role,
                        provider = it.provider
                    )
                }

                call.respond(
                    ApiResponse.success(
                        PersonasResponse(personas = personas)
                    )
                )
            }

            // Gate logs (audit)
            get("/gates/logs") {
                val logs = WiiivRegistry.gateLogger.getAllEntries().map { entry ->
                    mapOf(
                        "logId" to entry.logId,
                        "gate" to entry.gateName,
                        "requestId" to entry.requestId,
                        "result" to entry.result,
                        "denyCode" to entry.denyCode,
                        "timestamp" to entry.timestamp.toString()
                    )
                }

                call.respond(
                    ApiResponse.success(
                        mapOf("logs" to logs)
                    )
                )
            }
        }
    }
}
