package io.wiiiv.cli

import io.wiiiv.execution.impl.LlmImage
import io.wiiiv.cli.commands.resolvePath
import java.io.File

/**
 * 이미지 경로 감지 및 파싱
 *
 * macOS Finder에서 터미널로 이미지 파일을 드래그하면 삽입되는 경로를 감지하여
 * 이미지 바이트를 읽고 LlmImage로 변환한다.
 *
 * 지원 경로 형식:
 * - /Users/me/photo.png (공백 없는 절대 경로)
 * - /Users/me/my\ photo.png (백슬래시 이스케이프)
 * - '/Users/me/my photo.png' (단일 따옴표)
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

    private const val MAX_IMAGE_SIZE = 20 * 1024 * 1024L  // 20MB (OpenAI limit)

    data class ParseResult(val text: String, val images: List<LlmImage>)

    fun parse(input: String): ParseResult {
        val images = mutableListOf<LlmImage>()
        var remaining = input

        // 1. 단일 따옴표로 감싼 경로 추출: '/path/to/file.png'
        val quotedPattern = Regex("'(/[^']+)'")
        val quotedMatches = quotedPattern.findAll(remaining).toList().reversed()
        for (match in quotedMatches) {
            val path = match.groupValues[1]
            val image = tryLoadImage(path)
            if (image != null) {
                images.add(image)
                remaining = remaining.removeRange(match.range)
            }
        }

        // 2. 이스케이프된 공백이 있는 절대 경로: /path/to/my\ file.png
        val escapedPattern = Regex("""(/(?:[^\s'"]|\\[ ])+)""")
        val escapedMatches = escapedPattern.findAll(remaining).toList().reversed()
        for (match in escapedMatches) {
            val rawPath = match.value
            // 이스케이프된 공백을 실제 공백으로 변환
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

    private fun tryLoadImage(path: String): LlmImage? {
        val resolved = resolvePath(path)
        val file = File(resolved)

        // 파일 존재 확인
        if (!file.exists() || !file.isFile) return null

        // 이미지 확장자 확인
        val ext = file.extension.lowercase()
        if (ext !in IMAGE_EXTENSIONS) return null

        // 파일 크기 확인
        if (file.length() > MAX_IMAGE_SIZE) return null

        // MIME type
        val mimeType = MIME_TYPES[ext] ?: return null

        return try {
            val data = file.readBytes()
            LlmImage(data = data, mimeType = mimeType)
        } catch (_: Exception) {
            null
        }
    }
}
