package io.wiiiv.server

import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.wiiiv.server.config.configureAuthentication
import io.wiiiv.server.config.configureCors
import io.wiiiv.server.config.configureRouting
import io.wiiiv.server.config.configureSerialization
import io.wiiiv.server.config.configureStatusPages
import io.wiiiv.server.registry.WiiivRegistry
import org.mindrot.jbcrypt.BCrypt
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("io.wiiiv.server.Application")

fun main() {
    val port = System.getenv("WIIIV_PORT")?.toIntOrNull() ?: 8235
    embeddedServer(Netty, port = port, host = "0.0.0.0") {
        module()
    }.start(wait = true)
}

fun Application.module() {
    configureSerialization()
    configureAuthentication()
    configureCors()
    configureStatusPages()
    configureRouting()
    seedAdminAccount()
}

/**
 * Seed initial admin account (admin@wiiiv.io) if it does not already exist.
 * Runs once at server startup — idempotent.
 */
private fun seedAdminAccount() {
    val store = WiiivRegistry.platformStore
    if (store == null) {
        log.warn("[SEED] PlatformStore not available — skipping admin account seed")
        return
    }

    val email = "admin@wiiiv.io"
    val existing = store.findUserByEmail(email)
    if (existing != null) {
        log.info("[SEED] Admin account already exists: userId={}, email={}", existing.userId, email)
        return
    }

    val hash = BCrypt.hashpw("mako2122", BCrypt.gensalt(12))
    val user = store.createUser(email, "Admin", hash)
    log.info("[SEED] Created admin account: userId={}, email={}", user.userId, email)
}
