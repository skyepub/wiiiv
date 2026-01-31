package io.wiiiv.api.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.cors.routing.*

fun Application.configureCors() {
    install(CORS) {
        allowMethod(HttpMethod.Options)
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Patch)

        allowHeader(HttpHeaders.Authorization)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Accept)

        allowCredentials = true

        // Dev mode: allow all origins
        if (System.getenv("WIIIV_ENV") != "production") {
            anyHost()
        } else {
            val allowedHosts = System.getenv("CORS_ALLOWED_HOSTS")?.split(",") ?: listOf()
            allowedHosts.forEach { allowHost(it.trim()) }
        }
    }
}
