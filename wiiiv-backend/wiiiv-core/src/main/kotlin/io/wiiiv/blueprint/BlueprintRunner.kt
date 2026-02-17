package io.wiiiv.blueprint

import io.wiiiv.execution.ExecutionContext
import io.wiiiv.execution.Executor
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RetryPolicy
import io.wiiiv.runner.RunnerResult
import java.util.UUID

/**
 * Blueprint Runner - Blueprint 실행기
 *
 * Blueprint를 받아 ExecutionRunner로 실행한다.
 *
 * ## 역할
 *
 * 1. Blueprint를 ExecutionStep 목록으로 변환
 * 2. ExecutionContext 생성
 * 3. ExecutionRunner로 실행
 * 4. 결과 반환
 *
 * ## Canonical 위치
 *
 * BlueprintRunner는 Governor의 내부 도구이다.
 * Governor → BlueprintRunner → ExecutionRunner → Executor
 */
class BlueprintRunner(
    private val executor: Executor,
    private val retryPolicy: RetryPolicy = RetryPolicy.DEFAULT
) {
    private val runner = ExecutionRunner.create(executor, retryPolicy)

    /**
     * Blueprint 실행
     *
     * @param blueprint 실행할 Blueprint
     * @param instructionId 감사용 요청 ID (선택)
     * @return 실행 결과
     */
    suspend fun execute(
        blueprint: Blueprint,
        instructionId: String = UUID.randomUUID().toString()
    ): BlueprintExecutionResult {
        // Create execution context
        val context = ExecutionContext.create(
            executionId = UUID.randomUUID().toString(),
            blueprintId = blueprint.id,
            instructionId = instructionId
        )

        // Convert blueprint steps to execution steps
        val steps = blueprint.toExecutionSteps()

        // Execute
        val result = runner.execute(steps, context)

        return BlueprintExecutionResult(
            blueprintId = blueprint.id,
            runnerResult = result,
            context = context
        )
    }

    /**
     * JSON Blueprint 실행
     *
     * @param blueprintJson Blueprint JSON 문자열
     * @param instructionId 감사용 요청 ID (선택)
     * @return 실행 결과
     */
    suspend fun executeFromJson(
        blueprintJson: String,
        instructionId: String = UUID.randomUUID().toString()
    ): BlueprintExecutionResult {
        val blueprint = Blueprint.fromJson(blueprintJson)
        return execute(blueprint, instructionId)
    }

    companion object {
        /**
         * 기본 BlueprintRunner 생성
         */
        fun create(executor: Executor): BlueprintRunner = BlueprintRunner(executor)

        /**
         * 커스텀 RetryPolicy로 생성
         */
        fun create(executor: Executor, retryPolicy: RetryPolicy): BlueprintRunner =
            BlueprintRunner(executor, retryPolicy)
    }
}

/**
 * Blueprint Execution Result - Blueprint 실행 결과
 */
data class BlueprintExecutionResult(
    /**
     * 실행된 Blueprint ID
     */
    val blueprintId: String,

    /**
     * Runner 실행 결과
     */
    val runnerResult: RunnerResult,

    /**
     * 실행 컨텍스트 (step 출력 포함)
     */
    val context: ExecutionContext
) {
    /**
     * 성공 여부
     */
    val isSuccess: Boolean get() = runnerResult.isAllSuccess

    /**
     * 성공한 step 수
     */
    val successCount: Int get() = runnerResult.successCount

    /**
     * 실패한 step 수
     */
    val failureCount: Int get() = runnerResult.failureCount

    /**
     * 특정 step의 출력 조회
     */
    fun getStepOutput(stepId: String) = context.getStepOutput(stepId)
}
