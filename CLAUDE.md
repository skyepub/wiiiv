# wiiiv v2.0 - Claude Memory

> **wiiiv** (pronounced "Weave" / 위브) - LLM Governor 기반 실행 시스템
>
> 하늘나무 / SKYTREE

---

## 프로젝트 개요

wiiiv v2.0은 **LLM Governor 기반 실행 시스템**이다.

**LLM을 믿고 맡길 수 있게 만드는 구조를 제공한다.** LLM의 능력은 이미 충분하다. 부족한 것은 신뢰다. wiiiv는 판단-합의-통제-기록의 구조를 통해, 사람이 LLM에 업무를 맡기되 그 결과를 신뢰할 수 있는 기반을 만든다.

- **v1.0 (wiiiv-automata)**: 오토마타 기반 - 별도 저장소로 보존
- **v2.0 (wiiiv)**: LLM Governor 기반 - 현재 저장소

---

## 핵심 철학

```
문서가 코드보다 상위다
코드는 문서(계약)를 구현할 뿐이다
```

### 확률론적 판단 선언

wiiiv는 **확률론적 판단**을 전제로 설계된 시스템이다.

LLM의 출력이 비결정적인 것은 결함이 아니라 본성이다. 인간의 판단 또한 결정적이지 않다. 복잡계에서 결정론적 결론이란 존재하지 않는다. 결정론은 변수를 무시하고, 불확실성을 숨기고, 실패를 설명하지 못하는 환상이다.

wiiiv는 이 현실을 회피하지 않고 수용한다. 대신, 확률론적 판단의 신뢰를 구조적으로 최대한 끌어올린다:

| 확률의 문제 | 구조적 해답 | 원리 |
|---|---|---|
| 단일 판단의 오류 | **DACS** | 다중 관점 합의로 오류를 반복 제거 |
| 넘어서는 안 되는 선 | **Gate** | 판단과 무관하게 경계를 강제 |
| 판단의 흔들림 | **Blueprint** | 판단을 실행 계획으로 고정 |
| 판단의 불투명성 | **Audit** | 기록되지 않은 판단은 변명, 기록된 판단은 책임 |

따라서 다음은 wiiiv의 약점이 **아니다**:
- LLM이 같은 입력에 다른 출력을 내는 것
- 테스트에서 LLM 응답의 정확한 문구를 보장할 수 없는 것
- 판단 결과가 실행마다 미세하게 달라지는 것

이것들은 확률론적 시스템의 본성이며, wiiiv는 이 본성 위에서 신뢰를 구축한다.

> **wiiiv는 확률론적 판단을 전제로 하되, 그 판단을 신뢰할 수 있도록 구조적으로 최선을 다한 시스템이다.**
>
> 이 정의는 모델이 바뀌어도, LLM이 교체되어도 유지된다.

### 의도된 복잡성 선언

wiiiv의 세션 상태(TaskSlot, SessionContext, DraftSpec, executionHistory)는 복잡하다. 이것은 설계 결함이 아니라 **복잡성의 의도적 가시화**이다.

복잡성을 숨긴 시스템은 평소에 단순해 보이다가, 무너지면 원인을 알 수 없다. wiiiv는 반대 길을 택했다. 실제 인간 업무에서 발생하는 상태 복잡성 — "잠깐 보류", "이건 취소하고 이전 안으로", "아까 그 판단 다시 가져와" — 을 명시적 상태로 드러내고, 테스트로 봉인한다.

따라서 다음도 wiiiv의 약점이 **아니다**:
- 상태 전이 경로의 조합이 많은 것
- suspend/resume/cancel의 edge case가 존재하는 것
- 세션 모델이 단순하지 않은 것

다만 이 복잡성은 현재 단계(철학 검증)에서 정당하되, 향후 진화 시에는 **상태 전이를 서술 가능하게 만드는 구조화**(Transition Object, StateChangeReason 등)가 필요하다. 상태 수를 줄이는 것이 아니라, 전이를 설명할 수 있게 만드는 것이 올바른 방향이다.

---

## 아키텍처 (Canonical)

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
    ↓
Runner (오케스트레이션)
    ↓
RetryPolicy (재시도 규칙)
```

---

## 책임 경계 (절대 규칙)

| 계층 | 책임 | 금지 |
|------|------|------|
| **Governor** | 판단, 흐름 결정 | 직접 실행 |
| **DACS** | 합의 결과 반환 | 흐름 제어, 판단 |
| **Gate** | 정책 강제 (ALLOW/DENY) | 판단, 해석 |
| **Executor** | 실행만 | 판단, 해석, 흐름 제어 |
| **Runner** | 집계, 오케스트레이션 | 해석, 의미 판단 |
| **RetryPolicy** | 재시도 규칙 적용 | 정책 생성, 해석 |

---

## Canonical 문서 (docs/)

| 문서 | 버전 |
|------|------|
| Spec 정의서 | v1.0 |
| Governor 역할 정의서 | v1.1 |
| DACS v2 인터페이스 | v2.1 |
| Gate 최소 스펙 정의서 | v1.0 |
| Blueprint Spec | v1.1 |
| Blueprint Structure Schema | v1.0 |
| Blueprint Node Type Spec | v1.0 |
| Executor 정의서 | v1.0 |
| Executor Interface Spec | v1.0 |
| ExecutionRunner Spec | v1.0 |
| RetryPolicy Spec | v1.0 |
| Prompt Specification | v1.0 |
| HLX Standard | v1.0 |
| wiiiv API Reference | v2.0 |
| wiiiv CLI Reference | v2.0 |

---

## 프로젝트 구조

```
wiiiv/
├── docs/                    # Canonical Spec 문서
├── wiiiv-backend/           # 서버측 (Kotlin/Ktor)
│   ├── wiiiv-core/          # 핵심 실행 계층
│   │   └── src/main/kotlin/io/wiiiv/
│   │       ├── execution/   # Executor 계층
│   │       ├── runner/      # Runner 계층
│   │       ├── blueprint/   # Blueprint 계층
│   │       ├── gate/        # Gate 계층
│   │       ├── governor/    # Governor 계층
│   │       ├── dacs/        # DACS 계층
│   │       ├── rag/         # RAG 계층 (벡터 검색)
│   │       └── hlx/         # HLX 계층 (워크플로우 표준)
│   │           ├── model/   # 데이터 모델 (HlxNode, HlxWorkflow 등)
│   │           ├── parser/  # JSON 파서
│   │           ├── validation/ # 구조 검증기
│   │           └── runner/  # 실행 엔진 (HlxRunner, HlxNodeExecutor, HlxPrompt)
│   └── wiiiv-server/        # HTTP 서버 (Ktor/Netty, 현 wiiiv-api)
│       └── src/main/kotlin/io/wiiiv/server/
│           ├── config/      # 서버 설정 (Auth, CORS, Routing)
│           ├── dto/         # 요청/응답 DTO
│           ├── registry/    # 레지스트리
│           ├── routes/      # API 라우트
│           └── session/     # 세션 관리 (SessionManager, SseProgressBridge)
├── wiiiv-cli/               # 대화형 터미널 클라이언트 (서버 접속, core 무의존)
│   └── src/main/kotlin/io/wiiiv/cli/
│       ├── Main.kt          # 진입점 (서버 접속 + SSE REPL)
│       ├── client/          # API 클라이언트 (WiiivApiClient, ApiModels)
│       ├── model/           # CLI 전용 타입 (CliModels)
│       └── commands/        # 슬래시 명령 핸들러
├── build.gradle.kts
├── settings.gradle.kts
└── CLAUDE.md
```

---

## 기술 스택

- **Language**: Kotlin
- **JDK**: 17
- **Build**: Gradle (Kotlin DSL)
- **Serialization**: kotlinx.serialization
- **Coroutines**: kotlinx.coroutines
- **API Server**: Ktor 2.3.7 (Netty)
- **API Client**: Ktor Client (CIO)
- **CLI Framework**: clikt 4.2.1
- **Authentication**: JWT

---

## API v2 엔드포인트 (wiiiv-server)

### 인증

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v2/auth/auto-login` | 자동 로그인 (dev mode) |
| POST | `/api/v2/auth/login` | 수동 로그인 |
| GET | `/api/v2/auth/me` | 현재 사용자 정보 |

### Decision (Governor 판단 요청)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v2/decisions` | 새 판단 요청 |
| GET | `/api/v2/decisions/{id}` | 판단 결과 조회 |
| POST | `/api/v2/decisions/{id}/approve` | 사용자 승인 |
| POST | `/api/v2/decisions/{id}/reject` | 사용자 거부 |

### Blueprint (실행 계획)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v2/blueprints` | Blueprint 목록 |
| GET | `/api/v2/blueprints/{id}` | Blueprint 상세 |
| POST | `/api/v2/blueprints/{id}/validate` | Blueprint 검증 |

### Execution (실행)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v2/executions` | 새 실행 시작 |
| GET | `/api/v2/executions` | 실행 목록 |
| GET | `/api/v2/executions/{id}` | 실행 상태 조회 |
| POST | `/api/v2/executions/{id}/cancel` | 실행 취소 |
| GET | `/api/v2/executions/{id}/logs` | 실행 로그 |

### System (인트로스펙션)

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v2/system/health` | 헬스 체크 |
| GET | `/api/v2/system/info` | 시스템 정보 |
| GET | `/api/v2/system/executors` | 등록된 Executor 목록 |
| GET | `/api/v2/system/gates` | 등록된 Gate 목록 |
| GET | `/api/v2/system/personas` | DACS 페르소나 목록 |

### RAG (벡터 검색)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v2/rag/ingest` | 문서 수집 (벡터화) |
| POST | `/api/v2/rag/ingest/batch` | 배치 문서 수집 |
| POST | `/api/v2/rag/search` | 유사도 검색 |
| GET | `/api/v2/rag/size` | 저장소 크기 조회 |
| GET | `/api/v2/rag/documents` | 문서 목록 조회 |
| DELETE | `/api/v2/rag/{documentId}` | 문서 삭제 |
| DELETE | `/api/v2/rag` | 저장소 초기화 |

### Session (대화형 세션)

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v2/sessions` | 세션 생성 |
| GET | `/api/v2/sessions` | 내 세션 목록 |
| GET | `/api/v2/sessions/{id}` | 세션 정보 |
| DELETE | `/api/v2/sessions/{id}` | 세션 종료 |
| POST | `/api/v2/sessions/{id}/chat` | 메시지 전송 (SSE 스트리밍 응답) |
| GET | `/api/v2/sessions/{id}/state` | 전체 세션 상태 (spec, tasks, 서버 정보) |
| GET | `/api/v2/sessions/{id}/history` | 대화 이력 (페이징) |
| POST | `/api/v2/sessions/{id}/control` | 세션 제어 (switch, cancel, resetSpec, setWorkspace) |

---

## CLI 명령어 (wiiiv-cli)

### CLI 헌법
1. CLI는 판단하지 않는다
2. CLI는 해석하지 않는다
3. CLI는 API 리소스를 1:1로 반영한다
4. CLI는 상태를 만들지 않는다
5. CLI는 자동화 가능해야 한다

### 전역 옵션
| 옵션 | 설명 |
|------|------|
| `--json` | JSON 형식으로 출력 (자동화/스크립트용) |
| `--quiet, -q` | 최소 출력 |
| `--trace` | 상세 디버그 출력 |
| `--api` | API 서버 URL (기본: http://localhost:8235) |

### 명령어 구조

| 명령 | 하위 명령 | 설명 |
|------|-----------|------|
| `wiiiv auth` | login, logout, status, whoami | 인증 관리 |
| `wiiiv decision` | create, get, list, approve, reject | Governor 판단 요청 |
| `wiiiv blueprint` | get, list, inspect, validate, export | Blueprint 관리 |
| `wiiiv execution` | create, get, list, cancel, logs | 실행 관리 |
| `wiiiv system` | health, info, executors, gates, personas | 시스템 정보 |
| `wiiiv config` | show, set, get, reset | CLI 설정 |
| `wiiiv rag` | ingest, search, list, delete, clear, size | RAG 벡터 검색 |

### 사용 예시
```bash
# 인증
wiiiv auth login --auto          # 자동 로그인 (dev mode)
wiiiv auth status                # 인증 상태 확인

# 판단 요청
wiiiv decision create --input "Deploy to production"
wiiiv decision approve <decision-id>

# Blueprint
wiiiv blueprint list
wiiiv blueprint get <blueprint-id>
wiiiv blueprint inspect <blueprint-id>

# 실행
wiiiv execution create --blueprint <blueprint-id>
wiiiv execution logs <execution-id>

# 시스템 정보
wiiiv system health
wiiiv --json system executors    # JSON 출력

# RAG (벡터 검색)
wiiiv rag ingest --file document.txt --title "My Document"
wiiiv rag ingest --content "텍스트 내용"
wiiiv rag search "검색 쿼리" --top-k 5
wiiiv rag list                   # 수집된 문서 목록
wiiiv rag delete <document-id>   # 문서 삭제
wiiiv rag size                   # 저장소 크기
```

### 세션 저장 위치
- `~/.wiiiv/session.json`: JWT 토큰 저장
- `~/.wiiiv/config.json`: CLI 설정 저장

---

## 개발 원칙

1. **Canonical 문서 준수**: 문서에 없는 것은 구현하지 않는다
2. **책임 경계 엄수**: 계층 간 침범 금지
3. **판단 금지**: Executor/Runner는 판단하지 않는다
4. **해석 금지**: 결과의 의미는 상위 계층이 해석한다

---

## 현재 상태

- [x] Canonical 문서 완료 (11개)
- [x] Executor 인터페이스 구현
- [x] NoopExecutor 구현
- [x] FileExecutor 구현 (READ/WRITE/COPY/MOVE/DELETE/MKDIR)
- [x] CompositeExecutor 구현
- [x] ExecutionRunner 구현 (fail-fast, retry 지원)
- [x] Blueprint 모델 구현
- [x] BlueprintRunner 구현
- [x] Governor 최소 구현 (SimpleGovernor)
- [x] Gate 구현 (DACS/UserApproval/Permission/Cost + GateChain)
- [x] DACS 구현 (다중 페르소나 합의 엔진)
- [x] CommandExecutor 구현 (셸 명령 실행, 타임아웃, 환경변수)
- [x] 병렬 실행 지원 (ExecutionPlan, executeParallel, fail-fast)
- [x] ApiExecutor 구현 (HTTP 호출, 타임아웃, 리다이렉트)
- [x] LlmExecutor 구현 (LLM 호출, Provider 추상화)
- [x] LLM 기반 페르소나 구현 (LlmArchitect/LlmReviewer/LlmAdversary)
- [x] 실제 LLM API 연동 (OpenAIProvider, AnthropicProvider)
- [x] DbExecutor 구현 (데이터베이스 쿼리, ConnectionProvider 추상화)
- [x] WebSocketExecutor 구현 (SEND/RECEIVE/SEND_RECEIVE, 타임아웃)
- [x] MessageQueueExecutor 구현 (PUBLISH/CONSUME/REQUEST_REPLY, Provider 추상화)
- [x] GrpcExecutor 구현 (UNARY/SERVER_STREAMING/CLIENT_STREAMING/BIDIRECTIONAL_STREAMING, Provider 추상화)
- [x] MultimodalExecutor 구현 (ANALYZE_IMAGE/EXTRACT_TEXT/PARSE_DOCUMENT/TRANSCRIBE_AUDIO/VISION_QA)
- [x] 실제 Provider 구현 (OpenAIVisionProvider, AnthropicVisionProvider, KafkaProvider)
- [x] wiiiv-api 모듈 구현 (Ktor 2.3.7, JWT 인증, REST API)
- [x] API-Core 연동 (Decision→DACS→Governor→Blueprint→Executor)
- [x] Gate 체인 연동 (DACS→UserApproval→Permission→Cost)
- [x] API 테스트 작성 및 검증
- [x] wiiiv-cli 구현 (Kotlin + clikt, 리소스 중심 설계)
- [x] RAG 파이프라인 구현 (Embedding, VectorStore, Chunker, Retriever)
- [x] RagExecutor 구현 (INGEST/SEARCH/DELETE/CLEAR/SIZE)
- [x] RAG API 엔드포인트 구현 (/api/v2/rag/*)
- [x] RAG CLI 명령어 구현 (wiiiv rag *)
- [x] Phase 4: API Workflow Orchestration
  - [x] API_CALL BlueprintStepType (Blueprint → ApiCallStep 변환)
  - [x] API_WORKFLOW TaskType (DraftSpec 슬롯: intent, domain)
  - [x] GovernorPrompt API_WORKFLOW 템플릿 (반복적 API 결정 프롬프트)
  - [x] ConversationalGovernor.executeApiWorkflow() (반복 실행 루프, 최대 10회)
  - [x] RAG 통합 (ragPipeline으로 API 스펙 검색)
  - [x] MockApiServer (임베디드 Ktor Netty, E2E 테스트용)
  - [x] ApiWorkflowE2ETest (6개 시나리오: 단일호출, 2단계, 다단계쓰기, 에러복구, 중복방지, 단일완료)
- [x] HLX Phase 1: Core Model + Parser + Validator
  - [x] HlxNode sealed class (5노드: Observe/Transform/Decide/Act/Repeat)
  - [x] HlxNodeSerializer (JSON "type" 기반 다형성 직렬화)
  - [x] HlxWorkflow, HlxTrigger, HlxContext 데이터 모델
  - [x] HlxParser (parse/parseOrNull/parseAndValidate/toJson)
  - [x] HlxValidator (7가지 구조 검증 규칙)
  - [x] HlxParserTest (20개), HlxValidatorTest (17개)
- [x] HLX Phase 2: Execution Engine
  - [x] HlxPrompt (노드 타입별 LLM 프롬프트 템플릿)
  - [x] HlxNodeExecutor (개별 노드 LLM 실행기, JSON 추출)
  - [x] HlxRunner (워크플로우 실행 엔진, FlowControl, OnErrorPolicy)
  - [x] 결과 모델 (HlxExecutionResult, HlxNodeExecutionRecord, HlxExecutionStatus)
  - [x] Decide 분기 (JumpTo/EndWorkflow), Repeat 반복 (중첩 지원)
  - [x] onError 정책 (retry:N, skip, abort, retry:N then skip/decide)
  - [x] WiiivRegistry hlxRunner 등록
  - [x] HlxRunnerTest (28개)

**테스트 현황: 700+ 통과**

| 모듈 | 테스트 | 개수 |
|------|--------|------|
| wiiiv-core | ExecutorTest | 8 |
| wiiiv-core | FileExecutorTest | 14 |
| wiiiv-core | CommandExecutorTest | 18 |
| wiiiv-core | ApiExecutorTest | 20 |
| wiiiv-core | LlmExecutorTest | 23 |
| wiiiv-core | LlmProviderTest | 16 |
| wiiiv-core | DbExecutorTest | 26 |
| wiiiv-core | WebSocketExecutorTest | 12 |
| wiiiv-core | MessageQueueExecutorTest | 19 |
| wiiiv-core | GrpcExecutorTest | 19 |
| wiiiv-core | MultimodalExecutorTest | 22 |
| wiiiv-core | VisionProviderTest | 19 |
| wiiiv-core | BlueprintTest | 12 |
| wiiiv-core | GovernorTest | 12 |
| wiiiv-core | LlmGovernorTest | 12 |
| wiiiv-core | GateTest | 29 |
| wiiiv-core | DACSTest | 28 |
| wiiiv-core | LlmPersonaTest | 24 |
| wiiiv-core | IntegrationTest | 16 |
| wiiiv-core | E2EFlowTest | 15 |
| wiiiv-core | ParallelExecutionTest | 14 |
| wiiiv-core | RagTest | 33 |
| wiiiv-core | RagExecutorTest | 13 |
| wiiiv-core | HlxParserTest | 20 |
| wiiiv-core | HlxValidatorTest | 17 |
| wiiiv-core | HlxRunnerTest | 28 |
| **wiiiv-server** | **AuthRoutesTest** | **6** |
| **wiiiv-server** | **DecisionRoutesTest** | **8** |
| **wiiiv-server** | **BlueprintRoutesTest** | **8** |
| **wiiiv-server** | **ExecutionRoutesTest** | **10** |
| **wiiiv-server** | **SystemRoutesTest** | **13** |
| **wiiiv-cli** | **RagCommandTest** | **10** |
| **wiiiv-cli** | **AuthCommandTest** | **7** |
| **wiiiv-cli** | **SystemCommandTest** | **11** |
| **wiiiv-cli** | **WiiivClientTest** | **11** |
| wiiiv-core | LlmGovernorE2ETest | 2 |
| **wiiiv-server** | **SessionManagerTest** | **9** |
| **wiiiv-server** | **SessionRoutesTest** | **10** |
| **wiiiv-server** | **SessionStateRoutesTest** | **17** |
| **wiiiv-server** | **WiringVerificationTest** | **5** |
| **wiiiv-cli** | **E2EIntegrationTest** | **8** |
| **wiiiv-cli** | **WiiivApiClientTest** | **12** |

---

## DACS 정합성 규칙 (Canonical)

### VetoConsensusEngine (이전 이름: MajorityConsensusEngine)

| 조건 | 결과 |
|------|------|
| REJECT 1개 이상 | NO (거부권 발동) |
| ABSTAIN 1개 이상 | REVISION (정보 부족) |
| APPROVE 전원 | YES (만장일치) |

### REVISION 우선 원칙

DACS는 Gate가 아니다:
- 위험/민감 요소 발견 → **REVISION** (추가 맥락/승인 요구)
- 명백한 금지 패턴만 → **NO** (`/**`, `/etc/passwd` 등)

이렇게 해야 Gate의 존재 이유가 유지된다.

---

## 다음 단계

- [x] 전체 E2E 흐름 테스트 (Governor → DACS → Gate → Blueprint → Executor)
- [x] wiiiv-api 모듈 스켈레톤 (Ktor + JWT)
- [x] API와 Core 연동 (Decision → DACS → Governor → Blueprint → Executor)
- [x] Gate 체인 API 연동 (DACS → UserApproval → Permission → Cost)
- [x] API 테스트 작성 (45개 테스트)
- [x] CLI 구현 (wiiiv-cli)
- [x] CLI 테스트 작성 (47개 테스트 - Auth, System, RAG, Client, E2E)
- [x] 통합 E2E 테스트 (CLI → API → Core)
- [x] LlmGovernor 구현 (Governor → DACS → LLM pipeline)
- [x] HybridDACS 연동 (Rule-based + LLM 페르소나 합의)
- [x] Spec enrichment (intent → allowedOperations/allowedPaths 추론)
- [x] FAIL-CLOSED 설계 (LLM 없으면 REVISION/거부)
- [x] DecisionRoutes 하드코딩 제거 (intent 기반 동적 step 생성)
- [x] MockEmbedding → RealEmbedding 스위치 (OpenAIEmbeddingProvider)
- [x] LlmGovernor E2E 테스트 (성공 + 거부 시나리오)
- [x] HLX Phase 2: Execution Engine (HlxRunner, LLM 노드 실행, WiiivRegistry 등록)
- [ ] HLX Phase 3: Server + CLI 연동 (/api/v2/workflows, CLI /hlx 명령어)
- [ ] 배포 자동화 (Docker, CI/CD)

---

## [완료] v2.2 프로젝트 구조 재설계

> 상세 계획: `docs/project-structure-v2.2.md`

### 목표 구조

```
wiiiv/                          <- git root (모노레포 유지)
├── wiiiv-backend/              <- 서버측 (Kotlin/Ktor)
│   ├── wiiiv-core/             <- 엔진 라이브러리 (현재와 동일)
│   └── wiiiv-server/           <- HTTP/WebSocket 서버 (현 wiiiv-api)
├── wiiiv-cli/                  <- 터미널 클라이언트 (현 wiiiv-shell + wiiiv-cli 통합)
│   (실행 바이너리: wiiiv)       <- 대화형 + 명령형 모두 지원
├── wiiiv-frontend/             <- 웹 클라이언트 (향후)
├── wiiiv-app/                  <- 데스크톱 클라이언트 (향후)
└── docs/
```

### 핵심 원칙

- 모든 클라이언트는 wiiiv-server에 접속한다 (core 직접 호출 금지)
- 서버가 유일한 엔진 호스트다 (Governor, DACS, Executor는 서버에서만 실행)
- MySQL 비유: wiiiv-server = mysqld, wiiiv(cli) = mysql

### 마이그레이션 단계

- [x] **1단계**: 폴더 이동 + 리네임 (wiiiv-api → wiiiv-backend/wiiiv-server 등)
- [x] **2단계**: 서버에 대화형 세션 API 추가 (SSE 스트리밍)
- [x] **3단계**: wiiiv-cli를 서버 접속 클라이언트로 전환 (core 직접호출 제거)

### 진행 상태

- [x] 구조 논의 및 결정 완료
- [x] 계획 문서 작성 (`docs/project-structure-v2.2.md`)
- [x] CLAUDE.md 반영
- [x] 1단계 완료 (폴더 이동 + 패키지 리네임)
- [x] 2단계 완료 (서버 대화형 세션 API + SSE)
- [x] 3단계 완료 (CLI → 서버 접속 클라이언트 전환, core 의존성 완전 제거)

---

*wiiiv / 하늘나무 / SKYTREE*
