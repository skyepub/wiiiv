package io.wiiiv.cli

import io.wiiiv.cli.client.WiiivApiClient
import io.wiiiv.cli.model.CliActionType
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import kotlinx.coroutines.runBlocking

/**
 * wiiiv Shell - 대화형 Governor 인터페이스
 *
 * wiiiv-server에 접속하여 SSE 스트리밍으로 대화하는 REPL 환경.
 * core 의존성 없이 서버 API만으로 모든 기능을 수행한다.
 */
fun main(args: Array<String>) = runBlocking {
    // JLine3 Terminal을 맨 처음 초기화 — 모든 출력보다 먼저
    val terminal = TerminalBuilder.builder()
        .system(true)
        .encoding(StandardCharsets.UTF_8)
        .build()
    System.setOut(PrintStream(terminal.output(), true, StandardCharsets.UTF_8))

    val CYAN = "\u001B[36m"
    val BRIGHT_CYAN = "\u001B[96m"
    val WHITE = "\u001B[97m"
    val DIM = "\u001B[2m"
    val RESET = "\u001B[0m"

    val line = "\u2500".repeat(59)
    val pad = " ".repeat(9)

    // === 서버 접속 + 인증 (로고 전) ===
    val connArgs = ConnectionArgs.parse(args)
    val serverUrl = if (connArgs.host != "localhost" || connArgs.port != 8235) {
        connArgs.toServerUrl()
    } else {
        System.getenv("WIIIV_SERVER_URL") ?: connArgs.toServerUrl()
    }
    val client = WiiivApiClient(serverUrl)

    println("$pad${BRIGHT_CYAN}[INFO]${RESET} Connecting to $serverUrl")

    // 헬스 체크
    if (!client.healthCheck()) {
        println("$pad\u001B[31m[ERROR]\u001B[0m Cannot connect to wiiiv server at $serverUrl")
        println("$pad       Start the server: ./gradlew :wiiiv-server:run")
        return@runBlocking
    }
    println("$pad${BRIGHT_CYAN}[INFO]${RESET} Server connected")

    // 인증 (출력 줄 수 반환, -1이면 실패)
    val authLines = authenticate(client, connArgs, terminal, pad)
    if (authLines < 0) {
        client.close()
        return@runBlocking
    }

    // === 접속 정보 지우고 로고 애니메이션 ===
    val connLines = 2 + authLines  // Connecting + Server connected + auth 출력
    repeat(connLines) { print("\u001B[A\u001B[2K") }  // 커서 위로 + 줄 삭제

    val UP7 = "\u001B[7A"
    val logoLines = listOf(
        """                  o8o   o8o   o8o""",
        """                  `"'   `"'   `"'""",
        """oooo oooo    ooo oooo  oooo  oooo  oooo    ooo""",
        """ `88. `88.  .8'  `888  `888  `888   `88.  .8'""",
        """  `88..]88..8'    888   888   888    `88..8'""",
        """   `888'`888'     888   888   888     `888'""",
        """    `8'  `8'     o888o o888o o888o     `8'"""
    )

    fun printLogo(color: String) {
        for (l in logoLines) { println("$color$l$RESET") }
        System.out.flush()
    }

    println("${DIM}$line${RESET}")

    val steps = 12
    val minV = 35
    val maxV = 255
    fun cyanRgb(v: Int) = "\u001B[38;2;0;${v};${v}m"

    printLogo(cyanRgb(minV))
    Thread.sleep(300)

    for (i in 1..steps) {
        val v = minV + (maxV - minV) * i / steps
        print(UP7)
        printLogo(cyanRgb(v))
        Thread.sleep(60)
    }
    Thread.sleep(150)

    for (i in steps downTo 0) {
        val v = minV + (maxV - minV) * i / steps
        print(UP7)
        printLogo(cyanRgb(v))
        Thread.sleep(60)
    }
    Thread.sleep(200)

    print(UP7)
    println("${BRIGHT_CYAN}                  o8o   o8o   o8o${RESET}")
    println("${BRIGHT_CYAN}                  `\"'   `\"'   `\"'${RESET}")
    println("${CYAN}oooo oooo    ooo oooo  oooo  oooo  oooo    ooo${RESET}")
    println("${CYAN} `88. `88.  .8'  `888  `888  `888   `88.  .8'${RESET}   ${WHITE}v2.2  2025-${RESET}")
    println("${CYAN}  `88..]88..8'    888   888   888    `88..8'${RESET}")
    println("${CYAN}   `888'`888'     888   888   888     `888'${RESET}  ${DIM}skytree@wiiiv.io${RESET}")
    println("${CYAN}    `8'  `8'     o888o o888o o888o     `8'${RESET}")

    println()
    println("  ${WHITE}- A Natural Language-Based Multi-Decision Execution System${RESET}")
    println()

    // workspace 결정
    val workspace = System.getenv("WIIIV_WORKSPACE")
        ?: System.getProperty("user.dir")

    // 세션 생성
    val sessionResponse = try {
        client.createSession(workspace)
    } catch (e: Exception) {
        println("$pad\u001B[31m[ERROR]\u001B[0m Session creation failed: ${e.message}")
        client.close()
        return@runBlocking
    }

    val sessionId = sessionResponse.sessionId

    // 서버 정보 조회
    val serverInfo = try {
        val state = client.getSessionState(sessionId)

        if (state.serverInfo.llmAvailable) {
            println("$pad${BRIGHT_CYAN}[LLM]${RESET} ${state.serverInfo.modelName ?: "unknown"}")
        } else {
            println("$pad\u001B[33m[WARN]\u001B[0m LLM not available (basic mode)")
        }

        val dacsDetail = when (state.serverInfo.dacsTypeName) {
            "HybridDACS" -> "HybridDACS (3 rule + 3 LLM)"
            "SimpleDACS" -> "SimpleDACS (3 rule-based)"
            else -> state.serverInfo.dacsTypeName
        }
        println("$pad${BRIGHT_CYAN}[DACS]${RESET} $dacsDetail")

        if (state.serverInfo.ragAvailable) {
            println("$pad${BRIGHT_CYAN}[RAG]${RESET} enabled")
        }

        state.serverInfo
    } catch (e: Exception) {
        println("$pad${DIM}[INFO] Server info not available${RESET}")
        io.wiiiv.cli.client.ServerInfoDto(
            modelName = null,
            dacsTypeName = "unknown",
            llmAvailable = false,
            ragAvailable = false
        )
    }

    println()
    println("$pad${BRIGHT_CYAN}[SESSION]${RESET} ID: $sessionId")
    println()
    println("${pad}Type your message. 'exit' or 'quit' to end. '/help' for commands.")
    println("${pad}${DIM}Alt+Enter or Ctrl+J for newline${RESET}")
    println("  ${DIM}$line${RESET}")
    println()

    val inputReader = ShellInputReader(terminal)
    val progressDisplay = ShellProgressDisplay()

    // Shell 설정 및 컨텍스트
    val shellSettings = ShellSettings(workspace = workspace)
    val shellCtx = ShellContext(
        client = client,
        sessionId = sessionId,
        inputReader = inputReader,
        settings = shellSettings,
        serverInfo = serverInfo
    )

    // CommandDispatcher 초기화 (object init 트리거)
    CommandDispatcher

    val c = ShellColors
    var intentCounter = 0

    while (true) {
        val input = inputReader.readLine("${c.DIM}>${c.RESET} ")?.trim()

        if (input.isNullOrBlank()) {
            continue
        }

        if (input.lowercase() in listOf("exit", "quit", "q", "종료")) {
            println()
            println("Session ended. Bye!")
            break
        }

        // 이미지 경로 감지 (슬래시 명령보다 먼저 — /path/to/img.png 가 명령으로 오인되지 않도록)
        val (textPart, detectedImages) = ImageInputParser.parse(input)

        // 슬래시 명령 처리 — 이미지가 없을 때만 (이미지 경로는 /로 시작하므로)
        if (detectedImages.isEmpty() && CommandDispatcher.isCommand(input)) {
            CommandDispatcher.dispatch(input, shellCtx)
            continue
        }

        try {
            val effectiveText = textPart.ifBlank { "이 이미지를 분석하고 설명해주세요." }

            if (detectedImages.isNotEmpty()) {
                val sizeKB = detectedImages.sumOf { it.sizeBytes } / 1024
                println("      ${DIM}[image: ${detectedImages.size}장, ${sizeKB}KB]${RESET}")
            }

            progressDisplay.reset()

            // SSE 스트리밍 채팅
            client.chat(
                sessionId = sessionId,
                message = effectiveText,
                images = if (detectedImages.isNotEmpty()) detectedImages else null,
                autoContinue = shellSettings.autoContinue,
                maxContinue = shellSettings.maxContinue,
                onProgress = { event ->
                    progressDisplay.onServerProgress(event)
                },
                onResponse = { response ->
                    progressDisplay.ensureNewline()

                    // 실행 결과 캐시
                    if (response.executionSummary != null) {
                        shellCtx.lastExecutionSummary = response.executionSummary
                    }

                    print("${c.CYAN}wiiiv>${c.RESET} ")
                    println(ShellRenderer.render(response.message))

                    // action별 추가 정보 출력
                    val action = try {
                        CliActionType.valueOf(response.action.uppercase())
                    } catch (_: Exception) {
                        null
                    }

                    when (action) {
                        CliActionType.ASK -> {
                            response.askingFor?.let {
                                println("      (need: $it)")
                            }
                        }
                        CliActionType.CONFIRM -> {
                            response.confirmationSummary?.let {
                                intentCounter++
                                println(ShellRenderer.renderConfirmation(it, intentCounter))
                            }
                        }
                        CliActionType.EXECUTE -> {
                            println(ShellRenderer.renderExecutionResult(
                                "", response.executionSummary
                            ))
                        }
                        CliActionType.CANCEL -> {
                            println("      (session reset)")
                        }
                        else -> {}
                    }
                    println()
                },
                onError = { error ->
                    progressDisplay.ensureNewline()
                    println()
                    println("[ERROR] $error")
                    println()
                }
            )
        } catch (e: Exception) {
            progressDisplay.ensureNewline()
            println()
            println("[ERROR] ${e.message}")
            println()
        }
    }

    // 세션 종료
    try {
        client.deleteSession(sessionId)
    } catch (_: Exception) {}
    client.close()
    inputReader.close()
}

/**
 * 인증 플로우.
 *
 * - isAutoLogin → autoLogin() → 토큰 저장
 * - username 있음 → 저장된 토큰 시도 → 실패 시 비밀번호 프롬프트
 *
 * @return 출력한 줄 수 (성공), -1 (실패)
 */
private suspend fun authenticate(
    client: WiiivApiClient,
    connArgs: ConnectionArgs,
    terminal: Terminal,
    pad: String
): Int {
    val BRIGHT_CYAN = "\u001B[96m"
    val RESET = "\u001B[0m"
    val RED = "\u001B[31m"

    if (connArgs.isAutoLogin) {
        return try {
            val token = client.autoLogin()
            CredentialStore.saveToken(connArgs.credentialKey(), "auto", token)
            println("$pad${BRIGHT_CYAN}[INFO]${RESET} Authenticated")
            1
        } catch (e: Exception) {
            println("$pad${RED}[ERROR]${RESET} Login failed: ${e.message}")
            -1
        }
    }

    val username = connArgs.username!!
    val hostKey = connArgs.credentialKey()

    // 저장된 토큰 시도
    val saved = CredentialStore.getToken(hostKey)
    if (saved != null && saved.username == username) {
        client.setToken(saved.token)
        if (client.validateToken()) {
            println("$pad${BRIGHT_CYAN}[INFO]${RESET} Authenticated as $username (saved credential)")
            return 1
        }
        // 만료 — 삭제 후 비밀번호 프롬프트로 진행
        CredentialStore.removeToken(hostKey)
    }

    // 비밀번호 프롬프트
    val password = readPassword(terminal, "${pad}Password: ")
    if (password == null) {
        println()
        println("$pad${RED}[ERROR]${RESET} Login cancelled")
        return -1
    }
    println()

    return try {
        val token = client.login(username, password)
        CredentialStore.saveToken(hostKey, username, token)
        println("$pad${BRIGHT_CYAN}[INFO]${RESET} Authenticated as $username")
        2  // Password 줄 + Authenticated 줄
    } catch (e: Exception) {
        println("$pad${RED}[ERROR]${RESET} Login failed: ${e.message}")
        -1
    }
}

/**
 * JLine3 Terminal raw mode로 에코 없이 비밀번호 입력을 받는다.
 *
 * @return 비밀번호 문자열, Ctrl+C 시 null
 */
private fun readPassword(terminal: Terminal, prompt: String): String? {
    print(prompt)
    System.out.flush()

    val attrs = terminal.attributes
    try {
        terminal.enterRawMode()
        val reader = terminal.reader()
        val buf = StringBuilder()

        while (true) {
            val ch = reader.read()
            when {
                ch == -1 || ch == 3 -> { // EOF 또는 Ctrl+C
                    return null
                }
                ch == 13 || ch == 10 -> { // Enter
                    return buf.toString()
                }
                ch == 127 || ch == 8 -> { // Backspace / Delete
                    if (buf.isNotEmpty()) {
                        buf.deleteCharAt(buf.length - 1)
                        print("\b \b")
                        System.out.flush()
                    }
                }
                ch >= 32 -> { // 일반 문자
                    buf.append(ch.toChar())
                    print("*")
                    System.out.flush()
                }
            }
        }
    } finally {
        terminal.attributes = attrs
    }
}
