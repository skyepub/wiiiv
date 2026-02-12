package io.wiiiv.cli

import io.wiiiv.blueprint.Blueprint
import io.wiiiv.blueprint.BlueprintExecutionResult
import io.wiiiv.blueprint.BlueprintStep
import io.wiiiv.blueprint.BlueprintStepType
import io.wiiiv.execution.ExecutionResult

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
                // 코드 블록 시작
                inCodeBlock = true
                codeLanguage = trimmedLine.removePrefix("```").trim().lowercase()
                codeBuffer.clear()
                // 펜스 라인 제거 — 대신 언어 라벨 표시
                if (codeLanguage.isNotBlank()) {
                    result.appendLine("  ${c.DIM}[$codeLanguage]${c.RESET}")
                }
            } else if (inCodeBlock && trimmedLine == "```") {
                // 코드 블록 종료
                inCodeBlock = false
                val code = codeBuffer.toString().trimEnd()
                val highlighted = highlightCode(code, codeLanguage)
                result.append(highlighted)
                if (!highlighted.endsWith("\n")) result.appendLine()
            } else if (inCodeBlock) {
                codeBuffer.appendLine(line)
            } else {
                // 일반 텍스트 — 인라인 코드 처리
                result.appendLine(renderInlineCode(line))
            }
        }

        // 닫히지 않은 코드 블록 처리
        if (inCodeBlock) {
            result.append(highlightCode(codeBuffer.toString().trimEnd(), codeLanguage))
        }

        return result.toString().trimEnd()
    }

    /**
     * 인라인 코드 (`code`) 하이라이팅
     */
    private fun renderInlineCode(line: String): String {
        val regex = "`([^`]+)`".toRegex()
        return regex.replace(line) { match ->
            "${c.BRIGHT_CYAN}${match.groupValues[1]}${c.RESET}"
        }
    }

    /**
     * 코드 블록 문법 하이라이팅
     */
    private fun highlightCode(code: String, language: String): String {
        val keywords = when (language) {
            "kotlin", "kt" -> KOTLIN_KEYWORDS
            "javascript", "js", "typescript", "ts" -> JS_KEYWORDS
            "python", "py" -> PYTHON_KEYWORDS
            "sql" -> SQL_KEYWORDS
            else -> KOTLIN_KEYWORDS + JS_KEYWORDS // fallback
        }
        val caseSensitive = language != "sql"

        val lines = code.lines()
        val result = StringBuilder()

        for (line in lines) {
            result.appendLine("  ${highlightLine(line, keywords, caseSensitive, language)}")
        }

        return result.toString()
    }

    /**
     * 한 줄 하이라이팅
     */
    private fun highlightLine(line: String, keywords: Set<String>, caseSensitive: Boolean, language: String): String {
        if (line.isBlank()) return ""

        val sb = StringBuilder()
        var i = 0

        // 주석 감지
        val commentPrefix = when (language) {
            "kotlin", "kt", "javascript", "js", "typescript", "ts", "java" -> "//"
            "python", "py" -> "#"
            "sql" -> "--"
            else -> "//"
        }

        while (i < line.length) {
            // 주석
            if (line.startsWith(commentPrefix, i)) {
                sb.append("${c.DIM}${line.substring(i)}${c.RESET}")
                break
            }

            // 문자열 (쌍따옴표)
            if (line[i] == '"') {
                val end = findStringEnd(line, i, '"')
                sb.append("${c.BRIGHT_GREEN}${line.substring(i, end)}${c.RESET}")
                i = end
                continue
            }

            // 문자열 (홑따옴표)
            if (line[i] == '\'') {
                val end = findStringEnd(line, i, '\'')
                sb.append("${c.BRIGHT_GREEN}${line.substring(i, end)}${c.RESET}")
                i = end
                continue
            }

            // 숫자
            if (line[i].isDigit() && (i == 0 || !line[i - 1].isLetterOrDigit())) {
                val end = findNumberEnd(line, i)
                sb.append("${c.BRIGHT_YELLOW}${line.substring(i, end)}${c.RESET}")
                i = end
                continue
            }

            // 단어 (키워드 체크)
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
                    // 타입/클래스 이름
                    sb.append("${c.BRIGHT_CYAN}${word}${c.RESET}")
                } else {
                    sb.append(word)
                }
                i = end
                continue
            }

            // 점, 괄호 등 연산자
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
            if (line[i] == '\\') {
                i += 2
                continue
            }
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

    /**
     * CONFIRM 단계 렌더링
     *
     * ```
     *   ────── Intent #3 ──────────────────────────────────────────────────
     *     작업: 프로젝트 생성
     *     의도: 프로젝트 생성
     *     ...
     *   ──────────────────────────────────────────────────────────────────
     * ```
     */
    fun renderConfirmation(summary: String, intentNumber: Int): String {
        val sb = StringBuilder()
        val dash = "\u2500"

        // header: ────── Intent #N ────────────────
        val label = " Intent #$intentNumber "
        val headerPrefix = dash.repeat(6)
        val headerSuffixLen = (BOX_WIDTH - 6 - label.length).coerceAtLeast(3)
        val headerSuffix = dash.repeat(headerSuffixLen)
        sb.appendLine()
        sb.appendLine("  ${c.DIM}$headerPrefix${c.RESET}${c.BRIGHT_CYAN}$label${c.RESET}${c.DIM}$headerSuffix${c.RESET}")

        // content
        for (line in summary.trimEnd().lines()) {
            if (line.isBlank()) continue
            // key: value 형태를 감지하여 key를 강조
            val colonIdx = line.indexOf(':')
            if (colonIdx > 0 && colonIdx < line.length - 1) {
                val key = line.substring(0, colonIdx).trim()
                val value = line.substring(colonIdx + 1).trim()
                sb.appendLine("    ${c.DIM}$key:${c.RESET}  $value")
            } else {
                sb.appendLine("    $line")
            }
        }

        // footer
        sb.appendLine("  ${c.DIM}${dash.repeat(BOX_WIDTH)}${c.RESET}")

        return sb.toString().trimEnd()
    }

    // ── Execution Result Rendering ──────────────────────────────

    /**
     * 실행 결과 전용 렌더링
     *
     * Blueprint step 요약 (content 숨김) + 결과 표시
     */
    fun renderExecutionResult(
        message: String,
        blueprint: Blueprint?,
        executionResult: BlueprintExecutionResult?
    ): String {
        val sb = StringBuilder()

        // 메시지 렌더링
        sb.appendLine(render(message))

        // Blueprint 표시
        if (blueprint != null) {
            sb.appendLine()
            val shortId = blueprint.id.take(8)
            val stepCount = blueprint.steps.size
            sb.appendLine("  ${c.BRIGHT_CYAN}\uD83D\uDCCB Blueprint${c.RESET}  ${c.DIM}$shortId${c.RESET}  ($stepCount steps)")

            val ruler = "  ${c.DIM}${ "\u2500".repeat(55)}${c.RESET}"
            var sectionOpen = false

            blueprint.steps.forEachIndexed { i, step ->
                if (!sectionOpen) {
                    sb.appendLine(ruler)
                    sectionOpen = true
                }

                val emoji = stepStatusEmoji(i, executionResult)
                val summary = summarizeStep(step)
                sb.appendLine("  $emoji ${i + 1}. ${c.BRIGHT_CYAN}${step.type}${c.RESET}  $summary")

                // FILE_WRITE: 실선 닫고 → 코드 → 다음 step에서 다시 열림
                if (step.type == BlueprintStepType.FILE_WRITE) {
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
        }

        // Result 표시
        if (executionResult != null) {
            sb.appendLine()
            val resultLineWidth = 41

            if (executionResult.isSuccess) {
                sb.appendLine("  ${c.BRIGHT_GREEN}\u2728 실행 완료${c.RESET}")
                sb.appendLine("  ${c.DIM}\u2500\u2500 결과 ${ "\u2500".repeat(resultLineWidth)}${c.RESET}")

                // 파일 작업 요약
                val fileSteps = blueprint?.steps?.filter {
                    it.type in listOf(
                        BlueprintStepType.FILE_WRITE, BlueprintStepType.FILE_MKDIR,
                        BlueprintStepType.FILE_COPY, BlueprintStepType.FILE_MOVE,
                        BlueprintStepType.FILE_READ, BlueprintStepType.FILE_DELETE
                    )
                } ?: emptyList()

                val cmdSteps = blueprint?.steps?.filter {
                    it.type == BlueprintStepType.COMMAND
                } ?: emptyList()

                val apiSteps = blueprint?.steps?.filter {
                    it.type == BlueprintStepType.API_CALL
                } ?: emptyList()

                if (fileSteps.isNotEmpty()) {
                    val fileSuccessCount = countSuccessSteps(fileSteps, blueprint, executionResult)
                    val fileFailCount = fileSteps.size - fileSuccessCount
                    if (fileFailCount == 0) {
                        sb.appendLine("  파일 작업  ${c.BRIGHT_GREEN}${fileSteps.size}개 성공${c.RESET}")
                    } else {
                        sb.appendLine("  파일 작업  ${c.BRIGHT_GREEN}${fileSuccessCount}개 성공${c.RESET}  ${c.YELLOW}${fileFailCount}개 실패${c.RESET}")
                    }
                }

                if (cmdSteps.isNotEmpty()) {
                    val cmdSuccessCount = countSuccessSteps(cmdSteps, blueprint, executionResult)
                    val cmdFailCount = cmdSteps.size - cmdSuccessCount
                    if (cmdFailCount == 0) {
                        sb.appendLine("  명령 실행  ${c.BRIGHT_GREEN}${cmdSteps.size}개 성공${c.RESET}")
                    } else {
                        sb.appendLine("  명령 실행  ${c.YELLOW}${cmdFailCount}개 실패${c.RESET} \u2014 수동 확인 필요")
                    }
                }

                if (apiSteps.isNotEmpty()) {
                    val apiSuccessCount = countSuccessSteps(apiSteps, blueprint, executionResult)
                    val apiFailCount = apiSteps.size - apiSuccessCount
                    if (apiFailCount == 0) {
                        sb.appendLine("  API 호출  ${c.BRIGHT_GREEN}${apiSteps.size}개 성공${c.RESET}")
                    } else {
                        sb.appendLine("  API 호출  ${c.YELLOW}${apiFailCount}개 실패${c.RESET}")
                    }
                }

                // 소요 시간
                val totalMs = executionResult.runnerResult.results.sumOf { r -> r.meta.durationMs }
                sb.appendLine("  소요 시간  ${formatDuration(totalMs)}")

                sb.appendLine("  ${c.DIM}${ "\u2500".repeat(resultLineWidth + 6)}${c.RESET}")
            } else {
                sb.appendLine("  ${c.RED}\u274C 실행 실패${c.RESET}")
                sb.appendLine("  ${c.DIM}\u2500\u2500 결과 ${ "\u2500".repeat(resultLineWidth)}${c.RESET}")

                // 실패 상세
                val results = executionResult.runnerResult.results
                results.forEach { r ->
                    if (r is ExecutionResult.Failure) {
                        val stepId = r.meta.stepId
                        sb.appendLine("  ${c.RED}실패: $stepId: ${r.error.message}${c.RESET}")
                    }
                }

                sb.appendLine("  ${c.DIM}${ "\u2500".repeat(resultLineWidth + 6)}${c.RESET}")
            }
        }

        return sb.toString().trimEnd()
    }

    /**
     * Step의 성공/실패 이모지 결정
     */
    private fun stepStatusEmoji(
        index: Int,
        executionResult: BlueprintExecutionResult?
    ): String {
        if (executionResult == null) return "\u2B55"  // ⭕ (pending)

        val results = executionResult.runnerResult.results
        if (index >= results.size) return "\u2B55"

        return when (results[index]) {
            is ExecutionResult.Success -> "\u2705"   // ✅
            is ExecutionResult.Failure -> "\u274C"   // ❌
            is ExecutionResult.Cancelled -> "\u26A0\uFE0F"  // ⚠️
        }
    }

    /**
     * Step 요약 — content를 숨기고 핵심만 표시
     */
    fun summarizeStep(step: BlueprintStep): String {
        return when (step.type) {
            BlueprintStepType.FILE_MKDIR -> {
                val path = step.params["path"] ?: ""
                lastSegment(path) + "/"
            }
            BlueprintStepType.FILE_WRITE -> {
                val path = step.params["path"] ?: ""
                val content = step.params["content"] ?: ""
                val size = formatFileSize(content.length.toLong())
                "${lastTwoSegments(path)}  ${c.DIM}($size)${c.RESET}"
            }
            BlueprintStepType.FILE_READ -> {
                val path = step.params["path"] ?: ""
                lastTwoSegments(path)
            }
            BlueprintStepType.FILE_COPY, BlueprintStepType.FILE_MOVE -> {
                val source = step.params["source"] ?: ""
                val target = step.params["target"] ?: ""
                "${fileName(source)} \u2192 ${fileName(target)}"
            }
            BlueprintStepType.FILE_DELETE -> {
                val path = step.params["path"] ?: ""
                lastSegment(path)
            }
            BlueprintStepType.COMMAND -> {
                val cmd = step.params["command"] ?: ""
                val args = step.params["args"] ?: ""
                "$cmd $args".trim().take(60)
            }
            BlueprintStepType.API_CALL -> {
                val method = step.params["method"] ?: "GET"
                val url = step.params["url"] ?: ""
                "$method $url".take(60)
            }
            BlueprintStepType.NOOP -> "no-op"
        }
    }

    /**
     * 바이트 수를 KB 단위로 포맷
     */
    private fun formatFileSize(bytes: Long): String {
        return if (bytes < 1024) {
            "$bytes B"
        } else {
            val kb = bytes / 1024.0
            "%.1f KB".format(kb)
        }
    }

    /**
     * 밀리초를 읽기 좋은 시간으로 포맷
     */
    private fun formatDuration(ms: Long): String {
        return if (ms < 1000) {
            "${ms}ms"
        } else {
            "%.1fs".format(ms / 1000.0)
        }
    }

    /**
     * ANSI 이스케이프 제거 — 가시 문자 수 계산용
     */
    private fun stripAnsi(text: String): String {
        return text.replace(Regex("\u001B\\[[0-9;]*m"), "")
    }

    /**
     * 경로의 마지막 segment
     */
    private fun lastSegment(path: String): String {
        val normalized = path.replace("\\", "/").trimEnd('/')
        return normalized.substringAfterLast("/")
    }

    /**
     * 경로의 마지막 2 segment
     */
    private fun lastTwoSegments(path: String): String {
        val normalized = path.replace("\\", "/").trimEnd('/')
        val parts = normalized.split("/")
        return if (parts.size >= 2) {
            "${parts[parts.size - 2]}/${parts[parts.size - 1]}"
        } else {
            normalized
        }
    }

    /**
     * 경로에서 파일명만 추출
     */
    private fun fileName(path: String): String = lastSegment(path)

    /**
     * 파일 경로에서 언어 감지
     */
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

    /**
     * 특정 step 유형들의 성공 개수 계산
     */
    private fun countSuccessSteps(
        steps: List<BlueprintStep>,
        blueprint: Blueprint?,
        executionResult: BlueprintExecutionResult
    ): Int {
        if (blueprint == null) return 0
        val results = executionResult.runnerResult.results
        var count = 0
        for (step in steps) {
            val idx = blueprint.steps.indexOf(step)
            if (idx in results.indices && results[idx] is ExecutionResult.Success) {
                count++
            }
        }
        return count
    }
}
