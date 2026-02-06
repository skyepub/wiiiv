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
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * wiiiv Shell - 대화형 Governor 인터페이스
 *
 * ConversationalGovernor와 실시간으로 대화하는 REPL 환경
 */
fun main() = runBlocking {
    // UTF-8 출력 설정
    System.setOut(PrintStream(System.out, true, StandardCharsets.UTF_8))

    println("""
        ╔═══════════════════════════════════════════════════════════╗
        ║                    wiiiv Shell v2.1                       ║
        ║           Interactive Conversational Governor             ║
        ╚═══════════════════════════════════════════════════════════╝
    """.trimIndent())
    println()

    // LLM Provider 초기화
    val llmProvider = try {
        val key = System.getenv("OPENAI_API_KEY") ?: ""
        if (key.isNotBlank()) {
            println("[INFO] OpenAI API 키 감지됨 - LLM 모드로 시작합니다.")
            OpenAIProvider.fromEnv(model = "gpt-4o-mini")
        } else {
            println("[WARN] OPENAI_API_KEY 없음 - 기본 모드로 시작합니다.")
            null
        }
    } catch (e: Exception) {
        println("[WARN] LLM Provider 초기화 실패: ${e.message}")
        null
    }

    // DACS 초기화
    val dacs = if (llmProvider != null) {
        println("[INFO] HybridDACS 사용 (LLM + 규칙 기반)")
        HybridDACS(llmProvider, "gpt-4o-mini")
    } else {
        println("[INFO] SimpleDACS 사용 (규칙 기반)")
        SimpleDACS.DEFAULT
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
        model = if (llmProvider != null) "gpt-4o-mini" else null,
        blueprintRunner = blueprintRunner
    )

    // 세션 시작
    val session = governor.startSession()
    println()
    println("[SESSION] 세션 ID: ${session.sessionId}")
    println()
    println("대화를 시작하세요. 종료하려면 'exit' 또는 'quit'을 입력하세요.")
    println("─".repeat(60))
    println()

    val reader = BufferedReader(InputStreamReader(System.`in`))

    while (true) {
        print("You > ")
        System.out.flush()

        val input = reader.readLine()?.trim()

        if (input.isNullOrBlank()) {
            continue
        }

        if (input.lowercase() in listOf("exit", "quit", "q", "종료")) {
            println()
            println("세션을 종료합니다. 안녕히 가세요!")
            break
        }

        try {
            val response = governor.chat(session.sessionId, input)

            println()
            print("Governor [${response.action}] > ")
            println(response.message)

            // 추가 정보 출력
            when (response.action) {
                ActionType.ASK -> {
                    response.askingFor?.let {
                        println("  (다음 정보 필요: $it)")
                    }
                }
                ActionType.CONFIRM -> {
                    response.confirmationSummary?.let {
                        println()
                        println("─── 확인 요약 ───")
                        println(it)
                        println("─".repeat(20))
                    }
                }
                ActionType.EXECUTE -> {
                    response.blueprint?.let { bp ->
                        println()
                        println("─── Blueprint ───")
                        println("ID: ${bp.id}")
                        println("Steps: ${bp.steps.size}개")
                        bp.steps.forEachIndexed { i, step ->
                            println("  ${i + 1}. ${step.type}: ${step.params}")
                        }
                        println("─".repeat(20))
                    }
                    response.executionResult?.let { result ->
                        println()
                        println("─── 실행 결과 ───")
                        println("성공: ${result.isSuccess}")
                        println("성공 step: ${result.successCount}개")
                        if (result.failureCount > 0) {
                            println("실패 step: ${result.failureCount}개")
                        }
                        println("─".repeat(20))
                    }
                }
                ActionType.CANCEL -> {
                    println("  (세션이 리셋되었습니다)")
                }
                else -> {}
            }

            println()
        } catch (e: Exception) {
            println()
            println("[ERROR] ${e.message}")
            println()
        }
    }

    governor.endSession(session.sessionId)
}
