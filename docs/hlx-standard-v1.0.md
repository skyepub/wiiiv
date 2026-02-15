# HLX Standard v1.0

> **HLX — Human-Level eXecutable Workflow Standard**
>
> 인간 수준에서 정의하고 실행하는 워크플로우 언어
>
> **Open Standard**
>
> 하늘나무 / SKYTREE

---

## 1. 개요

### 1.1 HLX란

HLX(Human-Level eXecutable Workflow Standard)는 **인간 수준에서 정의 가능한 실행 구조**이다.

- 코드가 아니다
- n8n 복제품이 아니다
- 단순 자동화 툴이 아니다

HLX는:

> 인간이 읽고, LLM이 실행하고, 저장하고 재실행하는 워크플로우 표준

.hlx 파일은:

- 실행 파일이면서
- 문서이면서
- 설명이면서
- 실행 지시서이다

"Human-Level"의 의미: LLM은 도구이다. 핵심은 인간 수준에서 정의 가능한 실행 구조이다. LLM은 인간 수준에서 동작하므로 자연스럽게 이 구조를 실행할 수 있다.

### 1.2 오픈 표준 선언

.hlx는 **오픈 표준**이다.

- 누구나 .hlx를 생성하고 실행할 수 있다
- 특정 런타임에 종속되지 않는다
- JSON Schema로 검증 가능하다
- 커뮤니티가 플러그인과 도구를 자유롭게 만들 수 있다

wiiiv는 HLX의 참조 구현(Reference Implementation)이다.

### 1.3 .hlx의 4가지 필수 속성

| 속성 | 의미 |
|------|------|
| **Human Readable** | 자연어 description 중심, 인간이 읽고 수행 가능 |
| **LLM Executable** | 명확한 노드 구조, 어떤 LLM이든 읽고 실행 가능 |
| **Machine Validatable** | JSON Schema 기반 구조 검증 |
| **Evolvable** | 버전 관리, 스키마 URL, 확장성 내장 |

### 1.4 wiiiv와의 관계

```
HLX (.hlx)
  = 5개 노드로 구성된 실행 그래프
  = 인간 수준에서 정의하고 LLM이 실행하는 오픈 표준
  = 단독으로도 동작하는 워크플로우 포맷

wiiiv
  = HLX + Governance(DACS) + Gate + RAG + Audit
  = HLX의 참조 구현 + 엔터프라이즈 실행 안정성 레이어
```

### 1.5 설계 철학

1. **확률론적 판단 수용** — LLM의 비결정성은 결함이 아니라 본성이다
2. **구조로 안정성 확보** — 노드 구조가 LLM의 자유도를 제한하는 가드레일 역할
3. **모델 비종속** — GPT, Claude, Gemini 등 어떤 LLM이든 노드를 읽고 실행 가능
4. **인간 가독성** — 노드의 핵심 필드는 자연어 description이며, 인간이 읽어도 수행 가능
5. **저장과 재실행** — 워크플로우는 파일로 저장하고, 며칠 뒤 불러와 재실행 가능

### 1.6 핵심 통찰: 멱등성 향상 원리

```
노드 없이 실행:
  "알아서 해줘" → LLM이 전부 즉흥 → 확률적 변동 범위: 전체 워크플로우

노드 있이 실행:
  Observe → Transform → Decide → Act
  → 구조는 고정, 판단은 노드 내부에서만 발생
  → 확률적 변동 범위: 개별 노드 내부로 축소
```

---

## 2. 노드 타입 (5가지)

### 2.1 설계 근거

이 5개는 프로그래밍 패러다임이 아니라 **인간 사고의 최소 단위**이다.

| 노드 | 사고 단위 | 역할 |
|------|-----------|------|
| Observe | 인지 | 외부 세계에서 정보를 얻는다 |
| Transform | 이해 | 데이터를 해석/가공/정규화한다 |
| Decide | 판단 | 상황에 따라 다음 흐름을 선택한다 |
| Act | 행동 | 외부 세계에 영향을 준다 |
| Repeat | 반복 | 여러 대상에 동일 구조를 적용한다 |

- 줄이면 사고 구조가 깨진다
- 늘리면 불필요한 엔지니어링이 된다
- 이 5개가 최소 완전 집합이다

### 2.2 Observe (관찰)

외부 세계에서 정보를 얻는다.

```json
{
  "type": "observe",
  "description": "주문 시스템에서 최근 7일간 주문 목록을 조회한다",
  "target": "GET /api/orders?days=7",
  "output": "orders"
}
```

- API 호출 (GET)
- DB 조회
- 파일 읽기
- 센서 데이터 수집

**핵심**: 외부 상태를 변경하지 않는다 (읽기 전용).

### 2.3 Transform (변환)

데이터를 해석/가공/정규화한다.

```json
{
  "type": "transform",
  "hint": "filter",
  "description": "status가 UNPAID이고 3일 이상 된 건만 추출한다",
  "input": "orders",
  "output": "unpaidOrders"
}
```

**hint 값** (선택적 — LLM에게 방향을 유도):

| hint | 용도 |
|------|------|
| `filter` | 조건에 맞는 항목 추출 |
| `map` | 스키마/필드 매핑 |
| `normalize` | 포맷/단위 정규화 |
| `summarize` | 텍스트 요약 |
| `extract` | 데이터 추출 |
| `merge` | 병합/중복 제거 |

hint는 노드 타입이 아니라 **의도 힌트**이다. 타입은 Transform 하나로 유지한다.

**핵심**: 외부와 상호작용하지 않는다 (데이터 내부 처리).

### 2.4 Decide (결정)

상황에 따라 다음 흐름을 선택한다.

```json
{
  "type": "decide",
  "description": "미결제 건이 있으면 알림 발송, 없으면 종료",
  "input": "unpaidOrders",
  "branches": {
    "hasItems": "step4",
    "empty": "end"
  }
}
```

- 단순 조건: Rule-based 먼저 시도
- 복합 조건: LLM이 의미 기반 판단
- `determinismLevel` 필드로 전략 선택 가능

**핵심**: 순서는 고정, 경로는 유동.

### 2.5 Act (실행)

외부 세계에 영향을 준다.

```json
{
  "type": "act",
  "description": "해당 고객에게 결제 요청 알림을 발송한다",
  "target": "POST /api/notifications",
  "input": "order"
}
```

- API 호출 (POST/PUT/DELETE)
- 파일 쓰기
- 알림 발송
- 시스템 명령 실행

**핵심**: 외부 상태를 변경한다 (쓰기). Gate/승인이 필요할 수 있다.

### 2.6 Repeat (반복)

여러 대상에 동일 구조를 적용한다.

```json
{
  "type": "repeat",
  "description": "각 미결제 주문에 대해 알림을 발송한다",
  "over": "unpaidOrders",
  "as": "order",
  "body": [
    {
      "type": "act",
      "description": "해당 고객에게 결제 요청 알림을 발송한다",
      "target": "POST /api/notifications",
      "input": "order"
    }
  ]
}
```

- `over`: 반복 대상 변수
- `as`: 반복 내 현재 항목 변수명
- `body`: 반복 실행할 노드 목록 (중첩 가능)

**핵심**: body 안에 모든 노드 타입을 중첩할 수 있다 (Repeat 안에 Decide도 가능).

---

## 3. Node Contract (노드 계약)

### 3.1 공통 필드

| 필드 | 필수 | 타입 | 설명 |
|------|------|------|------|
| `id` | 필수 | String | 노드 고유 식별자 |
| `type` | 필수 | Enum | observe, transform, decide, act, repeat |
| `description` | **필수** | String | 자연어 실행 지시 — **핵심 필드** |
| `input` | 선택 | String | 입력 변수명 (context에서 읽음) |
| `output` | 선택 | String | 출력 변수명 (context에 저장) |
| `onError` | 선택 | String | 에러 처리 정책 |
| `aiRequired` | 선택 | Boolean | LLM 개입 필수 여부 (기본: true) |
| `determinismLevel` | 선택 | String | high / medium / low |

### 3.2 타입별 추가 필드

| 타입 | 추가 필드 | 설명 |
|------|-----------|------|
| observe | `target` | 호출 대상 (URL, DB 쿼리 등) |
| transform | `hint` | filter, map, normalize, summarize, extract, merge |
| decide | `branches` | 분기 맵 (조건명 → 대상 노드 ID) |
| act | `target` | 호출 대상 (URL, 명령 등) |
| repeat | `over`, `as`, `body` | 반복 대상, 항목 변수명, 실행 노드 목록 |

### 3.3 에러 처리 (onError)

에러 처리는 별도 노드가 아니라 **노드 속성**이다. Retry는 사고 단위가 아니라 실행 정책이다.

```
onError 값 예시:
  "retry:3"              → 3회 재시도 후 실패
  "retry:3 then skip"    → 3회 재시도 후 건너뛰기
  "retry:3 then decide"  → 3회 재시도 후 LLM이 판단
  "skip"                 → 무시하고 다음 노드
  "abort"                → 워크플로우 중단
```

---

## 4. Execution Context (실행 컨텍스트)

### 4.1 정의

.hlx는 선언형 구조이고, context는 실행 시점 상태이다.

```json
{
  "variables": {
    "orders": [...],
    "unpaidOrders": [...]
  },
  "_meta": {
    "workflowId": "process77",
    "startedAt": "2026-02-15T18:30:00Z",
    "currentNode": "step3",
    "status": "running",
    "nodeResults": {
      "step1": { "status": "success", "durationMs": 1200 },
      "step2": { "status": "success", "durationMs": 800 }
    }
  },
  "_iteration": {
    "index": 2,
    "total": 5,
    "currentItem": "order"
  }
}
```

### 4.2 변수 스코프

| 스코프 | 설명 | 접근 |
|--------|------|------|
| workflow | 전체 워크플로우 공유 변수 | `${orders}` |
| iteration | Repeat 내부 현재 항목 | `${order}` (as 필드로 정의) |
| _meta | 실행 상태 (읽기 전용) | `${_meta.currentNode}` |

### 4.3 상태 관리

```
pending   → 아직 실행 안 됨
running   → 실행 중
success   → 성공
failed    → 실패
waiting   → 인간 승인 대기 (Human Review)
suspended → 일시 중단 (resume 가능)
```

---

## 5. .hlx 파일 형식

### 5.1 형식

JSON 데이터 포맷이다. 실행 언어가 아니다.

- LLM이 바로 생성할 수 있다
- 인간이 읽을 수 있다
- 도구가 파싱할 수 있다
- 버전 관리(Git)에 친화적이다

### 5.2 구조

```json
{
  "$schema": "https://hlx.dev/schema/hlx-v1.0.json",
  "version": "1.0",
  "id": "process77",
  "name": "미결제 주문 알림 발송",
  "description": "주문 API에서 미결제 건을 조회하여 고객에게 결제 알림을 발송한다",
  "trigger": {
    "type": "manual",
    "schedule": null,
    "webhook": null
  },
  "nodes": [
    {
      "id": "step1",
      "type": "observe",
      "description": "주문 시스템에서 최근 7일간 주문 목록을 조회한다",
      "target": "GET /api/orders?days=7",
      "output": "orders"
    },
    {
      "id": "step2",
      "type": "transform",
      "hint": "filter",
      "description": "주문 목록에서 status가 UNPAID이고 생성일이 3일 이상 된 건만 추출한다",
      "input": "orders",
      "output": "unpaidOrders"
    },
    {
      "id": "step3",
      "type": "decide",
      "description": "미결제 건이 있으면 알림 발송으로 진행하고, 없으면 워크플로우를 종료한다",
      "input": "unpaidOrders",
      "branches": {
        "hasItems": "step4",
        "empty": "end"
      }
    },
    {
      "id": "step4",
      "type": "repeat",
      "description": "각 미결제 주문에 대해 알림을 발송한다",
      "over": "unpaidOrders",
      "as": "order",
      "body": [
        {
          "id": "step4a",
          "type": "act",
          "description": "해당 고객에게 결제 요청 알림을 발송한다. 주문번호와 고객 이메일을 포함한다.",
          "target": "POST /api/notifications",
          "input": "order"
        }
      ]
    }
  ]
}
```

### 5.3 Trigger 레이어

Trigger는 워크플로우 내부 노드가 아니라 **메타데이터 레벨**에서 정의한다.

```
Trigger (HTTP / CLI / Cron / Webhook)
        ↓
Execution Engine
        ↓
Workflow Graph (.hlx)
```

| type | 설명 |
|------|------|
| `manual` | 수동 실행 (CLI, UI) |
| `schedule` | 스케줄 실행 (cron 표현식) |
| `webhook` | HTTP 웹훅으로 트리거 |
| `event` | 이벤트 기반 트리거 |

---

## 6. LLM 실행 프로토콜

### 6.1 실행 흐름

```
1. .hlx 파일 로드
2. Execution Context 초기화
3. 첫 번째 노드부터 순차 실행:
   a. LLM에게 현재 노드 + context 제공
   b. LLM이 description을 읽고 해석
   c. LLM이 행동 결정 및 실행
   d. 결과를 context에 기록
   e. 다음 노드로 이동 (Decide면 분기)
4. 모든 노드 완료 시 워크플로우 종료
```

### 6.2 LLM에게 제공하는 정보

매 노드 실행 시 LLM에게 다음을 제공한다:

```
- 현재 노드 (type, description, target, hint 등)
- 현재 context (변수, 이전 노드 결과)
- 워크플로우 전체 구조 (선택적 — 맥락 이해용)
- RAG 검색 결과 (필요 시 — API 문서 등)
```

### 6.3 판단 레이어 분리

```
Level 1: Governance Layer (메타 판단)
  - "이 워크플로우를 실행해도 되는가?"
  - Governor, DACS, Gate, Human Approval
  - 워크플로우 실행 전에 동작

Level 2: Workflow Layer (실행 중 판단)
  - "이 데이터를 어떻게 변환하는가?"
  - Transform, Decide 노드 안에서 동작
  - 매 노드 실행 시 LLM 개입
```

---

## 7. 실행 방식

### 7.1 CLI 실행

```bash
hlx run process77.hlx
hlx run process77.hlx --dry-run     # 실행하지 않고 계획만 표시
hlx run process77.hlx --step        # 노드 하나씩 확인하며 실행
```

### 7.2 wiiiv 내장 실행

```
사용자: "작업77 실행해줘"
wiiiv → .hlx 로드 → Governor 승인 → LLM 노드별 실행
```

### 7.3 HTTP API 실행

```
POST /api/v2/workflows/{id}/run
→ Trigger Layer → Execution Engine → .hlx 해석 → 실행
```

---

## 8. 감사(Audit)

### 8.1 AI 판단 노드 기록

AI가 개입한 모든 노드는 다음을 기록한다:

| 필드 | 설명 |
|------|------|
| nodeId | 실행된 노드 |
| model | 사용된 LLM 모델 |
| input | LLM에 제공된 입력 |
| output | LLM의 출력 |
| reasoning | 판단 근거 요약 |
| durationMs | 소요 시간 |
| timestamp | 실행 시각 |

### 8.2 재현성

동일 .hlx + 동일 입력 데이터 + 동일 모델로 재실행 시:
- 구조는 동일하게 실행된다 (노드 순서 고정)
- 개별 노드 내 판단은 미세하게 다를 수 있다 (LLM 본성)
- Audit 기록을 통해 이전 실행과 비교 가능하다

---

## 9. 확장: 플러그인

### 9.1 Executor 플러그인

Act/Observe 노드의 `target`은 플러그인으로 확장 가능하다:

```json
{
  "type": "act",
  "target": "plugin:slack/send-message",
  "description": "Slack #ops 채널에 알림을 보낸다"
}
```

### 9.2 Transform 플러그인

특수 변환은 플러그인으로 제공 가능하다:

```json
{
  "type": "transform",
  "hint": "plugin:pdf/extract-tables",
  "description": "PDF에서 표 데이터를 추출한다"
}
```

---

## 10. 남은 설계 과제

| 과제 | 상태 | 설명 |
|------|------|------|
| Node Meta Model 코드 구현 | 미착수 | Kotlin data class 정의 |
| Execution Engine 구현 | 미착수 | AI-Aware Node Runner |
| .hlx 파서 | 미착수 | JSON → 내부 모델 변환 |
| Workflow Repository | 미착수 | 저장/조회/버전 관리 |
| Context Manager | 미착수 | 변수 스코프, 상태 관리 |
| Audit Logger | 미착수 | AI 판단 기록 |
| Plugin System | 미착수 | 동적 Executor 로딩 |
| CLI (hlx) | 미착수 | 독립 실행 도구 |
| Web UI 시각화 | 미착수 | 노드 그래프 표시 |

---

## 부록: 기존 시스템과의 비교

| 기존 | 인간 가독성 | LLM 실행 | 저장/재실행 | 판단 노드 |
|------|------------|---------|------------|----------|
| LangGraph | X (코드) | O | X | X |
| n8n/Zapier | O | X (결정론적) | O | X |
| CrewAI/AutoGen | X | O | X | X |
| **HLX** | **O** | **O** | **O** | **O** |

---

*HLX — Human-Level eXecutable Workflow Standard v1.0*
*하늘나무 / SKYTREE*
*2026-02-15*
