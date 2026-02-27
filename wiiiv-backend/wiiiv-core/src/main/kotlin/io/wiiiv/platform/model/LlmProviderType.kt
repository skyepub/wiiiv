package io.wiiiv.platform.model

/**
 * LLM Provider Type - 프로젝트별 LLM 공급자 유형
 *
 * ProjectPolicy.llmProvider 필드의 유효성 검증용.
 * DB 저장은 String (확장성), enum은 검증용.
 */
enum class LlmProviderType {
    OPENAI,
    ANTHROPIC,
    GOOGLE,
    OLLAMA,
    CUSTOM
}
