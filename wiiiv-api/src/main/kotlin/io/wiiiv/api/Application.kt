package io.wiiiv.api

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.wiiiv.api.config.configureAuthentication
import io.wiiiv.api.config.configureCors
import io.wiiiv.api.config.configureRouting
import io.wiiiv.api.config.configureSerialization
import io.wiiiv.api.config.configureStatusPages

fun main() {
    embeddedServer(Netty, port = 8235, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureAuthentication()
    configureCors()
    configureStatusPages()
    configureRouting()
}
