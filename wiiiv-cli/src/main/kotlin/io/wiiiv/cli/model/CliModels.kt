package io.wiiiv.cli.model

/**
 * CLI 로컬 타입 — core 의존성 대체
 */

enum class CliActionType {
    REPLY, ASK, CONFIRM, EXECUTE, CANCEL
}

enum class CliProgressPhase {
    LLM_THINKING, DACS_EVALUATING, BLUEPRINT_CREATING, EXECUTING, COMMAND_RUNNING, IMAGE_ANALYZING, DONE;

    companion object {
        fun fromString(name: String): CliProgressPhase =
            entries.find { it.name == name } ?: DONE
    }
}

enum class CliStepType {
    FILE_READ, FILE_WRITE, FILE_COPY, FILE_MOVE, FILE_DELETE, FILE_MKDIR, COMMAND, API_CALL, NOOP;

    companion object {
        fun fromString(name: String): CliStepType =
            entries.find { it.name == name } ?: NOOP
    }
}

enum class CliMessageRole {
    USER, GOVERNOR, SYSTEM
}

/**
 * 로컬 이미지 — base64 인코딩
 */
data class LocalImage(
    val base64: String,
    val mimeType: String,
    val sizeBytes: Int = 0
)
