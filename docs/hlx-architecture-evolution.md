# HLX Architecture Evolution — Blueprint에서 HLX로

> wiiiv 아키텍처 진화 논의 기록
>
> 하늘나무 / SKYTREE

---

## 1. 문제 인식: Blueprint의 한계

Blueprint은 Governor가 LLM을 통해 생성하는 실행 계획이다. 그러나 근본적인 한계가 있다.

### 1.1 비영속성

Blueprint은 메모리에서 순간적으로 존재한다. 생성 → 실행 → 소멸. 저장되지 않고, 재사용할 수 없다.

### 1.2 비결정적 구조

같은 요청에 대해 LLM이 매번 다른 Blueprint을 생성한다. 3개 스텝이 될 수도, 5개가 될 수도 있다. 구조 자체가 흔들린다.

### 1.3 멱등성 부재

동일 입력에 동일 출력을 보장할 수 없다. LLM의 비결정성이 전체 계획 구조에 전파된다.

### 1.4 재사용 불가

한 번 실행하면 사라지므로, 같은 작업을 다시 하려면 LLM이 처음부터 다시 계획을 세워야 한다.

```
사용자: "프로덕션 배포해줘"
  → LLM이 Blueprint 생성 (매번 다를 수 있음)
  → 실행
  → Blueprint 소멸

다음번에 같은 요청:
  → LLM이 또 Blueprint 생성 (이번엔 다른 구조)
  → 멱등성 보장 불가
```

---

## 2. HLX = Blueprint의 진화

HLX(Human-Level eXecutable Workflow Standard)는 Blueprint의 한계를 해결하는 진화 형태이다.

| | Blueprint | HLX |
|---|---|---|
| 생성 | 매번 LLM이 새로 생성 | 한 번 정의, 재사용 |
| 구조 일관성 | 보장 안 됨 | 고정 |
| 영속성 | 없음 (순간 존재) | 저장/버전 관리 가능 |
| 멱등성 | 낮음 | 구조적으로 높음 |
| LLM 변동 범위 | 전체 계획이 흔들림 | 노드 내부로 한정 |
| 분기 | 없음 | Decide 노드 |
| 반복 | 없음 | Repeat 노드 |
| 표현 방식 | 실행 타입 기반 (FILE, COMMAND, API) | 인지 모델 기반 (Observe/Transform/Decide/Act/Repeat) |

### 핵심 차이

Blueprint은 "LLM이 매번 전체 계획을 새로 짜는" 모델이다.
HLX는 "고정된 구조 안에서 LLM이 각 노드만 해석하는" 모델이다.

**HLX는 LLM의 비결정성을 구조로 가둔다.** 이것이 CLAUDE.md의 "확률론적 판단을 구조적으로 신뢰 가능하게"라는 철학과 정확히 일치한다.

---

## 3. HLX의 모듈화

HLX는 독립적인 실행 단위(모듈)로서 다음을 지원해야 한다:

| 능력 | 설명 |
|------|------|
| 재실행 | 같은 워크플로우를 변수만 바꿔 다시 실행 |
| 외부 호출 | 외부 시스템이 API로 워크플로우 실행 |
| 조합 | HLX 안에서 다른 HLX를 호출 (서브 워크플로우) |
| 공유 | .hlx 파일을 다른 wiiiv 인스턴스로 이동하여 실행 |
| 버전 관리 | 워크플로우 구조의 변경 이력 추적 |

프로그래밍 비유:

| 프로그래밍 | wiiiv HLX |
|-----------|-----------|
| 함수 정의 | .hlx 파일 작성 |
| 함수 저장 | Registry에 등록 |
| 함수 호출 | execute |
| 매개변수 | variables |
| 반환값 | context.variables |
| 함수 합성 | HLX에서 HLX 호출 |

---

## 4. 아키텍처 진화

### 4.1 기존 아키텍처

```
Governor → DACS → Blueprint(일회성) → Gate(독립 계층) → Executor → Runner
```

5개 계층. Blueprint은 일회성, Gate는 독립 계층.

### 4.2 진화된 아키텍처

```
Governor → DACS(veto) → HLX 생성 → HlxRunner(Act에 Gate+Executor 내장)
  판단       합의/거부      계획(영속)     실행
```

각 컴포넌트의 변화:

| 기존 | 미래 | 변화 |
|------|------|------|
| Governor | Governor | 유지 |
| DACS | DACS | **유지** (의도 수준 veto) |
| Blueprint | **HLX** | **대체** (일회성 → 영속 모듈) |
| Gate (독립 계층) | **Act 내부 Gate** | **위치 이동** |
| Executor | Executor | 유지 (HlxRunner가 호출) |
| BlueprintRunner | **HlxRunner** | **대체** |

### 4.3 HlxRunner 내부 구조

```
HlxRunner
  ├─ Observe   → LLM (정보 수집/해석)
  ├─ Transform → LLM (데이터 가공)
  ├─ Decide    → LLM (분기 선택)
  ├─ Act       → LLM 해석 → Gate 체크 → Executor 실행
  └─ Repeat    → 반복 제어
```

---

## 5. 노드별 역할 분석

### 5.1 위험도

| 노드 | 외부 영향 | 위험도 | Gate 필요 | Executor 필요 |
|------|----------|--------|----------|--------------|
| Observe | 읽기 전용 | 낮음 | X | △ (API 호출 시) |
| Transform | 없음 (내부 처리) | 없음 | X | X |
| Decide | 없음 (분기 선택) | 없음 | X | X |
| **Act** | **외부 상태 변경** | **높음** | **O** | **O** |
| Repeat | 없음 (반복 제어) | 없음 | X | X |

### 5.2 핵심 인식

**Act만 실제로 세상을 바꾼다.** 파일 삭제, 커맨드 실행, API 호출, DB 수정 — 전부 Act이다. 나머지 4개는 LLM이 "생각하는" 노드이고, Act만 "행동하는" 노드이다.

따라서 Gate도, Executor 연동도, 위험 통제도 전부 **Act에 집중**된다.

---

## 6. DACS와 Gate의 역할 구분

### 6.1 DACS = 전략적 판단 (의도 수준)

DACS는 HLX 생성 **전에** 동작한다. "이 일을 진행하는 게 맞는가?"를 판단한다.

```
사용자: "프로덕션 DB를 리팩터링해줘"

Architect: "스키마 마이그레이션 없이는 기술적으로 위험하다" → REJECT
Reviewer:  "요구사항이 모호하다, 어떤 테이블?" → ABSTAIN
Adversary: "프로덕션 직접 수정은 데이터 손실 위험" → REJECT

→ DACS: NO (veto) → HLX 생성 자체를 안 함
```

### 6.2 Gate = 전술적 통제 (행동 수준)

Gate는 Act 노드 실행 **직전에** 동작한다. "이 구체적 행동을 해도 되는가?"를 규칙으로 강제한다.

```
Act 노드: "임시 파일 정리"
  → LLM 해석: "rm -rf /tmp/old-files/"
  → Gate: ALLOW

Act 노드: "디스크 정리"
  → LLM 해석: "rm -rf /"
  → Gate: DENY (보호 경로 삭제 금지)
```

### 6.3 비교

| | DACS | Gate |
|---|---|---|
| 시점 | HLX 생성 **전** | Act 실행 **직전** |
| 성격 | 판단 (확률적) | 규칙 (결정적) |
| 질문 | "이걸 **해야** 하나?" | "이걸 **해도 되나**?" |
| 근거 | 다중 페르소나 합의 | 정책/규칙 |
| 실패 시 | 계획 자체를 안 세움 | 특정 행동만 차단 |

### 6.4 왜 둘 다 필요한가

Gate는 `rm -rf /`는 막지만, "프로덕션 DB 리팩터링이 지금 적절한가?"는 판단할 수 없다. 이것은 DACS의 영역이다.

DACS는 "하지 말았어야 할 일"을 막고, Gate는 "하면 안 되는 행동"을 막는다.

Gate가 존재하는 근본적 이유는 wiiiv의 확률론적 판단 선언에 있다:

> 넘어서는 안 되는 선 → **Gate** → 판단과 무관하게 경계를 강제

LLM이 "안전하다"고 판단해도, Gate는 정책으로 막는다. 이것은 확률론적 시스템의 필수 안전장치이다.

---

## 7. 결론

### Blueprint → HLX 전환은 단순한 기능 교체가 아니다

이것은 wiiiv의 "확률론적 판단을 구조적으로 신뢰 가능하게"라는 철학을 더 충실하게 구현하는 **아키텍처 진화**이다.

- **Blueprint**: LLM의 비결정성이 전체 계획에 전파됨
- **HLX**: LLM의 비결정성을 노드 내부로 가둠

### 최종 아키텍처

```
Governor → DACS(veto) → HLX 생성 → HlxRunner
  판단       합의/거부      계획(영속)     실행
                                        ├─ Observe   → LLM
                                        ├─ Transform → LLM
                                        ├─ Decide    → LLM
                                        ├─ Act       → Gate → Executor
                                        └─ Repeat    → 반복 제어
```

- Governor: 사용자 의도를 파악하고 판단한다
- DACS: 의도 수준에서 veto를 행사한다 (해야 하나?)
- HLX: 영속적이고 재사용 가능한 실행 계획이다
- HlxRunner: 노드를 순차 실행하며, Act에서 Gate+Executor를 호출한다
- Gate: Act 실행 직전에 결정론적 규칙을 강제한다 (해도 되나?)
- Executor: 실제 행동을 수행한다

---

*wiiiv / 하늘나무 / SKYTREE*
