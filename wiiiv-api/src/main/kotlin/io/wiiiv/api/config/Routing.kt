package io.wiiiv.api.config

import io.ktor.server.application.*
import io.ktor.server.plugins.callloging.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.wiiiv.api.routes.*
import org.slf4j.event.Level

fun Application.configureRouting() {
    install(CallLogging) {
        level = Level.INFO
    }

    routing {
        // Root
        get("/") {
            call.respondText("wiiiv v2.0 API")
        }

        // API v2 routes
        route("/api/v2") {
            authRoutes()
            decisionRoutes()
            blueprintRoutes()
            executionRoutes()
            systemRoutes()
            ragRoutes()
        }
    }
}
