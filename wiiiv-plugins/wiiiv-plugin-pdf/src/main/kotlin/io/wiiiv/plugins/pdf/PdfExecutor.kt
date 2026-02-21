package io.wiiiv.plugins.pdf

import io.wiiiv.execution.*
import io.wiiiv.plugin.PluginConfig
import kotlinx.serialization.json.JsonPrimitive
import org.apache.pdfbox.Loader
import org.apache.pdfbox.io.RandomAccessReadBufferedFile
import org.apache.pdfbox.multipdf.PDFMergerUtility
import org.apache.pdfbox.text.PDFTextStripper
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * PDF Executor — HTML→PDF 생성, PDF 파싱, PDF 병합
 *
 * 액션:
 * - generate: HTML 템플릿 + JSON 데이터 → PDF 파일 생성
 * - parse: PDF → 텍스트 추출
 * - merge: 여러 PDF 합치기
 */
class PdfExecutor(config: PluginConfig) : Executor {

    private val templateDir = config.env["TEMPLATE_DIR"]
        ?: "${System.getProperty("user.home")}/.wiiiv/templates/pdf"
    private val outputDir = config.env["OUTPUT_DIR"] ?: "/tmp"
    private val maxSizeMb = config.env["MAX_SIZE_MB"]?.toIntOrNull() ?: 50

    override fun canHandle(step: ExecutionStep): Boolean =
        step is ExecutionStep.PluginStep && step.pluginId == "pdf"

    override suspend fun execute(step: ExecutionStep, context: ExecutionContext): ExecutionResult {
        val ps = step as ExecutionStep.PluginStep
        val startedAt = Instant.now()

        return try {
            when (ps.action) {
                "generate" -> executeGenerate(ps, startedAt)
                "parse" -> executeParse(ps, startedAt)
                "merge" -> executeMerge(ps, startedAt)
                else -> contractViolation(ps.stepId, "UNKNOWN_ACTION", "Unknown pdf action: ${ps.action}")
            }
        } catch (e: IllegalArgumentException) {
            ExecutionResult.failure(
                error = ExecutionError.contractViolation("INVALID_PARAM", e.message ?: "Invalid parameter"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        } catch (e: Exception) {
            ExecutionResult.failure(
                error = ExecutionError.ioError("PDF_ERROR", "PDF error: ${e.message}"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }
    }

    private fun executeGenerate(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val template = ps.params["template"]
            ?: return contractViolation(ps.stepId, "MISSING_TEMPLATE", "generate requires 'template' param")
        val dataJson = ps.params["data"]
            ?: return contractViolation(ps.stepId, "MISSING_DATA", "generate requires 'data' param (JSON)")

        val outputPath = ps.params["output_path"]
            ?: "$outputDir/wiiiv-pdf-${UUID.randomUUID().toString().take(8)}.pdf"
        val pageSize = ps.params["page_size"] ?: "A4"

        // 템플릿 해석: file: 접두사면 파일 경로, 아니면 인라인 HTML
        val html = if (template.startsWith("file:")) {
            val templatePath = template.removePrefix("file:")
            val resolvedPath = if (templatePath.startsWith("/")) templatePath
            else "$templateDir/$templatePath"
            val data = TemplateEngine.jsonToMap(dataJson)
            TemplateEngine.renderFile(resolvedPath, data)
        } else {
            val data = TemplateEngine.jsonToMap(dataJson)
            TemplateEngine.render(template, data)
        }

        HtmlToPdfRenderer.render(html, outputPath, pageSize)

        // 파일 크기 검증
        val fileSize = File(outputPath).length()
        if (fileSize > maxSizeMb * 1_048_576L) {
            File(outputPath).delete()
            return ExecutionResult.failure(
                error = ExecutionError.contractViolation(
                    "PDF_TOO_LARGE",
                    "Generated PDF exceeds ${maxSizeMb}MB limit: ${fileSize / 1_048_576}MB"
                ),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now())
            )
        }

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput(
                stepId = ps.stepId,
                json = mapOf(
                    "action" to JsonPrimitive("generate"),
                    "output_path" to JsonPrimitive(outputPath),
                    "size_bytes" to JsonPrimitive(fileSize),
                    "status" to JsonPrimitive("generated")
                ),
                artifacts = mapOf("pdf" to outputPath),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(outputPath))
        )
    }

    private fun executeParse(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val path = ps.params["path"]
            ?: return contractViolation(ps.stepId, "MISSING_PATH", "parse requires 'path' param")
        val maxLength = ps.params["max_length"]?.toIntOrNull() ?: 51_200

        val file = File(path)
        if (!file.exists()) {
            return ExecutionResult.failure(
                error = ExecutionError.resourceNotFound("FILE_NOT_FOUND", "PDF file not found: $path"),
                meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), listOf(path))
            )
        }

        val document = Loader.loadPDF(RandomAccessReadBufferedFile(path))
        val stripper = PDFTextStripper()

        // 페이지 범위 지정
        ps.params["pages"]?.let { pages ->
            val parts = pages.split("-")
            if (parts.size == 2) {
                stripper.startPage = parts[0].trim().toIntOrNull() ?: 1
                stripper.endPage = parts[1].trim().toIntOrNull() ?: document.numberOfPages
            } else {
                val page = pages.toIntOrNull() ?: 1
                stripper.startPage = page
                stripper.endPage = page
            }
        }

        val text = stripper.getText(document).take(maxLength)
        val pageCount = document.numberOfPages
        document.close()

        val endedAt = Instant.now()
        return ExecutionResult.success(
            output = StepOutput.json(
                stepId = ps.stepId,
                data = mapOf(
                    "action" to JsonPrimitive("parse"),
                    "path" to JsonPrimitive(path),
                    "pages" to JsonPrimitive(pageCount),
                    "length" to JsonPrimitive(text.length),
                    "text" to JsonPrimitive(text)
                ),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, listOf(path))
        )
    }

    private fun executeMerge(ps: ExecutionStep.PluginStep, startedAt: Instant): ExecutionResult {
        val pathsStr = ps.params["paths"]
            ?: return contractViolation(ps.stepId, "MISSING_PATHS", "merge requires 'paths' param (comma-separated)")
        val paths = pathsStr.split(",").map { it.trim() }

        if (paths.size < 2) {
            return contractViolation(ps.stepId, "TOO_FEW_PATHS", "merge requires at least 2 PDF paths")
        }

        // 모든 파일 존재 확인
        for (path in paths) {
            if (!File(path).exists()) {
                return ExecutionResult.failure(
                    error = ExecutionError.resourceNotFound("FILE_NOT_FOUND", "PDF file not found: $path"),
                    meta = ExecutionMeta.of(ps.stepId, startedAt, Instant.now(), paths)
                )
            }
        }

        val outputPath = ps.params["output_path"]
            ?: "$outputDir/wiiiv-pdf-merged-${UUID.randomUUID().toString().take(8)}.pdf"

        val merger = PDFMergerUtility()
        merger.destinationFileName = outputPath
        paths.forEach { merger.addSource(File(it)) }
        merger.mergeDocuments(null)

        val fileSize = File(outputPath).length()
        val endedAt = Instant.now()

        return ExecutionResult.success(
            output = StepOutput(
                stepId = ps.stepId,
                json = mapOf(
                    "action" to JsonPrimitive("merge"),
                    "output_path" to JsonPrimitive(outputPath),
                    "merged_count" to JsonPrimitive(paths.size),
                    "size_bytes" to JsonPrimitive(fileSize),
                    "status" to JsonPrimitive("merged")
                ),
                artifacts = mapOf("pdf" to outputPath),
                durationMs = java.time.Duration.between(startedAt, endedAt).toMillis()
            ),
            meta = ExecutionMeta.of(ps.stepId, startedAt, endedAt, paths + outputPath)
        )
    }

    private fun contractViolation(stepId: String, code: String, message: String): ExecutionResult =
        ExecutionResult.contractViolation(stepId = stepId, code = code, message = message)

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean = false
}
