package io.wiiiv.rag.chunk

import java.security.MessageDigest

/**
 * 텍스트 청크
 *
 * 문서를 분할한 단위. 각 청크는 독립적으로 임베딩되고 검색된다.
 */
data class Chunk(
    val id: String,
    val content: String,
    val sourceId: String,
    val index: Int,
    val metadata: Map<String, String> = emptyMap()
) {
    companion object {
        /**
         * 결정론적 청크 ID 생성
         *
         * SHA-256(content|sourceId|index) → 동일 입력은 동일 ID
         */
        fun generateId(content: String, sourceId: String, index: Int): String {
            val input = "$content|$sourceId|$index"
            val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}

/**
 * 청커 인터페이스
 *
 * 텍스트를 의미 있는 청크로 분할한다.
 *
 * ## 헌법
 * - Chunker는 분할만 한다
 * - Chunker는 판단하지 않는다
 * - 동일 입력은 동일 출력을 보장한다 (결정론적)
 */
interface Chunker {

    /**
     * 청커 식별자
     */
    val chunkerId: String

    /**
     * 텍스트를 청크로 분할
     *
     * @param text 분할할 텍스트
     * @param sourceId 문서 식별자
     * @return 청크 목록 (순서 보장)
     */
    fun chunk(text: String, sourceId: String): List<Chunk>
}

/**
 * 고정 크기 청커
 *
 * 텍스트를 고정된 문자 수로 분할한다.
 * 컨텍스트 유지를 위해 overlap을 지원한다.
 *
 * @param chunkSize 청크 크기 (문자 수)
 * @param overlap 겹치는 문자 수 (컨텍스트 유지)
 */
class FixedSizeChunker(
    private val chunkSize: Int = 500,
    private val overlap: Int = 50
) : Chunker {

    override val chunkerId: String = "fixed-size-chunker"

    init {
        require(chunkSize > 0) { "chunkSize must be positive" }
        require(overlap >= 0) { "overlap must be non-negative" }
        require(overlap < chunkSize) { "overlap must be less than chunkSize" }
    }

    override fun chunk(text: String, sourceId: String): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<Chunk>()
        var startIndex = 0
        var chunkIndex = 0

        while (startIndex < text.length) {
            val endIndex = minOf(startIndex + chunkSize, text.length)
            val content = text.substring(startIndex, endIndex).trim()

            if (content.isNotBlank()) {
                chunks.add(
                    Chunk(
                        id = Chunk.generateId(content, sourceId, chunkIndex),
                        content = content,
                        sourceId = sourceId,
                        index = chunkIndex,
                        metadata = mapOf(
                            "chunker" to chunkerId,
                            "startOffset" to startIndex.toString(),
                            "endOffset" to endIndex.toString(),
                            "chunkSize" to chunkSize.toString(),
                            "overlap" to overlap.toString()
                        )
                    )
                )
                chunkIndex++
            }

            // 다음 시작 위치 (overlap 고려)
            startIndex = endIndex - overlap

            // 무한 루프 방지
            if (startIndex >= text.length - overlap) break
        }

        return chunks
    }
}

/**
 * 문장 기반 청커
 *
 * 문장 경계를 존중하여 분할한다.
 * 의미 보존에 유리하다.
 *
 * @param maxChunkSize 최대 청크 크기
 * @param delimiters 문장 구분자
 */
class SentenceChunker(
    private val maxChunkSize: Int = 1000,
    private val delimiters: Set<Char> = setOf('.', '!', '?', '\n')
) : Chunker {

    override val chunkerId: String = "sentence-chunker"

    init {
        require(maxChunkSize > 0) { "maxChunkSize must be positive" }
    }

    override fun chunk(text: String, sourceId: String): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val sentences = splitIntoSentences(text)
        val chunks = mutableListOf<Chunk>()
        var currentContent = StringBuilder()
        var chunkIndex = 0
        var startOffset = 0

        for (sentence in sentences) {
            // 현재 청크에 문장을 추가하면 최대 크기를 초과하는 경우
            if (currentContent.length + sentence.length > maxChunkSize && currentContent.isNotBlank()) {
                // 현재 청크 저장
                val content = currentContent.toString().trim()
                if (content.isNotBlank()) {
                    chunks.add(createChunk(content, sourceId, chunkIndex, startOffset))
                    chunkIndex++
                    startOffset += content.length
                }
                currentContent = StringBuilder()
            }

            // 문장이 최대 크기보다 큰 경우 강제 분할
            if (sentence.length > maxChunkSize) {
                if (currentContent.isNotBlank()) {
                    val content = currentContent.toString().trim()
                    chunks.add(createChunk(content, sourceId, chunkIndex, startOffset))
                    chunkIndex++
                    startOffset += content.length
                    currentContent = StringBuilder()
                }

                // 긴 문장 강제 분할
                val forcedChunks = forceSplit(sentence, sourceId, chunkIndex, startOffset)
                chunks.addAll(forcedChunks)
                chunkIndex += forcedChunks.size
                startOffset += sentence.length
            } else {
                currentContent.append(sentence)
            }
        }

        // 마지막 청크 저장
        if (currentContent.isNotBlank()) {
            val content = currentContent.toString().trim()
            if (content.isNotBlank()) {
                chunks.add(createChunk(content, sourceId, chunkIndex, startOffset))
            }
        }

        return chunks
    }

    private fun splitIntoSentences(text: String): List<String> {
        val sentences = mutableListOf<String>()
        var current = StringBuilder()

        for (char in text) {
            current.append(char)
            if (char in delimiters) {
                val sentence = current.toString()
                if (sentence.isNotBlank()) {
                    sentences.add(sentence)
                }
                current = StringBuilder()
            }
        }

        // 마지막 부분 (구분자로 끝나지 않는 경우)
        if (current.isNotBlank()) {
            sentences.add(current.toString())
        }

        return sentences
    }

    private fun forceSplit(text: String, sourceId: String, startIndex: Int, startOffset: Int): List<Chunk> {
        val chunks = mutableListOf<Chunk>()
        var index = startIndex
        var offset = startOffset
        var pos = 0

        while (pos < text.length) {
            val end = minOf(pos + maxChunkSize, text.length)
            val content = text.substring(pos, end).trim()

            if (content.isNotBlank()) {
                chunks.add(
                    Chunk(
                        id = Chunk.generateId(content, sourceId, index),
                        content = content,
                        sourceId = sourceId,
                        index = index,
                        metadata = mapOf(
                            "chunker" to chunkerId,
                            "startOffset" to offset.toString(),
                            "forceSplit" to "true"
                        )
                    )
                )
                index++
                offset += content.length
            }
            pos = end
        }

        return chunks
    }

    private fun createChunk(content: String, sourceId: String, index: Int, startOffset: Int): Chunk {
        return Chunk(
            id = Chunk.generateId(content, sourceId, index),
            content = content,
            sourceId = sourceId,
            index = index,
            metadata = mapOf(
                "chunker" to chunkerId,
                "startOffset" to startOffset.toString()
            )
        )
    }
}

/**
 * 토큰 기반 청커 (향후 확장)
 *
 * 토큰 수 기반으로 분할한다.
 * 임베딩 모델의 토큰 제한을 정확히 준수할 수 있다.
 */
class TokenChunker(
    private val maxTokens: Int = 512,
    private val overlap: Int = 50,
    private val tokenizer: (String) -> List<String> = { it.split(Regex("\\s+")) }
) : Chunker {

    override val chunkerId: String = "token-chunker"

    override fun chunk(text: String, sourceId: String): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val tokens = tokenizer(text)
        val chunks = mutableListOf<Chunk>()
        var startIndex = 0
        var chunkIndex = 0

        while (startIndex < tokens.size) {
            val endIndex = minOf(startIndex + maxTokens, tokens.size)
            val chunkTokens = tokens.subList(startIndex, endIndex)
            val content = chunkTokens.joinToString(" ").trim()

            if (content.isNotBlank()) {
                chunks.add(
                    Chunk(
                        id = Chunk.generateId(content, sourceId, chunkIndex),
                        content = content,
                        sourceId = sourceId,
                        index = chunkIndex,
                        metadata = mapOf(
                            "chunker" to chunkerId,
                            "tokenCount" to chunkTokens.size.toString()
                        )
                    )
                )
                chunkIndex++
            }

            startIndex = endIndex - overlap
            if (startIndex >= tokens.size - overlap) break
        }

        return chunks
    }
}
