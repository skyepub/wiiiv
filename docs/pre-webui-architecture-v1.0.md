# Pre-Web UI Architecture Design v1.0

> **wiiiv** - Web UI 진입 전 아키텍처 설계 합의서
>
> 작성일: 2026-02-28
> 참여: 하늘나무(Jiung Heo), Claude, ChatGPT
> 상태: **합의 완료 — 구현 대기**

---

## 개요

Web UI 구현 전에 논의가 필요한 4개 아키텍처 주제를 정리한다.
이 문서는 3자 논의(하늘나무 + Claude + GPT)를 통해 합의된 결과이다.

| # | 주제 | 핵심 결정 |
|---|------|----------|
| 1 | DACS/Gate 재설계 | GateContext 신뢰 구조화, Shadow DACS, 단계적 활성화 |
| 2 | 멀티 LLM | 3슬롯 모델, Provider enum, 로컬 LLM 지원, GHOST 검증 안내 |
| 3 | 사용자 메모리 | DB 저장, Markdown, 명시적 기억만, 오염 방지 |
| 4 | RAG 자동 로드 | 3범위 scope, DB 원본 + 메모리 벡터, 결정론적 호출 |

---

## 1. DACS / Gate 재설계

### 1.1 현재 문제

wiiiv에 **2개의 거버넌스 시스템**이 병렬 존재한다.

| 시스템 | 역할 | 상태 |
|--------|------|------|
| **State Determinism Gate** | 대화 흐름 통제 (ASK/CONFIRM/EXECUTE) | ✅ 실질적 주력 (GHOST 51/55 통과) |
| **정식 DACS → GateChain** | 실행 권한 통제 (ALLOW/DENY) | ⚠ 형식적 (4 Gate 중 실질 동작 0개) |

**구체적 문제점:**

- `isRisky()`가 대부분의 TaskType에서 `false` → DACS 거의 호출되지 않음
- HlxNodeExecutor에서 `dacsConsensus = "YES"`, `userApproved = true` 하드코딩
- ExecutionPermissionGate = PERMISSIVE, CostGate = UNLIMITED
- GateContext를 호출자가 자유롭게 구성 → "피고인이 자기 무죄 판결서를 쓰는 구조"

### 1.2 합의된 방향: C안 (역할 분리 + 실연결)

**두 시스템은 다른 관심사이므로 분리를 유지한다.**

- State Determinism Gate → **ConversationFlowController**로 이름 변경, Governor 내부 유지
- 정식 GateChain → 실행 권한 통제에 집중, 4개 Gate 모두 실연결

### 1.3 GateContext 신뢰 구조

```kotlin
// AS-IS: 아무나 아무 값으로 생성 가능
val gateContext = GateContext(dacsConsensus = "YES", userApproved = true)

// TO-BE: 신뢰 소스에서만 생성
class GateContext private constructor(...) {
    companion object {
        fun from(
            dacsResult: DACSResult,
            session: ConversationSession,
            executorMeta: ExecutorMeta?,
            projectPolicy: ProjectPolicy?
        ): GateContext { ... }
    }
}
```

**핵심**: 새 레이어(ExecutionOrchestrator) 추가가 아니라, 기존 데이터 구조를 조작 불가능하게 만든다.

### 1.4 DACS 호출 전략

| 항목 | 현재 | 변경 |
|------|------|------|
| 호출 조건 | `isRisky()` 일 때만 | **모든 EXECUTE에서 1회** |
| SimpleDACS | isRisky() 때만 | **항상 실행** (빠름) |
| LlmDACS | isRisky() 때만 | **isRisky() 때만** (비용 절감) |
| ACT 노드별 | 하드코딩 "YES" | **PermissionGate만** (DACS 재호출 없음) |

### 1.5 DACS 결과 영속화

| 저장소 | 내용 | 수명 |
|--------|------|------|
| Blueprint.specSnapshot | DACS 결과 전체 (consensus + reasons + persona opinions) | Blueprint 수명 |
| Audit DB | GateChain 결과 (통과/거부, 수치, 타임스탬프) | 영구 |
| GateContext | 런타임 일시적 | 요청 범위 |

### 1.6 GHOST 안전성 — 단계적 활성화

**기존 경로를 건드리지 않고, 옆에 추가한다.**

```
1단계: 이름 변경 (리스크 0)
       State Determinism Gate → ConversationFlowController

2단계: Shadow DACS (관찰만, 리스크 0)
       모든 EXECUTE에서 DACS 호출하되, 결과를 로그만 찍음
       기존 isRisky() 분기는 그대로 유지

3단계: GateContext 팩토리 (새 경로에만, 기존 경로 보존)
       Web UI와 함께

4단계: PermissionGate/CostGate 실연결 (새 경로에만)
       Web UI와 함께

5단계: HLX 하드코딩 제거 (fallback 유지)
       충분한 GHOST 회귀 검증 후

6단계: Shadow → 실제 차단 전환
       GHOST 회귀 검증 완료 후
```

### 1.7 Gate 실연결 목표

| Gate | 현재 | 목표 |
|------|------|------|
| DACSGate | "YES" 문자열 비교 | DACSResult 객체 검증 |
| UserApprovalGate | 항상 true | Web UI 실시간 확인 + 자동화 사전 승인 |
| PermissionGate | PERMISSIVE | ProjectPolicy.allowedStepTypes + allowedPlugins |
| CostGate | UNLIMITED | 프로젝트별 토큰 비용 한도 |

---

## 2. 멀티 LLM

### 2.1 철학

**wiiiv의 가치는 Governor-DACS-Gate-Blueprint 구조이다. LLM은 교체 가능한 부품이다.**

- 특정 벤더(OpenAI, Anthropic)를 강제하지 않는다
- 로컬 LLM(Ollama, vLLM, LM Studio)도 지원한다
- GHOST 검증 결과는 강제가 아닌 안내로 제공한다

### 2.2 모델 슬롯 (3개)

| 슬롯 | 용도 | 기본값 |
|------|------|--------|
| `governorModel` | Governor 판단, DACS(LlmDACS) | gpt-4o-mini |
| `generatorModel` | HLX/Blueprint JSON 생성 | governorModel과 동일 |
| `embeddingModel` | RAG 벡터 임베딩 | text-embedding-3-small |

**DACS 전용 슬롯 없음** — 호출 빈도 극히 낮으므로 governorModel을 따라간다.

### 2.3 Provider 타입

```kotlin
enum class LlmProviderType {
    OPENAI,       // OpenAI API
    ANTHROPIC,    // Anthropic API
    GOOGLE,       // Google AI API
    OLLAMA,       // 로컬 Ollama (OpenAI 호환)
    CUSTOM        // 커스텀 엔드포인트 (OpenAI 호환)
}
```

### 2.4 ProjectPolicy 확장

```kotlin
// 기존 ProjectPolicy에 추가
val llmProvider: LlmProviderType?   // null이면 서버 기본값
val llmBaseUrl: String?             // OLLAMA/CUSTOM 시 base URL
val governorModel: String?          // null이면 provider 기본 추천
val generatorModel: String?         // null이면 governorModel 따라감
val embeddingModel: String?         // null이면 provider 기본 embedding
```

### 2.5 API Key 관리

**우선순위: User → Project → Env (더 구체적인 것이 우선)**

```kotlin
fun resolveApiKey(userId: Long, projectId: Long, provider: String): String? {
    userKeyStore.get(userId, provider)?.let { return it }
    projectKeyStore.get(projectId, provider)?.let { return it }
    return System.getenv("${provider.uppercase()}_API_KEY")
}
```

**저장**: AES256 암호화 + `keyVersion` 컬럼 (masterKey 교체 대비)
**노출**: API/UI에서 항상 마스킹 (`sk-****abcd`)

### 2.6 로컬 LLM 지원

OpenAIProvider에 `baseUrl` 파라미터 추가 (1줄 변경):

```kotlin
// Ollama:     http://localhost:11434/v1
// vLLM:       http://192.168.1.100:8000/v1
// LM Studio:  http://localhost:1234/v1
```

기존 OpenAIProvider가 OpenAI 호환 API를 모두 커버한다.

### 2.7 Provider별 모델 추천표

| Provider | 최소 (비용 절감) | 가성비 (기본 추천) | 최고 (정확도 우선) |
|----------|----------------|------------------|-----------------|
| OpenAI | gpt-4o-mini | gpt-4o-mini | gpt-4o |
| Anthropic | claude-haiku-4-5 | claude-sonnet-4-6 | claude-opus-4-6 |
| Ollama/로컬 | llama3.2:3b | llama3.1:8b | llama3.1:70b |
| Google | gemini-2.0-flash | gemini-2.0-flash | gemini-2.5-pro |

DB/설정 파일로 관리. 모델 추가 시 코드 변경 불필요.

### 2.8 GHOST와의 관계

- GHOST 51/55 PASS는 `gpt-4o-mini` 기준
- 다른 모델 사용 시 Web UI에서 **"GHOST 미검증" 경고** 표시
- 강제가 아닌 정보 제공 — 사용자가 판단
- 모델별 PromptTemplate 분리는 **지금은 안 함** (YAGNI). 필요 시 그때 구현

### 2.9 구현 순서

```
지금 (GHOST 영향 0):
  1. OpenAIProvider에 baseUrl 파라미터 추가
  2. ProjectPolicy에 LLM 관련 필드 추가
  3. WiiivRegistry 하드코딩을 설정 조회로 변경
  4. 기본값은 현행 gpt-4o-mini 유지

Web UI와 함께:
  5. Provider 선택 UI + 모델 추천표
  6. API Key 입력/암호화/저장
  7. GHOST 검증 여부 경고 표시
```

---

## 3. 사용자 메모리

### 3.1 현재 상태

기억력 제로. 세션 종료 시 모든 컨텍스트 소실. 같은 사용자가 다시 와도 처음 만난 사람.

### 3.2 설계 원칙

| 원칙 | 설명 |
|------|------|
| **명시적 기억만** | "기억해" / "remember this" 명령으로만 저장 (자동 감지는 2단계) |
| **오염 방지** | 메모리는 컨텍스트 블록에만 주입, 판단/규칙 블록에 영향 금지 |
| **불변성** | 일반 대화·EXECUTE·DACS 중 memory write 금지, 별도 코드 경로만 허용 |
| **크기 제한** | 사용자 2000자 + 프로젝트 2000자 = 최대 4000자 (약 1000토큰) |
| **자동 요약 금지** | 초과 시 사용자에게 정리 요청. LLM 자동 요약은 오염 경로 |

### 3.3 저장소

**DB (PlatformStore 확장)** — wiiiv는 서버-클라이언트 구조이므로 파일이 아닌 DB.

```kotlin
data class UserMemory(
    val userId: Long,
    val projectId: Long?,     // null이면 사용자 전역 메모리
    val content: String,      // Markdown
    val updatedAt: String
)
```

### 3.4 포맷: Markdown

메모리의 소비자는 LLM이다. LLM은 Markdown을 가장 자연스럽게 읽고 쓴다.

```markdown
## 선호도
- 한국어 응답
- Spring Boot 기술 스택

## 시스템 정보
- skymall: home.skyepub.net:9090
- skystock: home.skyepub.net:9091

## 작업 이력
- 재고 자동화 워크플로우 생성 (2026-02-28)
```

### 3.5 프롬프트 주입 위치

```
System Rules          ← Governor 판단 규칙
Governor Rules        ← 상태 전환 규칙
DACS Rules            ← 합의 규칙
State Rules           ← State Determinism Gate 규칙
────────────────────────────────────
[User Memory]         ← 여기 (하위 컨텍스트)
[/User Memory]
────────────────────────────────────
Conversation History  ← 대화 이력
```

**메모리는 규칙 블록 아래, 대화 이력 위에 위치한다.**

### 3.6 오염 방지 경계

```
메모리가 영향을 줄 수 있는 것:
  ✅ 응답 언어/톤
  ✅ 도메인 정보 (URL, 스키마, 인증)
  ✅ 기술 스택 선호
  ✅ 이전 작업 참조

메모리가 영향을 주면 안 되는 것:
  ❌ action 결정 (ASK/CONFIRM/EXECUTE)
  ❌ DACS 합의 결과
  ❌ Gate 통과/거부
  ❌ ConversationFlowController 규칙
```

State Determinism Gate(ConversationFlowController)는 코드이므로 메모리 주입의 영향을 받지 않는다 — 구조적으로 안전.

### 3.7 API

| Method | Path | 설명 |
|--------|------|------|
| GET | `/api/v2/users/{id}/memory` | 사용자 전역 메모리 조회 |
| PUT | `/api/v2/users/{id}/memory` | 사용자 전역 메모리 수정 |
| GET | `/api/v2/projects/{id}/memory` | 프로젝트 메모리 조회 |
| PUT | `/api/v2/projects/{id}/memory` | 프로젝트 메모리 수정 |

### 3.8 구현 순서

```
엔진 작업:
  1. UserMemory 테이블 추가
  2. REST API (GET/PUT)
  3. Governor: 세션 시작 시 메모리 로드 → GovernorPrompt 주입
  4. Governor: "기억해" 감지 → 메모리 업데이트

Web UI:
  5. 메모리 조회/편집 화면 (Markdown 에디터)
```

---

## 4. RAG 자동 로드

### 4.1 현재 문제

- InMemoryVectorStore — 서버 재시작 시 벡터 전부 소실
- 자동 로딩 없음 — API 수동 호출만 가능
- scope 구분 없음 — 모든 사용자의 문서가 섞임

### 4.2 범위 모델 (Memory와 동일 계층)

| 범위 | Memory | RAG |
|------|--------|-----|
| **Global** | ❌ 없음 (개인화이므로) | ✅ 운영자가 지정, 전사 공용 |
| **User** | ✅ 사용자 메모리 | ✅ 개인 참고 문서 |
| **Project** | ✅ 프로젝트 메모리 | ✅ 프로젝트 전용 문서 |

**Global Memory가 없는 이유**: Memory는 "이 사람에 대한 기억"이므로 전역이 의미 없다.
RAG는 "참고 지식"이므로 전사 공유가 자연스럽다.

### 4.3 문서 레지스트리 (DB 영속화)

```kotlin
data class RagDocument(
    val documentId: String,
    val scope: String,           // "global" | "user:123" | "project:456"
    val title: String,
    val filePath: String?,       // 서버 로컬 파일 경로
    val content: String?,        // 직접 저장된 텍스트
    val contentHash: String,     // 변경 감지용 (SHA-256)
    val createdAt: String,
    val updatedAt: String
)
```

### 4.4 자동 재수집 (2단계)

**업로드 시**: 즉시 벡터화 + DB 저장 (현재와 동일)

**서버 재시작 시**: DB에서 복원, contentHash로 변경분만 재벡터화

```
서버 시작
  ↓
RagDocument 테이블에서 전체 목록 조회
  ↓
contentHash 비교 → 변경된 문서만 재벡터화
  ↓
InMemoryVectorStore에 적재
  ↓
서비스 시작
```

### 4.5 검색 scope 필터링

```kotlin
fun consultRag(query: String, userId: Long, projectId: Long): String? {
    val results = ragPipeline.search(query, topK = 5)
        .filter { entry ->
            val scope = entry.metadata["scope"] ?: "global"
            scope == "global"
                || scope == "user:$userId"
                || scope == "project:$projectId"
        }
    // ...
}
```

단일 VectorStore + scope 메타데이터 필터링. 별도 VectorStore 분리는 과잉 설계.

### 4.6 RAG 호출 시점: Governor 판단 기반 (결정론적)

| TaskType | RAG 호출 | 이유 |
|----------|---------|------|
| CONVERSATION / INFORMATION | ❌ | 대화일 뿐, 도메인 지식 불필요 |
| FILE_READ | ❌ | 경로가 명확 |
| API_WORKFLOW | ✅ | API 스펙, 인증 정보 필요 |
| PROJECT_CREATE | ✅ | 기술 스택, 도메인 정보 필요 |
| WORKFLOW_CREATE | ✅ | API 스펙, 인증 정보 필요 |
| DB_QUERY | ✅ | 스키마 정보 필요 |

**항상 자동 호출하지 않는 이유**: 대화의 70%는 인터뷰/확인이며 RAG 불필요. 매 턴 embedding API 호출 = 비용 + 지연.
**사용자 명시만도 아닌 이유**: 사용자가 RAG에 뭐가 있는지 모를 수 있다. Governor가 결정론적으로 판단하는 것이 안전.

### 4.7 API

| Method | Path | 설명 |
|--------|------|------|
| POST | `/api/v2/rag/global/ingest` | 전역 문서 수집 (관리자 전용) |
| POST | `/api/v2/rag/projects/{id}/ingest` | 프로젝트 문서 수집 |
| POST | `/api/v2/rag/users/{id}/ingest` | 사용자 문서 수집 |
| GET | `/api/v2/rag/documents?scope=...` | scope별 문서 목록 |
| DELETE | `/api/v2/rag/{documentId}` | 문서 삭제 |

### 4.8 구현 순서

```
엔진 작업:
  1. RagDocument 테이블 추가
  2. scope 메타데이터 기반 필터링
  3. 서버 시작 시 자동 재수집 (contentHash 기반)
  4. scope별 REST API 추가

Web UI:
  5. 파일 업로드 + 범위 선택 UI
  6. 문서 목록/삭제 관리 UI
```

---

## 전체 구현 우선순위

### Web UI 전 (GHOST 영향 0, 안전)

| 순서 | 작업 | 주제 |
|------|------|------|
| 1 | ConversationFlowController 이름 변경 | DACS/Gate |
| 2 | Shadow DACS (관찰만) | DACS/Gate |
| 3 | OpenAIProvider baseUrl 파라미터 추가 | 멀티 LLM |
| 4 | ProjectPolicy LLM 필드 추가 | 멀티 LLM |
| 5 | WiiivRegistry 하드코딩 → 설정 조회 | 멀티 LLM |
| 6 | UserMemory 테이블 + API | 사용자 메모리 |
| 7 | Governor 메모리 로드/저장 | 사용자 메모리 |
| 8 | RagDocument 테이블 + scope 필터 | RAG |
| 9 | 서버 시작 시 RAG 자동 재수집 | RAG |

### Web UI와 함께

| 순서 | 작업 | 주제 |
|------|------|------|
| 10 | GateContext 팩토리 (새 경로) | DACS/Gate |
| 11 | PermissionGate/CostGate 실연결 | DACS/Gate |
| 12 | Provider 선택 + API Key 관리 UI | 멀티 LLM |
| 13 | 메모리 편집 UI | 사용자 메모리 |
| 14 | RAG 파일 업로드 + 범위 선택 UI | RAG |

### 충분한 검증 후

| 순서 | 작업 | 주제 |
|------|------|------|
| 15 | HLX GateContext 하드코딩 제거 | DACS/Gate |
| 16 | Shadow DACS → 실제 차단 전환 | DACS/Gate |

---

## 공통 설계 원칙

1. **기존 경로를 건드리지 않는다** — 새 기능은 옆에 추가, 기존 동작 보존
2. **관찰 우선, 차단 나중** — Shadow 모드로 검증 후 활성화
3. **GHOST 회귀 검증 필수** — 구조 변경마다 최소 Z02 통과 확인
4. **YAGNI** — 필요하지 않은 추상화는 만들지 않는다
5. **벤더 중립** — LLM, Provider, 모델은 교체 가능한 부품
6. **거버넌스 오염 방지** — Memory/RAG는 컨텍스트 보조만, 판단 규칙에 간섭 금지

---

*wiiiv / 하늘나무 / SKYTREE*
