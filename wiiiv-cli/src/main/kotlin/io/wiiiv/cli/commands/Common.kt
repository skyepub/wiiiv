package io.wiiiv.cli.commands

import io.wiiiv.cli.CliContext
import io.wiiiv.cli.client.WiiivClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * CLI 공통 유틸리티
 */

private val json = Json { ignoreUnknownKeys = true }

/**
 * 세션 파일에서 토큰을 읽어 클라이언트 생성
 */
fun createClient(ctx: CliContext): WiiivClient {
    val session = loadSession()
    val token = session?.get("token")?.jsonPrimitive?.content
    val apiUrl = session?.get("apiUrl")?.jsonPrimitive?.content ?: ctx.apiUrl

    return WiiivClient(apiUrl, token)
}

/**
 * 세션 파일 로드
 */
fun loadSession(): JsonObject? {
    val file = getSessionFile()
    return if (file.exists()) {
        try {
            json.parseToJsonElement(file.readText()).jsonObject
        } catch (e: Exception) {
            null
        }
    } else null
}

/**
 * 세션 파일 경로
 */
private fun getSessionFile(): File {
    val wiiivDir = File(System.getProperty("user.home"), ".wiiiv")
    if (!wiiivDir.exists()) wiiivDir.mkdirs()
    return File(wiiivDir, "session.json")
}
