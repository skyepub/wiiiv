# Prompt Specification v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Prompt Specification |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-02-08 |
| 적용 범위 | wiiiv-core 전체 LLM 프롬프트 (GovernorPrompt / LlmPersona / LlmGovernor) |
| 상위 문서 | Governor 역할 정의서 v1.1 (Canonical) |
| 관련 문서 | Spec 정의서 v1.0, Blueprint Spec v1.1, Gate 최소 스펙 정의서 v1.0, DACS v2 인터페이스 v2.1 |

---

## 0. 문서의 위상

wiiiv의 프롬프트는 "동작하는 코드 안의 문자열"로 존재한다. 그러나 프롬프트는 코드가 아니라 **계약(Contract)** 이다.

> 이 문서에 정의된 불변 조건(Invariants)을 위반하는 프롬프트 변경은 **설계 위반**으로 간주한다.

프롬프트는 아래 4가지 신뢰 메커니즘의 접점이며, 시스템 동작을 실질적으로 결정한다:

| 메커니즘 | 역할 | 프롬프트와의 관계 |
|----------|------|-------------------|
| **DACS** | 합의(Consensus) | P-005/006/007이 투표 결과를 결정 |
| **Gate** | 경계(Policy Boundary) | P-008이 allowedOperations/paths를 산출 |
| **Blueprint** | 고정(Deterministic Plan) | P-001/003이 실행 계획 생성을 지시 |
| **Audit** | 기록(Traceability) | 모든 프롬프트의 입출력이 감사 대상 |

이 문서는 현행 프롬프트를 **있는 그대로** 명세화한다. 기존 코드 파일(GovernorPrompt.kt, LlmPersona.kt, LlmGovernor.kt)은 수정하지 않는다.

---

## 1. Why This Spec Exists

프롬프트가 계약으로 형식화되지 않았을 때 발생하는 문제:

1. **프롬프트 변경이 어떤 불변 조건을 위반하는지 알 수 없다** — 프롬프트에 불변 조건이 정의되어 있지 않으므로
2. **테스트가 "통과했는지"만 확인하고 "무엇이 보장되는지"를 명시하지 않는다**
3. **가드레일(Write Intent 등)이 프롬프트 실패 보완인지, 독립 정책인지 불명확하다**

---

## 2. Prompt Inventory (8 prompts, 3 files)

| ID | Name | Location | Role |
|------|------|------|------|
| P-001 | DEFAULT | GovernorPrompt.kt:14 | ConversationalGovernor 행동 정의 (대화/인터뷰/실행) |
| P-002 | PROJECT_GENERATION | GovernorPrompt.kt:346 | DraftSpec → 프로젝트 파일 구조 JSON 생성 |
| P-003 | API_WORKFLOW | GovernorPrompt.kt:424 | 반복적 API 호출 결정 (isComplete/isAbort/calls[]) |
| P-004 | EXECUTION_RESULT | GovernorPrompt.kt:542 | 실행 결과 포맷팅 (성공/실패 분기) |
| P-005 | ARCHITECT_PERSONA | LlmPersona.kt:195 | DACS 구조적 타당성 평가 |
| P-006 | REVIEWER_PERSONA | LlmPersona.kt:235 | DACS 요구사항 충족 평가 |
| P-007 | ADVERSARY_PERSONA | LlmPersona.kt:280 | DACS 보안/위험 평가 |
| P-008 | SPEC_ENRICHMENT | LlmGovernor.kt:128 | intent → allowedOperations/paths 추론 |

---

## 3. 3-Tier Invariant Taxonomy (Canonical)

프롬프트의 불변 조건을 3개 등급으로 분류한다.

### 3.1 S-class (Structural Invariant)

- **정의**: 기계적으로 100% 검증 가능한 조건
- **검증**: 단위 테스트, JSON 스키마 검증, 정규식 매칭
- **실패 시**: 즉시 reject (코드 레벨)
- **예시**: 응답이 반드시 JSON이다, `action` 필드가 enum 값이다, `calls[].url`이 절대 URL이다

### 3.2 B-class (Behavioral Invariant)

- **정의**: 통계적으로 검증 가능한 조건 (N회 실행 중 M회 이상 충족)
- **검증**: E2E 테스트 N회 반복, 성공률 측정
- **실패 시**: 프롬프트 1차 보완 → 여전히 임계치 미달이면 코드 가드레일로 승격 (§4 참조)
- **예시**: "조회 요청 시 GET만 호출" (95%+), "경로 명시 시 즉시 EXECUTE" (90%+)

### 3.3 E-class (Emergent Invariant)

- **정의**: 기계적 검증 불가능, 정성적 관찰만 가능한 조건
- **검증**: 수동 로그 리뷰, 사용자 피드백
- **실패 시**: 프롬프트 개선 (코드 승격 불가)
- **예시**: "자연스러운 한국어 응답", "사용자 의도의 뉘앙스 파악"

### 3.4 등급 간 관계

```
S-class ──(100% 검증)──→ 코드가 강제
B-class ──(통계 검증)──→ 프롬프트 우선, 실패 시 코드 승격 (§4)
E-class ──(정성 관찰)──→ 프롬프트만 (코드 승격 불가)
```

---

## 4. Guardrail Escalation Protocol (Canonical)

B-class 불변 조건이 프롬프트만으로 충족되지 않을 때의 승격 절차:

```
B-class 실패 감지
    → 프롬프트 강화 시도 (문구 수정, few-shot 추가 등)
    → 여전히 임계치 미달
        → 코드 가드레일로 승격 (ConversationalGovernor 등)
        → 이 문서에 가드레일 문서화:
            - 원래 B-class ID
            - 승격 사유
            - 코드 위치
```

### 4.1 현존 승격 사례

#### WRITE_INTENT_GUARD (v2: writeIntent 선언 기반)

| 항목 | 내용 |
|------|------|
| 원래 불변 조건 | P003-B03 (변경 요청 시 모든 대상 PUT/POST 완료 후 isComplete) |
| 승격 사유 | LLM이 intent를 "조회"로 축소 추출 → isComplete=true 오판 |
| v2 변경 | 키워드 기반 → writeIntent 선언 기반 일관성 검증으로 전환 |
| 코드 위치 | ConversationalGovernor.kt |
| 핵심 함수 | `hasExecutedWriteOperation()` — 실행 이력의 WRITE 존재 여부 확인 |
| 적용 지점 | `executeLlmDecidedTurn()` — 3개 지점 (writeIntent 저장, 완료 시 검증, 실행 전 검증) |

**4-Cell Consistency Matrix**:

| writeIntent | 실제 calls | Guard 결과 |
|---|---|---|
| `false` | READ만 | OK |
| `true` | WRITE >= 1 | OK |
| `true` | WRITE 없음 | Continue 강제 (Case 3) |
| `false` | WRITE 존재 | Abort (Case 4) |

**동작 원리**:

1. LLM이 첫 번째 API workflow 응답에서 `writeIntent: true/false`를 선언
2. `SessionContext.declaredWriteIntent`에 저장 (세션 내 고정, 이후 턴에서 변경 불가)
3. 일관성 검증:
   - Case 3 (writeIntent=true, WRITE 미실행): isComplete 거부, `PendingAction.ContinueExecution` 강제
   - Case 4 (writeIntent=false, WRITE 호출 존재): 즉시 Abort

---

## 5. Prompt Specification Sheets

### P-001: DEFAULT

- **위치**: `GovernorPrompt.kt:14`
- **소비자**: `ConversationalGovernor` — chat() 판단 루프
- **입력** (withContext 함수, line 230):
  - `draftSpec: DraftSpec` — 현재 Spec 상태 (intent, taskType, domain, techStack, targetPath, scale)
  - `recentHistory: List<ConversationMessage>` — 대화 이력
  - `executionHistory: List<TurnExecution>` — 실행 이력 (turn index + summary)
  - `taskList: List<TaskSlot>` — 활성 태스크 목록 (▶ ACTIVE / ⏸ SUSPENDED / ✓ COMPLETED)
- **출력 스키마**:
  ```json
  {
    "action": "REPLY | ASK | CONFIRM | EXECUTE | CANCEL",
    "message": "string",
    "specUpdates": { "intent": "...", "taskType": "...", ... },
    "askingFor": "string (optional)",
    "taskSwitch": { ... }
  }
  ```

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P001-S01 | S | 응답은 반드시 JSON이며 `action` 필드를 포함한다 | JSON parse + required field assert |
| P001-S02 | S | `action`은 REPLY/ASK/CONFIRM/EXECUTE/CANCEL 중 하나다 | enum validation |
| P001-S03 | S | `specUpdates`가 있으면 object 타입이다 | JSON schema / type check |
| P001-B01 | B | 경로 명시된 파일 읽기 요청 → EXECUTE (ASK 아님) | E2E 반복, 성공률 90%+ |
| P001-B02 | B | 일반 대화/지식 질문 → REPLY | E2E 반복, 성공률 95%+ |
| P001-B03 | B | 사용자 답변 시 specUpdates에 해당 값 반영 | E2E + session diff check |
| P001-B04 | B | SUSPENDED 작업 복귀 시 taskSwitch 포함 | E2E + action payload check |
| P001-E01 | E | 자연스러운 한국어 응답 | manual review |
| P001-E02 | E | 한 번에 하나의 질문만 | manual review |

---

### P-002: PROJECT_GENERATION

- **위치**: `GovernorPrompt.kt:346`
- **소비자**: 프로젝트 생성 파이프라인 (`projectGenerationPrompt()`, line 402)
- **입력**:
  - `draftSpec: DraftSpec` — intent, domain, techStack, scale, constraints
- **출력 스키마**:
  ```json
  {
    "files": [
      { "path": "src/main.kt", "content": "..." }
    ],
    "buildCommand": "gradle build",
    "testCommand": "gradle test"
  }
  ```

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P002-S01 | S | 응답은 JSON이며 `files[]` 배열을 포함한다 | JSON parse + required field assert |
| P002-S02 | S | `files[]` 각 요소는 `path`와 `content`를 포함한다 | JSON schema validation |
| P002-S03 | S | `buildCommand`와 `testCommand`는 문자열이다 | type check |
| P002-B01 | B | "외부 라이브러리 금지" 제약 시 외부 의존성 미포함 | E2E + dependency scan |
| P002-B02 | B | 생성된 코드는 컴파일/실행 가능한 완전한 코드다 (placeholder 없음) | E2E + build verification |
| P002-E01 | E | 파일/폴더 이름이 언어 관례에 자연스럽다 | manual review |

---

### P-003: API_WORKFLOW

- **위치**: `GovernorPrompt.kt:424`
- **소비자**: API 워크플로우 실행 루프 (`apiWorkflowPrompt()`, line 482)
- **입력**:
  - `intent: String` — 사용자 목표
  - `domain: String?` — 도메인 컨텍스트
  - `ragContext: String?` — RAG로 검색된 API 스펙
  - `executionHistory: List<String>` — 이전 API 호출 결과
  - `calledApis: List<String>` — 이미 호출한 API 목록 (중복 방지)
  - `recentHistory: List<ConversationMessage>` — 대화 이력
- **출력 스키마**:
  ```json
  {
    "isComplete": false,
    "isAbort": false,
    "reasoning": "string",
    "summary": "string",
    "calls": [
      {
        "method": "GET | POST | PUT | DELETE | PATCH",
        "url": "https://...",
        "headers": {},
        "body": {}
      }
    ]
  }
  ```

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P003-S01 | S | 응답은 JSON이며 `isComplete`, `isAbort`, `calls[]`를 포함한다 | JSON schema validation |
| P003-S02 | S | `calls[].method`는 GET/POST/PUT/DELETE/PATCH 중 하나다 | enum validation |
| P003-S03 | S | `calls[].url`은 `http(s)://`로 시작하는 절대 URL이다 | regex validation |
| P003-S04 | S | `isComplete=true`이면 `calls`는 빈 배열이다 | structural assert |
| P003-S05 | S | `writeIntent`는 boolean이며 필수 필드다 | JSON schema + type check |
| P003-B01 | B | 조회 목적 요청 → writeIntent=false 선언 | E2E 반복 + field assert, 95%+ |
| P003-B02 | B | 이미 호출한 API 재호출 금지 | E2E 반복 + dedup log check |
| P003-B03 | B | 변경 요청 시 모든 대상 PUT/POST 완료 후 isComplete | E2E 반복 + coverage check |
| P003-B04 | B | 사용자 지정 값 정확 사용 (예: "shipped" → body에 "shipped") | E2E 반복 + payload assert |
| P003-E01 | E | reasoning에 남은 작업 명시 | manual review |

#### 가드레일

| B-class ID | 가드레일 | 코드 위치 | 사유 |
|------------|----------|-----------|------|
| P003-B03 | WRITE_INTENT_GUARD (v2: writeIntent 선언 기반) | ConversationalGovernor.kt `executeLlmDecidedTurn()` | writeIntent 선언과 실제 호출의 일관성 검증 |

---

### P-004: EXECUTION_RESULT

- **위치**: `GovernorPrompt.kt:542`
- **소비자**: 실행 결과 포맷터 (`executionResultPrompt()`)
- **입력**:
  - `success: Boolean` — 실행 성공 여부
  - `result: String` — 실행 결과 또는 에러 메시지
- **출력**: 사용자에게 보여줄 메시지 문자열 (이 프롬프트는 JSON이 아닌 자연어 출력)

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P004-S01 | S | success=true 시 "실행 완료!" 포함, success=false 시 "문제가 발생" 포함 | string assertion |
| P004-S02 | S | result 내용이 응답에 포함된다 | string contains check |
| P004-B01 | B | 실패 시 원인과 가능한 다음 행동을 명시한다 | E2E + heuristic checks |
| P004-E01 | E | 사용자에게 과도하게 장황하지 않다 | manual review |

---

### P-005: ARCHITECT_PERSONA

- **위치**: `LlmPersona.kt:195`
- **소비자**: DACS 합의 엔진 (`LlmArchitect`)
- **입력** (buildPrompt, line 75):
  - `spec.id` — Spec UUID
  - `spec.name` — Spec 이름
  - `spec.description` — Spec 설명
  - `spec.allowedOperations` — 허용 연산 목록
  - `spec.allowedPaths` — 허용 경로 목록
  - `context` — 추가 맥락 (optional)
- **출력 스키마**:
  ```json
  {
    "vote": "APPROVE | REJECT | ABSTAIN",
    "summary": "평가 요약",
    "concerns": ["우려사항1", "우려사항2"]
  }
  ```
- **평가 관점**: STRUCTURAL — 구조적 타당성만 평가. 보안, 윤리, 비즈니스 요구사항은 평가하지 않음.

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P005-S01 | S | 응답은 JSON이며 `vote`, `summary`, `concerns`를 포함한다 | JSON schema validation |
| P005-S02 | S | `vote`는 APPROVE/REJECT/ABSTAIN 중 하나다 | enum validation |
| P005-B01 | B | 구조적 결함 발견 시 concerns에 구체 사유 포함 | E2E + targeted cases |
| P005-B02 | B | 보안/윤리 문제에 대해서는 판단하지 않는다 (역할 경계 준수) | E2E + cross-concern check |
| P005-E01 | E | summary가 판단 근거를 명확히 설명한다 | manual review |

---

### P-006: REVIEWER_PERSONA

- **위치**: `LlmPersona.kt:235`
- **소비자**: DACS 합의 엔진 (`LlmReviewer`)
- **입력**: P-005와 동일 (buildPrompt 공유)
- **출력 스키마**: P-005와 동일 (vote/summary/concerns)
- **평가 관점**: REQUIREMENTS — 요구사항 충족만 평가. 보안, 구현 상세는 평가하지 않음.

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P006-S01 | S | 응답은 JSON이며 `vote`, `summary`, `concerns`를 포함한다 | JSON schema validation |
| P006-S02 | S | `vote`는 APPROVE/REJECT/ABSTAIN 중 하나다 | enum validation |
| P006-B01 | B | 요구사항 누락 발견 시 concerns에 누락 항목 명시 | E2E + targeted cases |
| P006-B02 | B | 요구사항이 모순적이면 REJECT, 불명확하면 ABSTAIN | E2E + targeted cases |
| P006-E01 | E | summary가 요구사항 관점에서의 판단 근거를 설명한다 | manual review |

---

### P-007: ADVERSARY_PERSONA

- **위치**: `LlmPersona.kt:280`
- **소비자**: DACS 합의 엔진 (`LlmAdversary`)
- **입력**: P-005와 동일 (buildPrompt 공유)
- **출력 스키마**: P-005와 동일 (vote/summary/concerns)
- **평가 관점**: SECURITY & RISK — 보안/위험만 평가. REVISION-first 원칙 적용.

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P007-S01 | S | 응답은 JSON이며 `vote`, `summary`, `concerns`를 포함한다 | JSON schema validation |
| P007-S02 | S | `vote`는 APPROVE/REJECT/ABSTAIN 중 하나다 | enum validation |
| P007-B01 | B | 민감 경로(`/**`, `/etc/passwd`, `~/.ssh` 등) 접근 시 REJECT | E2E + targeted cases |
| P007-B02 | B | 불확실 시 ABSTAIN (무조건 REJECT 아님) — REVISION-first 원칙 | E2E + targeted cases |
| P007-B03 | B | 보안 위험 없는 일반 요청 시 APPROVE | E2E + false positive rate |
| P007-E01 | E | concerns가 구체적 위험 시나리오를 설명한다 | manual review |

---

### P-008: SPEC_ENRICHMENT

- **위치**: `LlmGovernor.kt:128`
- **소비자**: `LlmGovernor.enrichSpecFromIntent()` — intent → Spec 보강
- **입력**:
  - `spec.intent` — 사용자 의도 텍스트
- **출력 스키마**:
  ```json
  {
    "operations": ["FILE_READ", "FILE_WRITE", ...],
    "paths": ["/tmp/**", "/var/log/**", ...]
  }
  ```
- **유효 operations**: FILE_READ, FILE_WRITE, FILE_COPY, FILE_MOVE, FILE_DELETE, FILE_MKDIR, COMMAND

#### 불변 조건

| ID | 등급 | 조건 | 검증 방법 |
|----|------|------|-----------|
| P008-S01 | S | 응답은 JSON이며 `operations[]`, `paths[]`를 포함한다 | JSON schema validation |
| P008-S02 | S | `operations[]` 각 값은 유효한 RequestType enum이다 | enum validation |
| P008-S03 | S | `paths[]` 각 값은 문자열이다 | type check |
| P008-B01 | B | 보수적 추론 — 불필요한 operations를 포함하지 않는다 | E2E + false positive rate |
| P008-B02 | B | intent에 명시된 경로가 paths에 반영된다 | E2E + path coverage check |
| P008-E01 | E | paths가 실제 사용자 의도와 잘 부합한다 | manual review |

---

## 6. Dependency Map

프롬프트 간 호출·의존 관계:

```
사용자 입력
    │
    ▼
┌──────────────────────────────────────────────────────┐
│  P-001 DEFAULT (ConversationalGovernor)               │
│  → action 판단: REPLY / ASK / CONFIRM / EXECUTE      │
└──────────┬───────────────┬───────────────┬───────────┘
           │               │               │
     action=EXECUTE  action=EXECUTE  action=EXECUTE
     (PROJECT_CREATE) (API_WORKFLOW)  (FILE/COMMAND)
           │               │               │
           ▼               ▼               ▼
     ┌───────────┐  ┌───────────┐  ┌───────────────┐
     │  P-002    │  │  P-003    │  │  P-004        │
     │  PROJECT  │  │  API_WF   │  │  EXEC_RESULT  │
     │  GEN      │  │  (반복)   │  │  (포맷팅)     │
     └───────────┘  └─────┬─────┘  └───────────────┘
                          │
                    WRITE_INTENT_GUARD
                    (P003-B03 승격)

Spec 생성/보강 경로:
    intent
      │
      ▼
┌───────────┐     ┌─────────────────────────────────┐
│  P-008    │     │  P-005 / P-006 / P-007          │
│  SPEC     │────→│  DACS Personas                   │
│  ENRICH   │     │  (ARCHITECT / REVIEWER / ADVERSARY)│
└───────────┘     └─────────────────────────────────┘
  operations        vote → VetoConsensusEngine
  paths               → YES / NO / REVISION
```

### 의존 관계 요약

| 소스 | 대상 | 조건 |
|------|------|------|
| P-001 | P-002 | action=EXECUTE, taskType=PROJECT_CREATE |
| P-001 | P-003 | action=EXECUTE, taskType=API_WORKFLOW |
| P-001 | P-004 | 실행 완료 후 결과 포맷팅 |
| P-003 | WRITE_INTENT_GUARD | isComplete/calls 판단 시 가드레일 적용 |
| P-008 | P-005/006/007 | enriched Spec이 DACS 평가 대상으로 전달 |

---

## 7. DACS Persona 에러 처리 (공통)

LLM 호출 또는 응답 파싱 실패 시 (LlmPersona.kt):

| 상황 | vote | summary | concerns |
|------|------|---------|----------|
| LLM 호출 실패 | ABSTAIN | "LLM evaluation failed: {error}" | ["LLM provider error - manual review required"] |
| 응답 파싱 실패 | ABSTAIN | "Failed to parse LLM response: {error}" | ["Response parsing error - manual review required"] |

이는 **FAIL-CLOSED 설계**에 해당한다: LLM이 실패하면 ABSTAIN → VetoConsensusEngine에서 REVISION → 자동 승인 불가.

---

## 8. Version Policy

프롬프트 변경은 "코드 변경"이 아니라 "계약 변경"으로 취급한다.

| 변경 유형 | 버전 영향 | 예시 |
|-----------|-----------|------|
| S-class 조건 변경 (구조/스키마/필수 필드) | **MAJOR** | action enum에 새 값 추가 |
| B-class 조건 변경 (행동 규칙) | **MINOR** | few-shot 예시 추가, 행동 지침 수정 |
| E-class 조건 변경 또는 문구 개선 | **PATCH** | 한국어 어투 조정, 설명 보강 |
| 가드레일 승격 | **MINOR** | B-class → 코드 가드레일 |

프롬프트 텍스트가 변경되면 이 문서의 버전도 동일 규칙으로 업데이트한다.

---

## 9. Verification Checklist

1. **문서 구조 검증**: 기존 Canonical 문서(spec-definition-v1.0.md 등)와 동일 패턴인지 확인
2. **불변 조건 커버리지**: 8개 프롬프트 모두 최소 1개 S-class + 1개 B-class 보유
3. **승격 사례 정합성**: WRITE_INTENT_GUARD 코드와 문서 내용 일치 확인
4. **CLAUDE.md 업데이트**: Canonical 문서 테이블에 Prompt Spec 행 정상 반영

### 커버리지 검증 매트릭스

| Prompt | S-class | B-class | E-class | Guardrail |
|--------|---------|---------|---------|-----------|
| P-001 DEFAULT | 3 | 4 | 2 | - |
| P-002 PROJECT_GENERATION | 3 | 2 | 1 | - |
| P-003 API_WORKFLOW | 5 | 4 | 1 | WRITE_INTENT_GUARD (v2) |
| P-004 EXECUTION_RESULT | 2 | 1 | 1 | - |
| P-005 ARCHITECT_PERSONA | 2 | 2 | 1 | - |
| P-006 REVIEWER_PERSONA | 2 | 2 | 1 | - |
| P-007 ADVERSARY_PERSONA | 2 | 3 | 1 | - |
| P-008 SPEC_ENRICHMENT | 3 | 2 | 1 | - |
| **합계** | **22** | **20** | **9** | **1** |

---

*wiiiv / 하늘나무 / SKYTREE*
