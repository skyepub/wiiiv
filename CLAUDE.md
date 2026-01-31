# wiiiv v2.0 - Claude Memory

> **wiiiv** (pronounced "Weave" / 위브) - LLM Governor 기반 실행 시스템
>
> 하늘나무 / SKYTREE

---

## 프로젝트 개요

wiiiv v2.0은 **LLM Governor 기반 실행 시스템**이다.

- **v1.0 (wiiiv-automata)**: 오토마타 기반 - 별도 저장소로 보존
- **v2.0 (wiiiv)**: LLM Governor 기반 - 현재 저장소

---

## 핵심 철학

```
문서가 코드보다 상위다
코드는 문서(계약)를 구현할 뿐이다
```

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
| wiiiv API Reference | v2.0 |
| wiiiv CLI Reference | v2.0 |

---

## 프로젝트 구조

```
wiiiv/
├── docs/                    # Canonical Spec 문서
├── wiiiv-core/              # 핵심 실행 계층
│   └── src/main/kotlin/io/wiiiv/
│       ├── execution/       # Executor 계층
│       ├── runner/          # Runner 계층
│       ├── blueprint/       # Blueprint 계층
│       ├── gate/            # Gate 계층
│       ├── governor/        # Governor 계층
│       ├── dacs/            # DACS 계층
│       └── rag/             # RAG 계층 (벡터 검색)
│           ├── embedding/   # 임베딩 제공자
│           ├── vector/      # 벡터 저장소
│           ├── chunk/       # 문서 청킹
│           └── retrieval/   # 검색/리트리버
├── wiiiv-api/               # REST API 계층 (Ktor)
│   └── src/main/kotlin/io/wiiiv/api/
│       ├── config/          # 서버 설정 (Auth, CORS, Routing)
│       ├── dto/             # 요청/응답 DTO
│       │   ├── decision/    # Decision (Governor 판단)
│       │   ├── blueprint/   # Blueprint (실행 계획)
│       │   ├── execution/   # Execution (실행 결과)
│       │   ├── system/      # System (인트로스펙션)
│       │   └── rag/         # RAG (벡터 검색)
│       └── routes/          # API 라우트
├── wiiiv-cli/               # CLI 터미널 인터페이스 (Kotlin + clikt)
│   └── src/main/kotlin/io/wiiiv/cli/
│       ├── Main.kt          # 진입점
│       ├── client/          # HTTP 클라이언트 (Ktor Client)
│       ├── commands/        # CLI 명령 (auth, decision, blueprint, execution, system, config, rag)
│       └── output/          # 출력 포맷터 (human/JSON)
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

## API v2 엔드포인트 (wiiiv-api)

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

**테스트 현황: 456개 통과**

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
| wiiiv-core | BlueprintTest | 9 |
| wiiiv-core | GovernorTest | 12 |
| wiiiv-core | GateTest | 29 |
| wiiiv-core | DACSTest | 28 |
| wiiiv-core | LlmPersonaTest | 24 |
| wiiiv-core | IntegrationTest | 16 |
| wiiiv-core | E2EFlowTest | 15 |
| wiiiv-core | ParallelExecutionTest | 14 |
| wiiiv-core | RagTest | 33 |
| wiiiv-core | RagExecutorTest | 13 |
| **wiiiv-api** | **AuthRoutesTest** | **6** |
| **wiiiv-api** | **DecisionRoutesTest** | **8** |
| **wiiiv-api** | **BlueprintRoutesTest** | **8** |
| **wiiiv-api** | **ExecutionRoutesTest** | **10** |
| **wiiiv-api** | **SystemRoutesTest** | **13** |
| **wiiiv-cli** | **RagCommandTest** | **10** |
| **wiiiv-cli** | **AuthCommandTest** | **7** |
| **wiiiv-cli** | **SystemCommandTest** | **11** |
| **wiiiv-cli** | **WiiivClientTest** | **11** |
| **wiiiv-cli** | **E2EIntegrationTest** | **8** |

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
- [ ] 배포 자동화 (Docker, CI/CD)

---

*wiiiv / 하늘나무 / SKYTREE*
