# Gate 최소 스펙 정의서

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Gate 최소 스펙 정의서 |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-28 |
| 상위 문서 | Governor 역할 정의서 v1.0 (Canonical), DACS v2 인터페이스 문서 (Canonical) |
| 적용 범위 | wiiiv 실행 통제 계층 전반 |

---

## 0. Gate의 위상 (절대 규칙)

**Gate는 wiiiv에서 유일한 통제 주체다.**

- Gate는 판단하지 않는다
- Gate는 해석하지 않는다
- Gate는 설명하지 않는다
- **Gate는 강제한다**

Gate의 결정은:
- **최종적**이며
- **즉시 적용**되고
- **어떠한 경우에도 우회될 수 없다**

> Governor, DACS, Executor — 그 누구도 Gate 위에 있지 않다.

---

## 1. Gate의 정의

Gate는 실행 요청이 시스템 정책·합의·승인·권한을 만족하는지 **기계적으로 검사**하는 **결정론적 통제 계층(Automata)**이다.

Gate는 다음 질문에만 답한다:

> **"이 실행 요청을 통과시켜도 되는가?"**

---

## 2. Gate의 공통 특성

모든 Gate는 다음 특성을 가진다:

| 항목 | 규칙 |
|------|------|
| 판단 | ❌ 하지 않음 |
| 입력 | 구조화된 요청 |
| 출력 | **ALLOW / DENY** |
| 상태 | **Stateless (필수)** |
| 우회 | **불가능** |
| 예외 | **없음** |
| 관리자 | **없음** |

> **Gate는 if–else 이상의 지능을 가지면 안 된다.**

---

## 3. Gate의 종류 (최소 집합)

wiiiv는 다음 Gate만을 Canonical로 인정한다.

### 3.1 DACS Gate

**목적**: 합의 없는 실행을 차단

**입력**:
```json
{
  "dacs_consensus": "YES | NO | REVISION | null"
}
```

**규칙**:
```
IF dacs_consensus != "YES"
THEN DENY
```

**설명**:
- 예외 없음
- 관리자 우회 없음
- "임시 실행" 없음
- **합의 없으면 실행 없음**

---

### 3.2 User Approval Gate

**목적**: 사용자 승인 없는 실행 차단

**입력**:
```json
{
  "user_approved": true | false
}
```

**규칙**:
```
IF user_approved != true
THEN DENY
```

**설명**:
- 승인 기록은 반드시 로그로 남는다
- 승인 UI/UX는 Gate의 관심사가 아니다

---

### 3.3 Execution Permission Gate

**목적**: Executor 권한 검증

**입력**:
```json
{
  "executor_id": "...",
  "action": "..."
}
```

**규칙**:
```
IF executor NOT permitted for action
THEN DENY
```

**설명**:
- 권한 매핑은 정적 정의
- 런타임 판단 없음

---

### 3.4 Cost / Policy Gate (선택적이지만 권장)

**목적**: 비용, 정책 한도 초과 차단

**입력**:
```json
{
  "estimated_cost": number,
  "cost_limit": number
}
```

**규칙**:
```
IF estimated_cost > cost_limit
THEN DENY
```

**설명**:
- 계산 방식은 Gate 외부 책임
- Gate는 비교만 수행

---

## 4. Gate 체인 규칙

Gate는 반드시 **체인 형태**로 평가된다.

```
DACS Gate
  → User Approval Gate
    → Permission Gate
      → Cost / Policy Gate
```

- **하나라도 DENY → 전체 DENY**
- **모든 Gate 통과 → ALLOW**
- Gate 체인 순서는 **고정**이다.

### 4.1 Runtime Enforcement (선택적)

Gate는 **실행 전 검사(Pre-check)**가 기본이지만, **실행 중 강제 중단(Runtime Enforcement)**도 수행할 수 있다.

| 시점 | 설명 |
|------|------|
| **Pre-check** | Blueprint 생성 후, Executor 호출 전에 Gate 체인 통과 |
| **Runtime Enforcement** | 실행 도중에도 Gate가 강제 중단을 수행할 수 있음 |

#### Runtime Enforcement 규칙

| 규칙 | 설명 |
|------|------|
| 실행 중단 시 결과 | Executor는 `Cancelled` (cancel_source: `GATE_ENFORCEMENT`) 반환 |
| 중단 이유 설명 | Gate는 설명하지 않음 (DENY 사실만 전달) |
| 재시도 가능 여부 | ❌ (정책 위반이므로 재시도 금지) |

> **Gate Runtime Enforcement는 Pre-check를 보완하는 선택적 메커니즘이다.**
> v1.0에서는 Pre-check만으로도 충분하나, 장기 실행이나 스트리밍 작업에서 Runtime Enforcement가 필요할 수 있다.

---

## 5. Gate와 Governor의 관계

| 항목 | Governor | Gate |
|------|:--------:|:----:|
| 실행 판단 | ✅ | ❌ |
| 실행 통제 | ❌ | ✅ |
| Gate 결과 해석 | ❌ | ❌ |
| Gate 우회 | ❌ | ❌ |

> Governor는 Gate의 결과를 **설명할 수는 있지만**, **변경할 수는 없다**.

---

## 6. Gate와 DACS의 관계

- DACS는 합의 결과를 제공한다
- Gate는 그 결과를 **검사만** 한다

Gate는:
- 합의 이유를 보지 않는다
- 합의 과정에 관심 없다

> **Gate는 문자열 "YES"만 본다.**

---

## 7. 로그와 감사 (필수)

모든 Gate는 다음을 기록해야 한다:

- Gate 이름
- 입력 요약
- 결과 (ALLOW / DENY)
- 타임스탬프

Gate 로그는:
- **수정 불가**
- **삭제 불가**
- **Governor 접근 불가**

---

## 8. 절대 금지 사항

Gate 구현에서 다음은 **설계 위반**이다:

```
❌ 예외 처리
❌ 관리자 플래그
❌ 강제 통과 옵션
❌ 조건부 허용
❌ "이번만 허용"
```

> **Gate는 차갑고 무자비해야 한다.**

---

## 9. 최종 선언

**Gate는 wiiiv의 윤리적·재정적·시스템적 최후 방어선이다.**

Governor가 아무리 똑똑해도,
DACS가 아무리 만장일치여도,

**Gate가 DENY하면 실행은 없다.**

---

## Canonical 상태 요약

| 구성 요소 | 상태 |
|-----------|------|
| Governor 역할 정의서 v1.0 | ✅ Canonical |
| DACS v2 인터페이스 | ✅ Canonical |
| Gate 최소 스펙 정의서 | ✅ Canonical |

> **wiiiv 핵심 삼각형 완성**

---

## 다음 단계 (선택)

- [ ] 기존 코드 정리 가이드
- [ ] Executor 인터페이스 문서
- [ ] 전체 아키텍처 다이어그램 (헌법 버전)

---

*wiiiv / 하늘나무 / SKYTREE*
