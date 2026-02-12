package io.wiiiv.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import java.io.File

/**
 * ~/.wiiiv/credentials에 호스트별 JWT 토큰을 저장/로드한다.
 *
 * 파일 형식 (JSON):
 * {
 *   "version": 1,
 *   "hosts": {
 *     "localhost:8235": { "token": "eyJ...", "username": "admin", "savedAt": 1700000000000 }
 *   }
 * }
 */
object CredentialStore {

    @Serializable
    data class HostCredential(
        val token: String,
        val username: String,
        val savedAt: Long
    )

    @Serializable
    data class CredentialFile(
        val version: Int = 1,
        val hosts: MutableMap<String, HostCredential> = mutableMapOf()
    )

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private fun credentialFile(): File {
        val home = System.getProperty("user.home")
        return File(home, ".wiiiv/credentials")
    }

    fun load(): CredentialFile {
        val file = credentialFile()
        if (!file.exists()) return CredentialFile()
        return try {
            json.decodeFromString<CredentialFile>(file.readText())
        } catch (_: Exception) {
            CredentialFile()
        }
    }

    fun saveToken(hostKey: String, username: String, token: String) {
        val creds = load()
        creds.hosts[hostKey] = HostCredential(
            token = token,
            username = username,
            savedAt = System.currentTimeMillis()
        )
        write(creds)
    }

    fun getToken(hostKey: String): HostCredential? {
        return load().hosts[hostKey]
    }

    fun removeToken(hostKey: String) {
        val creds = load()
        if (creds.hosts.remove(hostKey) != null) {
            write(creds)
        }
    }

    private fun write(creds: CredentialFile) {
        val file = credentialFile()
        file.parentFile.mkdirs()
        file.writeText(json.encodeToString(creds))
    }
}
