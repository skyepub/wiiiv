package io.wiiiv.plugins.pdf

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.WiiivPlugin

/**
 * PDF Plugin — HTML→PDF 생성, PDF 파싱, PDF 병합
 *
 * 코드가 진실 (single source of truth):
 * actions, risk, capability는 여기서 선언한다.
 */
class PdfPlugin : WiiivPlugin {
    override val pluginId = "pdf"
    override val displayName = "PDF Generator"
    override val version = "1.0.0"

    override fun createExecutor(config: PluginConfig): Executor = PdfExecutor(config)

    override fun executorMeta(): ExecutorMeta = ExecutorMeta(
        scheme = "pdf",
        name = "PdfExecutor",
        capabilities = setOf(Capability.READ, Capability.WRITE),
        idempotent = false,
        riskLevel = RiskLevel.MEDIUM,
        stepType = StepType.PLUGIN,
        description = "HTML 템플릿 → PDF 생성, PDF 파싱, PDF 병합",
        actionRiskLevels = mapOf(
            "generate" to RiskLevel.MEDIUM,
            "parse" to RiskLevel.LOW,
            "merge" to RiskLevel.MEDIUM
        )
    )

    override fun actions(): List<PluginAction> = listOf(
        PluginAction(
            name = "generate",
            description = "HTML 템플릿 + JSON 데이터 → PDF 파일 생성",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.WRITE),
            requiredParams = listOf("template", "data"),
            optionalParams = listOf("output_path", "page_size", "margin")
        ),
        PluginAction(
            name = "parse",
            description = "PDF → 텍스트 추출",
            riskLevel = RiskLevel.LOW,
            capabilities = setOf(Capability.READ),
            requiredParams = listOf("path"),
            optionalParams = listOf("pages", "max_length")
        ),
        PluginAction(
            name = "merge",
            description = "여러 PDF 합치기",
            riskLevel = RiskLevel.MEDIUM,
            capabilities = setOf(Capability.WRITE),
            requiredParams = listOf("paths"),
            optionalParams = listOf("output_path")
        )
    )
}
