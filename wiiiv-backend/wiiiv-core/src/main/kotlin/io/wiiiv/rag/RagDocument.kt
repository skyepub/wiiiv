package io.wiiiv.rag

import kotlinx.serialization.Serializable
import java.time.Instant

/**
 * RagDocument - RAG 문서 메타데이터 (영속 계층)
 *
 * 벡터 저장소와 별도로, 문서 자체의 메타데이터와 원본 내용을 DB에 보관한다.
 * 서버 재시작 시 이 정보를 기반으로 벡터를 재생성(자동 재수집)할 수 있다.
 *
 * scope: "global" | "user:{id}" | "project:{id}"
 */
@Serializable
data class RagDocument(
    val documentId: String,
    val scope: String = "global",
    val title: String,
    val filePath: String? = null,
    val content: String? = null,
    val contentHash: String,       // SHA-256
    val chunkCount: Int = 0,
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String = Instant.now().toString()
)
