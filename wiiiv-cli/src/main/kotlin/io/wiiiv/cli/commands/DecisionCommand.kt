package io.wiiiv.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.wiiiv.cli.CliContext
import io.wiiiv.cli.client.WiiivClient
import io.wiiiv.cli.output.Printer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*

/**
 * wiiiv decision - "의뢰" 단계
 *
 * ❗ judge / decide / ask 같은 동사는 쓰지 않음
 *
 * 의미:
 * - Spec 전달
 * - Governor + DACS 내부 수행
 * - 결과를 Blueprint로만 반환
 *
 * CLI는:
 * - YES/NO 판단 ❌
 * - 좋다/나쁘다 ❌
 * - Blueprint 구조 그대로 출력
 */
class DecisionCommand : CliktCommand(
    name = "decision",
    help = "Governor 판단 요청 관리"
) {
    override fun run() = Unit

    init {
        subcommands(
            DecisionCreate(),
            DecisionGet(),
            DecisionList(),
            DecisionApprove(),
            DecisionReject()
        )
    }
}

/**
 * wiiiv decision create --input "..." [--constraint ...]
 */
class DecisionCreate : CliktCommand(
    name = "create",
    help = "새 판단 요청 (Governor + DACS)"
) {
    private val ctx by requireObject<CliContext>()
    private val input by option("-i", "--input", help = "의도/요청 내용").required()
    private val constraints by option("-c", "--constraint", help = "제약 조건 (여러 개 가능)").multiple()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            Printer.info(ctx, "Creating decision...")

            val response = client.createDecision(
                intent = input,
                constraints = constraints.ifEmpty { null }
            )

            Printer.print(ctx, response)

            // 성공 시 Blueprint ID 강조
            if (!ctx.json && !ctx.quiet) {
                val data = response["data"]?.jsonObject
                val blueprintId = data?.get("blueprintId")?.jsonPrimitive?.contentOrNull
                val status = data?.get("status")?.jsonPrimitive?.contentOrNull

                println()
                println("─".repeat(50))
                println("Status: $status")
                if (blueprintId != null) {
                    println("Blueprint ID: $blueprintId")
                    println()
                    println("Next steps:")
                    println("  wiiiv blueprint get $blueprintId")
                    println("  wiiiv decision approve ${data?.get("decisionId")?.jsonPrimitive?.content}")
                }
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to create decision")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv decision get <decision-id>
 */
class DecisionGet : CliktCommand(
    name = "get",
    help = "판단 결과 조회"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("decision-id", help = "Decision ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.getDecision(id)
            Printer.print(ctx, response)
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to get decision")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv decision list
 */
class DecisionList : CliktCommand(
    name = "list",
    help = "판단 목록 조회"
) {
    private val ctx by requireObject<CliContext>()

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            // Note: API에 list 엔드포인트가 없으면 추가 필요
            Printer.info(ctx, "Decision list endpoint not yet implemented in API")
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to list decisions")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv decision approve <decision-id>
 *
 * Note: 이 명령은 사용자 승인을 Gate에 전달하는 것이지,
 * CLI가 판단하는 것이 아님
 */
class DecisionApprove : CliktCommand(
    name = "approve",
    help = "사용자 승인 (Gate 통과용)"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("decision-id", help = "Decision ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.approveDecision(id)
            Printer.print(ctx, response)

            if (!ctx.json && !ctx.quiet) {
                val data = response["data"]?.jsonObject
                val approved = data?.get("approved")?.jsonPrimitive?.booleanOrNull
                val blueprintId = data?.get("blueprintId")?.jsonPrimitive?.contentOrNull

                if (approved == true && blueprintId != null) {
                    println()
                    println("Ready for execution:")
                    println("  wiiiv execution create --blueprint $blueprintId")
                }
            }
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to approve decision")
        } finally {
            client.close()
        }
    }
}

/**
 * wiiiv decision reject <decision-id>
 */
class DecisionReject : CliktCommand(
    name = "reject",
    help = "사용자 거부"
) {
    private val ctx by requireObject<CliContext>()
    private val id by argument("decision-id", help = "Decision ID")

    override fun run() = runBlocking {
        val client = createClient(ctx)
        try {
            val response = client.rejectDecision(id)
            Printer.print(ctx, response)
        } catch (e: Exception) {
            Printer.error(ctx, e.message ?: "Failed to reject decision")
        } finally {
            client.close()
        }
    }
}
