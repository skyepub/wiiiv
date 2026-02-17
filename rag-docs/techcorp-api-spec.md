# TechCorp 프로젝트 관리 API v2.1

## 개요
TechCorp 내부 프로젝트/태스크 관리 시스템의 REST API 스펙이다.
Base URL: `https://api.techcorp.internal/v2`
인증: Bearer Token (헤더: `Authorization: Bearer {token}`)

## 인증

### 토큰 발급
```
POST /auth/token
Content-Type: application/json

{
  "username": "admin",
  "password": "tc-admin-2024"
}

응답:
{
  "token": "eyJhbGciOi...",
  "expiresIn": 3600
}
```

## 프로젝트 API

### 프로젝트 목록 조회
```
GET /projects
Authorization: Bearer {token}

Query Parameters:
  - status: active | archived | all (기본값: active)
  - page: 페이지 번호 (기본값: 1)
  - limit: 페이지당 건수 (기본값: 20, 최대: 100)

응답:
{
  "projects": [
    {
      "id": "proj-001",
      "name": "Phoenix Backend Rewrite",
      "status": "active",
      "owner": "user-101",
      "teamSize": 5,
      "budget": 50000,
      "startDate": "2024-01-15",
      "dueDate": "2024-06-30",
      "completionRate": 68
    }
  ],
  "total": 12,
  "page": 1,
  "limit": 20
}
```

### 프로젝트 상세 조회
```
GET /projects/{projectId}
Authorization: Bearer {token}

응답:
{
  "id": "proj-001",
  "name": "Phoenix Backend Rewrite",
  "description": "레거시 모놀리스를 마이크로서비스로 전환",
  "status": "active",
  "owner": "user-101",
  "team": ["user-101", "user-102", "user-103", "user-104", "user-105"],
  "budget": 50000,
  "spent": 34000,
  "startDate": "2024-01-15",
  "dueDate": "2024-06-30",
  "completionRate": 68,
  "tags": ["backend", "microservice", "migration"],
  "createdAt": "2024-01-10T09:00:00Z"
}
```

### 프로젝트 생성
```
POST /projects
Authorization: Bearer {token}
Content-Type: application/json

{
  "name": "프로젝트명",
  "description": "설명",
  "owner": "user-id",
  "team": ["user-id-1", "user-id-2"],
  "budget": 30000,
  "startDate": "2024-03-01",
  "dueDate": "2024-09-30",
  "tags": ["태그1", "태그2"]
}

응답: 201 Created
{
  "id": "proj-013",
  "name": "프로젝트명",
  ...
}
```

### 프로젝트 수정
```
PUT /projects/{projectId}
Authorization: Bearer {token}
Content-Type: application/json

수정 가능 필드: name, description, status, owner, team, budget, dueDate, tags
status 변경 규칙:
  - active → archived (완료 또는 중단)
  - archived → active (재활성화)
  - active → active (필드만 변경)

{
  "status": "archived",
  "completionRate": 100
}

응답: 200 OK (수정된 프로젝트 전체 반환)
```

## 태스크 API

### 프로젝트별 태스크 목록
```
GET /projects/{projectId}/tasks
Authorization: Bearer {token}

Query Parameters:
  - status: todo | in_progress | review | done | all (기본값: all)
  - assignee: user-id (특정 담당자 필터)
  - priority: low | medium | high | critical

응답:
{
  "tasks": [
    {
      "id": "task-001",
      "projectId": "proj-001",
      "title": "API Gateway 설계",
      "status": "done",
      "priority": "high",
      "assignee": "user-102",
      "estimatedHours": 40,
      "actualHours": 35,
      "dueDate": "2024-02-28",
      "completedAt": "2024-02-25T17:30:00Z"
    },
    {
      "id": "task-002",
      "projectId": "proj-001",
      "title": "사용자 서비스 마이그레이션",
      "status": "in_progress",
      "priority": "critical",
      "assignee": "user-103",
      "estimatedHours": 80,
      "actualHours": 52,
      "dueDate": "2024-04-15"
    }
  ],
  "total": 24
}
```

### 태스크 생성
```
POST /projects/{projectId}/tasks
Authorization: Bearer {token}
Content-Type: application/json

{
  "title": "태스크 제목",
  "description": "상세 설명",
  "priority": "high",
  "assignee": "user-id",
  "estimatedHours": 20,
  "dueDate": "2024-05-15",
  "tags": ["api", "design"]
}

응답: 201 Created
```

### 태스크 상태 변경
```
PATCH /projects/{projectId}/tasks/{taskId}
Authorization: Bearer {token}
Content-Type: application/json

상태 전이 규칙:
  - todo → in_progress (착수)
  - in_progress → review (검토 요청)
  - review → done (완료 승인)
  - review → in_progress (반려, 재작업)
  - done → in_progress (재오픈)
  - 직접 todo → done 불가 (반드시 in_progress 거쳐야 함)

{
  "status": "review",
  "actualHours": 52
}

응답: 200 OK
```

## 팀원 API

### 팀원 목록
```
GET /users
Authorization: Bearer {token}

Query Parameters:
  - role: developer | designer | manager | all (기본값: all)
  - department: engineering | design | product

응답:
{
  "users": [
    {
      "id": "user-101",
      "name": "김철수",
      "email": "cs.kim@techcorp.com",
      "role": "manager",
      "department": "engineering",
      "activeProjects": 3
    },
    {
      "id": "user-102",
      "name": "이영희",
      "email": "yh.lee@techcorp.com",
      "role": "developer",
      "department": "engineering",
      "activeProjects": 2
    },
    {
      "id": "user-103",
      "name": "박민수",
      "email": "ms.park@techcorp.com",
      "role": "developer",
      "department": "engineering",
      "activeProjects": 1
    }
  ],
  "total": 15
}
```

### 팀원 상세
```
GET /users/{userId}
Authorization: Bearer {token}

응답:
{
  "id": "user-102",
  "name": "이영희",
  "email": "yh.lee@techcorp.com",
  "role": "developer",
  "department": "engineering",
  "skills": ["Java", "Kotlin", "Spring Boot", "Kubernetes"],
  "activeProjects": 2,
  "completedTasks": 47,
  "avgTaskCompletionDays": 4.2
}
```

## 대시보드 API

### 프로젝트 대시보드 요약
```
GET /projects/{projectId}/dashboard
Authorization: Bearer {token}

응답:
{
  "projectId": "proj-001",
  "name": "Phoenix Backend Rewrite",
  "summary": {
    "totalTasks": 24,
    "tasksByStatus": {
      "todo": 5,
      "in_progress": 8,
      "review": 3,
      "done": 8
    },
    "completionRate": 68,
    "budgetUsage": 68,
    "daysRemaining": 45,
    "riskLevel": "medium"
  },
  "recentActivity": [
    {
      "type": "task_completed",
      "taskId": "task-015",
      "userId": "user-104",
      "timestamp": "2024-05-10T14:30:00Z"
    }
  ]
}
```

## 에러 코드

| HTTP 상태 | 코드 | 설명 |
|-----------|------|------|
| 400 | INVALID_REQUEST | 잘못된 요청 형식 |
| 401 | UNAUTHORIZED | 인증 토큰 없음/만료 |
| 403 | FORBIDDEN | 권한 부족 (예: 다른 팀 프로젝트 수정) |
| 404 | NOT_FOUND | 리소스 없음 |
| 409 | CONFLICT | 상태 전이 규칙 위반 (예: todo → done 직접 변경) |
| 422 | VALIDATION_ERROR | 필수 필드 누락 또는 형식 오류 |
| 429 | RATE_LIMITED | 분당 100회 초과 |

## 비즈니스 규칙

1. **예산 초과 방지**: spent가 budget의 90%를 초과하면 경고 플래그 (riskLevel: "high")
2. **마감 임박 경고**: daysRemaining < 14이면 riskLevel 최소 "medium"
3. **태스크 상태 전이**: todo → done 직접 변경 불가, 반드시 in_progress를 거쳐야 함
4. **팀 사이즈 제한**: 한 프로젝트 최대 15명
5. **우선순위 에스컬레이션**: critical 태스크가 7일 이상 in_progress면 자동으로 담당자 매니저에게 알림
