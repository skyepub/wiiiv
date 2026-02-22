package io.wiiiv.server

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginAction
import io.wiiiv.plugin.PluginConfig
import io.wiiiv.plugin.PluginRegistry
import io.wiiiv.plugin.LoadedPlugin
import io.wiiiv.plugins.cron.CronParser
import io.wiiiv.plugins.cron.CronPlugin
import io.wiiiv.plugins.pdf.PdfPlugin
import io.wiiiv.plugins.pdf.TemplateEngine
import io.wiiiv.plugins.spreadsheet.SpreadsheetPlugin
import io.wiiiv.plugins.webfetch.WebFetchPlugin
import io.wiiiv.plugins.webfetch.SsrfGuard as WebFetchSsrfGuard
import io.wiiiv.plugins.webhook.WebhookPlugin
import io.wiiiv.plugins.webhook.SsrfGuard as WebhookSsrfGuard
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.time.ZonedDateTime
import kotlin.test.*

/**
 * PluginUnitTest — 플러그인 인프라 + 개별 Executor 단위 테스트
 *
 * wiiiv-server에 배치 (순환 참조 방지)
 */
class PluginUnitTest {

    private lateinit var tempDir: File

    /** 테스트용 ExecutionContext 생성 (필수 파라미터 제공) */
    private fun testContext() = ExecutionContext(
        executionId = "test-exec-${System.nanoTime()}",
        blueprintId = "test-bp",
        instructionId = "test-inst"
    )

    @BeforeTest
    fun setup() {
        tempDir = Files.createTempDirectory("plugin-test").toFile()
    }

    @AfterTest
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    // ════════════════════════════════════════════
    //  1. Plugin Infrastructure
    // ════════════════════════════════════════════

    @Test
    fun `WiiivPlugin interface — pluginId, version, actions correctly declared`() {
        val plugins = listOf(CronPlugin(), PdfPlugin(), SpreadsheetPlugin(), WebFetchPlugin(), WebhookPlugin())
        plugins.forEach { plugin ->
            assertTrue(plugin.pluginId.isNotBlank(), "pluginId should not be blank: ${plugin::class.simpleName}")
            assertTrue(plugin.version.isNotBlank(), "version should not be blank: ${plugin.pluginId}")
            assertTrue(plugin.displayName.isNotBlank(), "displayName should not be blank: ${plugin.pluginId}")
            assertTrue(plugin.actions().isNotEmpty(), "actions should not be empty: ${plugin.pluginId}")
        }
    }

    @Test
    fun `PluginAction — requiredParams validation`() {
        val plugins = listOf(CronPlugin(), PdfPlugin(), SpreadsheetPlugin(), WebFetchPlugin(), WebhookPlugin())
        plugins.forEach { plugin ->
            plugin.actions().forEach { action ->
                assertNotNull(action.requiredParams, "requiredParams null: ${plugin.pluginId}/${action.name}")
                assertTrue(action.riskLevel in listOf(RiskLevel.LOW, RiskLevel.MEDIUM, RiskLevel.HIGH),
                    "Invalid riskLevel for ${plugin.pluginId}/${action.name}")
            }
        }
    }

    @Test
    fun `PluginConfig — env map construction`() {
        val config = PluginConfig(env = mapOf("KEY" to "value", "OTHER" to "123"))
        assertEquals("value", config.env["KEY"])
        assertEquals("123", config.env["OTHER"])
    }

    @Test
    fun `PluginRegistry — get by pluginId, all, size`() {
        val plugins = listOf(CronPlugin(), PdfPlugin(), WebhookPlugin())
        val dummyClassLoader = java.net.URLClassLoader(arrayOf(), this::class.java.classLoader)
        val loaded = plugins.map { plugin ->
            LoadedPlugin(
                plugin = plugin,
                executor = plugin.createExecutor(PluginConfig()),
                meta = plugin.executorMeta(),
                actions = plugin.actions(),
                manifest = null,
                jarPath = "test.jar",
                classLoader = dummyClassLoader
            )
        }
        val registry = PluginRegistry(loaded)

        assertEquals(3, registry.size)
        assertEquals(3, registry.all().size)
        assertNotNull(registry.get("cron"))
        assertNotNull(registry.get("pdf"))
        assertNotNull(registry.get("webhook"))
        assertNull(registry.get("non-existent"))
    }

    @Test
    fun `LoadedPlugin — executor and meta correctly paired`() {
        val plugin = CronPlugin()
        val config = PluginConfig()
        val executor = plugin.createExecutor(config)
        val meta = plugin.executorMeta()

        assertNotNull(executor)
        assertEquals("cron", meta.scheme)
        assertEquals(StepType.PLUGIN, meta.stepType)
    }

    // ════════════════════════════════════════════
    //  2. CronPlugin
    // ════════════════════════════════════════════

    @Test
    fun `CronParser — parse valid expression Mon 9am`() {
        val parser = CronParser("0 9 * * 1")
        // Find next Monday 9:00 from now
        val next = parser.nextExecution(ZonedDateTime.now())
        assertEquals(0, next.minute)
        assertEquals(9, next.hour)
    }

    @Test
    fun `CronParser — reject invalid expression`() {
        assertFailsWith<IllegalArgumentException> {
            CronParser("60 * * *") // only 4 fields, need 5
        }
    }

    @Test
    fun `CronPlugin actions — 4 actions`() {
        val plugin = CronPlugin()
        val actions = plugin.actions()
        assertEquals(4, actions.size)
        val names = actions.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("delay", "schedule", "list", "cancel")))
    }

    @Test
    fun `CronExecutor delay — waits specified duration`() = runBlocking {
        val plugin = CronPlugin()
        val executor = plugin.createExecutor(PluginConfig())
        val step = ExecutionStep.PluginStep(
            stepId = "test-delay",
            pluginId = "cron",
            action = "delay",
            params = mapOf("duration_ms" to "50")
        )
        val start = System.currentTimeMillis()
        val result = executor.execute(step, testContext())
        val elapsed = System.currentTimeMillis() - start

        assertTrue(result.isSuccess, "delay should succeed")
        assertTrue(elapsed >= 40, "should have waited ~50ms, elapsed=$elapsed")
    }

    @Test
    fun `CronExecutor schedule — missing cron_expr returns CONTRACT_VIOLATION`() = runBlocking {
        val plugin = CronPlugin()
        val executor = plugin.createExecutor(PluginConfig())
        val step = ExecutionStep.PluginStep(
            stepId = "test-sched",
            pluginId = "cron",
            action = "schedule",
            params = mapOf("callback_url" to "http://localhost/cb")
        )
        val result = executor.execute(step, testContext())
        assertFalse(result.isSuccess)
    }

    // ════════════════════════════════════════════
    //  3. PdfPlugin
    // ════════════════════════════════════════════

    @Test
    fun `PdfPlugin actions — 3 actions`() {
        val plugin = PdfPlugin()
        val actions = plugin.actions()
        assertEquals(3, actions.size)
        val names = actions.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("generate", "parse", "merge")))
    }

    @Test
    fun `PdfExecutor generate — inline HTML template creates PDF file`() = runBlocking {
        val plugin = PdfPlugin()
        val outputPath = File(tempDir, "test-output.pdf").absolutePath
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("OUTPUT_DIR" to tempDir.absolutePath)))
        val step = ExecutionStep.PluginStep(
            stepId = "test-gen",
            pluginId = "pdf",
            action = "generate",
            params = mapOf(
                "template" to "<h1>Hello {{name}}</h1><p>This is a test</p>",
                "data" to """{"name":"World"}""",
                "output_path" to outputPath
            )
        )
        val result = executor.execute(step, testContext())
        assertTrue(result.isSuccess, "generate should succeed: ${(result as? ExecutionResult.Failure)?.error}")
        assertTrue(File(outputPath).exists(), "PDF file should exist")
        assertTrue(File(outputPath).length() > 0, "PDF file should not be empty")
    }

    @Test
    fun `PdfExecutor parse — missing path returns CONTRACT_VIOLATION`() = runBlocking {
        val plugin = PdfPlugin()
        val executor = plugin.createExecutor(PluginConfig())
        val step = ExecutionStep.PluginStep(
            stepId = "test-parse",
            pluginId = "pdf",
            action = "parse",
            params = emptyMap()
        )
        val result = executor.execute(step, testContext())
        assertFalse(result.isSuccess)
    }

    @Test
    fun `PdfExecutor parse — extract text from generated PDF`() = runBlocking {
        val plugin = PdfPlugin()
        val outputPath = File(tempDir, "gen.pdf").absolutePath
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("OUTPUT_DIR" to tempDir.absolutePath)))

        // First generate a PDF
        val genStep = ExecutionStep.PluginStep(
            stepId = "gen",
            pluginId = "pdf",
            action = "generate",
            params = mapOf(
                "template" to "<p>Hello extraction test</p>",
                "data" to "{}",
                "output_path" to outputPath
            )
        )
        executor.execute(genStep, testContext())

        // Then parse it
        val parseStep = ExecutionStep.PluginStep(
            stepId = "parse",
            pluginId = "pdf",
            action = "parse",
            params = mapOf("path" to outputPath)
        )
        val result = executor.execute(parseStep, testContext())
        assertTrue(result.isSuccess, "parse should succeed")
    }

    @Test
    fun `PdfExecutor merge — 2 PDFs merged`() = runBlocking {
        val plugin = PdfPlugin()
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("OUTPUT_DIR" to tempDir.absolutePath)))

        val pdf1 = File(tempDir, "a.pdf").absolutePath
        val pdf2 = File(tempDir, "b.pdf").absolutePath
        val merged = File(tempDir, "merged.pdf").absolutePath

        // Generate 2 PDFs
        for ((path, text) in listOf(pdf1 to "Page A", pdf2 to "Page B")) {
            val step = ExecutionStep.PluginStep(
                stepId = "gen-$text",
                pluginId = "pdf",
                action = "generate",
                params = mapOf("template" to "<p>$text</p>", "data" to "{}", "output_path" to path)
            )
            executor.execute(step, testContext())
        }

        // Merge
        val mergeStep = ExecutionStep.PluginStep(
            stepId = "merge",
            pluginId = "pdf",
            action = "merge",
            params = mapOf("paths" to "$pdf1,$pdf2", "output_path" to merged)
        )
        val result = executor.execute(mergeStep, testContext())
        assertTrue(result.isSuccess, "merge should succeed")
        assertTrue(File(merged).exists())
    }

    @Test
    fun `TemplateEngine — Mustache variable substitution`() {
        val rendered = TemplateEngine.render("Hello {{name}}, age {{age}}", mapOf("name" to "Bob", "age" to 30))
        assertTrue(rendered.contains("Hello Bob"))
        assertTrue(rendered.contains("age 30"))
    }

    // ════════════════════════════════════════════
    //  4. SpreadsheetPlugin
    // ════════════════════════════════════════════

    @Test
    fun `SpreadsheetPlugin actions — 4 actions`() {
        val plugin = SpreadsheetPlugin()
        val actions = plugin.actions()
        assertEquals(4, actions.size)
        val names = actions.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("read_excel", "write_excel", "read_csv", "write_csv")))
    }

    @Test
    fun `SpreadsheetExecutor write_csv then read_csv — roundtrip`() = runBlocking {
        val csvPath = File(tempDir, "test.csv").absolutePath
        val plugin = SpreadsheetPlugin()
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("BASE_DIR" to tempDir.absolutePath)))

        // Write
        val writeStep = ExecutionStep.PluginStep(
            stepId = "write-csv",
            pluginId = "spreadsheet",
            action = "write_csv",
            params = mapOf(
                "path" to csvPath,
                "data" to """[{"name":"Alice","score":"95"},{"name":"Bob","score":"87"}]"""
            )
        )
        val writeResult = executor.execute(writeStep, testContext())
        assertTrue(writeResult.isSuccess, "write_csv should succeed")

        // Read
        val readStep = ExecutionStep.PluginStep(
            stepId = "read-csv",
            pluginId = "spreadsheet",
            action = "read_csv",
            params = mapOf("path" to csvPath)
        )
        val readResult = executor.execute(readStep, testContext())
        assertTrue(readResult.isSuccess, "read_csv should succeed")
    }

    @Test
    fun `SpreadsheetExecutor write_excel then read_excel — roundtrip`() = runBlocking {
        val xlsxPath = File(tempDir, "test.xlsx").absolutePath
        val plugin = SpreadsheetPlugin()
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("BASE_DIR" to tempDir.absolutePath)))

        // Write
        val writeStep = ExecutionStep.PluginStep(
            stepId = "write-xlsx",
            pluginId = "spreadsheet",
            action = "write_excel",
            params = mapOf(
                "path" to xlsxPath,
                "data" to """[{"id":"1","name":"Item A"},{"id":"2","name":"Item B"}]"""
            )
        )
        val writeResult = executor.execute(writeStep, testContext())
        assertTrue(writeResult.isSuccess, "write_excel should succeed")

        // Read
        val readStep = ExecutionStep.PluginStep(
            stepId = "read-xlsx",
            pluginId = "spreadsheet",
            action = "read_excel",
            params = mapOf("path" to xlsxPath)
        )
        val readResult = executor.execute(readStep, testContext())
        assertTrue(readResult.isSuccess, "read_excel should succeed")
    }

    @Test
    fun `SpreadsheetExecutor — path outside baseDir rejected`() = runBlocking {
        val plugin = SpreadsheetPlugin()
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("BASE_DIR" to tempDir.absolutePath)))

        val step = ExecutionStep.PluginStep(
            stepId = "bad-path",
            pluginId = "spreadsheet",
            action = "read_csv",
            params = mapOf("path" to "/etc/passwd")
        )
        val result = executor.execute(step, testContext())
        assertFalse(result.isSuccess, "should reject path outside baseDir")
    }

    @Test
    fun `SpreadsheetExecutor read_csv — custom delimiter`() = runBlocking {
        val csvPath = File(tempDir, "tab.csv").absolutePath
        val plugin = SpreadsheetPlugin()
        val executor = plugin.createExecutor(PluginConfig(env = mapOf("BASE_DIR" to tempDir.absolutePath)))

        // Write with tab delimiter
        val writeStep = ExecutionStep.PluginStep(
            stepId = "write-tab",
            pluginId = "spreadsheet",
            action = "write_csv",
            params = mapOf(
                "path" to csvPath,
                "data" to """[{"col1":"a","col2":"b"}]""",
                "delimiter" to "\t"
            )
        )
        executor.execute(writeStep, testContext())

        // Read with tab delimiter
        val readStep = ExecutionStep.PluginStep(
            stepId = "read-tab",
            pluginId = "spreadsheet",
            action = "read_csv",
            params = mapOf("path" to csvPath, "delimiter" to "\t")
        )
        val result = executor.execute(readStep, testContext())
        assertTrue(result.isSuccess)
    }

    // ════════════════════════════════════════════
    //  5. WebFetchPlugin
    // ════════════════════════════════════════════

    @Test
    fun `WebFetchPlugin actions — 3 actions`() {
        val plugin = WebFetchPlugin()
        val actions = plugin.actions()
        assertEquals(3, actions.size)
        val names = actions.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("fetch", "fetch_json", "search")))
    }

    @Test
    fun `WebFetch SsrfGuard — blocks file scheme`() {
        val guard = WebFetchSsrfGuard()
        val result = guard.validate("file:///etc/passwd")
        assertNotNull(result)
        assertTrue(result.contains("BLOCKED_SCHEME"))
    }

    @Test
    fun `WebFetch SsrfGuard — blocks metadata IP 169_254_169_254`() {
        val guard = WebFetchSsrfGuard()
        val result = guard.validate("http://169.254.169.254/latest/meta-data/")
        assertNotNull(result)
        assertTrue(result.contains("BLOCKED_METADATA_IP"))
    }

    @Test
    fun `WebFetch SsrfGuard — allows https public URL`() {
        val guard = WebFetchSsrfGuard()
        val result = guard.validate("https://example.com/api")
        assertNull(result)
    }

    @Test
    fun `WebFetchExecutor fetch — missing url returns CONTRACT_VIOLATION`() = runBlocking {
        val plugin = WebFetchPlugin()
        val executor = plugin.createExecutor(PluginConfig())
        val step = ExecutionStep.PluginStep(
            stepId = "test-fetch",
            pluginId = "webfetch",
            action = "fetch",
            params = emptyMap()
        )
        val result = executor.execute(step, testContext())
        assertFalse(result.isSuccess)
    }

    // ════════════════════════════════════════════
    //  6. WebhookPlugin
    // ════════════════════════════════════════════

    @Test
    fun `WebhookPlugin actions — 3 actions`() {
        val plugin = WebhookPlugin()
        val actions = plugin.actions()
        assertEquals(3, actions.size)
        val names = actions.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf("ping", "send", "send_form")))
    }

    @Test
    fun `Webhook SsrfGuard — blocks private IP when ALLOW_PRIVATE_IP false`() {
        val guard = WebhookSsrfGuard(allowPrivateIp = false, allowLocalhost = false)
        val result = guard.validate("http://192.168.1.1/api")
        assertNotNull(result)
        assertTrue(result.contains("BLOCKED"))
    }

    @Test
    fun `WebhookExecutor send — missing url returns CONTRACT_VIOLATION`() = runBlocking {
        val plugin = WebhookPlugin()
        val executor = plugin.createExecutor(PluginConfig())
        val step = ExecutionStep.PluginStep(
            stepId = "test-send",
            pluginId = "webhook",
            action = "send",
            params = mapOf("body" to """{"test":true}""")
        )
        val result = executor.execute(step, testContext())
        assertFalse(result.isSuccess)
    }

    @Test
    fun `WebhookExecutor send — missing body returns CONTRACT_VIOLATION`() = runBlocking {
        val plugin = WebhookPlugin()
        val executor = plugin.createExecutor(PluginConfig())
        val step = ExecutionStep.PluginStep(
            stepId = "test-send-nobody",
            pluginId = "webhook",
            action = "send",
            params = mapOf("url" to "https://example.com/hook")
        )
        val result = executor.execute(step, testContext())
        assertFalse(result.isSuccess)
    }

    @Test
    fun `WebhookPlugin executorMeta — scheme webhook, riskLevel MEDIUM`() {
        val plugin = WebhookPlugin()
        val meta = plugin.executorMeta()
        assertEquals("webhook", meta.scheme)
        assertEquals(RiskLevel.MEDIUM, meta.riskLevel)
    }

    // ════════════════════════════════════════════
    //  7. Plugin ExecutorMeta Governance
    // ════════════════════════════════════════════

    @Test
    fun `all 5 plugins have distinct schemes`() {
        val plugins = listOf(CronPlugin(), PdfPlugin(), SpreadsheetPlugin(), WebFetchPlugin(), WebhookPlugin())
        val schemes = plugins.map { it.executorMeta().scheme }
        assertEquals(schemes.size, schemes.toSet().size, "All schemes should be unique: $schemes")
    }

    @Test
    fun `all plugin actions declare riskLevel`() {
        val plugins = listOf(CronPlugin(), PdfPlugin(), SpreadsheetPlugin(), WebFetchPlugin(), WebhookPlugin())
        plugins.forEach { plugin ->
            plugin.actions().forEach { action ->
                assertNotNull(action.riskLevel, "${plugin.pluginId}/${action.name} missing riskLevel")
            }
        }
    }

    @Test
    fun `all plugin actions declare requiredParams`() {
        val plugins = listOf(CronPlugin(), PdfPlugin(), SpreadsheetPlugin(), WebFetchPlugin(), WebhookPlugin())
        plugins.forEach { plugin ->
            plugin.actions().forEach { action ->
                assertNotNull(action.requiredParams, "${plugin.pluginId}/${action.name} missing requiredParams")
            }
        }
    }
}
