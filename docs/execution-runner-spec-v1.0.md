# ExecutionRunner Specification v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | ExecutionRunner Specification |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-30 |
| 상위 문서 | Governor 역할 정의서 v1.1, Executor 정의서 v1.0, Executor Interface Spec v1.0 |
| 적용 범위 | wiiiv 실행 오케스트레이션 계층 |

---

## 0. 문서의 위치와 성격

본 문서는 wiiiv 시스템에서 **ExecutionRunner(이하 Runner)**의 책임, 위상, 동작 규칙을 정의한다.

**Runner는 독립 계층이 아니다.**
**Runner는 Governor의 내부 구현체이며, Blueprint 실행을 Executor에게 위임하는 오케스트레이션 도구이다.**

본 문서는 Executor 정의서 v1.0 및 Executor Interface Spec v1.0을 전제로 한다.

---

## 1. Runner의 한 문장 정의

> **ExecutionRunner는 Blueprint에 정의된 실행 흐름을 따라 step 실행을 Executor에 위임하고, 그 결과를 집계하여 Governor에게 반환하는 실행 오케스트레이터이다.**

---

## 2. Runner의 위상

### 2.1 계층 구조

```
Governor
 └─ ExecutionRunner
     └─ Executor
```

| 규칙 | 설명 |
|------|------|
| Runner는 Governor 외부에서 직접 호출되지 않는다 | Governor만이 Runner를 호출할 수 있다 |
| Runner는 독립적인 판단 주체가 아니다 | 판단은 Governor에서 이미 끝났다 |
| Runner의 모든 실행은 Governor의 실행 위임으로만 시작된다 | Runner는 스스로 실행을 시작하지 않는다 |

> **Runner가 독립 계층이 되면 "누가 Runner를 호출하나?", "Runner 실패는 누가 판단하나?"라는 불필요한 책임 경계 논쟁이 생긴다. Runner는 Governor의 내부 구현체로 고정한다.**

---

## 3. Runner의 기본 원칙

### 3.1 판단 금지 원칙

Runner는 다음을 수행해서는 안 된다:

```
❌ 실행 결과의 의미 판단
❌ 성공/실패를 해석하여 흐름을 재구성
❌ "이 정도면 괜찮다"와 같은 정성적 판단
❌ "n개 중 m개 성공이면 OK" 같은 해석
```

> **판단은 오직 Governor의 책임이다.**

### 3.2 집계 허용 원칙

Runner는 다음을 수행할 수 있다:

```
✅ step 실행 결과를 순서대로 집계
✅ step 결과를 ExecutionContext에 append-only로 저장
✅ 병렬 실행 결과를 목록 형태로 유지
✅ 결과 상태의 기계적 집계
```

> **집계는 허용되나, 해석은 금지된다.**

---

## 4. 입력 / 출력 정의

### 4.1 입력

Runner는 다음 입력만을 받는다:

| 입력 | 설명 |
|------|------|
| **Execution Blueprint** | 실행할 Blueprint |
| **ExecutionContext** | 실행 컨텍스트 |
| **(선택) 실행 옵션** | timeout, tracing on/off 등 |

Runner는 다음을 입력으로 받지 않는다:

```
❌ Spec 원문
❌ DACS 결과
❌ 사용자 대화
```

### 4.2 출력

Runner의 출력은 **실행 결과 집합**이다:

| 출력 | 설명 |
|------|------|
| step별 ExecutionResult 목록 | 각 step의 Success/Failure/Cancelled |
| 최종 실행 상태 | completed / failed / cancelled |
| ExecutionTrace (선택) | 감사/디버깅용 추적 정보 |

#### 최종 실행 상태 결정 규칙

최종 실행 상태는 step 실행 결과들의 **기계적 집계**로 결정된다:

| 조건 | 최종 상태 |
|------|----------|
| 모든 step이 Success | `completed` |
| 하나라도 Failure 존재 | `failed` |
| 하나라도 Cancelled 존재 (외부 취소 포함) | `cancelled` |

#### 상태 결정 우선순위

Cancelled와 Failure가 동시에 존재할 경우 (병렬 실행 등):

```
1. cancelled  ← Cancelled가 하나라도 있으면 최우선
2. failed     ← Cancelled 없고 Failure가 있으면
3. completed  ← 모두 Success인 경우만
```

> **이 결정은 의미 해석이 아닌, 결과 상태의 단순 집계이다.**
> Runner는 단일 "요약 결과"를 생성하지 않는다.

---

## 5. Step 실행 규칙

### 5.1 기본 실행 흐름

1. Blueprint에 정의된 순서를 따른다
2. 각 step은 Executor에 위임되어 실행된다
3. Executor의 결과는 그대로 Runner로 반환된다

### 5.2 병렬 실행 규칙

#### 5.2.1 병렬 허용 조건

| 규칙 | 설명 |
|------|------|
| 명시적 parallel group만 허용 | Blueprint에 명시된 group만 병렬 실행 가능 |
| DAG 기반 의존성 실행 | v1.0 범위에서 **제외** |

#### 5.2.2 병렬 실행 방식

- 동일 parallel group에 속한 step들은 동시에 실행된다
- 실행은 coroutine / async 등 병렬 메커니즘을 사용한다
- 각 step 결과는 개별적으로 수집된다

#### 5.2.3 병렬 group 실패 처리

**v1.0에서 병렬 group의 실패 정책은 fail-fast로 고정된다.**

병렬 group 내 step 중 하나라도 Failure 발생 시:

| 처리 | 설명 |
|------|------|
| Runner는 해당 group을 실패로 간주 | 즉시 실패 처리 |
| 남은 step들은 cancel 된다 | 진행 중인 step 중단 |

이때 취소된 step의 결과는:

| 필드 | 값 |
|------|-----|
| 결과 타입 | `Cancelled` |
| cancel_source | `PARENT_CANCELLED` |

> **이는 상위 실행 흐름(Runner)에 의한 중단이기 때문이다.**

#### 5.2.4 병렬 실패 정책 확장성

```
v1.0에서 병렬 group의 실패 정책은 fail-fast로 고정된다.
Blueprint 계약에서 이를 override하는 것은 허용되지 않는다.

향후 버전에서 parallelPolicy 확장이 논의될 수 있으나,
v1.0의 범위에는 포함되지 않는다.
```

---

## 6. Failure / Cancelled 처리 규칙

### 6.1 Failure 처리

Executor가 Failure를 반환하면:

| 처리 | 설명 |
|------|------|
| Runner는 이를 사실로 기록 | 해석하지 않음 |
| RetryPolicy에 따라 재시도 여부를 판단 | §7 참조 |

> **Runner는 Failure의 의미를 해석하지 않는다.**

### 6.2 Cancelled 처리

Cancelled는 다음 사유로 발생할 수 있다:

| cancel_source | 설명 |
|---------------|------|
| USER_REQUEST | 사용자 취소 |
| SYSTEM_SHUTDOWN | 시스템 중단 |
| PARENT_CANCELLED | 병렬 group 실패에 따른 상위 취소 |
| GATE_ENFORCEMENT | Gate runtime enforcement |
| TIMEOUT | 시스템 타임아웃 |

병렬 group 실패로 인한 취소는 `cancel_source = PARENT_CANCELLED`로 기록한다.

---

## 7. RetryPolicy 연계 규칙

### 7.1 RetryPolicy의 위치

재시도 정책은 **Blueprint 계약에 포함**될 수 있다.

| 조건 | 적용 정책 |
|------|----------|
| Step에 retryPolicy가 명시된 경우 | Blueprint 계약 우선 |
| 명시되지 않은 경우 | Runner의 기본 RetryPolicy 적용 |

### 7.2 Runner의 역할

Runner는 RetryPolicy를 **적용**할 수 있으나:

```
❌ 재시도 정책을 생성하지 않는다
❌ 재시도 정책을 해석하지 않는다
```

> **Runner는 Blueprint 계약과 RetryPolicy 규칙을 기계적으로 적용할 뿐이다.**

### 7.3 Executor와 재시도의 관계

**Executor는 재시도 여부를 알지 못한다.**

| 규칙 | 설명 |
|------|------|
| Executor는 매 실행 요청을 독립적인 단일 실행으로 취급 | 이전 시도와 무관 |
| 이 실행이 몇 번째 시도인지는 Executor의 관심사가 아니다 | 완전한 무지 |

> **재시도 횟수, 간격, 조건 판단은 전적으로 Runner의 책임이다.**
> 이는 Executor를 순수하게 유지하기 위한 의도적 설계다.

---

## 8. 취소(Cancellation) 규칙

Runner는 Governor 또는 시스템으로부터 취소 요청을 받을 수 있다.

취소 요청 시:

| 처리 | 설명 |
|------|------|
| 현재 실행 중인 step들을 중단 요청 | Executor.cancel() 호출 |
| Executor는 Cancelled를 반환 | 적절한 cancel_source 포함 |

> **Runner는 취소를 복구하거나 재시도하지 않는다.**

---

## 9. Blueprint 계약 위반 처리

다음과 같은 경우 Runner는 실행을 중단한다:

```
- step 타입 미지원
- parallel group 정의 오류
- retryPolicy 형식 오류
- 필수 필드 누락
```

### 9.1 CONTRACT_VIOLATION 처리

CONTRACT_VIOLATION이 발생한 경우:

| 처리 | 설명 |
|------|------|
| Executor 호출 | ❌ 호출하지 않음 |
| Runner가 직접 Failure 생성 | ✅ |
| error.category | `CONTRACT_VIOLATION` |

> **이 Failure는 "실행 중 오류"가 아니라, "Blueprint 계약 위반으로 인한 실행 불가 상태"를 의미한다.**

---

## 10. 핵심 요약

```
Runner는 Governor의 내부 실행 도구이다
Runner는 흐름을 실행하지만, 판단하지 않는다
Runner는 결과를 집계하지만 해석하지 않는다
병렬은 명시적 group만 허용한다
병렬 실패 시 cancel_source는 PARENT_CANCELLED이다
v1.0에서 병렬 실패 정책은 fail-fast로 고정된다
재시도 정책은 Blueprint 계약에 있다
Executor는 재시도 여부를 알지 못한다
CONTRACT_VIOLATION 시 Runner가 직접 Failure를 생성한다
```

---

## 11. 최종 선언

**ExecutionRunner는 판단 계층이 아니다.**

판단은 Governor에서 끝났고, Runner는 그 판단을 실행으로 옮길 뿐이다.

본 문서에서 정의된 Runner 책임 경계를 벗어나는 구현은 **확장이 아니라 설계 위반**이다.

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
| **ExecutionRunner Spec v1.0** | ✅ **Canonical** |

---

*wiiiv / 하늘나무 / SKYTREE*
