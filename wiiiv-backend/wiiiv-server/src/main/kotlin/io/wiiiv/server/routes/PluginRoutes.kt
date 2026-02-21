package io.wiiiv.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.dto.common.ApiError
import io.wiiiv.server.dto.common.ApiResponse
import io.wiiiv.server.registry.WiiivRegistry
import kotlinx.serialization.Serializable

/**
 * Plugin Routes — 플러그인 조회 API
 *
 * GET  /api/v2/plugins         로드된 플러그인 목록
 * GET  /api/v2/plugins/{id}    플러그인 상세 (액션, 거버넌스 메타 포함)
 */
fun Route.pluginRoutes() {
    route("/plugins") {
        authenticate("auth-jwt") {
            // GET /plugins — 목록
            get {
                val plugins = WiiivRegistry.pluginRegistry.all().map { lp ->
                    PluginSummaryDto(
                        pluginId = lp.plugin.pluginId,
                        displayName = lp.plugin.displayName,
                        version = lp.plugin.version,
                        scheme = lp.meta.scheme,
                        riskLevel = lp.meta.riskLevel.name,
                        actionCount = lp.actions.size,
                        description = lp.meta.description
                    )
                }
                call.respond(ApiResponse.success(PluginListResponse(plugins = plugins, total = plugins.size)))
            }

            // GET /plugins/{id} — 상세
            get("/{pluginId}") {
                val pluginId = call.parameters["pluginId"]
                    ?: return@get call.respond(
                        HttpStatusCode.BadRequest,
                        ApiResponse.error<PluginDetailDto>(
                            ApiError(code = "MISSING_PARAM", message = "pluginId required")
                        )
                    )

                val lp = WiiivRegistry.pluginRegistry.get(pluginId)
                    ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        ApiResponse.error<PluginDetailDto>(
                            ApiError(code = "NOT_FOUND", message = "Plugin not found: $pluginId")
                        )
                    )

                val detail = PluginDetailDto(
                    pluginId = lp.plugin.pluginId,
                    displayName = lp.plugin.displayName,
                    version = lp.plugin.version,
                    scheme = lp.meta.scheme,
                    riskLevel = lp.meta.riskLevel.name,
                    capabilities = lp.meta.capabilities.map { it.name },
                    idempotent = lp.meta.idempotent,
                    description = lp.meta.description,
                    jarPath = lp.jarPath,
                    actions = lp.actions.map { action ->
                        PluginActionDto(
                            name = action.name,
                            description = action.description,
                            riskLevel = action.riskLevel.name,
                            capabilities = action.capabilities.map { it.name },
                            requiredParams = action.requiredParams,
                            optionalParams = action.optionalParams
                        )
                    }
                )
                call.respond(ApiResponse.success(detail))
            }
        }
    }
}

@Serializable
data class PluginListResponse(
    val plugins: List<PluginSummaryDto>,
    val total: Int
)

@Serializable
data class PluginSummaryDto(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val scheme: String,
    val riskLevel: String,
    val actionCount: Int,
    val description: String
)

@Serializable
data class PluginDetailDto(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val scheme: String,
    val riskLevel: String,
    val capabilities: List<String>,
    val idempotent: Boolean,
    val description: String,
    val jarPath: String,
    val actions: List<PluginActionDto>
)

@Serializable
data class PluginActionDto(
    val name: String,
    val description: String,
    val riskLevel: String,
    val capabilities: List<String>,
    val requiredParams: List<String>,
    val optionalParams: List<String>
)
