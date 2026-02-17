# Blueprint Node Type Specification v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Blueprint Node Type Specification |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-29 |
| 상위 문서 | Blueprint Structure Schema v1.0 |
| 적용 범위 | `execution_plan.steps[]` 내부 노드 타입 |

---

## 1. 목적 (Purpose)

이 문서는 Execution Blueprint의 `execution_plan.steps[]`에 들어갈 **노드 타입**을 정의한다.

- 어떤 노드 타입이 **정규(Canonical)**인가
- 각 노드의 **최소 필드**는 무엇인가
- Gate가 `action`을 **어떻게 해석**하는가

---

## 2. 설계 원칙 (Design Principles)

### 2.1 최소 노드 집합

- 노드 타입은 **최소한**으로 유지한다
- "있으면 좋은" 타입은 추가하지 않는다
- 필요해지면 그때 확장한다

### 2.2 LLM 친화적

- LLM이 Blueprint를 **생성할 수 있어야** 한다
- 노드 구조는 **직관적**이어야 한다
- 복잡한 중첩/참조를 피한다

### 2.3 의미 중심

- 노드는 **"무엇을 한다"**를 표현한다
- 노드는 **"어떻게 한다"**를 표현하지 않는다
- 구현 세부사항은 Executor 책임이다

### 2.4 Gate 검증 가능

- 모든 노드는 `action` 필드를 가진다
- Permission Gate는 `action`만 검증한다
- 노드의 내부 `params`는 Gate가 보지 않는다

---

## 3. 공통 노드 구조 (Common Structure)

모든 노드는 다음 공통 필드를 가진다:

```json
{
  "step_id": "string (required)",
  "type": "string (required)",
  "action": "string (required)",
  "description": "string (optional)",
  "params": { }
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `step_id` | string | ✅ | 단계 고유 식별자 |
| `type` | string | ✅ | 노드 타입 (아래 정의) |
| `action` | string | ✅ | 수행 작업 (**Gate 검증 대상**) |
| `description` | string | ⚪ | 인간 이해용 설명 |
| `params` | object | ⚪ | 타입별 파라미터 |

---

## 4. 정규 노드 타입 (Canonical Node Types)

### v1.0 정규 노드 집합

| Type | Action 예시 | 설명 |
|------|------------|------|
| `code_generation` | `generate`, `modify`, `delete` | 코드 생성/수정/삭제 |
| `file_operation` | `read`, `write`, `delete`, `move` | 파일 시스템 작업 |
| `llm_call` | `complete`, `analyze`, `summarize` | LLM 호출 |
| `api_call` | `request` | 외부 API 호출 |
| `user_interaction` | `ask`, `confirm`, `notify` | 사용자 상호작용 |

---

## 5. 노드 타입 상세 정의

### 5.1 `code_generation`

**목적**: 코드 파일 생성, 수정, 삭제

#### Actions

| Action | 설명 | Permission Level |
|--------|------|------------------|
| `generate` | 새 코드 파일 생성 | `write` |
| `modify` | 기존 코드 파일 수정 | `write` |
| `delete` | 코드 파일 삭제 | `delete` |

#### Params

```json
{
  "step_id": "step_001",
  "type": "code_generation",
  "action": "generate",
  "description": "TodoController.kt 생성",
  "params": {
    "target": "src/main/kotlin/TodoController.kt",
    "language": "kotlin",
    "framework": "spring-boot",
    "content_hint": "REST controller for Todo CRUD"
  }
}
```

| Param | 타입 | 필수 | 설명 |
|-------|------|------|------|
| `target` | string | ✅ | 대상 파일 경로 |
| `language` | string | ⚪ | 프로그래밍 언어 |
| `framework` | string | ⚪ | 프레임워크 |
| `content_hint` | string | ⚪ | 생성 힌트 (LLM용) |

---

### 5.2 `file_operation`

**목적**: 일반 파일 시스템 작업

#### Actions

| Action | 설명 | Permission Level |
|--------|------|------------------|
| `read` | 파일 읽기 | `read` |
| `write` | 파일 쓰기 | `write` |
| `delete` | 파일 삭제 | `delete` |
| `move` | 파일 이동/이름 변경 | `write` |

#### Params

```json
{
  "step_id": "step_002",
  "type": "file_operation",
  "action": "write",
  "description": "설정 파일 생성",
  "params": {
    "path": "config/application.yml",
    "content": "server:\n  port: 8080"
  }
}
```

| Param | 타입 | 필수 | 설명 |
|-------|------|------|------|
| `path` | string | ✅ | 파일 경로 |
| `content` | string | `write`시 필수 | 파일 내용 |
| `destination` | string | `move`시 필수 | 이동 대상 경로 |

---

### 5.3 `llm_call`

**목적**: LLM API 호출

#### Actions

| Action | 설명 | Permission Level |
|--------|------|------------------|
| `complete` | 텍스트 완성 | `llm` |
| `analyze` | 분석 요청 | `llm` |
| `summarize` | 요약 요청 | `llm` |

#### Params

```json
{
  "step_id": "step_003",
  "type": "llm_call",
  "action": "complete",
  "description": "API 문서 생성",
  "params": {
    "prompt": "Generate API documentation for...",
    "model": "gpt-4o",
    "max_tokens": 2000,
    "output_key": "api_docs"
  }
}
```

| Param | 타입 | 필수 | 설명 |
|-------|------|------|------|
| `prompt` | string | ✅ | LLM 프롬프트 |
| `model` | string | ⚪ | 사용할 모델 |
| `max_tokens` | number | ⚪ | 최대 토큰 수 |
| `output_key` | string | ⚪ | 결과 저장 키 |

---

### 5.4 `api_call`

**목적**: 외부 HTTP API 호출

#### Actions

| Action | 설명 | Permission Level |
|--------|------|------------------|
| `request` | HTTP 요청 | `network` |

#### Params

```json
{
  "step_id": "step_004",
  "type": "api_call",
  "action": "request",
  "description": "외부 서비스 데이터 조회",
  "params": {
    "method": "GET",
    "url": "https://api.example.com/data",
    "headers": { "Authorization": "Bearer {{token}}" },
    "output_key": "external_data"
  }
}
```

| Param | 타입 | 필수 | 설명 |
|-------|------|------|------|
| `method` | string | ✅ | HTTP 메서드 |
| `url` | string | ✅ | 요청 URL |
| `headers` | object | ⚪ | HTTP 헤더 |
| `body` | any | ⚪ | 요청 바디 |
| `output_key` | string | ⚪ | 결과 저장 키 |

---

### 5.5 `user_interaction`

**목적**: 사용자 상호작용 (실행 중 대화)

#### Actions

| Action | 설명 | Permission Level |
|--------|------|------------------|
| `ask` | 사용자에게 질문 | `interaction` |
| `confirm` | 사용자 확인 요청 | `interaction` |
| `notify` | 사용자에게 알림 | `interaction` |

#### Params

```json
{
  "step_id": "step_005",
  "type": "user_interaction",
  "action": "confirm",
  "description": "DB 마이그레이션 확인",
  "params": {
    "message": "데이터베이스 스키마를 변경하시겠습니까?",
    "options": ["yes", "no"],
    "default": "no",
    "output_key": "user_confirm"
  }
}
```

| Param | 타입 | 필수 | 설명 |
|-------|------|------|------|
| `message` | string | ✅ | 표시할 메시지 |
| `options` | array | `ask`/`confirm`시 | 선택 옵션 |
| `default` | string | ⚪ | 기본값 |
| `output_key` | string | ⚪ | 응답 저장 키 |

---

## 6. Gate 검증 규칙 (Permission Gate)

### 6.1 Action → Permission 매핑

Permission Gate는 `action`을 Permission Level로 매핑한다:

| Action | Permission Level |
|--------|------------------|
| `generate`, `write`, `move`, `modify` | `write` |
| `read` | `read` |
| `delete` | `delete` |
| `complete`, `analyze`, `summarize` | `llm` |
| `request` | `network` |
| `ask`, `confirm`, `notify` | `interaction` |

### 6.2 검증 로직

```
FOR each step in execution_plan.steps:
    action = step.action
    permission = action_to_permission(action)
    IF requester NOT permitted for permission:
        DENY
```

### 6.3 Gate는 무엇을 보지 않는가

Gate는 다음을 **검증에 사용하지 않는다**:

- `params` 내부 값
- `description`
- `content_hint`
- 파일 경로의 구체적 위치

> Gate는 **"무엇을 하려는가"**만 본다. **"어떻게 하려는가"**는 보지 않는다.

---

## 7. 노드 실행 순서

### 7.1 기본 순서

`steps` 배열의 순서대로 **순차 실행**이 기본이다.

```json
"steps": [
  { "step_id": "step_001", ... },  // 첫 번째 실행
  { "step_id": "step_002", ... },  // 두 번째 실행
  { "step_id": "step_003", ... }   // 세 번째 실행
]
```

### 7.2 의존성 (v1.0에서는 미지원)

v1.0에서는 명시적 의존성(`depends_on`)을 지원하지 않는다.

복잡한 의존성이 필요하면 **여러 Blueprint로 분리**한다.

---

## 8. 확장 규칙

### 8.1 새 노드 타입 추가

새 노드 타입 추가 시:

1. 이 문서에 섹션 추가
2. 최소 필드 정의
3. Action → Permission 매핑 추가
4. Gate 검증 로직 업데이트

### 8.2 새 Action 추가

기존 타입에 새 Action 추가 시:

1. 해당 타입 섹션에 Action 추가
2. Permission Level 매핑 추가

### 8.3 금지된 확장

다음은 **설계 위반**이다:

- Gate가 `params` 내부를 검증하도록 변경
- 노드가 다른 노드를 직접 호출
- 노드가 Blueprint를 수정

---

## 9. 미래 노드 타입 (Non-binding)

다음 타입은 v1.0에 포함되지 않지만, 향후 추가될 수 있다:

| Type | 설명 | 고려 사항 |
|------|------|----------|
| `shell_command` | 셸 명령 실행 | 보안 위험, 신중한 검토 필요 |
| `db_query` | 데이터베이스 쿼리 | 권한 모델 복잡성 |
| `batch` | 배치 작업 | 비용/시간 제어 |

---

## 10. 전체 예시 (Multi-Step Blueprint)

```json
{
  "execution_plan": {
    "mode": "multi-step",
    "steps": [
      {
        "step_id": "step_001",
        "type": "code_generation",
        "action": "generate",
        "description": "Entity 클래스 생성",
        "params": {
          "target": "src/main/kotlin/Todo.kt",
          "language": "kotlin"
        }
      },
      {
        "step_id": "step_002",
        "type": "code_generation",
        "action": "generate",
        "description": "Repository 인터페이스 생성",
        "params": {
          "target": "src/main/kotlin/TodoRepository.kt",
          "language": "kotlin"
        }
      },
      {
        "step_id": "step_003",
        "type": "code_generation",
        "action": "generate",
        "description": "Service 클래스 생성",
        "params": {
          "target": "src/main/kotlin/TodoService.kt",
          "language": "kotlin"
        }
      },
      {
        "step_id": "step_004",
        "type": "code_generation",
        "action": "generate",
        "description": "Controller 클래스 생성",
        "params": {
          "target": "src/main/kotlin/TodoController.kt",
          "language": "kotlin"
        }
      },
      {
        "step_id": "step_005",
        "type": "user_interaction",
        "action": "notify",
        "description": "완료 알림",
        "params": {
          "message": "Todo API 생성이 완료되었습니다. 4개 파일이 생성되었습니다."
        }
      }
    ],
    "estimated_cost": {
      "tokens": 8000,
      "api_calls": 4
    }
  }
}
```

---

## Canonical 상태 요약

| 구성 요소 | 상태 |
|-----------|------|
| Governor 역할 정의서 v1.0 | ✅ Canonical |
| DACS v2 인터페이스 | ✅ Canonical |
| Gate 최소 스펙 정의서 | ✅ Canonical |
| Execution Blueprint Spec v1.0 | ✅ Canonical |
| Blueprint Structure Schema v1.0 | ✅ Canonical |
| Blueprint Node Type Spec v1.0 | ✅ Canonical |

---

## 문서 완결 선언

이 문서로 wiiiv의 **Canonical 문서 체계**가 완성되었다:

```
Governor (판단)
    ↓
DACS (합의)
    ↓
Blueprint (판단의 고정)
    ├── Structure Schema (구조)
    └── Node Type Spec (실행 단위)
    ↓
Gate (통제)
    ↓
Executor (실행)
```

**개념 → 헌법 → 실행 자산 → 시스템**으로의 전환이 완료되었다.

---

*wiiiv / 하늘나무 / SKYTREE*
