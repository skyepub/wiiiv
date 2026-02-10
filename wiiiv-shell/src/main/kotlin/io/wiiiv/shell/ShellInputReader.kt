package io.wiiiv.shell

import org.jline.reader.*
import org.jline.reader.impl.DefaultParser
import org.jline.terminal.Terminal
import org.jline.terminal.TerminalBuilder
import org.jline.keymap.KeyMap

/**
 * JLine3 기반 멀티라인 입력 리더
 *
 * - Enter → 제출 (accept-line)
 * - Alt+Enter → 줄바꿈 삽입 (가장 범용적)
 * - Ctrl+J (\n) → 줄바꿈 삽입 (Git Bash/mintty에서 Ctrl+Enter로 동작)
 *
 * secondary prompt(". ")가 줄바꿈 후 표시된다.
 */
class ShellInputReader {

    private val terminal: Terminal
    private val lineReader: LineReader
    private val simpleReader: LineReader

    init {
        terminal = TerminalBuilder.builder()
            .system(true)
            .build()

        lineReader = LineReaderBuilder.builder()
            .terminal(terminal)
            .parser(MultilineParser())
            .variable(LineReader.SECONDARY_PROMPT_PATTERN, ". ")
            .build()

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
            .build()
    }

    /**
     * 멀티라인 입력 읽기. Enter로 제출, Alt+Enter/Ctrl+J로 줄바꿈.
     * @return 입력 문자열, EOF면 null
     */
    fun readLine(prompt: String): String? {
        return try {
            lineReader.readLine(prompt)
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
     * 멀티라인 파서: Enter는 항상 제출. 줄바꿈은 self-insert-newline 위젯이 담당.
     *
     * EOFError를 던지지 않으므로 Enter 한 번으로 즉시 제출된다.
     * secondary prompt는 버퍼에 \n이 포함된 상태에서 JLine이 자동 표시한다.
     */
    private class MultilineParser : Parser {
        private val delegate = DefaultParser()

        override fun parse(line: String, cursor: Int, context: Parser.ParseContext): ParsedLine {
            return delegate.parse(line, cursor, context)
        }
    }
}
