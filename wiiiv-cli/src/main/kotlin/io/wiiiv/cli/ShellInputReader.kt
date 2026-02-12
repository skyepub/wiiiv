package io.wiiiv.cli

import org.jline.reader.*
import org.jline.reader.impl.DefaultParser
import org.jline.reader.CompletingParsedLine
import org.jline.terminal.Terminal
import org.jline.keymap.KeyMap

/**
 * JLine3 기반 멀티라인 입력 리더
 *
 * - Enter → 제출 (accept-line)
 * - Alt+Enter → 줄바꿈 삽입 (가장 범용적)
 * - Ctrl+J (\n) → 줄바꿈 삽입 (Git Bash/mintty에서 Ctrl+Enter로 동작)
 *
 * secondary prompt(". ")가 줄바꿈 후 표시된다.
 * Terminal은 외부에서 주입받는다 — System.out 연결을 Main에서 제어하기 위함.
 *
 * ## Windows 백슬래시 보존
 *
 * JLine3의 LineReaderImpl.finish()가 readLine() 반환 직전에
 * parser.isEscapeChar()를 호출하여 '\'를 제거한다.
 * 이 문제를 3중으로 방어한다:
 *
 * 1. WindowsSafeParser.isEscapeChar() → false 반환
 * 2. DISABLE_EVENT_EXPANSION → finish()의 이스케이프 루프 자체를 건너뜀
 * 3. accept-line 위젯 오버라이드 → JLine 처리 전 원본 버퍼를 캡처
 */
class ShellInputReader(private val terminal: Terminal) {

    private val lineReader: LineReader
    private val simpleReader: LineReader

    /** accept-line 위젯이 캡처한 원본 버퍼 (JLine 후처리 이전) */
    @Volatile
    private var lastRawBuffer: String = ""

    init {
        lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(WindowsSafeParser())
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, ". ")
            .build()

        // 방어 2: 이벤트 확장 비활성화 — finish()의 이스케이프 처리도 건너뜀
        lineReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION)

        // 방어 3: Enter 키를 가로채서 원본 버퍼 캡처 후 진짜 accept-line 호출
        lineReader.widgets["accept-line-capture"] = Widget {
            lastRawBuffer = lineReader.buffer.toString()
            lineReader.callWidget(LineReader.ACCEPT_LINE)
            true
        }
        lineReader.keyMaps[LineReader.MAIN]?.bind(
            Reference("accept-line-capture"),
            "\r"
        )

        // 커스텀 위젯: 버퍼에 \n 삽입
        lineReader.widgets["self-insert-newline"] = Widget {
            lineReader.buffer.write("\n")
            true
        }

        // Alt+Enter → 줄바꿈 삽입
        lineReader.keyMaps[LineReader.MAIN]?.bind(
            Reference("self-insert-newline"),
            KeyMap.alt('\r')
        )

        // Ctrl+J (\n) → 줄바꿈 삽입
        lineReader.keyMaps[LineReader.MAIN]?.bind(
            Reference("self-insert-newline"),
            "\n"
        )

        // 단순 1줄 리더 (confirm 등)
        simpleReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(WindowsSafeParser())
            .build()

        simpleReader.setOpt(LineReader.Option.DISABLE_EVENT_EXPANSION)
    }

    /**
     * 멀티라인 입력 읽기. Enter로 제출, Alt+Enter/Ctrl+J로 줄바꿈.
     * @return 입력 문자열, EOF면 null
     */
    fun readLine(prompt: String): String? {
        return try {
            val processed = lineReader.readLine(prompt)
            // accept-line-capture 위젯이 캡처한 원본 버퍼 사용
            // (JLine의 finish()가 백슬래시를 제거했을 수 있으므로)
            val raw = lastRawBuffer
            if (raw.isNotEmpty()) raw else processed
        } catch (e: UserInterruptException) {
            null
        } catch (e: EndOfFileException) {
            null
        }
    }

    /**
     * 단순 1줄 입력 (confirm, y/N 프롬프트 등)
     * @return 입력 문자열, EOF면 null
     */
    fun readSimpleLine(prompt: String): String? {
        return try {
            simpleReader.readLine(prompt)
        } catch (e: UserInterruptException) {
            null
        } catch (e: EndOfFileException) {
            null
        }
    }

    fun close() {
        terminal.close()
    }

    /**
     * Windows 백슬래시 안전 파서
     *
     * JLine3의 두 가지 백슬래시 처리를 모두 무력화한다:
     *
     * 1. DefaultParser.parse() — setEscapeChars(charArrayOf())로 이스케이프 비활성화
     * 2. LineReaderImpl.finish() — isEscapeChar()를 false로 오버라이드
     *
     * finish()는 readLine() 반환 전에 parser.isEscapeChar()를 호출하여
     * 이스케이프 문자를 제거한다. Parser 인터페이스의 기본 구현은
     * '\\' → true를 반환하므로, 오버라이드하지 않으면 C:\Users가 C:Users로 변환된다.
     */
    private class WindowsSafeParser : Parser {
        private val delegate = DefaultParser().apply {
            setEscapeChars(charArrayOf())
        }

        /**
         * 방어 1: LineReaderImpl.finish()가 호출하는 메서드.
         * false를 반환하여 백슬래시 제거를 방지한다.
         *
         * Parser 인터페이스 기본값: ch == '\\' → true (백슬래시 제거)
         */
        override fun isEscapeChar(ch: Char): Boolean = false

        override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine {
            val parsed = delegate.parse(line, cursor, context)

            // CompletingParsedLine 래퍼로 JLine 경고 방지
            return object : CompletingParsedLine {
                override fun word(): String = parsed.word()
                override fun wordCursor(): Int = parsed.wordCursor()
                override fun wordIndex(): Int = parsed.wordIndex()
                override fun words(): List<String> = parsed.words()
                override fun line(): String = line  // 원본 그대로 반환
                override fun cursor(): Int = parsed.cursor()
                override fun escape(candidate: CharSequence, complete: Boolean): CharSequence = candidate
                override fun rawWordCursor(): Int = parsed.wordCursor()
                override fun rawWordLength(): Int = parsed.word().length
            }
        }
    }
}
