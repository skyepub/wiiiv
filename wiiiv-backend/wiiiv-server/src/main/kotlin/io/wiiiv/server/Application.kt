package io.wiiiv.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.wiiiv.server.config.configureAuthentication
import io.wiiiv.server.config.configureCors
import io.wiiiv.server.config.configureRouting
import io.wiiiv.server.config.configureSerialization
import io.wiiiv.server.config.configureStatusPages

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
