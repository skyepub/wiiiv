package io.wiiiv.plugins.spreadsheet

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginConfig
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.time.Instant

/**
 * Spreadsheet Executor — Excel/CSV 파일 읽기/쓰기
 *
 * 액션:
 * - read_excel: Excel → JSON 배열
 * - write_excel: JSON → Excel 파일 생성
 * - read_csv: CSV → JSON 배열
 * - write_csv: JSON → CSV 파일 생성
 */
class SpreadsheetExecutor(config: PluginConfig) : Executor {

    private val baseDir = config.env["BASE_DIR"] ?: "/tmp"
    private val maxRows = config.env["MAX_ROWS"]?.toIntOrNull() ?: 100_000

    override fun canHandle(step: ExecutionStep): Boolean =
        step is ExecutionStep.PluginStep && step.pluginId == "spreadsheet"

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val ps = step as ExecutionStep.PluginStep
        val startedAt = Instant.now()

        return try {
            when (ps.action) {
                "read_excel" -> executeReadExcel(ps, startedAt)
                "write_excel" -> executeWriteExcel(ps, startedAt)
                "read_csv" -> executeReadCsv(ps, startedAt)
                "write_csv" -> executeWriteCsv(ps, startedAt)
                else -> contractViolation(ps.stepId, "UNKNOWN_ACTION", "Unknown spreadsheet action: ${ps.action}")
            }
        } catch (e: IllegalArgumentException) {
            ExecutionResult.failure(
                error = ExecutionError.contractViolation("INVALID_PARAM", e.message ?: "Invalid parameter"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.ioError("SPREADSHEET_ERROR", "Spreadsheet error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }
    }

    private fun executeReadExcel(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val path = ps.params["path"]
            ?: return contractViolation(ps.stepId, "MISSING_PATH", "read_excel requires 'path' param")
        validatePath(path)?.let { return it(ps.stepId) }

        val sheet = ps.params["sheet"]
        val headerRow = ps.params["header_row"]?.toIntOrNull() ?: 0

        val json = ExcelHandler.read(path, sheet, headerRow, maxRows)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("read_excel"),
                    "path" to JsonPrimitive(path),
                    "data" to JsonPrimitive(json)
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(path))
        )
    }

    private fun executeWriteExcel(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val path = ps.params["path"]
            ?: return contractViolation(ps.stepId, "MISSING_PATH", "write_excel requires 'path' param")
        val data = ps.params["data"]
            ?: return contractViolation(ps.stepId, "MISSING_DATA", "write_excel requires 'data' param (JSON array)")
        validatePath(path)?.let { return it(ps.stepId) }

        val sheetName = ps.params["sheet"] ?: "Sheet1"
        ExcelHandler.write(path, data, sheetName)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput(
                stepId = ps.stepId,
                json = mapOf(
                    "action" to JsonPrimitive("write_excel"),
                    "path" to JsonPrimitive(path),
                    "status" to JsonPrimitive("written")
                ),
                artifacts = mapOf("excel" to path),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(path))
        )
    }

    private fun executeReadCsv(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val path = ps.params["path"]
            ?: return contractViolation(ps.stepId, "MISSING_PATH", "read_csv requires 'path' param")
        validatePath(path)?.let { return it(ps.stepId) }

        val delimiter = ps.params["delimiter"]?.firstOrNull() ?: ','
        val encoding = ps.params["encoding"] ?: "UTF-8"
        val hasHeader = ps.params["header"]?.toBoolean() ?: true

        val json = CsvHandler.read(path, delimiter, encoding, hasHeader, maxRows)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("read_csv"),
                    "path" to JsonPrimitive(path),
                    "data" to JsonPrimitive(json)
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(path))
        )
    }

    private fun executeWriteCsv(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val path = ps.params["path"]
            ?: return contractViolation(ps.stepId, "MISSING_PATH", "write_csv requires 'path' param")
        val data = ps.params["data"]
            ?: return contractViolation(ps.stepId, "MISSING_DATA", "write_csv requires 'data' param (JSON array)")
        validatePath(path)?.let { return it(ps.stepId) }

        val delimiter = ps.params["delimiter"]?.firstOrNull() ?: ','
        val encoding = ps.params["encoding"] ?: "UTF-8"
        val writeHeader = ps.params["header"]?.toBoolean() ?: true

        CsvHandler.write(path, data, delimiter, encoding, writeHeader)
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput(
                stepId = ps.stepId,
                json = mapOf(
                    "action" to JsonPrimitive("write_csv"),
                    "path" to JsonPrimitive(path),
                    "status" to JsonPrimitive("written")
                ),
                artifacts = mapOf("csv" to path),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(path))
        )
    }

    /**
     * 경로 보안: 절대경로가 baseDir 하위인지 검증
     */
    private fun validatePath(path: String): ((String) -> ExecutionResult)? {
        val canonical = File(path).canonicalPath
        val baseCanonical = File(baseDir).canonicalPath
        if (!canonical.startsWith(baseCanonical)) {
            return { stepId ->
                ExecutionResult.failure(
                    error = ExecutionError(
                        category = ErrorCategory.PERMISSION_DENIED,
                        code = "PATH_OUTSIDE_BASE_DIR",
                        message = "Path $path is outside allowed base directory: $baseDir"
                    ),
                    meta = ExecutionMeta.now(stepId)
                )
            }
        }
        return null
    }

    private fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
        ExecutionResult.contractViolation(stepId = stepId, code = code, message = message)

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = false
}
