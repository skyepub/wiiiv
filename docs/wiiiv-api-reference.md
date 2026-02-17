# wiiiv API v2 Reference

> **wiiiv** REST API 레퍼런스
>
> Base URL: `http://localhost:8235/api/v2`

---

## 목차

1. [개요](#개요)
2. [인증](#인증)
3. [공통 응답 형식](#공통-응답-형식)
4. [에러 코드](#에러-코드)
5. [Auth API](#auth-api)
6. [Decision API](#decision-api)
7. [Blueprint API](#blueprint-api)
8. [Execution API](#execution-api)
9. [System API](#system-api)

---

## 개요

wiiiv API v2는 LLM Governor 기반 실행 시스템의 REST API이다.

### 핵심 흐름

```
Decision → DACS 합의 → Blueprint 생성 → Gate 체크 → Execution
```

1. **Decision**: Governor에게 판단 요청 (DACS 합의 포함)
2. **Blueprint**: Governor가 생성한 실행 계획
3. **Execution**: Gate 통과 후 Blueprint 실행

---

## 인증

JWT Bearer 토큰 인증을 사용한다.

### 헤더 형식

```
Authorization: Bearer <token>
```

### 토큰 획득

- 개발 모드: `GET /api/v2/auth/auto-login`
- 운영 모드: `POST /api/v2/auth/login`

### 인증이 필요 없는 엔드포인트

- `GET /api/v2/auth/auto-login` (dev mode)
- `POST /api/v2/auth/login`
- `GET /api/v2/system/health`
- `GET /api/v2/system/info`

---

## 공통 응답 형식

모든 API는 동일한 응답 구조를 사용한다.

### 성공 응답

```json
{
  "success": true,
  "data": { ... },
  "error": null
}
```

### 실패 응답

```json
{
  "success": false,
  "data": null,
  "error": {
    "code": "ERROR_CODE",
    "message": "Human readable message",
    "details": { ... }
  }
}
```

---

## 에러 코드

| HTTP | 코드 | 설명 |
|------|------|------|
| 400 | `BAD_REQUEST` | 잘못된 요청 |
| 401 | `UNAUTHORIZED` | 인증 필요 / 토큰 만료 |
| 403 | `FORBIDDEN` | 접근 거부 (Gate 거부 포함) |
| 404 | `NOT_FOUND` | 리소스 없음 |
| 409 | `CONFLICT` | 상태 충돌 |
| 500 | `INTERNAL_ERROR` | 서버 내부 오류 |

---

## Auth API

인증 관련 엔드포인트

### GET /auth/auto-login

개발 모드에서 자동 로그인

**인증**: 불필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

---

### POST /auth/login

사용자 로그인

**인증**: 불필요

**요청**

```json
{
  "username": "admin",
  "password": "admin123"
}
```

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIs...",
    "tokenType": "Bearer",
    "expiresIn": 86400
  }
}
```

**에러** `401 Unauthorized` - 잘못된 자격 증명

---

### GET /auth/me

현재 사용자 정보 조회

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "userId": "dev-user",
    "username": "dev-user",
    "roles": ["admin"]
  }
}
```

---

## Decision API

Governor 판단 요청 관련 엔드포인트

### POST /decisions

새 판단 요청 (DACS 합의 + Blueprint 생성)

**인증**: 필요

**요청**

```json
{
  "spec": {
    "intent": "Read a configuration file",
    "constraints": ["read-only", "config files only"],
    "metadata": {
      "priority": "high"
    }
  },
  "context": {
    "userId": "user-123",
    "sessionId": "session-456",
    "previousDecisionId": null
  }
}
```

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `spec.intent` | string | O | 의도 설명 |
| `spec.constraints` | string[] | X | 제약 조건 |
| `spec.metadata` | object | X | 추가 메타데이터 |
| `context.userId` | string | X | 사용자 ID |
| `context.sessionId` | string | X | 세션 ID |
| `context.previousDecisionId` | string | X | 이전 판단 ID |

**응답** `201 Created`

```json
{
  "success": true,
  "data": {
    "decisionId": "d-12345678",
    "status": "APPROVED",
    "consensus": {
      "outcome": "YES",
      "votes": [
        {
          "persona": "architect",
          "vote": "APPROVE",
          "reason": "Safe file read operation within allowed scope"
        },
        {
          "persona": "reviewer",
          "vote": "APPROVE",
          "reason": "Constraints are well-defined"
        },
        {
          "persona": "adversary",
          "vote": "APPROVE",
          "reason": "No security concerns identified"
        }
      ],
      "rationale": "All personas approved the request"
    },
    "blueprintId": "bp-87654321",
    "requiresApproval": false,
    "message": "Decision approved. Blueprint ready for execution."
  }
}
```

**Decision Status**

| 상태 | 설명 |
|------|------|
| `APPROVED` | DACS 승인 (Blueprint 생성됨) |
| `REJECTED` | DACS 거부 |
| `NEEDS_REVISION` | 추가 정보 필요 |
| `PENDING_APPROVAL` | 사용자 승인 대기 |

**Consensus Outcome**

| 결과 | 설명 |
|------|------|
| `YES` | 전원 승인 (만장일치) |
| `NO` | 1개 이상 거부 |
| `REVISION` | 1개 이상 보류 |

---

### GET /decisions/{id}

판단 결과 조회

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "decisionId": "d-12345678",
    "status": "APPROVED",
    "consensus": { ... },
    "blueprintId": "bp-87654321",
    "requiresApproval": false
  }
}
```

**에러** `404 Not Found` - 판단 없음

---

### POST /decisions/{id}/approve

사용자 승인 (Gate 통과용)

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "decisionId": "d-12345678",
    "approved": true,
    "blueprintId": "bp-87654321",
    "message": "Decision approved by user. Ready for execution."
  }
}
```

**이미 승인된 경우**

```json
{
  "success": true,
  "data": {
    "decisionId": "d-12345678",
    "approved": true,
    "message": "Already approved"
  }
}
```

---

### POST /decisions/{id}/reject

사용자 거부

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "decisionId": "d-12345678",
    "rejected": true,
    "message": "Decision rejected by user"
  }
}
```

---

## Blueprint API

실행 계획 조회 관련 엔드포인트

### GET /blueprints

Blueprint 목록 조회

**인증**: 필요

**쿼리 파라미터**

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `page` | int | 1 | 페이지 번호 |
| `pageSize` | int | 20 | 페이지 크기 |

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "blueprints": [
      {
        "id": "bp-87654321",
        "decisionId": "d-12345678",
        "status": "APPROVED",
        "nodeCount": 3,
        "createdAt": "2024-01-15T10:30:00Z"
      }
    ],
    "total": 1,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### GET /blueprints/{id}

Blueprint 상세 조회

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "id": "bp-87654321",
    "decisionId": "d-12345678",
    "status": "APPROVED",
    "structure": {
      "nodes": [
        {
          "id": "step-1",
          "type": "FILE_READ",
          "config": {
            "path": "/tmp/config.json"
          },
          "dependsOn": null
        },
        {
          "id": "step-2",
          "type": "FILE_WRITE",
          "config": {
            "path": "/tmp/output.json"
          },
          "dependsOn": ["step-1"]
        }
      ],
      "edges": [
        {
          "from": "step-1",
          "to": "step-2",
          "condition": null
        }
      ]
    },
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": null
  }
}
```

**Blueprint Status**

| 상태 | 설명 |
|------|------|
| `DRAFT` | 초안 |
| `APPROVED` | 승인됨 (실행 가능) |
| `EXECUTED` | 실행 완료 |
| `FAILED` | 실행 실패 |
| `CANCELLED` | 취소됨 |

**에러** `404 Not Found` - Blueprint 없음

---

### POST /blueprints/{id}/validate

Blueprint 유효성 검증

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "blueprintId": "bp-87654321",
    "valid": true,
    "errors": [],
    "warnings": []
  }
}
```

**유효성 오류가 있는 경우**

```json
{
  "success": true,
  "data": {
    "blueprintId": "bp-87654321",
    "valid": false,
    "errors": [
      "Blueprint has no steps",
      "Step has empty ID"
    ],
    "warnings": []
  }
}
```

---

## Execution API

Blueprint 실행 관련 엔드포인트

### POST /executions

새 실행 시작

**인증**: 필요

**Gate 체크**: DACS → User Approval → Permission → Cost

**요청**

```json
{
  "blueprintId": "bp-87654321",
  "options": {
    "dryRun": false,
    "stopOnError": true,
    "parallelism": 1,
    "timeout": 60000
  }
}
```

| 필드 | 타입 | 기본값 | 설명 |
|------|------|--------|------|
| `blueprintId` | string | - | Blueprint ID (필수) |
| `options.dryRun` | boolean | false | 시뮬레이션 모드 |
| `options.stopOnError` | boolean | true | 오류 시 중단 |
| `options.parallelism` | int | 1 | 병렬 실행 수준 |
| `options.timeout` | long | null | 타임아웃 (ms) |

**응답** `202 Accepted`

```json
{
  "success": true,
  "data": {
    "executionId": "ex-11111111",
    "blueprintId": "bp-87654321",
    "status": "RUNNING",
    "startedAt": "2024-01-15T10:35:00Z",
    "completedAt": null,
    "results": null,
    "error": null
  }
}
```

**Gate 거부** `403 Forbidden`

```json
{
  "success": false,
  "error": {
    "code": "FORBIDDEN",
    "message": "Access denied"
  }
}
```

**에러**

| HTTP | 상황 |
|------|------|
| 404 | Blueprint 없음 |
| 403 | Gate 거부 (사용자 미승인 등) |

---

### GET /executions

실행 목록 조회

**인증**: 필요

**쿼리 파라미터**

| 파라미터 | 타입 | 기본값 | 설명 |
|----------|------|--------|------|
| `page` | int | 1 | 페이지 번호 |
| `pageSize` | int | 20 | 페이지 크기 |
| `blueprintId` | string | - | Blueprint로 필터링 |

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "executions": [
      {
        "executionId": "ex-11111111",
        "blueprintId": "bp-87654321",
        "status": "COMPLETED",
        "startedAt": "2024-01-15T10:35:00Z",
        "completedAt": "2024-01-15T10:35:05Z"
      }
    ],
    "total": 1,
    "page": 1,
    "pageSize": 20
  }
}
```

---

### GET /executions/{id}

실행 상태 조회

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "executionId": "ex-11111111",
    "blueprintId": "bp-87654321",
    "status": "COMPLETED",
    "startedAt": "2024-01-15T10:35:00Z",
    "completedAt": "2024-01-15T10:35:05Z",
    "results": [
      {
        "nodeId": "step-1",
        "status": "SUCCESS",
        "output": "File read successfully",
        "duration": 150,
        "error": null
      },
      {
        "nodeId": "step-2",
        "status": "SUCCESS",
        "output": "File written successfully",
        "duration": 200,
        "error": null
      }
    ],
    "error": null
  }
}
```

**Execution Status**

| 상태 | 설명 |
|------|------|
| `PENDING` | 대기 중 |
| `RUNNING` | 실행 중 |
| `COMPLETED` | 완료 |
| `FAILED` | 실패 |
| `CANCELLED` | 취소됨 |

---

### POST /executions/{id}/cancel

실행 취소

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "executionId": "ex-11111111",
    "cancelled": true,
    "message": "Execution cancellation requested"
  }
}
```

---

### GET /executions/{id}/logs

실행 로그 조회

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "executionId": "ex-11111111",
    "logs": [
      "[2024-01-15T10:35:00Z] Starting execution ex-11111111",
      "[2024-01-15T10:35:01Z] step-1: SUCCESS - File read successfully",
      "[2024-01-15T10:35:02Z] step-2: SUCCESS - File written successfully",
      "[2024-01-15T10:35:05Z] Execution completed"
    ]
  }
}
```

---

## System API

시스템 인트로스펙션 엔드포인트

### GET /system/health

헬스 체크

**인증**: 불필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "checks": {
      "core": {
        "status": "healthy",
        "message": "Core components operational"
      },
      "executors": {
        "status": "healthy",
        "message": "2 executors available"
      },
      "gates": {
        "status": "healthy",
        "message": "4 gates active"
      },
      "dacs": {
        "status": "healthy",
        "message": "3 personas configured"
      }
    }
  }
}
```

---

### GET /system/info

시스템 정보

**인증**: 불필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "version": "2.0.0",
    "uptime": 3600000,
    "status": "running"
  }
}
```

---

### GET /system/executors

등록된 Executor 목록

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "executors": [
      {
        "id": "file-executor",
        "type": "FileExecutor",
        "supportedStepTypes": [
          "FILE_READ",
          "FILE_WRITE",
          "FILE_COPY",
          "FILE_MOVE",
          "FILE_DELETE",
          "MKDIR"
        ],
        "status": "available"
      },
      {
        "id": "command-executor",
        "type": "CommandExecutor",
        "supportedStepTypes": ["COMMAND"],
        "status": "available"
      }
    ]
  }
}
```

---

### GET /system/gates

등록된 Gate 목록

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "gates": [
      {
        "id": "dacs-gate",
        "type": "DACS Gate",
        "priority": 0,
        "status": "active"
      },
      {
        "id": "user-approval-gate",
        "type": "User Approval Gate",
        "priority": 1,
        "status": "active"
      },
      {
        "id": "permission-gate",
        "type": "Execution Permission Gate",
        "priority": 2,
        "status": "active"
      },
      {
        "id": "cost-gate",
        "type": "Cost Gate",
        "priority": 3,
        "status": "active"
      }
    ]
  }
}
```

**Gate Chain 순서**

1. **DACS Gate**: DACS 합의 결과 확인 (YES만 통과)
2. **User Approval Gate**: 사용자 승인 확인
3. **Permission Gate**: Executor 권한 확인
4. **Cost Gate**: 비용 한도 확인

---

### GET /system/personas

DACS 페르소나 목록

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "personas": [
      {
        "id": "architect",
        "name": "Architect",
        "role": "Evaluates technical feasibility and design",
        "provider": null
      },
      {
        "id": "reviewer",
        "name": "Reviewer",
        "role": "Reviews code quality and standards",
        "provider": null
      },
      {
        "id": "adversary",
        "name": "Adversary",
        "role": "Identifies security risks and edge cases",
        "provider": null
      }
    ]
  }
}
```

---

### GET /system/gates/logs

Gate 로그 조회

**인증**: 필요

**응답** `200 OK`

```json
{
  "success": true,
  "data": {
    "logs": [
      {
        "logId": "gl-12345",
        "timestamp": "2024-01-15T10:35:00Z",
        "gate": "DACS Gate",
        "blueprintId": "bp-87654321",
        "result": "ALLOW",
        "reason": "DACS consensus: YES"
      }
    ]
  }
}
```

---

## 예제: 전체 흐름

### 1. 토큰 획득

```bash
curl http://localhost:8235/api/v2/auth/auto-login
```

### 2. 판단 요청

```bash
curl -X POST http://localhost:8235/api/v2/decisions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "spec": {
      "intent": "Read config file"
    }
  }'
```

### 3. 사용자 승인

```bash
curl -X POST http://localhost:8235/api/v2/decisions/$DECISION_ID/approve \
  -H "Authorization: Bearer $TOKEN"
```

### 4. 실행

```bash
curl -X POST http://localhost:8235/api/v2/executions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "blueprintId": "'$BLUEPRINT_ID'"
  }'
```

### 5. 상태 확인

```bash
curl http://localhost:8235/api/v2/executions/$EXECUTION_ID \
  -H "Authorization: Bearer $TOKEN"
```

---

*wiiiv v2.0 API Reference / 하늘나무 / SKYTREE*
