package io.wiiiv.server.config

import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.server.routes.*
import io.wiiiv.BuildInfo
import org.slf4j.event.Level

fun Application.configureRouting() {
    install(CallLogging) {
        level = Level.INFO
    }

    routing {
        // Root
        get("/") {
            call.respondText("wiiiv ${BuildInfo.FULL_VERSION} API")
        }

        // API v2 routes
        route("/api/v2") {
            authRoutes()
            decisionRoutes()
            blueprintRoutes()
            executionRoutes()
            systemRoutes()
            ragRoutes()
            hlxRoutes()
            sessionRoutes()
            devRoutes()
            auditRoutes()
            pluginRoutes()
            platformRoutes()
            workflowRoutes()         // 저장된 워크플로우 관리
            projectScopedRoutes()    // F-4: 프로젝트 스코프 라우트
        }
    }
}
