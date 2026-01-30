# RetryPolicy Specification v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | RetryPolicy Specification |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-30 |
| 상위 문서 | ExecutionRunner Spec v1.0, Executor 정의서 v1.0, Executor Interface Spec v1.0, Blueprint Spec v1.1 |
| 적용 범위 | wiiiv 실행 오케스트레이션 계층 |

---

## 1. RetryPolicy의 목적

**RetryPolicy는 실행 중 발생한 실패에 대해 해당 실행을 다시 시도할 수 있는지 여부를 기계적으로 판단하는 규칙 집합이다.**

RetryPolicy는 다음을 목표로 한다:

- 일시적 실패(IO, 타임아웃 등)에 대한 실행 신뢰성 향상
- 실행 흐름에서 판단 로직 제거
- Executor 및 Governor 책임 경계 보호

> **RetryPolicy는 의미 판단, 흐름 제어, 결과 해석을 수행하지 않는다.**

---

## 2. 책임 경계 (Absolute Rules)

RetryPolicy는 다음을 **절대 하지 않는다**:

```
❌ 실행 결과의 의미 해석
❌ 성공/실패의 중요도 판단
❌ 실행 흐름 결정 (다음 step 이동 여부)
❌ 결과 요약, 병합, 축약
❌ Executor 동작 변경
```

> **RetryPolicy는 ExecutionRunner 내부에서만 사용되며, Governor, Executor, Gate는 RetryPolicy를 직접 참조하거나 호출하지 않는다.**

---

## 3. RetryPolicy의 위치

RetryPolicy는 **Blueprint 계약의 일부**로 정의된다.

```json
{
  "stepId": "example_step",
  "type": "FILE_OPERATION",
  "retryPolicy": {
    "enabled": true,
    "maxAttempts": 3,
    "intervalMs": 1000
  }
}
```

### 3.1 우선순위 규칙

| 조건 | 적용 정책 |
|------|----------|
| Step에 retryPolicy가 명시된 경우 | Blueprint 계약이 최우선 |
| Step에 retryPolicy가 없는 경우 | Runner의 기본 RetryPolicy 적용 |

### 3.2 Executor와의 분리

| 규칙 | 설명 |
|------|------|
| Executor는 retryPolicy의 존재 여부를 알지 못한다 | 완전한 무지 |
| Executor는 "이번이 몇 번째 시도인지"를 인지하지 않는다 | 매 실행이 독립적 |

> **Executor는 재시도 여부를 명시하지 않는다.**

---

## 4. RetryPolicy의 입력

RetryPolicy는 오직 **ExecutionResult.Failure**만을 입력으로 받는다.

```kotlin
ExecutionResult.Failure {
  error: ExecutionError {
    category: ErrorCategory
    code: String
    message: String
  }
}
```

> **RetryPolicy는 error.category만을 기준으로 재시도 여부를 판단한다.**
> message 내용이나 실행 맥락을 해석하지 않는다.

---

## 5. ErrorCategory와 재시도 규칙

### 5.1 ErrorCategory 기준 (Executor Interface Spec v1.0 준수)

| ErrorCategory | 설명 | 재시도 |
|---------------|------|--------|
| IO_ERROR | 파일/네트워크 일시 오류 | ⭕ |
| TIMEOUT | 실행 시간 초과 | ⭕ |
| EXTERNAL_SERVICE_ERROR | 외부 서비스 오류 (API, DB, LLM) | ⭕ |
| RESOURCE_NOT_FOUND | 리소스 없음 | ❌ |
| PERMISSION_DENIED | 권한 문제 | ❌ |
| CONTRACT_VIOLATION | 계약 위반 | ❌ |
| UNKNOWN | 분류 불가 | ❌ |

### 5.2 기본 원칙

> **애매하면 재시도하지 않는다.**

RetryPolicy는 ErrorCategory의 **소비자**일 뿐, **정의자**가 아니다.
ErrorCategory의 정의는 Executor Interface Spec v1.0을 단일 기준으로 한다.

---

## 6. Cancelled와 RetryPolicy

**Cancelled는 Failure가 아니므로 RetryPolicy의 대상이 아니다.**

| cancel_source | 재시도 |
|---------------|--------|
| USER_REQUEST | ❌ |
| TIMEOUT | ❌ (외부 강제 중단) |
| SYSTEM_SHUTDOWN | ❌ |
| GATE_ENFORCEMENT | ❌ (정책 위반) |
| PARENT_CANCELLED | ❌ |

> **Cancelled는 "실패"가 아니라 "중단"이다. RetryPolicy의 대상이 아니다.**

---

## 7. 재시도 횟수 및 간격 (v1.0 최소 스펙)

RetryPolicy v1.0은 **단순한 고정 정책**만을 지원한다.

```json
{
  "retryPolicy": {
    "enabled": true,
    "maxAttempts": 3,
    "intervalMs": 1000
  }
}
```

### 7.1 v1.0 제약

| 기능 | 지원 |
|------|------|
| 고정 간격 재시도 | ✅ |
| exponential backoff | ❌ |
| jitter | ❌ |
| adaptive retry | ❌ |

> **이는 RetryPolicy가 판단 로직으로 확장되는 것을 방지하기 위함이다.**

---

## 8. ExecutionRunner와의 관계

### 8.1 적용 주체

| 규칙 | 설명 |
|------|------|
| RetryPolicy는 ExecutionRunner에 의해 적용된다 | Runner가 유일한 적용 주체 |
| Executor는 재시도 여부를 알지 못한다 | 완전한 분리 |
| 각 재시도는 완전히 새로운 실행으로 취급된다 | 이전 시도와 독립적 |

### 8.2 기록 원칙

| 규칙 | 설명 |
|------|------|
| 각 시도는 개별 ExecutionResult로 기록 | 모든 시도 보존 |
| 결과는 append-only로 보존 | 병합/요약 금지 |
| 최종 결과만 남기기 위한 병합 수행 안 함 | 원본 유지 |

> **실행 결과에 대한 후속 처리(사용자 알림, 흐름 결정, 다음 단계 진행 등)는 Governor의 책임이다.**

---

## 9. 병렬 실행과 RetryPolicy

### 9.1 적용 범위

| 규칙 | 설명 |
|------|------|
| RetryPolicy는 step 단위로만 적용된다 | 개별 step 기준 |
| 병렬 group 단위 재시도 | ❌ 불허 |

### 9.2 병렬 group 실패 시

병렬 group에서 하나라도 Failure 발생 시:

| 처리 | 설명 |
|------|------|
| 나머지 step | Cancelled (PARENT_CANCELLED) |
| Cancelled step 재시도 | ❌ 대상 아님 |

> **Cancelled는 재시도 대상이 아니다.**

---

## 10. 핵심 요약

```
RetryPolicy는 error.category만을 기준으로 재시도 여부를 판단한다
Executor는 재시도 가능 여부를 명시하지 않는다
Cancelled는 RetryPolicy의 대상이 아니다
각 재시도 시도는 개별 ExecutionResult로 기록된다
결과 병합/요약은 수행하지 않는다
v1.0에서는 고정 간격 재시도만 지원한다
애매하면 재시도하지 않는다
```

---

## 11. 최종 선언

**RetryPolicy는 실행 신뢰성을 높이기 위한 기계적 보조 수단이다.**

RetryPolicy는 실행 결과의 의미를 판단하지 않으며, 실행 흐름을 결정하지 않는다.

RetryPolicy의 오용 또는 확장은 **wiiiv 설계 원칙에 대한 명백한 위반**이다.

---

## 12. 버전 정책

| 버전 | 범위 |
|------|------|
| v1.0 | 고정 재시도 정책만 지원 |
| v1.x | backoff, jitter 등 확장 가능 (판단 로직 도입 금지) |
| v2.0 | Governor 명시 승인 하에 확장 가능 (별도 문서 필요) |

---

## Canonical 상태 요약

| 구성 요소 | 상태 |
|-----------|------|
| Governor 역할 정의서 v1.1 | ✅ Canonical |
| Spec 정의서 v1.0 | ✅ Canonical |
| DACS v2 인터페이스 v2.1 | ✅ Canonical |
| Gate 최소 스펙 정의서 v1.0 | ✅ Canonical |
| Blueprint Spec v1.1 | ✅ Canonical |
| Blueprint Structure Schema v1.0 | ✅ Canonical |
| Blueprint Node Type Spec v1.0 | ✅ Canonical |
| Executor 정의서 v1.0 | ✅ Canonical |
| Executor Interface Spec v1.0 | ✅ Canonical |
| ExecutionRunner Spec v1.0 | ✅ Canonical |
| **RetryPolicy Spec v1.0** | ✅ **Canonical** |

---

*wiiiv / 하늘나무 / SKYTREE*
