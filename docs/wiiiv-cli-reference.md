# wiiiv CLI Reference Guide

> **wiiiv-cli** v2.0 - LLM Governor 기반 실행 시스템 터미널 인터페이스
>
> wiiiv / 하늘나무 / SKYTREE

---

## 목차

1. [개요](#개요)
2. [설치](#설치)
3. [CLI 헌법](#cli-헌법)
4. [전역 옵션](#전역-옵션)
5. [명령어 레퍼런스](#명령어-레퍼런스)
   - [auth](#auth---인증-관리)
   - [decision](#decision---governor-판단-요청)
   - [blueprint](#blueprint---실행-계획-관리)
   - [execution](#execution---실행-관리)
   - [system](#system---시스템-정보)
   - [config](#config---cli-설정)
6. [출력 형식](#출력-형식)
7. [설정 파일](#설정-파일)
8. [사용 예시](#사용-예시)
9. [오류 처리](#오류-처리)

---

## 개요

wiiiv CLI는 wiiiv API의 터미널 인터페이스입니다. REST API의 모든 리소스를 1:1로 매핑하여 터미널에서 Governor 기반 실행 시스템을 제어할 수 있습니다.

```
CLI = REST API의 인간 친화적 Projection
```

### 기술 스택

| 항목 | 기술 |
|------|------|
| Language | Kotlin |
| CLI Framework | clikt 4.2.1 |
| HTTP Client | Ktor Client (CIO) |
| Serialization | kotlinx.serialization |

---

## 설치

### Gradle 빌드

```bash
# 빌드
./gradlew :wiiiv-cli:build

# 설치 (실행 스크립트 생성)
./gradlew :wiiiv-cli:installDist

# 실행
./wiiiv-cli/build/install/wiiiv-cli/bin/wiiiv-cli --help
```

### 심볼릭 링크 (권장)

```bash
ln -s $(pwd)/wiiiv-cli/build/install/wiiiv-cli/bin/wiiiv-cli /usr/local/bin/wiiiv
```

---

## CLI 헌법

wiiiv CLI는 다음 5가지 원칙을 따릅니다:

| 조항 | 원칙 | 설명 |
|------|------|------|
| 1조 | CLI는 판단하지 않는다 | 좋다/나쁘다 평가 금지 |
| 2조 | CLI는 해석하지 않는다 | 결과의 의미 해석 금지 |
| 3조 | CLI는 API를 1:1 반영한다 | 리소스 중심 설계 |
| 4조 | CLI는 상태를 만들지 않는다 | 세션 외 상태 저장 금지 |
| 5조 | CLI는 자동화 가능해야 한다 | `--json` 옵션 지원 |

---

## 전역 옵션

모든 명령에서 사용 가능한 전역 옵션:

| 옵션 | 단축 | 설명 | 기본값 |
|------|------|------|--------|
| `--json` | | JSON 형식으로 출력 (스크립트/자동화용) | false |
| `--quiet` | `-q` | 최소 출력 (에러만 표시) | false |
| `--trace` | | 상세 디버그 출력 | false |
| `--api` | | API 서버 URL | `http://localhost:8235` |
| `--help` | `-h` | 도움말 표시 | |

### 예시

```bash
# JSON 출력
wiiiv --json system health

# 다른 서버 접속
wiiiv --api http://production:8235 system health

# 조용한 모드
wiiiv -q auth login --auto
```

---

## 명령어 레퍼런스

### auth - 인증 관리

JWT 기반 인증을 관리합니다. 토큰은 `~/.wiiiv/session.json`에 저장됩니다.

#### auth login

로그인하여 JWT 토큰을 획득합니다.

```
wiiiv auth login [OPTIONS]
```

| 옵션 | 단축 | 설명 |
|------|------|------|
| `--username` | `-u` | 사용자명 |
| `--password` | `-p` | 비밀번호 |
| `--auto` | | 자동 로그인 (dev mode) |

**예시:**

```bash
# 자동 로그인 (개발용)
wiiiv auth login --auto

# 수동 로그인
wiiiv auth login -u admin -p secret

# 대화형 로그인
wiiiv auth login
Username: admin
Password: ****
```

**출력:**

```
→ Auto-login (dev mode)...
✓ Logged in successfully
```

---

#### auth logout

현재 세션을 삭제합니다.

```
wiiiv auth logout
```

**출력:**

```
✓ Logged out successfully
```

---

#### auth status

현재 인증 상태를 확인합니다.

```
wiiiv auth status
```

**출력 (인증됨):**

```
Status: Authenticated
API URL: http://localhost:8235
Token: eyJhbGciOiJIUzI1NiIs...
```

**출력 (미인증):**

```
Status: Not authenticated
Run 'wiiiv auth login' to authenticate
```

**JSON 출력:**

```json
{"authenticated": true, "apiUrl": "http://localhost:8235", "tokenPreview": "eyJhbGciOiJIUzI1NiIs..."}
```

---

#### auth whoami

현재 로그인한 사용자 정보를 조회합니다.

```
wiiiv auth whoami
```

**출력:**

```
userId: dev-user
username: dev-user
roles:
  - admin
```

---

### decision - Governor 판단 요청

Governor에게 판단을 요청하고 DACS 합의 결과를 확인합니다.

#### decision create

새로운 판단 요청을 생성합니다.

```
wiiiv decision create [OPTIONS]
```

| 옵션 | 단축 | 설명 | 필수 |
|------|------|------|------|
| `--input` | `-i` | 의도/요청 내용 | ✓ |
| `--constraint` | `-c` | 제약 조건 (여러 개 가능) | |

**예시:**

```bash
# 기본 요청
wiiiv decision create --input "Deploy application to production"

# 제약 조건 포함
wiiiv decision create \
  --input "Backup database" \
  --constraint "max-size:10GB" \
  --constraint "compress:true"
```

**출력:**

```
→ Creating decision...
decisionId: f908a28b-27e4-4a6f-b2d0-870d45378016
status: APPROVED
consensus:
  outcome: YES
  votes:
    [0]
      persona: architect
      vote: APPROVE
      reason: Spec structure is valid
    [1]
      persona: reviewer
      vote: APPROVE
      reason: Requirements are clear
    [2]
      persona: adversary
      vote: APPROVE
      reason: No security concerns identified
  rationale: All personas approved
blueprintId: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
requiresApproval: false
message: Decision approved. Blueprint ready for execution.

──────────────────────────────────────────────────
Status: APPROVED
Blueprint ID: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e

Next steps:
  wiiiv blueprint get bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
  wiiiv decision approve f908a28b-27e4-4a6f-b2d0-870d45378016
```

---

#### decision get

판단 결과를 조회합니다.

```
wiiiv decision get <decision-id>
```

**예시:**

```bash
wiiiv decision get f908a28b-27e4-4a6f-b2d0-870d45378016
```

---

#### decision list

판단 목록을 조회합니다.

```
wiiiv decision list
```

> Note: API 엔드포인트 미구현 시 안내 메시지 출력

---

#### decision approve

사용자 승인을 Gate에 전달합니다.

```
wiiiv decision approve <decision-id>
```

**예시:**

```bash
wiiiv decision approve f908a28b-27e4-4a6f-b2d0-870d45378016
```

**출력 (승인 성공 시):**

```
approved: true
blueprintId: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e

Ready for execution:
  wiiiv execution create --blueprint bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
```

---

#### decision reject

사용자 거부를 전달합니다.

```
wiiiv decision reject <decision-id>
```

---

### blueprint - 실행 계획 관리

Blueprint는 Governor가 생성한 실행 계획입니다. CLI는 이를 조회하고 검증할 수 있지만, 직접 생성하거나 수정하지 않습니다.

#### blueprint get

Blueprint 상세 정보를 조회합니다.

```
wiiiv blueprint get <blueprint-id>
```

**출력:**

```
id: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
decisionId: spec-852acd5b
status: APPROVED
structure:
  nodes:
    [0]
      id: step-80857b89
      type: FILE_READ
      config:
        path: /tmp/test.txt
      dependsOn: null
  edges:
createdAt: 2026-01-30T11:42:05.486738Z
updatedAt: null
```

---

#### blueprint list

Blueprint 목록을 조회합니다.

```
wiiiv blueprint list [OPTIONS]
```

| 옵션 | 단축 | 설명 | 기본값 |
|------|------|------|--------|
| `--page` | `-p` | 페이지 번호 | 1 |
| `--size` | `-s` | 페이지 크기 | 20 |

**출력:**

```
Blueprints (1 total):

ID           │ STATUS   │ NODES │ CREATED
─────────────┼──────────┼───────┼────────────────────
bp-11c761c0- │ APPROVED │ 1     │ 2026-01-30T11:42:05
```

---

#### blueprint inspect

Blueprint 구조를 상세 분석합니다.

```
wiiiv blueprint inspect <blueprint-id>
```

**출력:**

```
Blueprint: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
Status: APPROVED
Created: 2026-01-30T11:42:05.486738Z

Execution Plan (1 steps):
──────────────────────────────────────────────────

Step 1: step-80857b89
  Type: FILE_READ
  Config:
    path: /tmp/test.txt
```

---

#### blueprint validate

Blueprint 유효성을 검증합니다.

```
wiiiv blueprint validate <blueprint-id>
```

**출력:**

```
blueprintId: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
valid: true
errors:
warnings:

✓ Blueprint is valid
```

---

#### blueprint export

Blueprint를 JSON 파일로 내보냅니다.

```
wiiiv blueprint export <blueprint-id> [OPTIONS]
```

| 옵션 | 단축 | 설명 |
|------|------|------|
| `--output` | `-o` | 출력 파일 경로 (미지정 시 stdout) |

**예시:**

```bash
# stdout 출력
wiiiv blueprint export bp-123

# 파일 저장
wiiiv blueprint export bp-123 -o blueprint.json
```

---

### execution - 실행 관리

Blueprint를 실제로 실행하고 상태를 모니터링합니다.

#### execution create

새 실행을 시작합니다.

```
wiiiv execution create [OPTIONS]
```

| 옵션 | 단축 | 설명 | 필수 |
|------|------|------|------|
| `--blueprint` | `-b` | Blueprint ID | ✓ |
| `--dry-run` | | 시뮬레이션 모드 (실제 실행 안 함) | |

**예시:**

```bash
# 실행
wiiiv execution create --blueprint bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e

# 시뮬레이션
wiiiv execution create --blueprint bp-123 --dry-run
```

**출력 (성공):**

```
→ Creating execution...
executionId: exec-abc123
status: RUNNING

──────────────────────────────────────────────────
Execution ID: exec-abc123
Status: RUNNING

Monitor with:
  wiiiv execution get exec-abc123
  wiiiv execution logs exec-abc123
```

**출력 (Gate 거부):**

```
→ Creating execution...
Error: Access denied
```

---

#### execution get

실행 상태를 조회합니다.

```
wiiiv execution get <execution-id>
```

**출력:**

```
Execution: exec-abc123
Blueprint: bp-11c761c0-ef2f-4a60-a044-eea0fabd6b4e
Status: COMPLETED
Started: 2026-01-30T11:45:00Z
Completed: 2026-01-30T11:45:05Z

Step Results:

NODE         │ STATUS    │ DURATION │ OUTPUT/ERROR
─────────────┼───────────┼──────────┼─────────────
step-80857b89│ SUCCESS   │ 150ms    │ File content here...
```

---

#### execution list

실행 목록을 조회합니다.

```
wiiiv execution list [OPTIONS]
```

| 옵션 | 단축 | 설명 | 기본값 |
|------|------|------|--------|
| `--blueprint` | `-b` | Blueprint ID로 필터링 | |
| `--page` | `-p` | 페이지 번호 | 1 |
| `--size` | `-s` | 페이지 크기 | 20 |

**출력:**

```
Executions (5 total):

ID           │ BLUEPRINT    │ STATUS    │ STARTED
─────────────┼──────────────┼───────────┼────────────────────
exec-abc123  │ bp-11c761c0- │ COMPLETED │ 2026-01-30T11:45:00
exec-def456  │ bp-22d872d1- │ RUNNING   │ 2026-01-30T11:50:00
```

---

#### execution cancel

실행 중인 작업을 취소합니다.

```
wiiiv execution cancel <execution-id>
```

---

#### execution logs

실행 로그를 조회합니다.

```
wiiiv execution logs <execution-id> [OPTIONS]
```

| 옵션 | 단축 | 설명 |
|------|------|------|
| `--follow` | `-f` | 실시간 로그 추적 (미구현) |

**예시:**

```bash
wiiiv execution logs exec-abc123
```

---

### system - 시스템 정보

시스템 상태와 등록된 컴포넌트를 조회합니다.

#### system health

헬스 체크를 수행합니다.

```
wiiiv system health
```

**출력:**

```
System Status: healthy

Health Checks:
  ✓ core: healthy
  ✓ executors: healthy
  ✓ gates: healthy
  ✓ dacs: healthy
```

**JSON 출력:**

```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "checks": {
      "core": {"status": "healthy", "message": "wiiiv-core loaded"},
      "executors": {"status": "healthy", "message": "10 registered"},
      "gates": {"status": "healthy", "message": "4 registered"},
      "dacs": {"status": "healthy", "message": "3 personas"}
    }
  }
}
```

---

#### system info

시스템 정보를 조회합니다.

```
wiiiv system info
```

**출력:**

```
wiiiv Server
  Version: 2.0.0-SNAPSHOT
  Status: running
  Uptime: 3600s
  API URL: http://localhost:8235
```

---

#### system executors

등록된 Executor 목록을 조회합니다.

```
wiiiv system executors
```

**출력:**

```
Registered Executors (10):

ID                  │ TYPE                 │ STATUS    │ SUPPORTED STEPS
────────────────────┼──────────────────────┼───────────┼────────────────
file-executor       │ FileExecutor         │ available │ FILE
command-executor    │ CommandExecutor      │ available │ COMMAND
noop-executor       │ NoopExecutor         │ available │ NOOP
api-executor        │ ApiExecutor          │ available │ API
llm-executor        │ LlmExecutor          │ available │ LLM
db-executor         │ DbExecutor           │ available │ DB
websocket-executor  │ WebSocketExecutor    │ available │ WEBSOCKET
mq-executor         │ MessageQueueExecutor │ available │ MESSAGE_QUEUE
grpc-executor       │ GrpcExecutor         │ available │ GRPC
multimodal-executor │ MultimodalExecutor   │ available │ MULTIMODAL
```

---

#### system gates

등록된 Gate 체인을 조회합니다.

```
wiiiv system gates
```

**출력:**

```
Gate Chain (4 gates):

  1. ● DACS Gate
     ID: gate-dacs
  2. ● User Approval Gate
     ID: gate-user-approval
  3. ● Execution Permission Gate
     ID: gate-permission
  4. ● Cost Gate
     ID: gate-cost

Execution Flow: Request → DACS → User → Execution → Cost → Executor
```

---

#### system personas

DACS 페르소나 목록을 조회합니다.

```
wiiiv system personas
```

**출력:**

```
DACS Personas (3):

  Architect
    ID: architect
    Role: Technical feasibility

  Reviewer
    ID: reviewer
    Role: Requirements validation

  Adversary
    ID: adversary
    Role: Security analysis
```

---

### config - CLI 설정

CLI 로컬 설정을 관리합니다. 서버 상태와 무관하게 CLI UX만 담당합니다.

#### config show

현재 설정을 표시합니다.

```
wiiiv config show
```

**출력:**

```
CLI Configuration (~/.wiiiv/config.json):

  api.baseUrl = http://localhost:8235
  output.color = true
  output.format = human
```

---

#### config set

설정 값을 변경합니다.

```
wiiiv config set <key> <value>
```

**예시:**

```bash
wiiiv config set api.baseUrl http://production:8235
wiiiv config set output.color false
```

---

#### config get

설정 값을 조회합니다.

```
wiiiv config get <key>
```

**예시:**

```bash
wiiiv config get api.baseUrl
# http://localhost:8235
```

---

#### config reset

설정을 기본값으로 초기화합니다.

```
wiiiv config reset
```

---

## 출력 형식

### Human 형식 (기본)

사람이 읽기 쉬운 형식으로 출력합니다.

```bash
wiiiv system health
```

```
System Status: healthy

Health Checks:
  ✓ core: healthy
  ✓ executors: healthy
```

### JSON 형식

`--json` 옵션을 사용하면 모든 출력이 JSON으로 변환됩니다. 스크립트나 자동화에 적합합니다.

```bash
wiiiv --json system health
```

```json
{
  "success": true,
  "data": {
    "status": "healthy",
    "checks": {...}
  }
}
```

### Quiet 형식

`--quiet` 또는 `-q` 옵션을 사용하면 에러만 출력합니다.

```bash
wiiiv -q auth login --auto
# (성공 시 출력 없음)
```

---

## 설정 파일

### 세션 파일

**경로:** `~/.wiiiv/session.json`

JWT 토큰과 API URL을 저장합니다.

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "apiUrl": "http://localhost:8235"
}
```

### 설정 파일

**경로:** `~/.wiiiv/config.json`

CLI 설정을 저장합니다.

```json
{
  "api.baseUrl": "http://localhost:8235",
  "output.color": true,
  "output.format": "human"
}
```

### 기본 설정

| 키 | 기본값 | 설명 |
|-----|--------|------|
| `api.baseUrl` | `http://localhost:8235` | API 서버 URL |
| `output.color` | `true` | 색상 출력 사용 |
| `output.format` | `human` | 출력 형식 (human/json) |

---

## 사용 예시

### 전체 워크플로우

```bash
# 1. 로그인
wiiiv auth login --auto

# 2. 시스템 상태 확인
wiiiv system health

# 3. 판단 요청 생성
wiiiv decision create --input "Create backup of /var/log"

# 4. Blueprint 확인
wiiiv blueprint get bp-xxx

# 5. Blueprint 구조 분석
wiiiv blueprint inspect bp-xxx

# 6. Blueprint 유효성 검증
wiiiv blueprint validate bp-xxx

# 7. 실행 (Gate 정책 통과 시)
wiiiv execution create --blueprint bp-xxx

# 8. 실행 상태 확인
wiiiv execution get exec-xxx

# 9. 로그 확인
wiiiv execution logs exec-xxx

# 10. 로그아웃
wiiiv auth logout
```

### 스크립트 자동화

```bash
#!/bin/bash

# JSON 출력으로 파싱 가능
HEALTH=$(wiiiv --json system health | jq -r '.data.status')

if [ "$HEALTH" != "healthy" ]; then
  echo "System unhealthy!"
  exit 1
fi

# Decision 생성 후 Blueprint ID 추출
RESULT=$(wiiiv --json decision create --input "Deploy app")
BLUEPRINT_ID=$(echo $RESULT | jq -r '.data.blueprintId')

# Blueprint 검증
VALID=$(wiiiv --json blueprint validate $BLUEPRINT_ID | jq -r '.data.valid')

if [ "$VALID" == "true" ]; then
  wiiiv execution create --blueprint $BLUEPRINT_ID
fi
```

### 다중 환경

```bash
# 개발 환경
wiiiv --api http://localhost:8235 system health

# 스테이징 환경
wiiiv --api http://staging:8235 system health

# 프로덕션 환경
wiiiv --api http://production:8235 system health
```

---

## 오류 처리

### 인증 오류

```
✗ Error: Not authenticated. Run 'wiiiv auth login' first.
```

**해결:** `wiiiv auth login --auto` 실행

### 연결 오류

```
✗ Error: Connection refused
```

**해결:** API 서버가 실행 중인지 확인

### Gate 거부

```
Error: Access denied
```

**해결:** Gate 정책 확인, 필요 시 Decision 승인

### 리소스 없음

```
✗ Error: Blueprint not found
```

**해결:** ID 확인, `wiiiv blueprint list`로 목록 조회

---

## 종료 코드

| 코드 | 의미 |
|------|------|
| 0 | 성공 |
| 1 | 일반 오류 |
| 2 | 인증 오류 |
| 3 | 연결 오류 |

---

*wiiiv CLI Reference Guide v2.0*

*wiiiv / 하늘나무 / SKYTREE*
