# Executor Interface Spec v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Executor Interface Specification |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-30 |
| 상위 문서 | Executor 정의서 v1.0, Blueprint Node Type Spec v1.0 |
| 적용 범위 | wiiiv Executor 구현 전반 |

---

## 0. 문서의 목적

본 문서는 Executor의 **프로그래밍 인터페이스**를 정의한다.

- 메서드 시그니처
- 입력/출력 타입
- ExecutionContext 구조
- ExecutionResult 구조
- Trace 연계 규약

이 문서는 Executor 정의서 v1.0의 **구현 명세**에 해당한다.

---

## 1. Executor 인터페이스

### 1.1 Core Interface

```kotlin
interface Executor {
    /**
     * Execute a single step from Blueprint
     *
     * @param step The step to execute (from Blueprint)
     * @param context Execution context (environment, variables, resources)
     * @return Execution result (Success/Failure/Cancelled)
     */
    suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult

    /**
     * Cancel an ongoing execution
     *
     * @param executionId The execution to cancel
     * @param reason Cancellation reason
     * @return true if cancellation was successful
     */
    suspend fun cancel(
        executionId: String,
        reason: CancelReason
    ): Boolean

    /**
     * Check if this executor can handle a step type
     *
     * @param step The step to check
     * @return true if this executor can handle the step
     */
    fun canHandle(step: ExecutionStep): Boolean
}
```

### 1.2 Composite Executor

```kotlin
/**
 * Executor that delegates to specialized executors
 */
class CompositeExecutor(
    private val executors: List<Executor>
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        val executor = executors.find { it.canHandle(step) }
            ?: return ExecutionResult.Failure(
                error = ExecutionError(
                    category = ErrorCategory.CONTRACT_VIOLATION,
                    code = "NO_EXECUTOR",
                    message = "No executor found for step type: ${step.type}"
                ),
                meta = ExecutionMeta.now(step.stepId)
            )

        return executor.execute(step, context)
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // Delegate to all executors
        return executors.any { it.cancel(executionId, reason) }
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return executors.any { it.canHandle(step) }
    }
}
```

---

## 2. ExecutionStep 정의

### 2.1 기본 구조

ExecutionStep은 Blueprint의 step을 Executor가 소비할 수 있는 형태로 표현한다.

```kotlin
sealed class ExecutionStep {
    abstract val stepId: String
    abstract val type: StepType
    abstract val params: Map<String, Any>
}

enum class StepType {
    CODE_GENERATION,
    FILE_OPERATION,
    LLM_CALL,
    API_CALL,
    USER_INTERACTION,  // 예약됨 (§2.3 참조)
    COMMAND,           // Shell command
    DB_OPERATION,      // Database query/mutation
    MULTIMODAL         // Image/document processing
}
```

### 2.2 Step Type별 정의

#### FileStep

```kotlin
data class FileStep(
    override val stepId: String,
    val action: FileAction,
    override val params: Map<String, Any> = emptyMap()
) : ExecutionStep() {
    override val type = StepType.FILE_OPERATION
}

enum class FileAction {
    READ, WRITE, COPY, MOVE, DELETE, MKDIR
}
```

#### CommandStep

```kotlin
data class CommandStep(
    override val stepId: String,
    val command: String,
    val args: List<String> = emptyList(),
    val workingDir: String? = null,
    val env: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 60_000,
    val stdin: String? = null,
    override val params: Map<String, Any> = emptyMap()
) : ExecutionStep() {
    override val type = StepType.COMMAND
}
```

#### LlmCallStep

```kotlin
data class LlmCallStep(
    override val stepId: String,
    val action: LlmAction,
    val prompt: String,
    val model: String? = null,
    val maxTokens: Int? = null,
    override val params: Map<String, Any> = emptyMap()
) : ExecutionStep() {
    override val type = StepType.LLM_CALL
}

enum class LlmAction {
    COMPLETE, ANALYZE, SUMMARIZE
}
```

#### DbStep

```kotlin
data class DbStep(
    override val stepId: String,
    val sql: String,
    val mode: DbMode,
    val connectionId: String? = null,
    override val params: Map<String, Any> = emptyMap()
) : ExecutionStep() {
    override val type = StepType.DB_OPERATION
}

enum class DbMode {
    QUERY, MUTATION, DDL
}
```

#### ApiCallStep

```kotlin
data class ApiCallStep(
    override val stepId: String,
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
    val timeoutMs: Long = 30_000,
    override val params: Map<String, Any> = emptyMap()
) : ExecutionStep() {
    override val type = StepType.API_CALL
}

enum class HttpMethod {
    GET, POST, PUT, DELETE, PATCH
}
```

### 2.3 USER_INTERACTION StepType의 지위

`USER_INTERACTION`은 StepType에 정의되어 있으나, 현 시점에서 **예약된 타입**이다.

#### 지위 정의

| 항목 | 설명 |
|------|------|
| 정의 여부 | ✅ StepType enum에 존재 |
| Step 구현체 | ⚠️ 미정의 (UserInteractionStep 없음) |
| Executor 구현체 | ⚠️ 미구현 |
| 사용 가능 여부 | ❌ v1.0에서는 사용하지 않음 |

#### 설계 의도

USER_INTERACTION은 다음을 위해 예약되었다:

- 실행 흐름 중 사용자 입력이 필요한 경우
- 확인/승인 대기가 필요한 step
- 대화형 실행 시나리오

#### 현 시점 규칙

| 규칙 | 설명 |
|------|------|
| Blueprint에 USER_INTERACTION step 포함 | ❌ 불허 (v1.0) |
| Executor가 USER_INTERACTION 처리 | ❌ CONTRACT_VIOLATION 반환 |

> **USER_INTERACTION은 향후 확장을 위한 예약 타입이며, v1.0에서는 사용되지 않는다.**

---

## 3. ExecutionContext 정의

### 3.0 Context의 성격

ExecutionContext는 실행 세션의 **컨텍스트 컨테이너**다.

| 항목 | 성격 |
|------|------|
| 식별자 (executionId, blueprintId 등) | **불변** - 세션 동안 변경되지 않음 |
| stepOutputs | **누적** - step 실행 결과가 추가됨 (기존 값 수정 불가) |
| environment | **읽기 전용** - 실행 중 변경되지 않음 |
| resources | **관리됨** - 리소스 생명주기에 따라 추가/제거 |

> **ExecutionContext의 식별자 필드는 불변이며, stepOutputs는 append-only 방식으로 누적된다.**

### 3.1 구조

```kotlin
data class ExecutionContext(
    /**
     * Unique execution ID (for this execution session)
     */
    val executionId: String,

    /**
     * Blueprint ID that this execution belongs to
     */
    val blueprintId: String,

    /**
     * Instruction/Request ID for audit
     */
    val instructionId: String,

    /**
     * Variable store for step outputs
     * Key: stepId, Value: StepOutput
     */
    val stepOutputs: MutableMap<String, StepOutput> = mutableMapOf(),

    /**
     * Environment variables
     */
    val environment: Map<String, String> = emptyMap(),

    /**
     * Resource handles (DB connections, file handles, etc.)
     */
    val resources: ResourceRegistry = ResourceRegistry(),

    /**
     * Execution options
     */
    val options: ExecutionOptions = ExecutionOptions.DEFAULT,

    /**
     * Trace collector for audit
     */
    val traceCollector: TraceCollector? = null
)
```

### 3.2 ExecutionOptions

```kotlin
data class ExecutionOptions(
    /**
     * Maximum output size in bytes (stdout/stderr)
     */
    val maxOutputBytes: Long = 1_048_576,  // 1MB

    /**
     * Default timeout for steps (can be overridden per step)
     */
    val defaultTimeoutMs: Long = 120_000,  // 2 minutes

    /**
     * Whether to collect detailed traces
     */
    val enableTracing: Boolean = true,

    /**
     * Whether to mask sensitive data in output
     */
    val maskSensitiveData: Boolean = true
) {
    companion object {
        val DEFAULT = ExecutionOptions()
    }
}
```

### 3.3 ResourceRegistry

```kotlin
class ResourceRegistry {
    private val resources: MutableMap<String, Any> = mutableMapOf()

    fun <T> get(key: String): T? = resources[key] as? T

    fun put(key: String, resource: Any) {
        resources[key] = resource
    }

    fun remove(key: String): Any? = resources.remove(key)

    fun close() {
        resources.values.forEach { resource ->
            if (resource is AutoCloseable) {
                runCatching { resource.close() }
            }
        }
        resources.clear()
    }
}
```

---

## 4. ExecutionResult 정의

### 4.0 불변성 원칙 (Immutability Principle)

ExecutionResult는 **불변 객체(immutable object)**로 설계된다.

| 원칙 | 설명 |
|------|------|
| 생성 후 변경 불가 | ExecutionResult는 한 번 생성되면 수정되지 않는다 |
| data class 사용 | Kotlin data class의 불변성 보장 활용 |
| 복사 시 새 객체 생성 | 수정이 필요하면 copy()로 새 객체 생성 |

> **ExecutionResult는 실행 사실의 스냅샷이다. 이 스냅샷은 변경되지 않는다.**

### 4.1 Sealed Class 구조

```kotlin
sealed class ExecutionResult {
    abstract val meta: ExecutionMeta

    /**
     * Step executed successfully
     */
    data class Success(
        val output: StepOutput,
        override val meta: ExecutionMeta
    ) : ExecutionResult()

    /**
     * Step execution failed
     */
    data class Failure(
        val error: ExecutionError,
        val partialOutput: StepOutput? = null,
        override val meta: ExecutionMeta
    ) : ExecutionResult()

    /**
     * Step execution was cancelled
     */
    data class Cancelled(
        val reason: CancelReason,
        val partialOutput: StepOutput? = null,
        override val meta: ExecutionMeta
    ) : ExecutionResult()
}
```

### 4.2 ExecutionMeta

```kotlin
data class ExecutionMeta(
    /**
     * Step ID that was executed
     */
    val stepId: String,

    /**
     * Execution start time (ISO-8601)
     */
    val startedAt: Instant,

    /**
     * Execution end time (ISO-8601)
     */
    val endedAt: Instant,

    /**
     * Duration in milliseconds
     */
    val durationMs: Long,

    /**
     * Resource identifiers used
     * (file paths, URLs, DB identifiers, etc.)
     */
    val resourceRefs: List<String> = emptyList(),

    /**
     * Trace ID for audit correlation
     */
    val traceId: String? = null
) {
    companion object {
        fun now(stepId: String): ExecutionMeta {
            val now = Instant.now()
            return ExecutionMeta(
                stepId = stepId,
                startedAt = now,
                endedAt = now,
                durationMs = 0
            )
        }
    }
}
```

### 4.3 ExecutionError

```kotlin
data class ExecutionError(
    /**
     * Error category for classification
     */
    val category: ErrorCategory,

    /**
     * Error code (standardized)
     */
    val code: String,

    /**
     * Human-readable error message
     */
    val message: String,

    /**
     * Additional details (stack trace, raw response, etc.)
     */
    val details: Map<String, Any> = emptyMap()
)

enum class ErrorCategory {
    /** Blueprint/Step schema violation */
    CONTRACT_VIOLATION,

    /** Resource not found (file, URL, DB) */
    RESOURCE_NOT_FOUND,

    /** Permission denied */
    PERMISSION_DENIED,

    /** Network/IO error */
    IO_ERROR,

    /** Timeout exceeded */
    TIMEOUT,

    /** External service error (API, DB, LLM) */
    EXTERNAL_SERVICE_ERROR,

    /** Unknown/unexpected error */
    UNKNOWN
}
```

### 4.4 CancelReason

```kotlin
data class CancelReason(
    /**
     * Who/what initiated the cancellation
     */
    val source: CancelSource,

    /**
     * Reason message
     */
    val message: String
)

enum class CancelSource {
    /** User requested cancellation */
    USER_REQUEST,

    /** System timeout */
    TIMEOUT,

    /** System shutdown */
    SYSTEM_SHUTDOWN,

    /** Gate runtime enforcement */
    GATE_ENFORCEMENT,

    /** Parent execution cancelled */
    PARENT_CANCELLED
}
```

---

## 5. StepOutput 정의

### 5.1 구조

```kotlin
data class StepOutput(
    /**
     * Step ID that produced this output
     */
    val stepId: String,

    /**
     * Structured JSON output
     */
    val json: Map<String, JsonElement> = emptyMap(),

    /**
     * Standard output (for command steps)
     */
    val stdout: String? = null,

    /**
     * Standard error (for command steps)
     */
    val stderr: String? = null,

    /**
     * Exit code (for command steps)
     */
    val exitCode: Int? = null,

    /**
     * File artifacts produced
     * Key: artifact name, Value: file path
     */
    val artifacts: Map<String, String> = emptyMap(),

    /**
     * Warnings (non-fatal issues)
     */
    val warnings: List<String> = emptyList(),

    /**
     * Confidence score (for multimodal/LLM steps)
     */
    val confidence: Double? = null,

    /**
     * Raw response (for API/LLM steps)
     */
    val rawResponse: String? = null,

    /**
     * Execution duration in milliseconds
     */
    val durationMs: Long = 0
)
```

---

## 6. Trace 연계 규약

### 6.1 TraceCollector Interface

```kotlin
interface TraceCollector {
    /**
     * Record step execution start
     */
    fun onStepStart(stepId: String, step: ExecutionStep, context: ExecutionContext)

    /**
     * Record step execution end
     */
    fun onStepEnd(stepId: String, result: ExecutionResult)

    /**
     * Record intermediate event during execution
     */
    fun onEvent(stepId: String, event: TraceEvent)
}
```

### 6.2 TraceEvent

```kotlin
sealed class TraceEvent {
    abstract val timestamp: Instant
    abstract val message: String

    data class Info(
        override val timestamp: Instant,
        override val message: String,
        val data: Map<String, Any> = emptyMap()
    ) : TraceEvent()

    data class Warning(
        override val timestamp: Instant,
        override val message: String,
        val data: Map<String, Any> = emptyMap()
    ) : TraceEvent()

    data class ResourceAccess(
        override val timestamp: Instant,
        override val message: String,
        val resourceType: String,
        val resourceId: String,
        val action: String
    ) : TraceEvent()
}
```

### 6.3 Trace 기록 원칙

| 원칙 | 설명 |
|------|------|
| 모든 step 시작/종료 기록 | `onStepStart`, `onStepEnd` 호출 필수 |
| 리소스 접근 기록 | 파일/DB/API 접근 시 `ResourceAccess` 이벤트 기록 |
| 민감 정보 마스킹 | 비밀번호, 토큰 등은 마스킹 처리 |
| 비동기 기록 | Trace 기록이 실행을 blocking하지 않음 |

---

## 7. Specialized Executor 인터페이스

### 7.1 FileExecutor

```kotlin
interface FileExecutor : Executor {
    override fun canHandle(step: ExecutionStep): Boolean =
        step.type == StepType.FILE_OPERATION
}
```

### 7.2 CommandExecutor

```kotlin
interface CommandExecutor : Executor {
    override fun canHandle(step: ExecutionStep): Boolean =
        step.type == StepType.COMMAND
}
```

### 7.3 DbExecutor

```kotlin
interface DbExecutor : Executor {
    override fun canHandle(step: ExecutionStep): Boolean =
        step.type == StepType.DB_OPERATION
}
```

### 7.4 LlmExecutor

```kotlin
interface LlmExecutor : Executor {
    override fun canHandle(step: ExecutionStep): Boolean =
        step.type == StepType.LLM_CALL
}
```

### 7.5 ApiExecutor

```kotlin
interface ApiExecutor : Executor {
    override fun canHandle(step: ExecutionStep): Boolean =
        step.type == StepType.API_CALL
}
```

---

## 8. 결과 타입 제약 (Canonical)

### 8.1 허용되는 결과 타입

```
✅ ExecutionResult.Success
✅ ExecutionResult.Failure
✅ ExecutionResult.Cancelled
```

### 8.2 금지된 결과 타입

```
❌ ReviewRequired (→ Governor/DACS 계층)
❌ Denied (→ Gate 계층)
❌ Pending (→ 실행 중 상태, 결과가 아님)
```

### 8.3 Failure vs Cancelled 구분 기준

| 상황 | 결과 타입 |
|------|----------|
| 파일 없음, 권한 부족, DB 오류 | `Failure` |
| 네트워크 타임아웃 (실행 중 발생) | `Failure` |
| 사용자가 취소 요청 | `Cancelled` |
| 시스템 타임아웃 (외부에서 중단) | `Cancelled` |
| Gate runtime enforcement | `Cancelled` |
| 부모 실행 취소로 인한 연쇄 취소 | `Cancelled` |

---

## 9. 핵심 요약

```
Executor.execute(step, context) → ExecutionResult
ExecutionResult = Success | Failure | Cancelled

Success: output + meta
Failure: error + partialOutput? + meta
Cancelled: reason + partialOutput? + meta

Executor는 ReviewRequired/Denied를 반환하지 않는다
Executor는 Trace를 통해 실행 기록을 남긴다
Executor는 Step Type별로 특화된 구현을 가진다
```

---

## 10. wiiiv 1.0 매핑 가이드

### 10.1 기존 → 신규 타입 매핑

| wiiiv 1.0 | wiiiv 2.0 |
|-----------|-----------|
| `StepExecutor.Result.Success` | `ExecutionResult.Success` |
| `StepExecutor.Result.Error` | `ExecutionResult.Failure` |
| `StepExecutor.Result.ReviewRequired` | **제거** (Governor/DACS로 이동) |
| `StepExecutor.Result.Denied` | **제거** (Gate로 이동) |
| `BatchStep` | `ExecutionStep` |
| `BatchExecutionContext` | `ExecutionContext` |
| `StepOutput` | `StepOutput` (구조 유지) |

### 10.2 기존 코드 수정 포인트

1. `sealed class Result`에서 `ReviewRequired`, `Denied` 제거
2. Gate 호출 로직을 Executor 외부로 이동
3. `Cancelled` 결과 타입 추가
4. `ExecutionMeta` 추가하여 timing/trace 정보 표준화

---

## Canonical 상태 요약

| 구성 요소 | 상태 |
|-----------|------|
| Executor 정의서 v1.0 | ✅ Canonical |
| **Executor Interface Spec v1.0** | ✅ **Canonical** |

---

*wiiiv / 하늘나무 / SKYTREE*
