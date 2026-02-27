package io.wiiiv.platform.model

import kotlinx.serialization.Serializable
import java.time.Instant

@Serializable
data class ProjectPolicy(
    val projectId: Long,
    val allowedStepTypes: String = "[]",
    val allowedPlugins: String = "[]",
    val maxRequestsPerDay: Int? = null,
    // LLM 설정 (null이면 서버 기본값 사용)
    val llmProvider: String? = null,       // "OPENAI" | "ANTHROPIC" | "GOOGLE" | "OLLAMA" | "CUSTOM"
    val llmBaseUrl: String? = null,        // OLLAMA/CUSTOM 시 base URL
    val governorModel: String? = null,     // null이면 서버 기본값
    val generatorModel: String? = null,    // null이면 governorModel 따라감
    val embeddingModel: String? = null,    // null이면 서버 기본 embedding
    val createdAt: String = Instant.now().toString(),
    val updatedAt: String? = null
)
