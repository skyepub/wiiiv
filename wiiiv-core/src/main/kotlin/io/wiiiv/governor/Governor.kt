package io.wiiiv.governor

import io.wiiiv.blueprint.*
import java.time.Instant
import java.util.UUID

/**
 * Governor - 판단 주체
 *
 * Canonical: Governor 역할 정의서 v1.1
 *
 * ## Governor의 역할
 *
 * "Governor는 요청을 해석하고, Spec을 근거로 판단하며,
 *  Blueprint를 생성하고, 흐름을 제어하는 유일한 판단 주체다."
 *
 * ## Governor의 핵심 책임
 *
 * 1. **요청 해석**: 외부 요청의 의미를 파악
 * 2. **판단**: Spec을 근거로 DACS를 통해 합의 도출
 * 3. **Blueprint 생성**: 판단 결과를 실행 가능한 형태로 고정
 * 4. **흐름 제어**: 실행 결과를 바탕으로 다음 행동 결정
 *
 * ## Governor의 금지 사항
 *
 * - 직접 실행하지 않는다 (Executor의 역할)
 * - Blueprint를 수정하지 않는다 (불변)
 * - 결과를 임의로 해석하지 않는다 (기록된 대로 전달)
 *
 * ## v2.0 최소 구현
 *
 * 이 구현은 최소 기능만 제공한다:
 * - Spec 기반 Blueprint 생성
 * - DACS 없이 직접 판단 (v2.1에서 DACS 통합)
 */
interface Governor {
    /**
     * Governor 식별자
     */
    val id: String

    /**
     * Spec을 기반으로 Blueprint 생성
     *
     * @param request 처리할 요청
     * @param spec 판단의 근거가 되는 Spec
     * @return 생성된 Blueprint
     */
    suspend fun createBlueprint(request: GovernorRequest, spec: Spec): GovernorResult
}

/**
 * Governor Request - Governor에 대한 요청
 *
 * 외부에서 들어오는 요청을 표현
 */
data class GovernorRequest(
    /**
     * 요청 ID
     */
    val requestId: String = UUID.randomUUID().toString(),

    /**
     * 요청 유형
     */
    val type: RequestType,

    /**
     * 요청 대상 경로 (파일 경로 등)
     */
    val targetPath: String? = null,

    /**
     * 요청 내용 (쓰기 내용 등)
     */
    val content: String? = null,

    /**
     * 추가 파라미터
     */
    val params: Map<String, String> = emptyMap()
)

/**
 * Request Type - 요청 유형
 */
enum class RequestType {
    FILE_READ,
    FILE_WRITE,
    FILE_COPY,
    FILE_MOVE,
    FILE_DELETE,
    FILE_MKDIR,
    COMMAND,
    CUSTOM
}

/**
 * Spec - 판단의 근거
 *
 * Canonical: Spec 정의서 v1.0
 *
 * "Spec은 시스템의 기준이다. Governor는 Spec을 기준으로 판단한다."
 */
data class Spec(
    /**
     * Spec 식별자
     */
    val id: String,

    /**
     * Spec 버전
     */
    val version: String = "1.0.0",

    /**
     * Spec 이름
     */
    val name: String,

    /**
     * Spec 설명
     */
    val description: String = "",

    /**
     * 허용된 작업 목록
     */
    val allowedOperations: List<RequestType> = emptyList(),

    /**
     * 허용된 경로 패턴 (glob 패턴)
     */
    val allowedPaths: List<String> = emptyList(),

    /**
     * 추가 제약 조건
     */
    val constraints: Map<String, String> = emptyMap()
)

/**
 * Governor Result - Governor 판단 결과
 */
sealed class GovernorResult {
    /**
     * Blueprint 생성 성공
     */
    data class BlueprintCreated(
        val blueprint: Blueprint
    ) : GovernorResult()

    /**
     * 요청 거부
     */
    data class Denied(
        val reason: String,
        val specId: String
    ) : GovernorResult()

    /**
     * 판단 실패
     */
    data class Failed(
        val error: String
    ) : GovernorResult()
}

/**
 * Simple Governor - 최소 Governor 구현
 *
 * v2.0 최소 구현:
 * - DACS 없이 직접 판단
 * - Spec의 allowedOperations만 확인
 * - Blueprint 생성
 */
class SimpleGovernor(
    override val id: String = "gov-simple-${UUID.randomUUID().toString().take(8)}"
) : Governor {

    override suspend fun createBlueprint(request: GovernorRequest, spec: Spec): GovernorResult {
        // 1. 요청 유효성 검사
        if (request.type == RequestType.CUSTOM) {
            return GovernorResult.Failed("CUSTOM type requires explicit step definition")
        }

        // 2. Spec 기반 판단 (최소 구현: allowedOperations만 확인)
        if (spec.allowedOperations.isNotEmpty() && request.type !in spec.allowedOperations) {
            return GovernorResult.Denied(
                reason = "Operation ${request.type} not allowed by spec ${spec.id}",
                specId = spec.id
            )
        }

        // 3. 경로 검사 (선택적)
        if (spec.allowedPaths.isNotEmpty() && request.targetPath != null) {
            val pathAllowed = spec.allowedPaths.any { pattern ->
                matchGlobPattern(pattern, request.targetPath)
            }
            if (!pathAllowed) {
                return GovernorResult.Denied(
                    reason = "Path ${request.targetPath} not allowed by spec ${spec.id}",
                    specId = spec.id
                )
            }
        }

        // 4. Blueprint 생성
        val now = Instant.now().toString()
        val blueprintId = "bp-${UUID.randomUUID()}"

        val step = createBlueprintStep(request)
            ?: return GovernorResult.Failed("Failed to create step for request type: ${request.type}")

        val blueprint = Blueprint(
            id = blueprintId,
            version = "1.0",
            specSnapshot = SpecSnapshot(
                specId = spec.id,
                specVersion = spec.version,
                snapshotAt = now,
                governorId = id,
                dacsResult = "DIRECT_ALLOW" // DACS 없이 직접 허용
            ),
            steps = listOf(step),
            metadata = BlueprintMetadata(
                createdAt = now,
                createdBy = id,
                description = "Blueprint for ${request.type} operation",
                tags = listOf("auto-generated", request.type.name.lowercase())
            )
        )

        return GovernorResult.BlueprintCreated(blueprint)
    }

    /**
     * 요청을 BlueprintStep으로 변환
     */
    private fun createBlueprintStep(request: GovernorRequest): BlueprintStep? {
        val stepId = "step-${UUID.randomUUID().toString().take(8)}"

        return when (request.type) {
            RequestType.FILE_READ -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_READ,
                    params = mapOf("path" to path)
                )
            }
            RequestType.FILE_WRITE -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_WRITE,
                    params = mapOf(
                        "path" to path,
                        "content" to (request.content ?: "")
                    )
                )
            }
            RequestType.FILE_COPY -> {
                val source = request.targetPath ?: return null
                val target = request.params["target"] ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_COPY,
                    params = mapOf(
                        "source" to source,
                        "target" to target
                    )
                )
            }
            RequestType.FILE_MOVE -> {
                val source = request.targetPath ?: return null
                val target = request.params["target"] ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_MOVE,
                    params = mapOf(
                        "source" to source,
                        "target" to target
                    )
                )
            }
            RequestType.FILE_DELETE -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_DELETE,
                    params = mapOf("path" to path)
                )
            }
            RequestType.FILE_MKDIR -> {
                val path = request.targetPath ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.FILE_MKDIR,
                    params = mapOf("path" to path)
                )
            }
            RequestType.COMMAND -> {
                val command = request.params["command"] ?: return null
                BlueprintStep(
                    stepId = stepId,
                    type = BlueprintStepType.COMMAND,
                    params = mapOf(
                        "command" to command,
                        "args" to (request.params["args"] ?: ""),
                        "workingDir" to (request.params["workingDir"] ?: ""),
                        "timeoutMs" to (request.params["timeoutMs"] ?: "60000")
                    )
                )
            }
            RequestType.CUSTOM -> null
        }
    }

    /**
     * 간단한 glob 패턴 매칭
     *
     * 지원 패턴:
     * - * : 단일 경로 세그먼트
     * - ** : 모든 하위 경로
     * - 정확한 경로 매칭
     */
    private fun matchGlobPattern(pattern: String, path: String): Boolean {
        // 정확한 매칭
        if (pattern == path) return true

        // ** 패턴: 모든 하위 경로
        if (pattern.endsWith("/**")) {
            val prefix = pattern.dropLast(3)
            return path.startsWith(prefix)
        }

        // * 패턴: 단일 세그먼트
        if (pattern.contains("*") && !pattern.contains("**")) {
            val regex = pattern
                .replace(".", "\\.")
                .replace("*", "[^/]*")
            return Regex(regex).matches(path)
        }

        return false
    }

    companion object {
        /**
         * 기본 Governor 인스턴스
         */
        val DEFAULT = SimpleGovernor("gov-default")
    }
}
