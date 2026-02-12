package io.wiiiv.cli

import io.wiiiv.cli.client.ExecutionSummaryDto
import io.wiiiv.cli.client.StepSummaryDto
import io.wiiiv.cli.model.CliStepType

/**
 * Shell 출력 렌더러
 *
 * 마크다운 코드 펜스를 제거하고 문법 하이라이팅을 적용한다.
 */
object ShellRenderer {

    private val c = ShellColors

    // 언어별 키워드
    private val KOTLIN_KEYWORDS = setOf(
        "val", "var", "fun", "class", "object", "interface", "enum",
        "if", "else", "when", "for", "while", "do", "return", "break", "continue",
        "import", "package", "try", "catch", "finally", "throw",
        "private", "public", "protected", "internal", "open", "override",
        "data", "sealed", "abstract", "companion", "suspend", "inline",
        "null", "true", "false", "is", "as", "in", "by", "this", "super"
    )

    private val JS_KEYWORDS = setOf(
        "const", "let", "var", "function", "class", "async", "await",
        "if", "else", "for", "while", "do", "return", "break", "continue",
        "import", "export", "from", "try", "catch", "finally", "throw",
        "new", "this", "super", "typeof", "instanceof",
        "null", "undefined", "true", "false", "of", "in"
    )

    private val PYTHON_KEYWORDS = setOf(
        "def", "class", "if", "elif", "else", "for", "while", "return",
        "import", "from", "try", "except", "finally", "raise", "with", "as",
        "None", "True", "False", "and", "or", "not", "in", "is", "lambda",
        "pass", "break", "continue", "yield", "async", "await", "self"
    )

    private val SQL_KEYWORDS = setOf(
        "SELECT", "FROM", "WHERE", "INSERT", "UPDATE", "DELETE", "SET",
        "JOIN", "LEFT", "RIGHT", "INNER", "ON", "AND", "OR", "NOT",
        "ORDER", "BY", "GROUP", "HAVING", "LIMIT", "OFFSET", "AS",
        "INTO", "VALUES", "CREATE", "DROP", "ALTER", "TABLE", "INDEX",
        "NULL", "TRUE", "FALSE", "IN", "LIKE", "BETWEEN", "EXISTS",
        "ASC", "DESC", "DISTINCT", "COUNT", "SUM", "AVG", "MAX", "MIN"
    )

    /**
     * 메시지를 렌더링 — 코드 펜스 제거 + 하이라이팅 적용
     */
    fun render(message: String): String {
        if (!c.enabled) return message

        val lines = message.lines()
        val result = StringBuilder()
        var inCodeBlock = false
        var codeLanguage = ""
        val codeBuffer = StringBuilder()

        for (line in lines) {
            val trimmedLine = line.trim()

            if (!inCodeBlock && trimmedLine.startsWith("```")) {
                inCodeBlock = true
                codeLanguage = trimmedLine.removePrefix("```").trim().lowercase()
                codeBuffer.clear()
                if (codeLanguage.isNotBlank()) {
                    result.appendLine("  ${c.DIM}[$codeLanguage]${c.RESET}")
                }
            } else if (inCodeBlock && trimmedLine == "```") {
                inCodeBlock = false
                val code = codeBuffer.toString().trimEnd()
                val highlighted = highlightCode(code, codeLanguage)
                result.append(highlighted)
                if (!highlighted.endsWith("\n")) result.appendLine()
            } else if (inCodeBlock) {
                codeBuffer.appendLine(line)
            } else {
                result.appendLine(renderInlineCode(line))
            }
        }

        if (inCodeBlock) {
            result.append(highlightCode(codeBuffer.toString().trimEnd(), codeLanguage))
        }

        return result.toString().trimEnd()
    }

    private fun renderInlineCode(line: String): String {
        val regex = "`([^`]+)`".toRegex()
        return regex.replace(line) { match ->
            "${c.BRIGHT_CYAN}${match.groupValues[1]}${c.RESET}"
        }
    }

    private fun highlightCode(code: String, language: String): String {
        val keywords = when (language) {
            "kotlin", "kt" -> KOTLIN_KEYWORDS
            "javascript", "js", "typescript", "ts" -> JS_KEYWORDS
            "python", "py" -> PYTHON_KEYWORDS
            "sql" -> SQL_KEYWORDS
            else -> KOTLIN_KEYWORDS + JS_KEYWORDS
        }
        val caseSensitive = language != "sql"

        val lines = code.lines()
        val result = StringBuilder()

        for (line in lines) {
            result.appendLine("  ${highlightLine(line, keywords, caseSensitive, language)}")
        }

        return result.toString()
    }

    private fun highlightLine(line: String, keywords: Set<String>, caseSensitive: Boolean, language: String): String {
        if (line.isBlank()) return ""

        val sb = StringBuilder()
        var i = 0

        val commentPrefix = when (language) {
            "kotlin", "kt", "javascript", "js", "typescript", "ts", "java" -> "//"
            "python", "py" -> "#"
            "sql" -> "--"
            else -> "//"
        }

        while (i < line.length) {
            if (line.startsWith(commentPrefix, i)) {
                sb.append("${c.DIM}${line.substring(i)}${c.RESET}")
                break
            }

            if (line[i] == '"') {
                val end = findStringEnd(line, i, '"')
                sb.append("${c.BRIGHT_GREEN}${line.substring(i, end)}${c.RESET}")
                i = end
                continue
            }

            if (line[i] == '\'') {
                val end = findStringEnd(line, i, '\'')
                sb.append("${c.BRIGHT_GREEN}${line.substring(i, end)}${c.RESET}")
                i = end
                continue
            }

            if (line[i].isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
                val end = findNumberEnd(line, i)
                sb.append("${c.BRIGHT_YELLOW}${line.substring(i, end)}${c.RESET}")
                i = end
                continue
            }

            if (line[i].isLetter() || line[i] == '_') {
                val end = findWordEnd(line, i)
                val word = line.substring(i, end)
                val isKeyword = if (caseSensitive) {
                    word in keywords
                } else {
                    word.uppercase() in keywords
                }

                if (isKeyword) {
                    sb.append("${c.BRIGHT_MAGENTA}${word}${c.RESET}")
                } else if (word[0].isUpperCase() && language != "sql") {
                    sb.append("${c.BRIGHT_CYAN}${word}${c.RESET}")
                } else {
                    sb.append(word)
                }
                i = end
                continue
            }

            if (line[i] in "(){}[].,;:") {
                sb.append("${c.DIM}${line[i]}${c.RESET}")
                i++
                continue
            }

            sb.append(line[i])
            i++
        }

        return sb.toString()
    }

    private fun findStringEnd(line: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < line.length) {
            if (line[i] == '\\') { i += 2; continue }
            if (line[i] == quote) return i + 1
            i++
        }
        return line.length
    }

    private fun findNumberEnd(line: String, start: Int): Int {
        var i = start
        while (i < line.length && (line[i].isDigit() || line[i] == '.')) i++
        return i
    }

    private fun findWordEnd(line: String, start: Int): Int {
        var i = start
        while (i < line.length && (line[i].isLetterOrDigit() || line[i] == '_')) i++
        return i
    }

    // ── Confirmation Rendering ─────────────────────────────────

    private val BOX_WIDTH = 75

    fun renderConfirmation(summary: String, intentNumber: Int): String {
        val sb = StringBuilder()
        val dash = "\u2500"

        val label = " Intent #$intentNumber "
        val headerPrefix = dash.repeat(6)
        val headerSuffixLen = (BOX_WIDTH - 6 - label.length).coerceAtLeast(3)
        val headerSuffix = dash.repeat(headerSuffixLen)
        sb.appendLine()
        sb.appendLine("  ${c.DIM}$headerPrefix${c.RESET}${c.BRIGHT_CYAN}$label${c.RESET}${c.DIM}$headerSuffix${c.RESET}")

        for (line in summary.trimEnd().lines()) {
            if (line.isBlank()) continue
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && colonIdx < line.length - 1) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                sb.appendLine("    ${c.DIM}$key:${c.RESET}  $value")
            } else {
                sb.appendLine("    $line")
            }
        }

        sb.appendLine("  ${c.DIM}${dash.repeat(BOX_WIDTH)}${c.RESET}")

        return sb.toString().trimEnd()
    }

    // ── Execution Result Rendering ──────────────────────────────

    /**
     * 실행 결과 전용 렌더링 — ExecutionSummaryDto 기반
     */
    fun renderExecutionResult(
        message: String,
        executionSummary: ExecutionSummaryDto?
    ): String {
        val sb = StringBuilder()

        sb.appendLine(render(message))

        if (executionSummary != null) {
            sb.appendLine()
            val shortId = executionSummary.blueprintId.take(8)
            val stepCount = executionSummary.steps.size
            sb.appendLine("  ${c.BRIGHT_CYAN}\uD83D\uDCCB Blueprint${c.RESET}  ${c.DIM}$shortId${c.RESET}  ($stepCount steps)")

            val ruler = "  ${c.DIM}${"\u2500".repeat(55)}${c.RESET}"
            var sectionOpen = false

            executionSummary.steps.forEachIndexed { i, step ->
                if (!sectionOpen) {
                    sb.appendLine(ruler)
                    sectionOpen = true
                }

                val emoji = stepStatusEmoji(step)
                val summary = summarizeStep(step)
                sb.appendLine("  $emoji ${i + 1}. ${c.BRIGHT_CYAN}${step.type}${c.RESET}  $summary")

                // FILE_WRITE: content 표시
                val stepType = CliStepType.fromString(step.type)
                if (stepType == CliStepType.FILE_WRITE) {
                    val content = step.params["content"]
                    if (!content.isNullOrBlank()) {
                        val path = step.params["path"] ?: ""
                        val lang = detectLanguage(path)

                        sb.appendLine(ruler)
                        sectionOpen = false

                        sb.appendLine()
                        val highlighted = highlightCode(content.trimEnd(), lang)
                        for (line in highlighted.lines()) {
                            sb.appendLine(line)
                        }
                        sb.appendLine()
                    }
                }
            }

            if (sectionOpen) {
                sb.appendLine(ruler)
            }

            // Result 표시
            sb.appendLine()
            val resultLineWidth = 41

            if (executionSummary.failureCount == 0) {
                sb.appendLine("  ${c.BRIGHT_GREEN}\u2728 실행 완료${c.RESET}")
                sb.appendLine("  ${c.DIM}\u2500\u2500 결과 ${"\u2500".repeat(resultLineWidth)}${c.RESET}")

                val fileSteps = executionSummary.steps.filter {
                    val t = CliStepType.fromString(it.type)
                    t in listOf(CliStepType.FILE_WRITE, CliStepType.FILE_MKDIR,
                        CliStepType.FILE_COPY, CliStepType.FILE_MOVE,
                        CliStepType.FILE_READ, CliStepType.FILE_DELETE)
                }
                val cmdSteps = executionSummary.steps.filter { CliStepType.fromString(it.type) == CliStepType.COMMAND }
                val apiSteps = executionSummary.steps.filter { CliStepType.fromString(it.type) == CliStepType.API_CALL }

                if (fileSteps.isNotEmpty()) {
                    val successCount = fileSteps.count { it.success }
                    val failCount = fileSteps.size - successCount
                    if (failCount == 0) {
                        sb.appendLine("  파일 작업  ${c.BRIGHT_GREEN}${fileSteps.size}개 성공${c.RESET}")
                    } else {
                        sb.appendLine("  파일 작업  ${c.BRIGHT_GREEN}${successCount}개 성공${c.RESET}  ${c.YELLOW}${failCount}개 실패${c.RESET}")
                    }
                }

                if (cmdSteps.isNotEmpty()) {
                    val successCount = cmdSteps.count { it.success }
                    val failCount = cmdSteps.size - successCount
                    if (failCount == 0) {
                        sb.appendLine("  명령 실행  ${c.BRIGHT_GREEN}${cmdSteps.size}개 성공${c.RESET}")
                    } else {
                        sb.appendLine("  명령 실행  ${c.YELLOW}${failCount}개 실패${c.RESET} \u2014 수동 확인 필요")
                    }
                }

                if (apiSteps.isNotEmpty()) {
                    val successCount = apiSteps.count { it.success }
                    val failCount = apiSteps.size - successCount
                    if (failCount == 0) {
                        sb.appendLine("  API 호출  ${c.BRIGHT_GREEN}${apiSteps.size}개 성공${c.RESET}")
                    } else {
                        sb.appendLine("  API 호출  ${c.YELLOW}${failCount}개 실패${c.RESET}")
                    }
                }

                sb.appendLine("  소요 시간  ${formatDuration(executionSummary.totalDurationMs)}")
                sb.appendLine("  ${c.DIM}${"\u2500".repeat(resultLineWidth + 6)}${c.RESET}")
            } else {
                sb.appendLine("  ${c.RED}\u274C 실행 실패${c.RESET}")
                sb.appendLine("  ${c.DIM}\u2500\u2500 결과 ${"\u2500".repeat(resultLineWidth)}${c.RESET}")

                executionSummary.steps.filter { !it.success }.forEach { step ->
                    val errorMsg = step.error ?: step.stderr ?: "Unknown error"
                    sb.appendLine("  ${c.RED}실패: ${step.stepId}: $errorMsg${c.RESET}")
                }

                sb.appendLine("  ${c.DIM}${"\u2500".repeat(resultLineWidth + 6)}${c.RESET}")
            }
        }

        return sb.toString().trimEnd()
    }

    private fun stepStatusEmoji(step: StepSummaryDto): String {
        return if (step.success) "\u2705" else "\u274C"
    }

    fun summarizeStep(step: StepSummaryDto): String {
        val stepType = CliStepType.fromString(step.type)
        return when (stepType) {
            CliStepType.FILE_MKDIR -> {
                val path = step.params["path"] ?: ""
                lastSegment(path) + "/"
            }
            CliStepType.FILE_WRITE -> {
                val path = step.params["path"] ?: ""
                val content = step.params["content"] ?: ""
                val size = formatFileSize(content.length.toLong())
                "${lastTwoSegments(path)}  ${c.DIM}($size)${c.RESET}"
            }
            CliStepType.FILE_READ -> {
                val path = step.params["path"] ?: ""
                lastTwoSegments(path)
            }
            CliStepType.FILE_COPY, CliStepType.FILE_MOVE -> {
                val source = step.params["source"] ?: ""
                val target = step.params["target"] ?: ""
                "${fileName(source)} \u2192 ${fileName(target)}"
            }
            CliStepType.FILE_DELETE -> {
                val path = step.params["path"] ?: ""
                lastSegment(path)
            }
            CliStepType.COMMAND -> {
                val cmd = step.params["command"] ?: ""
                val args = step.params["args"] ?: ""
                "$cmd $args".trim().take(60)
            }
            CliStepType.API_CALL -> {
                val method = step.params["method"] ?: "GET"
                val url = step.params["url"] ?: ""
                "$method $url".take(60)
            }
            CliStepType.NOOP -> "no-op"
        }
    }

    private fun formatFileSize(bytes: Long): String {
        return if (bytes < 1024) "$bytes B"
        else "%.1f KB".format(bytes / 1024.0)
    }

    private fun formatDuration(ms: Long): String {
        return if (ms < 1000) "${ms}ms"
        else "%.1fs".format(ms / 1000.0)
    }

    private fun lastSegment(path: String): String {
        val normalized = path.replace("\\", "/").trimEnd('/')
        return normalized.substringAfterLast("/")
    }

    private fun lastTwoSegments(path: String): String {
        val normalized = path.replace("\\", "/").trimEnd('/')
        val parts = normalized.split("/")
        return if (parts.size >= 2) "${parts[parts.size - 2]}/${parts[parts.size - 1]}"
        else normalized
    }

    private fun fileName(path: String): String = lastSegment(path)

    private fun detectLanguage(path: String): String {
        val ext = path.substringAfterLast(".", "").lowercase()
        return when (ext) {
            "kt", "kts" -> "kotlin"
            "java" -> "java"
            "js", "mjs", "cjs" -> "javascript"
            "ts", "tsx" -> "typescript"
            "py" -> "python"
            "sql" -> "sql"
            "sh", "bash" -> "bash"
            "json" -> "json"
            "yaml", "yml" -> "yaml"
            "xml", "html", "htm" -> "xml"
            "css", "scss" -> "css"
            "rb" -> "ruby"
            "go" -> "go"
            "rs" -> "rust"
            "c", "h" -> "c"
            "cpp", "hpp", "cc" -> "cpp"
            else -> ""
        }
    }
}
