package io.wiiiv.cli

import io.wiiiv.cli.model.LocalImage
import io.wiiiv.cli.commands.resolvePath
import java.io.File
import java.util.Base64

/**
 * 이미지 경로 감지 및 파싱
 *
 * 터미널로 이미지 파일을 드래그하면 삽입되는 경로를 감지하여
 * 이미지 바이트를 읽고 Base64로 인코딩하여 LocalImage로 변환한다.
 */
object ImageInputParser {

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

    private val MIME_TYPES = mapOf(
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png" to "image/png",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp"
    )

    private const val MAX_IMAGE_SIZE = 20 * 1024 * 1024L  // 20MB

    data class ParseResult(val text: String, val images: List<LocalImage>)

    fun parse(input: String): ParseResult {
        val images = mutableListOf<LocalImage>()
        var remaining = input

        // 1. 쌍따옴표로 감싼 경로 추출: "C:\Users\...\file.png"
        val doubleQuotedPattern = Regex(""""([^"]+)"""")
        val doubleQuotedMatches = doubleQuotedPattern.findAll(remaining).toList().reversed()
        for (match in doubleQuotedMatches) {
            val path = match.groupValues[1]
            val image = tryLoadImage(path)
            if (image != null) {
                images.add(image)
                remaining = remaining.removeRange(match.range)
            }
        }

        // 2. 단일 따옴표로 감싼 경로 추출: '/path/to/file.png' 또는 'C:\...\file.png'
        val quotedPattern = Regex("'([^']+)'")
        val quotedMatches = quotedPattern.findAll(remaining).toList().reversed()
        for (match in quotedMatches) {
            val path = match.groupValues[1]
            val image = tryLoadImage(path)
            if (image != null) {
                images.add(image)
                remaining = remaining.removeRange(match.range)
            }
        }

        // 3. Windows 절대 경로: C:\Users\...\file.png (공백 없는 경로)
        val windowsPattern = Regex("""[A-Za-z]:[\\\/][^\s'"]+""")
        val windowsMatches = windowsPattern.findAll(remaining).toList().reversed()
        for (match in windowsMatches) {
            val path = match.value
            val image = tryLoadImage(path)
            if (image != null) {
                images.add(image)
                remaining = remaining.removeRange(match.range)
            }
        }

        // 4. 이스케이프된 공백이 있는 Unix 절대 경로: /path/to/my\ file.png
        val escapedPattern = Regex("""(/(?:[^\s'"]|\\[ ])+)""")
        val escapedMatches = escapedPattern.findAll(remaining).toList().reversed()
        for (match in escapedMatches) {
            val rawPath = match.value
            val path = rawPath.replace("\\ ", " ")
            val image = tryLoadImage(path)
            if (image != null) {
                images.add(image)
                remaining = remaining.removeRange(match.range)
            }
        }

        // 텍스트 정리: 여러 공백 → 단일 공백
        val text = remaining.trim().replace(Regex("\\s+"), " ")

        // images는 역순으로 추가했으므로 뒤집기
        return ParseResult(text, images.reversed())
    }

    private fun tryLoadImage(path: String): LocalImage? {
        val resolved = resolvePath(path)
        val file = File(resolved)

        if (!file.exists() || !file.isFile) return null

        val ext = file.extension.lowercase()
        if (ext !in IMAGE_EXTENSIONS) return null

        if (file.length() > MAX_IMAGE_SIZE) return null

        val mimeType = MIME_TYPES[ext] ?: return null

        return try {
            val data = file.readBytes()
            val base64 = Base64.getEncoder().encodeToString(data)
            LocalImage(base64 = base64, mimeType = mimeType, sizeBytes = data.size)
        } catch (_: Exception) {
            null
        }
    }
}
