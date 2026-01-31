package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.client.WiiivClient
import io.wiiiv.cli.output.Printer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.io.File

/**
 * wiiiv auth - 인증 / 세션
 *
 * JWT 저장 위치: ~/.wiiiv/session.json
 * CLI는 토큰 내용을 해석하지 않음
 * 만료 여부 판단 ❌ → API 호출 결과에 위임
 */
class AuthCommand : CliktCommand(
    name = "auth",
    help = "인증 관리"
) {
    override fun run() = Unit

    init {
        subcommands(
            AuthLogin(),
            AuthLogout(),
            AuthStatus(),
            AuthWhoami()
        )
    }
}

private fun getSessionFile(): File {
    val wiiivDir = File(System.getProperty("user.home"), ".wiiiv")
    if (!wiiivDir.exists()) wiiivDir.mkdirs()
    return File(wiiivDir, "session.json")
}

private fun saveToken(token: String, apiUrl: String) {
    val session = buildJsonObject {
        put("token", token)
        put("apiUrl", apiUrl)
    }
    getSessionFile().writeText(Json.encodeToString(JsonObject.serializer(), session))
}

private fun clearSession() {
    getSessionFile().delete()
}

/**
 * wiiiv auth login
 */
class AuthLogin : CliktCommand(
    name = "login",
    help = "로그인 (dev 모드: 자동 로그인)"
) {
    private val ctx by requireObject<CliContext>()
    private val username by option("-u", "--username", help = "사용자명")
    private val password by option("-p", "--password", help = "비밀번호")
    private val auto by option("--auto", help = "자동 로그인 (dev mode)").flag()

    override fun run() = runBlocking {
        val client = WiiivClient(ctx.apiUrl)
        try {
            val token = if (auto || (username == null && password == null)) {
                Printer.info(ctx, "Auto-login (dev mode)...")
                client.autoLogin()
            } else {
                val u = username ?: promptInput("Username")
                val p = password ?: promptInput("Password", hideInput = true)
                client.login(u, p)
            }

            saveToken(token, ctx.apiUrl)
            Printer.success(ctx, "Logged in successfully")

            if (ctx.json) {
                println("""{"success": true, "token": "${token.take(20)}..."}""")
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Login failed")
        } finally {
            client.close()
        }
    }

    private fun promptInput(text: String, hideInput: Boolean = false): String {
        print("$text: ")
        System.out.flush()
        return if (hideInput) {
            System.console()?.readPassword()?.let { String(it) } ?: readLine() ?: ""
        } else {
            readLine() ?: ""
        }
    }
}

/**
 * wiiiv auth logout
 */
class AuthLogout : CliktCommand(
    name = "logout",
    help = "로그아웃 (세션 삭제)"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() {
        clearSession()
        Printer.success(ctx, "Logged out successfully")
    }
}

/**
 * wiiiv auth status
 */
class AuthStatus : CliktCommand(
    name = "status",
    help = "인증 상태 확인"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() {
        val session = loadSession()
        if (session != null) {
            val token = session["token"]?.jsonPrimitive?.content
            val apiUrl = session["apiUrl"]?.jsonPrimitive?.content
            if (ctx.json) {
                println(buildJsonObject {
                    put("authenticated", true)
                    put("apiUrl", apiUrl)
                    put("tokenPreview", token?.take(20) + "...")
                })
            } else {
                println("Status: Authenticated")
                println("API URL: $apiUrl")
                println("Token: ${token?.take(20)}...")
            }
        } else {
            if (ctx.json) {
                println("""{"authenticated": false}""")
            } else {
                println("Status: Not authenticated")
                println("Run 'wiiiv auth login' to authenticate")
            }
        }
    }
}

/**
 * wiiiv auth whoami
 */
class AuthWhoami : CliktCommand(
    name = "whoami",
    help = "현재 사용자 정보"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val session = loadSession()
        if (session == null) {
            Printer.error(ctx, "Not authenticated. Run 'wiiiv auth login' first.")
            return@runBlocking
        }

        val token = session["token"]?.jsonPrimitive?.content
        val apiUrl = session["apiUrl"]?.jsonPrimitive?.content ?: ctx.apiUrl

        val client = WiiivClient(apiUrl, token)
        try {
            val response = client.get("/api/v2/auth/me")
            Printer.print(ctx, response)
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to get user info")
        } finally {
            client.close()
        }
    }
}
