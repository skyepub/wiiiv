package io.wiiiv.plugins.spreadsheet

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.WiiivPlugin

/**
 * Spreadsheet Plugin — Excel/CSV 파일 읽기/쓰기
 *
 * 코드가 진실 (single source of truth):
 * actions, risk, capability는 여기서 선언한다.
 */
class SpreadsheetPlugin : WiiivPlugin {
    override val pluginId = "spreadsheet"
    override val displayName = "Spreadsheet (Excel/CSV)"
    override val version = "1.0.0"

    override fun createExecutor(config: PluginConfig): Executor = SpreadsheetExecutor(config)

    override fun executorMeta(): ExecutorMeta = ExecutorMeta(
        scheme = "spreadsheet",
        name = "SpreadsheetExecutor",
        capabilities = setOf(Capability.READ, Capability.WRITE),
        idempotent = false,
        riskLevel = RiskLevel.MEDIUM,
        stepType = StepType.PLUGIN,
        description = "Excel/CSV 파일 읽기/쓰기 데이터 변환",
        actionRiskLevels = mapOf(
            "read_excel" to RiskLevel.LOW,
            "write_excel" to RiskLevel.MEDIUM,
            "read_csv" to RiskLevel.LOW,
            "write_csv" to RiskLevel.MEDIUM
        )
    )

    override fun actions(): List<PluginAction> = listOf(
        PluginAction(
            name = "read_excel",
            description = "Excel 파일 → JSON 배열 변환",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("path"),
            optionalParams = listOf("sheet", "range", "header_row")
        ),
        PluginAction(
            name = "write_excel",
            description = "JSON 배열 → Excel 파일 생성",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.WRITE),
            requiredParams = listOf("path", "data"),
            optionalParams = listOf("sheet", "template_path")
        ),
        PluginAction(
            name = "read_csv",
            description = "CSV 파일 → JSON 배열 변환",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("path"),
            optionalParams = listOf("delimiter", "encoding", "header")
        ),
        PluginAction(
            name = "write_csv",
            description = "JSON 배열 → CSV 파일 생성",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.WRITE),
            requiredParams = listOf("path", "data"),
            optionalParams = listOf("delimiter", "encoding", "header")
        )
    )
}
