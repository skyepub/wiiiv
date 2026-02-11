package io.wiiiv.shell

import io.wiiiv.blueprint.BlueprintRunner
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import io.wiiiv.dacs.SimpleDACS
import io.wiiiv.dacs.HybridDACS
import io.wiiiv.execution.CompositeExecutor
import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.execution.impl.CommandExecutor
import io.wiiiv.execution.impl.NoopExecutor
import io.wiiiv.execution.impl.OpenAIProvider
import io.wiiiv.governor.ActionType
import io.wiiiv.governor.ConversationalGovernor
import io.wiiiv.governor.NextAction
import io.wiiiv.rag.RagPipeline
import io.wiiiv.rag.embedding.OpenAIEmbeddingProvider
import io.wiiiv.rag.vector.InMemoryVectorStore
import kotlinx.coroutines.runBlocking

/**
 * wiiiv Shell - 대화형 Governor 인터페이스
 *
 * ConversationalGovernor와 실시간으로 대화하는 REPL 환경
 */
fun main() = runBlocking {
    // UTF-8 출력 설정
    System.setOut(PrintStream(System.out, true, StandardCharsets.UTF_8))

    // Logo animation uses raw ANSI codes (including 24-bit RGB)
    val CYAN = "\u001B[36m"
    val BRIGHT_CYAN = "\u001B[96m"
    val WHITE = "\u001B[97m"
    val DIM = "\u001B[2m"
    val RESET = "\u001B[0m"
    val BOLD = "\u001B[1m"

    val line = "\u2500".repeat(59)

    fun animPrint(text: String, delayMs: Long = 30) {
        println(text)
        System.out.flush()
        Thread.sleep(delayMs)
    }

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

    // separator
    println("${DIM}$line${RESET}")

    // smooth breathing with 24-bit RGB gradient
    val steps = 12
    val minV = 35
    val maxV = 255
    fun cyanRgb(v: Int) = "\u001B[38;2;0;${v};${v}m"

    // initial: dim
    printLogo(cyanRgb(minV))
    Thread.sleep(300)

    // fade up: dim → bright
    for (i in 1..steps) {
        val v = minV + (maxV - minV) * i / steps
        print(UP7)
        printLogo(cyanRgb(v))
        Thread.sleep(60)
    }
    Thread.sleep(150)

    // fade down: bright → dim
    for (i in steps downTo 0) {
        val v = minV + (maxV - minV) * i / steps
        print(UP7)
        printLogo(cyanRgb(v))
        Thread.sleep(60)
    }
    Thread.sleep(200)

    // final: settle with info text
    print(UP7)
    println("${BRIGHT_CYAN}                  o8o   o8o   o8o${RESET}")
    println("${BRIGHT_CYAN}                  `\"'   `\"'   `\"'${RESET}")
    println("${CYAN}oooo oooo    ooo oooo  oooo  oooo  oooo    ooo${RESET}")
    println("${CYAN} `88. `88.  .8'  `888  `888  `888   `88.  .8'${RESET}   ${WHITE}v2.1  2025-${RESET}")
    println("${CYAN}  `88..]88..8'    888   888   888    `88..8'${RESET}")
    println("${CYAN}   `888'`888'     888   888   888     `888'${RESET}  ${DIM}skytree@wiiiv.io${RESET}")
    println("${CYAN}    `8'  `8'     o888o o888o o888o     `8'${RESET}")

    println()
    animPrint("  ${WHITE}- A Natural Language-Based Multi-Decision Execution System${RESET}", 30)
    println()
    println()

    val pad = " ".repeat(9)

    // LLM Provider 초기화
    val modelName = "gpt-4o-mini"
    val llmProvider = try {
        val key = System.getenv("OPENAI_API_KEY") ?: ""
        if (key.isNotBlank()) {
            println("$pad${BRIGHT_CYAN}[INFO]${RESET} OpenAI API key detected - LLM mode")
            OpenAIProvider.fromEnv(model = modelName)
        } else {
            println("$pad\u001B[33m[WARN]\u001B[0m OPENAI_API_KEY not set - basic mode")
            null
        }
    } catch (e: Exception) {
        println("$pad\u001B[33m[WARN]\u001B[0m LLM Provider init failed: ${e.message}")
        null
    }

    // DACS 초기화
    val dacsTypeName: String
    val dacs = if (llmProvider != null) {
        dacsTypeName = "HybridDACS"
        println("$pad${BRIGHT_CYAN}[INFO]${RESET} HybridDACS (LLM + rule-based)")
        HybridDACS(llmProvider, modelName)
    } else {
        dacsTypeName = "SimpleDACS"
        println("$pad${BRIGHT_CYAN}[INFO]${RESET} SimpleDACS (rule-based)")
        SimpleDACS.DEFAULT
    }

    // RAG Pipeline 초기화
    val ragPipeline = if (llmProvider != null) {
        try {
            val embeddingProvider = OpenAIEmbeddingProvider.fromEnv()
            val pipeline = RagPipeline(
                embeddingProvider = embeddingProvider,
                vectorStore = InMemoryVectorStore("wiiiv-shell-rag")
            )
            println("$pad${BRIGHT_CYAN}[INFO]${RESET} RAG enabled (OpenAI embeddings)")
            pipeline
        } catch (e: Exception) {
            println("$pad\u001B[33m[WARN]\u001B[0m RAG init failed: ${e.message}")
            null
        }
    } else {
        null
    }

    // Executor 초기화
    val executor = CompositeExecutor(
        executors = listOf(
            FileExecutor(),
            CommandExecutor(),
            NoopExecutor(handleAll = false)
        )
    )
    val blueprintRunner = BlueprintRunner.create(executor)

    // ConversationalGovernor 생성
    val governor = ConversationalGovernor.create(
        id = "gov-shell",
        dacs = dacs,
        llmProvider = llmProvider,
        model = if (llmProvider != null) modelName else null,
        blueprintRunner = blueprintRunner,
        ragPipeline = ragPipeline
    )

    // Progress Display 설정
    val progressDisplay = ShellProgressDisplay()
    governor.progressListener = progressDisplay

    // 세션 시작
    val session = governor.startSession()
    println()
    println("$pad${BRIGHT_CYAN}[SESSION]${RESET} ID: ${session.sessionId}")
    println()
    println("${pad}Type your message. 'exit' or 'quit' to end. '/help' for commands.")
    println("${pad}${DIM}Alt+Enter or Ctrl+J for newline${RESET}")
    println("  ${DIM}$line${RESET}")
    println()

    val inputReader = ShellInputReader()

    // Shell 설정 및 컨텍스트
    val shellSettings = ShellSettings()
    val shellCtx = ShellContext(
        governor = governor,
        sessionId = session.sessionId,
        session = session,
        inputReader = inputReader,
        modelName = if (llmProvider != null) modelName else null,
        dacsTypeName = dacsTypeName,
        llmProviderPresent = llmProvider != null,
        settings = shellSettings,
        ragPipeline = ragPipeline
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
                val sizeKB = detectedImages.sumOf { it.data.size } / 1024
                println("      ${DIM}[image: ${detectedImages.size}장, ${sizeKB}KB]${RESET}")
            }

            progressDisplay.reset()
            var response = governor.chat(session.sessionId, effectiveText, detectedImages)
            progressDisplay.ensureNewline()
            var continuations = 0

            // auto-continue loop: nextAction이 CONTINUE_EXECUTION이면 자동 계속
            do {
                print("${c.CYAN}wiiiv>${c.RESET} ")
                println(ShellRenderer.render(response.message))

                // 추가 정보 출력
                when (response.action) {
                    ActionType.ASK -> {
                        response.askingFor?.let {
                            println("      (need: $it)")
                        }
                    }
                    ActionType.CONFIRM -> {
                        response.confirmationSummary?.let {
                            intentCounter++
                            println(ShellRenderer.renderConfirmation(it, intentCounter))
                        }
                    }
                    ActionType.EXECUTE -> {
                        println(ShellRenderer.renderExecutionResult(
                            "", response.blueprint, response.executionResult
                        ))
                    }
                    ActionType.CANCEL -> {
                        println("      (session reset)")
                    }
                    else -> {}
                }
                println()

                // auto-continue: ShellSettings 기반
                val maxCont = shellSettings.maxContinue
                if (shellSettings.autoContinue
                    && response.nextAction == NextAction.CONTINUE_EXECUTION
                    && continuations < maxCont
                ) {
                    continuations++
                    val reason = response.message.lines().firstOrNull { it.isNotBlank() }?.take(60) ?: ""
                    println("      (auto-continue $continuations/$maxCont: $reason)")
                    response = governor.chat(session.sessionId, "계속")
                    progressDisplay.ensureNewline()
                } else {
                    break
                }
            } while (true)
        } catch (e: Exception) {
            println()
            println("[ERROR] ${e.message}")
            println()
        }
    }

    governor.endSession(session.sessionId)
    inputReader.close()
}
