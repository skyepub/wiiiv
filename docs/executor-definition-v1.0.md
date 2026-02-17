# Executor 정의서 v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Executor 정의서 |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-30 |
| 상위 문서 | Governor 역할 정의서 v1.1, Blueprint Spec v1.1, Gate 최소 스펙 정의서 v1.0 |
| 적용 범위 | wiiiv 실행 계층 전반 |

---

## 0. 문서의 위치와 목적

본 문서는 wiiiv에서 **Executor의 정체성, 책임 경계, 입력/출력 규칙, Gate와의 상호작용 원칙**을 정의한다.

Executor는 Spec 축(Spec / Governor / DACS / Blueprint)이 완결된 이후의 **실행 계층**이며, 본 문서는 구현 이전에 반드시 Canonical로 고정되어야 한다.

---

## 1. Executor의 정의

> **Executor는 Blueprint에 정의된 step을 판단 없이 실행하고, 결과를 raw하게 반환하는 실행 프레임이다.**

---

## 2. Executor의 기본 전제

| 전제 | 설명 |
|------|------|
| 왜 실행하는지를 묻지 않는다 | 실행 목적은 Executor의 관심사가 아니다 |
| 무엇을 실행할지를 결정하지 않는다 | Blueprint가 이미 결정했다 |
| Blueprint만 입력으로 받는다 | Spec, DACS 결과 등은 보지 않는다 |
| 실행 결과를 해석하지 않는다 | 실행 사실과 결과만 반환한다 |
| **Blueprint를 신뢰한다** | 정합성과 합법성이 이미 검증되었다고 가정한다 |

> **Executor는 Blueprint의 정합성과 합법성이 이미 검증되었다고 가정하고 실행한다.**
> "Blueprint가 이상하면 Executor가 막아야 하나?"라는 질문의 답은 **아니오**다.

---

## 3. Executor가 하지 않는 것 (절대 제약)

### 3.1 판단/의미 해석 금지

```
❌ Spec의 의미 해석
❌ 사용자 의도 추론
❌ 실행 목적 재해석
❌ "이건 위험해 보인다 / 적절치 않다"와 같은 의미 판단
```

### 3.2 Spec 관련 행위 금지

```
❌ Spec 참조
❌ Spec 수정
❌ Spec 보완 요청
```

### 3.3 흐름 제어 금지 (Blueprint를 넘어서는 제어)

```
❌ Step 추가/삭제/재정렬
❌ "리뷰가 필요하다" 같은 흐름 변경 신호 생성
❌ "거부(Denied)" 같은 정책 판정 결과 생성
```

**ReviewRequired / Denied 류의 결과는 Executor의 산물이 아니다:**

| 결과 | 책임 계층 |
|------|----------|
| ReviewRequired | Governor/DACS 계층 |
| Denied (정책적 거부) | Gate 계층 |

### 3.4 LLM 결과 해석 금지

Executor는 LLM을 호출할 수 있으나(§6 참조), 다음은 금지된다:

```
❌ LLM 결과를 요약/분석/판단하여 의미를 부여
❌ "이 답은 부적절하다" 같은 평가
❌ 결과를 근거로 실행 흐름을 바꾸는 행위
```

---

## 4. Executor의 책임 (해야 하는 것)

### 4.1 계약 소비 (Consume)

- Blueprint의 step을 **그대로 실행**한다
- Step 실행 순서/조건은 Blueprint에 이미 확정된 것을 따른다

### 4.2 실행 실패의 관측과 보고

Executor는 실행 중 발생한 **"사실(fact)"**을 관측하고 반환해야 한다.

예:
- 파일 없음
- 권한 부족
- 네트워크 오류
- 프로세스 timeout
- DB 연결 오류
- HTTP 오류 코드 (요청이 실제로 수행된 결과)

> **Executor는 실패를 "판단"하지 않고, 실패를 "사실로 보고"한다.**

### 4.3 Audit/Trace 기록을 위한 최소 메타데이터 제공

Executor는 실행 결과에 다음과 같은 최소 메타데이터를 포함할 수 있다:

| 메타데이터 | 설명 |
|-----------|------|
| 실행 시작/종료 시각 | timing |
| 실행 대상 | step id / node id |
| 리소스 식별자 | 파일 경로, 명령, URL, DB 식별자 등 |
| 표준화된 error code / exit code | 오류 분류 |
| stdout/stderr | 필요 시 제한/마스킹 |

---

## 5. 입력 정의 (Input Contract)

### 5.1 Executor의 유일한 입력

Executor는 오직 다음만 입력으로 받는다:

| 입력 | 설명 |
|------|------|
| **Execution Blueprint** | 또는 Blueprint에서 분리된 Step 단위 |
| **Execution Context** | 환경/권한/리소스 핸들/변수 저장소 등 |

### 5.2 금지 입력

```
❌ Spec 원문
❌ DACS 결과 (YES/NO/REVISION)
❌ 사용자 대화 원본
❌ Governor의 "추가 의도 설명"
```

---

## 6. LLM 호출(llm_call)에 대한 규칙

Blueprint Node Type Spec에 `llm_call`이 존재하므로, Executor는 LLM 호출을 **실행 행위로서** 수행할 수 있다.

### 6.1 허용 범위

- `llm_call` step의 정의에 따라 LLM API를 호출한다
- 결과를 **raw output**으로 반환한다 (텍스트/구조화 응답)

### 6.2 금지 범위

```
❌ 결과를 해석/요약/의미화하여 판단에 사용
❌ "이 답은 부적절하다" 같은 평가
❌ 결과에 따라 흐름을 변경하거나 step을 삽입/삭제
```

> **LLM 호출은 "실행"으로 허용되지만, "판단"은 금지된다.**
> Executor는 LLM을 도구처럼 호출하고, 결과를 그대로 반환한다.

---

## 7. 출력 정의 (Output Contract)

Executor의 출력은 **"정책 판단"이 아니라 "실행 결과"**여야 한다.

### 7.1 표준 결과 타입

#### Success

| 필드 | 설명 |
|------|------|
| output | step output (raw) |
| meta | timing, ids, resource refs |

#### Failure

| 필드 | 설명 |
|------|------|
| error | message, category, code |
| meta | timing, ids, resource refs, partial output (가능하면) |

#### Cancelled

| 필드 | 설명 |
|------|------|
| cancel_reason | 요청 주체/타임아웃/시스템 중단 등 |
| meta | timing, ids, resource refs |

> **ReviewRequired / Denied는 존재하지 않는다.**

### 7.2 Failure와 Cancelled의 구분

| 결과 | 의미 |
|------|------|
| **Failure** | 실행이 시도된 후 오류로 인해 완료되지 않은 상태 |
| **Cancelled** | 실행이 외부 요인에 의해 중단되어, 정상적인 완료 또는 실패 판단이 불가능한 상태 |

이 구분은 이후 **재시도, 롤백, 감사 판단**에 활용될 수 있다.

### 7.3 Failure의 의미

Failure는 다음을 **의미한다**:
- step 실행이 계획대로 완료되지 않았다
- 오류가 발생했거나, 리소스 조건이 충족되지 않았다

Failure는 다음을 **의미하지 않는다**:
```
❌ "이 step은 하면 안 된다" (정책 거부)
❌ "사용자가 다시 검토해야 한다" (리뷰 요구)
```

---

## 8. Gate와 Executor의 상호작용 원칙

### 8.1 Gate의 역할 재확인

Gate는 **권한/정책 검증**을 담당한다:
- 실행 허용 여부 결정
- 위험한 실행 차단
- 정책 위반 감지

### 8.2 Executor의 역할

Executor는 **Gate의 결정을 해석하거나 대체하지 않는다.**

#### Gate Pre-check의 시점

Gate에 의한 권한 및 정책 검증(Pre-check)은 **Executor 호출 이전, Blueprint 생성 이후 단계에서 완료**된다.

```
Blueprint 생성 → Gate 체인 통과 → Executor 호출
```

- Executor는 **Gate를 직접 호출하지 않는다**
- Executor는 **이미 Gate를 통과한 Blueprint만을 입력으로 받는다**

#### 상호작용 패턴

| 패턴 | 설명 |
|------|------|
| **Pre-check (완료됨)** | 실행 전 Gate가 step 수행 권한을 검증. Gate가 "불허"면 실행 자체가 시작되지 않는다 |
| **Runtime enforcement (선택)** | 실행 도중에도 Gate가 강제 중단/차단을 수행할 수 있다. 이 경우 Executor는 **Cancelled**로 사실을 반환한다 (GATE_ENFORCEMENT) |

> **Gate runtime enforcement 시 Executor의 반환 규칙:**
> - Gate가 실행 도중 강제 중단을 요청하면, Executor는 이를 **Cancelled**(CancelSource: GATE_ENFORCEMENT)로 반환한다
> - 이는 Failure가 아니다. Failure는 실행 시도 후 발생한 오류이며, Gate enforcement는 외부에서 주입된 중단 신호이다
> - Executor는 Gate의 enforcement를 해석하거나 우회하지 않으며, 단지 사실(중단되었음)을 반환한다

> **정책적 거부는 Gate의 결과이며, Executor는 이를 "판단 결과"로 재포장하지 않는다.**
> Executor는 단지 "실행이 시작되지 않았다/중단되었다"를 사실로 반환한다.

---

## 9. Blueprint 불완전/위반 상황 처리

Executor는 Blueprint를 **"해석"하지 않지만**, 기계적 계약 위반은 감지할 수 있다.

예:
- step 필수 필드 누락
- node type 미지원
- 파라미터 타입 불일치 (명백한 스키마 오류)

이 경우 Executor는:

| 처리 | 허용 |
|------|------|
| Success로 처리 | ❌ |
| 내부적으로 보정 | ❌ |
| **Failure로 반환 (contract violation category)** | ✅ |

---

## 10. 기존 wiiiv 1.0 StepExecutor 재배치 가이드

wiiiv 1.0의 StepExecutor 체계는 **"실행 로직" 측면에서 유효**하지만, 다음 요소는 새 구조에서 제거/이동되어야 한다.

### 10.1 제거/이동

| 기존 요소 | 새 위치 |
|----------|---------|
| `Result.ReviewRequired` | Governor/DACS 계층으로 이동 (질문/보완 흐름) |
| `Result.Denied` | Gate 계층으로 이동 (정책 거부) |

### 10.2 유지

| 요소 | 상태 |
|------|------|
| File/DB/Command/HTTP 등의 실행 자체 | Executor의 핵심 책임으로 **유지** |

단, 의미 판단/흐름 제어 요소는 제거한다.

---

## 11. 핵심 요약

```
Executor는 판단하지 않는다
Executor는 Spec을 보지 않는다
Executor는 Blueprint만 실행한다
Executor는 LLM을 호출할 수 있으나 결과를 해석하지 않는다
Executor의 출력은 Success/Failure/Cancelled이며 정책 판정이 아니다
Gate는 정책을 결정하고, Executor는 그 결과를 대체하지 않는다
Executor는 Gate를 직접 호출하지 않는다
```

---

## 12. 최종 선언

**본 문서에서 정의된 Executor 책임 경계를 벗어나는 구현은 확장이 아니라 설계 위반이다.**

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
| **Executor 정의서 v1.0** | ✅ **Canonical** |

---

## 핵심 축 완결 선언

이 문서로 **wiiiv 핵심 축 설계가 완결**되었다:

```
Spec (판단 자산)
    ↓
Governor (판단 주체)
    ↓
DACS (합의 엔진)
    ↓
Blueprint (판단의 고정)
    ↓
Gate (통제)
    ↓
Executor (실행)
```

> **이제 wiiiv는 "설계 시스템"에서 "구현 가능한 시스템"으로 전환되었다.**

---

*wiiiv / 하늘나무 / SKYTREE*
