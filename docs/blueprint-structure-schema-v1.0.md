# Blueprint Structure Schema v1.0

> **wiiiv Canonical Document**

---

## 문서 메타데이터

| 항목 | 내용 |
|------|------|
| 문서명 | Blueprint Structure Schema |
| 버전 | v1.0 |
| 상태 | **Canonical** |
| 작성일 | 2026-01-29 |
| 상위 문서 | Execution Blueprint Specification v1.0 |
| 적용 범위 | wiiiv Blueprint JSON 구조 |

---

## 1. 목적 (Purpose)

이 문서는 Execution Blueprint의 **최소 구조**를 정의한다.

- Blueprint가 **무엇을 반드시 포함해야 하는지**
- 어떤 필드는 **불변(immutable)** 인지
- 어떤 필드는 **확장 가능**한지

이 스키마는:
- 실행의 기준
- 저장의 기준
- 재현의 기준

이 된다.

---

## 2. 설계 원칙 (Design Principles)

- 단일 JSON 객체
- 사람이 읽을 수 있어야 함
- LLM이 생성 가능해야 함
- 불변 자산으로 저장 가능해야 함
- Node Type과 분리된 구조

> **모든 필드는 생성 후 수정 불가** (불변 원칙)

---

## 3. 최상위 구조 (Top-Level Structure)

```json
{
  "blueprint_id": "uuid",
  "version": "1.0",
  "created_at": "ISO-8601",

  "requester": { },

  "spec": { },
  "dacs_result": { },
  "governor_judgment": { },

  "execution_plan": { },

  "metadata": { }
}
```

---

## 4. 필수 필드 정의 (Required Fields)

### 4.1 `blueprint_id` (immutable)

| 항목 | 내용 |
|------|------|
| 타입 | `string` (UUID) |
| 역할 | Blueprint의 전역 식별자 |
| 규칙 | 생성 시 1회만 부여, 절대 변경 불가 |

```json
"blueprint_id": "7c1e9c4e-9f21-4b3c-9c3b-2d1c8e8c9b7a"
```

---

### 4.2 `version`

| 항목 | 내용 |
|------|------|
| 타입 | `string` |
| 값 | `"1.0"` |
| 의미 | Blueprint 구조 스키마 버전 (Governor 모델 버전과 무관) |

```json
"version": "1.0"
```

---

### 4.3 `created_at` (immutable)

| 항목 | 내용 |
|------|------|
| 타입 | `string` (ISO-8601) |
| 의미 | 판단이 고정된 시점 (실행 시각이 아님) |

```json
"created_at": "2026-01-29T10:15:30Z"
```

---

### 4.4 `requester`

| 항목 | 내용 |
|------|------|
| 타입 | `object` |
| 역할 | 실행 요청 주체, 책임 귀속 판단의 기준 |

```json
"requester": {
  "type": "user",
  "id": "user_123"
}
```

#### 필수 하위 필드

| 필드 | 타입 | 값 | 설명 |
|------|------|-----|------|
| `type` | string | `"user"` \| `"system"` | 요청 주체 구분 |
| `id` | string | - | 주체 식별자 |

---

## 5. 판단 자산 영역 (Judgment Assets)

### 5.1 `spec` (immutable snapshot)

| 항목 | 내용 |
|------|------|
| 타입 | `object` |
| 의미 | DACS 합의 대상이었던 원본 Spec 스냅샷 |
| 규칙 | Blueprint 생성 시점의 Spec을 그대로 포함, 내부 수정 불가 |
| Gate 입력 | ❌ (참조용) |

```json
"spec": {
  "spec_id": "spec_67890",
  "intent": "Todo API를 만들어줘",
  "details": { },
  "language": "ko"
}
```

---

### 5.2 `dacs_result` (immutable)

| 항목 | 내용 |
|------|------|
| 타입 | `object` |
| 의미 | DACS v2 인터페이스 결과 |
| 규칙 | Blueprint 생성 이후 변경 불가, `YES`인 경우만 실행 가능 후보 |
| Gate 입력 | ✅ (DACS Gate) |

```json
"dacs_result": {
  "consensus": "YES",
  "reason": "구조적 타당성 확인, 보안 위험 없음"
}
```

#### 필수 하위 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `consensus` | string | `"YES"` \| `"NO"` \| `"REVISION"` |
| `reason` | string | 합의 이유 |

> Blueprint가 생성되었다면 `consensus`는 반드시 `"YES"`

---

### 5.3 `governor_judgment` (immutable)

| 항목 | 내용 |
|------|------|
| 타입 | `object` |
| 의미 | Governor가 왜 이 Blueprint를 생성했는지에 대한 요약 |
| 목적 | 재현, 감사, 인간 이해 |
| Gate 입력 | ❌ (참조용) |

```json
"governor_judgment": {
  "summary": "단순 CRUD API 요청, 위험도 낮음",
  "assumptions": [
    "Standard REST conventions",
    "No external authentication"
  ],
  "notes": "Safe to proceed"
}
```

#### 하위 필드

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `summary` | string | ✅ | 판단 요약 |
| `assumptions` | array | ⚪ | 판단 시 가정한 조건들 |
| `notes` | string | ⚪ | 추가 메모 |

---

## 6. 실행 정의 영역 (Execution Definition)

### 6.1 `execution_plan` (immutable)

| 항목 | 내용 |
|------|------|
| 타입 | `object` |
| 의미 | 실제 실행될 작업의 구조적 정의 |
| 규칙 | `steps`의 구체 타입은 Node Type Spec에서 정의, Blueprint에서는 순서와 결합만 표현 |
| Gate 입력 | ✅ (Permission Gate, Cost/Policy Gate) |

```json
"execution_plan": {
  "mode": "single",
  "steps": [ ],
  "estimated_cost": {
    "tokens": 5000,
    "api_calls": 2
  }
}
```

#### 필수 하위 필드

| 필드 | 타입 | 값 | 설명 |
|------|------|-----|------|
| `mode` | string | `"single"` \| `"multi-step"` | 실행 복잡도 |
| `steps` | array | - | 실행 단계 목록 (Node Type Spec 참조) |

#### 선택 하위 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `estimated_cost` | object | 예상 비용 (Cost/Policy Gate 입력) |

---

## 7. 메타데이터 영역 (Metadata)

### 7.1 `metadata` (optional)

| 항목 | 내용 |
|------|------|
| 타입 | `object` |
| 의미 | 실행과 무관한 보조 정보 |
| 규칙 | 실행 로직에 영향 ❌, Gate 판단에 영향 ❌ |

```json
"metadata": {
  "tags": ["api", "todo"],
  "source": "interactive",
  "related_blueprints": []
}
```

#### 선택 하위 필드

| 필드 | 타입 | 값 | 설명 |
|------|------|-----|------|
| `tags` | array | - | 분류 태그 |
| `source` | string | `"interactive"` \| `"replay"` \| `"import"` | Blueprint 출처 |
| `related_blueprints` | array | - | 관련 Blueprint ID 목록 |

---

## 8. 불변성 규칙 (Immutability Rules)

다음 필드는 **생성 이후 절대 변경 불가**:

- `blueprint_id`
- `created_at`
- `spec`
- `dacs_result`
- `governor_judgment`
- `execution_plan`

변경이 필요할 경우:
- ❌ 수정
- ✅ 새 Blueprint 생성

---

## 9. Gate 입력 경계 (Gate Input Boundary)

Gate는 다음 필드**만** 검증에 사용한다:

| Gate | 사용 필드 |
|------|----------|
| **DACS Gate** | `dacs_result.consensus` |
| **User Approval Gate** | `requester` |
| **Permission Gate** | `requester`, `execution_plan.steps[].action` |
| **Cost/Policy Gate** | `execution_plan.estimated_cost` |

> **Gate는 `spec`, `governor_judgment`, `metadata`를 검증에 사용하지 않는다.**

이 경계는:
- Governor의 판단 자유를 보장하고
- Gate의 단순성을 강제하며
- 책임 경계를 명확히 한다

---

## 10. 전체 예시 (Full Example)

```json
{
  "blueprint_id": "7c1e9c4e-9f21-4b3c-9c3b-2d1c8e8c9b7a",
  "version": "1.0",
  "created_at": "2026-01-29T10:15:30Z",

  "requester": {
    "type": "user",
    "id": "user_123"
  },

  "spec": {
    "spec_id": "spec_67890",
    "intent": "Todo API를 만들어줘",
    "details": {
      "endpoints": ["GET /todos", "POST /todos", "DELETE /todos/:id"],
      "tech_stack": {
        "language": "Kotlin",
        "framework": "Spring Boot"
      }
    },
    "language": "ko"
  },

  "dacs_result": {
    "consensus": "YES",
    "reason": "구조적 타당성 확인, 보안 위험 없음, 요구사항 명확"
  },

  "governor_judgment": {
    "summary": "단순 CRUD API 요청, 위험도 낮음",
    "assumptions": [
      "Standard REST conventions",
      "No external authentication"
    ],
    "notes": "Safe to proceed"
  },

  "execution_plan": {
    "mode": "multi-step",
    "steps": [
      {
        "step_id": "step_001",
        "type": "code_generation",
        "action": "generate",
        "target": "TodoController.kt"
      },
      {
        "step_id": "step_002",
        "type": "code_generation",
        "action": "generate",
        "target": "TodoService.kt"
      }
    ],
    "estimated_cost": {
      "tokens": 5000,
      "api_calls": 2
    }
  },

  "metadata": {
    "tags": ["api", "todo", "crud"],
    "source": "interactive",
    "related_blueprints": []
  }
}
```

---

## 11. 스키마 검증 규칙

### 11.1 필수 필드 누락

```
Blueprint 생성 거부
오류: "Required field missing: {field_name}"
```

### 11.2 타입 불일치

```
Blueprint 생성 거부
오류: "Type mismatch: {field_name} expected {expected}, got {actual}"
```

### 11.3 불변 필드 수정 시도

```
작업 거부
오류: "Blueprint is immutable. Create a new Blueprint instead."
```

---

## 12. 버전 호환성

| 버전 | 호환성 |
|------|--------|
| 1.0 → 1.x | 하위 호환 보장 |
| 1.x → 2.0 | 마이그레이션 필요 가능 |

새 필드 추가는 1.x 내에서 가능하며, 기존 Blueprint 해석에 영향 없음.

---

## Canonical 상태 요약

| 구성 요소 | 상태 |
|-----------|------|
| Governor 역할 정의서 v1.0 | ✅ Canonical |
| DACS v2 인터페이스 | ✅ Canonical |
| Gate 최소 스펙 정의서 | ✅ Canonical |
| Execution Blueprint Spec v1.0 | ✅ Canonical |
| Blueprint Structure Schema v1.0 | ✅ Canonical |

---

## 다음 단계

- [ ] Blueprint Node Type Specification v1.0

---

*wiiiv / 하늘나무 / SKYTREE*
