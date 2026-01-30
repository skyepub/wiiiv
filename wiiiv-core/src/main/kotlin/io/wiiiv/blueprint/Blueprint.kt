package io.wiiiv.blueprint

import io.wiiiv.execution.ExecutionStep
import io.wiiiv.execution.FileAction
import io.wiiiv.runner.StepRetryPolicy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Execution Blueprint
 *
 * Canonical: Blueprint Spec v1.1
 *
 * ## Blueprint의 정의
 *
 * "Execution Blueprint는 wiiiv 시스템에서 Governor의 판단이 고정된 결과물이며,
 *  실행·검증·재현의 유일한 기준 자산이다."
 *
 * ## Blueprint의 기본 전제
 *
 * - Blueprint는 실행을 위한 최종 자산이다
 * - Executor와 Gate는 Blueprint만을 기준으로 동작한다
 * - Executor와 Gate는 Spec을 직접 참조하지 않는다
 * - Blueprint는 자신이 어떤 판단을 근거로 생성되었는지를 명확히 포함해야 한다
 *
 * ## 불변성 원칙
 *
 * - Blueprint는 요청 단위로 생성된다
 * - 한 번 생성된 Blueprint는 절대 수정되지 않는다
 * - 변경이 필요할 경우, 새로운 Blueprint를 생성한다
 *
 * @property id Blueprint 고유 식별자
 * @property version Blueprint 버전
 * @property specSnapshot 원본 Spec의 스냅샷 참조
 * @property steps 실행할 step 목록
 * @property metadata 메타데이터
 */
@Serializable
data class Blueprint(
    /**
     * Blueprint 고유 식별자
     */
    val id: String,

    /**
     * Blueprint 버전
     */
    val version: String = "1.0",

    /**
     * Spec 스냅샷 정보
     *
     * Canonical: Blueprint Spec v1.1 §3
     * "Blueprint는 Spec 없이 존재할 수 없다"
     */
    val specSnapshot: SpecSnapshot,

    /**
     * 실행할 step 목록
     *
     * 순서대로 실행됨 (v1.0에서는 순차 실행만 지원)
     */
    val steps: List<BlueprintStep>,

    /**
     * 메타데이터
     */
    val metadata: BlueprintMetadata = BlueprintMetadata()
) {
    /**
     * Blueprint를 ExecutionStep 목록으로 변환
     */
    fun toExecutionSteps(): List<ExecutionStep> {
        return steps.map { it.toExecutionStep() }
    }

    companion object {
        private val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /**
         * JSON 문자열에서 Blueprint 파싱
         */
        fun fromJson(jsonString: String): Blueprint {
            return json.decodeFromString(serializer(), jsonString)
        }

        /**
         * Blueprint를 JSON 문자열로 변환
         */
        fun toJson(blueprint: Blueprint): String {
            return json.encodeToString(serializer(), blueprint)
        }
    }
}

/**
 * Spec Snapshot - Spec 스냅샷 정보
 *
 * Canonical: Blueprint Spec v1.1 §3
 *
 * Blueprint에 포함되는 Spec은 Blueprint 생성 시점의 Spec 상태를 고정한 스냅샷이다.
 */
@Serializable
data class SpecSnapshot(
    /**
     * 원본 Spec의 식별자
     */
    val specId: String,

    /**
     * 생성 시점의 버전 또는 해시
     */
    val specVersion: String? = null,

    /**
     * 스냅샷이 고정된 시점 (ISO-8601)
     */
    val snapshotAt: String,

    /**
     * 판단을 내린 Governor 식별자
     */
    val governorId: String,

    /**
     * DACS 판단 결과 요약 (선택)
     */
    val dacsResult: String? = null
)

/**
 * Blueprint Metadata - 메타데이터
 */
@Serializable
data class BlueprintMetadata(
    /**
     * 생성 시각 (ISO-8601)
     */
    val createdAt: String = java.time.Instant.now().toString(),

    /**
     * 생성 주체
     */
    val createdBy: String = "system",

    /**
     * 설명
     */
    val description: String = "",

    /**
     * 태그
     */
    val tags: List<String> = emptyList()
)

/**
 * Blueprint Step - Blueprint 내 step 정의
 *
 * JSON에서 파싱되어 ExecutionStep으로 변환됨
 */
@Serializable
data class BlueprintStep(
    /**
     * Step 고유 식별자
     */
    val stepId: String,

    /**
     * Step 유형
     */
    val type: BlueprintStepType,

    /**
     * Step 파라미터
     */
    val params: Map<String, String> = emptyMap(),

    /**
     * Step별 재시도 정책 (선택)
     *
     * Canonical: RetryPolicy Spec v1.0 §3
     * "Step에 retryPolicy가 명시된 경우 → Blueprint 계약이 최우선"
     */
    val retryPolicy: StepRetryPolicy? = null
) {
    /**
     * ExecutionStep으로 변환
     */
    fun toExecutionStep(): ExecutionStep {
        return when (type) {
            BlueprintStepType.FILE_READ -> ExecutionStep.FileStep(
                stepId = stepId,
                action = FileAction.READ,
                path = params["path"] ?: error("FILE_READ requires 'path' param")
            )
            BlueprintStepType.FILE_WRITE -> ExecutionStep.FileStep(
                stepId = stepId,
                action = FileAction.WRITE,
                path = params["path"] ?: error("FILE_WRITE requires 'path' param"),
                content = params["content"]
            )
            BlueprintStepType.FILE_COPY -> ExecutionStep.FileStep(
                stepId = stepId,
                action = FileAction.COPY,
                path = params["source"] ?: error("FILE_COPY requires 'source' param"),
                targetPath = params["target"] ?: error("FILE_COPY requires 'target' param")
            )
            BlueprintStepType.FILE_MOVE -> ExecutionStep.FileStep(
                stepId = stepId,
                action = FileAction.MOVE,
                path = params["source"] ?: error("FILE_MOVE requires 'source' param"),
                targetPath = params["target"] ?: error("FILE_MOVE requires 'target' param")
            )
            BlueprintStepType.FILE_DELETE -> ExecutionStep.FileStep(
                stepId = stepId,
                action = FileAction.DELETE,
                path = params["path"] ?: error("FILE_DELETE requires 'path' param")
            )
            BlueprintStepType.FILE_MKDIR -> ExecutionStep.FileStep(
                stepId = stepId,
                action = FileAction.MKDIR,
                path = params["path"] ?: error("FILE_MKDIR requires 'path' param")
            )
            BlueprintStepType.COMMAND -> ExecutionStep.CommandStep(
                stepId = stepId,
                command = params["command"] ?: error("COMMAND requires 'command' param"),
                args = params["args"]?.split(" ") ?: emptyList(),
                workingDir = params["workingDir"],
                timeoutMs = params["timeoutMs"]?.toLongOrNull() ?: 60_000
            )
            BlueprintStepType.NOOP -> ExecutionStep.NoopStep(
                stepId = stepId,
                params = params
            )
        }
    }
}

/**
 * Blueprint Step Type
 *
 * Blueprint JSON에서 사용되는 step 유형
 */
@Serializable
enum class BlueprintStepType {
    @SerialName("file_read")
    FILE_READ,

    @SerialName("file_write")
    FILE_WRITE,

    @SerialName("file_copy")
    FILE_COPY,

    @SerialName("file_move")
    FILE_MOVE,

    @SerialName("file_delete")
    FILE_DELETE,

    @SerialName("file_mkdir")
    FILE_MKDIR,

    @SerialName("command")
    COMMAND,

    @SerialName("noop")
    NOOP
}
