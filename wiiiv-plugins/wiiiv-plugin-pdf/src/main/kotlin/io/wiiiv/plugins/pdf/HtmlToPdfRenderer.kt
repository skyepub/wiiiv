package io.wiiiv.plugins.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.File

/**
 * PDF 렌더러 — Mustache 렌더링된 텍스트를 PDFBox 3.x로 직접 PDF 생성
 *
 * HTML 태그를 간단히 파싱하여 텍스트, 제목, 구분선 등을 렌더링한다.
 */
object HtmlToPdfRenderer {

    private const val MARGIN = 50f
    private const val FONT_SIZE_BODY = 12f
    private const val FONT_SIZE_H1 = 24f
    private const val FONT_SIZE_H2 = 18f
    private const val FONT_SIZE_H3 = 14f
    private const val LINE_HEIGHT = 1.4f

    // PDFBox 3.x: Standard14Fonts.FontName 기반 폰트 생성
    private fun helvetica() = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private fun helveticaBold() = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)

    fun render(html: String, outputPath: String, pageSize: String = "A4") {
        val outputFile = File(outputPath)
        outputFile.parentFile?.mkdirs()

        val rect = when (pageSize.uppercase()) {
            "LETTER" -> PDRectangle.LETTER
            "A3" -> PDRectangle.A3
            "LEGAL" -> PDRectangle.LEGAL
            else -> PDRectangle.A4
        }

        val document = PDDocument()
        val lines = parseHtmlToLines(html)
        var page = PDPage(rect)
        document.addPage(page)
        var contentStream = PDPageContentStream(document, page)
        var yPos = rect.height - MARGIN

        for (line in lines) {
            val fontSize = when (line.type) {
                LineType.H1 -> FONT_SIZE_H1
                LineType.H2 -> FONT_SIZE_H2
                LineType.H3 -> FONT_SIZE_H3
                else -> FONT_SIZE_BODY
            }
            val font = if (line.type in listOf(LineType.H1, LineType.H2, LineType.H3))
                helveticaBold() else helvetica()
            val lineSpacing = fontSize * LINE_HEIGHT

            if (yPos - lineSpacing < MARGIN) {
                contentStream.close()
                page = PDPage(rect)
                document.addPage(page)
                contentStream = PDPageContentStream(document, page)
                yPos = rect.height - MARGIN
            }

            if (line.type == LineType.HR) {
                contentStream.setLineWidth(0.5f)
                contentStream.moveTo(MARGIN, yPos)
                contentStream.lineTo(rect.width - MARGIN, yPos)
                contentStream.stroke()
                yPos -= lineSpacing
                continue
            }

            if (line.text.isNotBlank()) {
                val maxWidth = rect.width - 2 * MARGIN
                val wrappedLines = wrapText(line.text, font, fontSize, maxWidth)
                for (wl in wrappedLines) {
                    if (yPos - lineSpacing < MARGIN) {
                        contentStream.close()
                        page = PDPage(rect)
                        document.addPage(page)
                        contentStream = PDPageContentStream(document, page)
                        yPos = rect.height - MARGIN
                    }
                    contentStream.beginText()
                    contentStream.setFont(font, fontSize)
                    contentStream.newLineAtOffset(MARGIN, yPos)
                    contentStream.showText(wl)
                    contentStream.endText()
                    yPos -= lineSpacing
                }
            }

            if (line.type == LineType.P || line.type in listOf(LineType.H1, LineType.H2, LineType.H3)) {
                yPos -= fontSize * 0.5f
            }
        }

        contentStream.close()
        document.save(outputFile)
        document.close()
    }

    private data class TextLine(val text: String, val type: LineType)
    private enum class LineType { P, H1, H2, H3, HR }

    private fun parseHtmlToLines(html: String): List<TextLine> {
        val lines = mutableListOf<TextLine>()
        val cleaned = html
            .replace(Regex("<br\\s*/?>"), "\n")
            .replace(Regex("</?(?:html|body|div|span|table|tr|td|th|ul|ol|li|strong|em|b|i|a)[^>]*>"), "\n")

        val tagPattern = Regex("<(h[1-3]|p|hr)[^>]*>(.*?)</\\1>|<hr\\s*/?>", RegexOption.DOT_MATCHES_ALL)
        var lastEnd = 0

        for (match in tagPattern.findAll(cleaned)) {
            if (match.range.first > lastEnd) {
                val between = stripTags(cleaned.substring(lastEnd, match.range.first)).trim()
                if (between.isNotBlank()) {
                    between.split("\n").filter { it.isNotBlank() }.forEach {
                        lines.add(TextLine(it.trim(), LineType.P))
                    }
                }
            }

            val tag = match.groupValues.getOrElse(1) { "hr" }
            val content = stripTags(match.groupValues.getOrElse(2) { "" }).trim()
            when (tag) {
                "h1" -> if (content.isNotBlank()) lines.add(TextLine(content, LineType.H1))
                "h2" -> if (content.isNotBlank()) lines.add(TextLine(content, LineType.H2))
                "h3" -> if (content.isNotBlank()) lines.add(TextLine(content, LineType.H3))
                "p" -> if (content.isNotBlank()) lines.add(TextLine(content, LineType.P))
                "hr" -> lines.add(TextLine("", LineType.HR))
            }
            lastEnd = match.range.last + 1
        }

        if (lastEnd < cleaned.length) {
            val remaining = stripTags(cleaned.substring(lastEnd)).trim()
            if (remaining.isNotBlank()) {
                remaining.split("\n").filter { it.isNotBlank() }.forEach {
                    lines.add(TextLine(it.trim(), LineType.P))
                }
            }
        }

        return lines
    }

    private fun stripTags(text: String): String =
        text.replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")

    private fun wrapText(text: String, font: PDType1Font, fontSize: Float, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var currentLine = StringBuilder()

        for (word in words) {
            val testLine = if (currentLine.isEmpty()) word else "$currentLine $word"
            val width = font.getStringWidth(testLine) / 1000f * fontSize
            if (width > maxWidth && currentLine.isNotEmpty()) {
                lines.add(currentLine.toString())
                currentLine = StringBuilder(word)
            } else {
                currentLine = StringBuilder(testLine)
            }
        }
        if (currentLine.isNotEmpty()) lines.add(currentLine.toString())
        return lines
    }
}
