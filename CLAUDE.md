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
│       └── dacs/            # DACS 계층
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

**테스트 현황: 322개 통과**

| 테스트 | 개수 |
|--------|------|
| ExecutorTest | 8 |
| FileExecutorTest | 14 |
| CommandExecutorTest | 18 |
| ApiExecutorTest | 20 |
| LlmExecutorTest | 23 |
| LlmProviderTest | 16 |
| DbExecutorTest | 26 |
| WebSocketExecutorTest | 12 |
| MessageQueueExecutorTest | 19 |
| GrpcExecutorTest | 19 |
| BlueprintTest | 9 |
| GovernorTest | 12 |
| GateTest | 29 |
| DACSTest | 28 |
| LlmPersonaTest | 24 |
| IntegrationTest | 16 |
| E2EFlowTest | 15 |
| ParallelExecutionTest | 14 |

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

---

*wiiiv / 하늘나무 / SKYTREE*
