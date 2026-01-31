package io.wiiiv.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.wiiiv.cli.commands.*

/**
 * wiiiv CLI - LLM Governor 기반 실행 시스템 터미널 인터페이스
 *
 * ## CLI 헌법
 * 1. CLI는 판단하지 않는다
 * 2. CLI는 해석하지 않는다
 * 3. CLI는 API 리소스를 1:1로 반영한다
 * 4. CLI는 상태를 만들지 않는다
 * 5. CLI는 자동화 가능해야 한다
 *
 * CLI = REST API의 인간 친화적 Projection
 */
class Wiiiv : CliktCommand(
    name = "wiiiv",
    help = "wiiiv v2.0 - LLM Governor 기반 실행 시스템"
) {
    val json by option("--json", help = "JSON 형식으로 출력 (자동화/스크립트용)").flag()
    val quiet by option("--quiet", "-q", help = "최소 출력").flag()
    val trace by option("--trace", help = "상세 디버그 출력").flag()
    val api by option("--api", help = "API 서버 URL").default("http://localhost:8235")

    override fun run() {
        // 전역 옵션을 컨텍스트에 저장
        currentContext.obj = CliContext(
            json = json,
            quiet = quiet,
            trace = trace,
            apiUrl = api
        )
    }
}

/**
 * CLI 전역 컨텍스트
 */
data class CliContext(
    val json: Boolean = false,
    val quiet: Boolean = false,
    val trace: Boolean = false,
    val apiUrl: String = "http://localhost:8235"
)

fun main(args: Array<String>) {
    val cli = Wiiiv()
        .subcommands(
            AuthCommand(),
            DecisionCommand(),
            BlueprintCommand(),
            ExecutionCommand(),
            SystemCommand(),
            ConfigCommand(),
            RagCommand()
        )
    cli.main(args)
}
