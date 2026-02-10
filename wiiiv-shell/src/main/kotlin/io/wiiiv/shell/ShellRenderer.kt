package io.wiiiv.shell

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
}
