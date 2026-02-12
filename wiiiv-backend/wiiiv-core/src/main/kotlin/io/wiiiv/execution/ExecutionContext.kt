package io.wiiiv.execution

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Execution Context - 실행 컨텍스트
 *
 * Canonical: Executor Interface Spec v1.0 §3
 *
 * 실행 세션의 컨텍스트 컨테이너.
 *
 * ## Context의 성격 (Canonical §3.0)
 *
 * | 항목 | 성격 |
 * |------|------|
 * | 식별자 (executionId, blueprintId 등) | 불변 - 세션 동안 변경되지 않음 |
 * | stepOutputs | 누적 - step 실행 결과가 추가됨 (기존 값 수정 불가) |
 * | environment | 읽기 전용 - 실행 중 변경되지 않음 |
 * | resources | 관리됨 - 리소스 생명주기에 따라 추가/제거 |
 *
 * @property executionId 실행 세션의 고유 ID
 * @property blueprintId 이 실행이 속한 Blueprint의 ID
 * @property instructionId 감사용 요청 ID
 * @property environment 환경 변수
 * @property options 실행 옵션
 */
data class ExecutionContext(
    /**
     * 실행 세션의 고유 ID
     *
     * 불변 - 세션 동안 변경되지 않음
     */
    val executionId: String,

    /**
     * 이 실행이 속한 Blueprint의 ID
     *
     * 불변 - 세션 동안 변경되지 않음
     */
    val blueprintId: String,

    /**
     * 감사용 요청 ID
     *
     * 불변 - 세션 동안 변경되지 않음
     */
    val instructionId: String,

    /**
     * 환경 변수
     *
     * 읽기 전용 - 실행 중 변경되지 않음
     */
    val environment: Map<String, String> = emptyMap(),

    /**
     * 실행 옵션
     */
    val options: ExecutionOptions = ExecutionOptions.DEFAULT
) {
    /**
     * Step 출력 저장소
     *
     * Append-only: 기존 값 수정 불가, 추가만 가능
     * Key: stepId, Value: StepOutput
     */
    private val _stepOutputs: MutableMap<String, StepOutput> = ConcurrentHashMap()

    /**
     * Step 출력 조회 (읽기 전용)
     */
    val stepOutputs: Map<String, StepOutput> get() = _stepOutputs.toMap()

    /**
     * Step 출력 추가 (append-only)
     *
     * 이미 존재하는 stepId에 대해 덮어쓰기를 시도하면 예외 발생.
     * 이는 append-only 원칙을 강제하기 위함.
     */
    fun addStepOutput(stepId: String, output: StepOutput) {
        require(!_stepOutputs.containsKey(stepId)) {
            "Step output already exists for stepId: $stepId (append-only violation)"
        }
        _stepOutputs[stepId] = output
    }

    /**
     * 특정 step의 출력 조회
     */
    fun getStepOutput(stepId: String): StepOutput? = _stepOutputs[stepId]

    /**
     * 리소스 레지스트리
     *
     * DB 연결, 파일 핸들 등의 리소스 관리
     */
    private val _resources: MutableMap<String, Any> = ConcurrentHashMap()

    /**
     * 리소스 조회
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getResource(key: String): T? = _resources[key] as? T

    /**
     * 리소스 등록
     */
    fun putResource(key: String, resource: Any) {
        _resources[key] = resource
    }

    /**
     * 리소스 제거
     */
    fun removeResource(key: String): Any? = _resources.remove(key)

    /**
     * 모든 리소스 정리
     */
    fun closeResources() {
        _resources.values.forEach { resource ->
            if (resource is AutoCloseable) {
                runCatching { resource.close() }
            }
        }
        _resources.clear()
    }

    companion object {
        /**
         * 새 ExecutionContext 생성
         */
        fun create(
            executionId: String,
            blueprintId: String,
            instructionId: String,
            environment: Map<String, String> = emptyMap(),
            options: ExecutionOptions = ExecutionOptions.DEFAULT
        ): ExecutionContext = ExecutionContext(
            executionId = executionId,
            blueprintId = blueprintId,
            instructionId = instructionId,
            environment = environment,
            options = options
        )
    }
}

/**
 * Execution Options - 실행 옵션
 *
 * Canonical: Executor Interface Spec v1.0 §3.2
 */
@Serializable
data class ExecutionOptions(
    /**
     * 최대 출력 크기 (바이트)
     *
     * stdout/stderr 등의 출력 제한
     */
    val maxOutputBytes: Long = 1_048_576,  // 1MB

    /**
     * 기본 타임아웃 (밀리초)
     *
     * step별로 override 가능
     */
    val defaultTimeoutMs: Long = 120_000,  // 2 minutes

    /**
     * 상세 추적 활성화 여부
     */
    val enableTracing: Boolean = true,

    /**
     * 민감 데이터 마스킹 여부
     */
    val maskSensitiveData: Boolean = true
) {
    companion object {
        val DEFAULT = ExecutionOptions()
    }
}
