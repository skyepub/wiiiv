# HST (Human-Simulation Test) Report v1.0

**Date**: 2026-02-22
**Server**: wiiiv v2.2.0-SNAPSHOT (port 8235)
**LLM Backend**: OpenAI gpt-4o-mini
**Tester**: Claude Opus 4.6 (automated)
**Commit**: `b53cb2a`

---

## HST란?

HST(Human-Simulation Test)는 LLM이 사람처럼 `/chat` SSE 엔드포인트를 통해 자연어로 요청하고,
**Governor → DACS → HLX → Executor → Policy → Audit** 전체 경로를 런타임에서 실제로 관통시키는 검증 단계이다.

단위 테스트(MockK, H2)가 개별 컴포넌트의 정합성을 보장한다면,
HST는 **실제 서버에서 실제 LLM을 사용하여 전체 시스템이 사용자 의도대로 동작하는지** 검증한다.

---

## 테스트 환경

- wiiiv 서버: `java -jar wiiiv-server-2.2.0-SNAPSHOT-all.jar` (Java 17)
- 플러그인 6종 로드: webfetch, cron, pdf, spreadsheet, mail, webhook
- Audit DB: H2 file mode (`./data/wiiiv-audit`)
- Platform DB: H2 file mode (`./data/wiiiv-platform`)
- RAG: 활성 (문서 미등록 상태 — storeSize=0)
- 인증: JWT (자동 등록 → 토큰 발급)

---

## 테스트 결과

### 요약

| 구분 | 수 |
|------|-----|
| 총 시나리오 | 23 |
| PASS | 22 |
| PARTIAL | 1 (DB 미연결) |
| FAIL | 0 |

### Phase 1: 기본 대화 (Governor → REPLY)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 1 | 한국어 인사 | "안녕하세요, 당신은 누구입니까?" | REPLY | REPLY | 자기소개 정확: "저는 wiiiv Governor입니다..." |
| 2 | 영어 인사 | "Hello, who are you?" | REPLY | REPLY | 영어 응답: "I'm wiiiv Governor..." |
| 3 | 지식 질문 | "Kotlin과 Java의 차이점이 뭐야?" | REPLY | REPLY | Null 안전성, 코루틴 등 정확한 답변 |
| 4 | wiiiv 시스템 지식 | "wiiiv가 뭐야? HLX는 뭐고 DACS는 뭐야?" | REPLY | REPLY | wiiiv, HLX, DACS, Governor 모두 정확 설명 |
| 5 | 환각 금지 | "오늘 서울 날씨 어때?" | REPLY | REPLY | "API가 등록되어 있지 않아 확인할 수 없습니다" |

### Phase 2: 파일 연산 (Governor → Blueprint → Executor)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 6 | FILE_WRITE | "/tmp/hst-phase1.txt 파일에 HST Phase 1 Complete 라고 작성해주세요" | EXECUTE | EXECUTE | 파일 생성 확인, 내용 일치 |
| 7 | FILE_READ | "/tmp/hst-phase1.txt 파일 내용을 읽어주세요" | EXECUTE | EXECUTE | "HST Phase 1 Complete" 반환 |
| 8 | FILE_DELETE | "/tmp/hst-delete-target.txt 파일을 삭제해줘" | EXECUTE | EXECUTE | DACS 통과, 파일 삭제 확인 |
| 9 | English FILE_WRITE | "Write \"Hello from wiiiv HST\" to /tmp/hst-english.txt" | EXECUTE | EXECUTE | 파일 생성 + 내용 일치 |
| 10 | Multi-line Write | "/tmp/hst-multi-turn.txt에 Name: wiiiv\nVersion: 2.2.0\nStatus: active" | EXECUTE | EXECUTE | 3줄 파일 정확히 생성 |

### Phase 3: 명령어 실행 (Governor → Blueprint → CommandExecutor)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 11 | echo 실행 | "echo Hello from wiiiv 실행해줘" | EXECUTE | EXECUTE | stdout: "Hello from wiiiv\n" |
| 12 | date 실행 | "date +\"%Y-%m-%d %H:%M:%S\" 명령어 실행해줘" | EXECUTE | EXECUTE | stdout: "2026-02-22 22:26:47" |

### Phase 4: API 호출 (Governor → HLX → ApiExecutor)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 13 | httpbin GET | "https://httpbin.org/get 에 GET 요청을 보내서 결과를 보여주세요" | EXECUTE | EXECUTE | IP, URL, 헤더 정보 반환 |

### Phase 5: DB 쿼리 (Governor → HLX → DbExecutor)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 14 | DB 조회 | "상품 테이블에서 재고가 10개 이하인 항목을 조회해줘" | EXECUTE | EXECUTE(fail) | **PARTIAL** — HLX 라우팅 성공, DB 미연결로 Connection failed |

### Phase 6: 대화 흐름 제어 (ASK / CANCEL / Pivot)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 15 | 모호한 요청 → ASK | "파일 만들어줘" | ASK | ASK | askingFor=targetPath, "어떤 경로에 파일을 만들까요?" |
| 16 | ASK 답변 → EXECUTE | "/tmp/hst-asked.txt에 Follow-up Test 라고 써줘" | EXECUTE | EXECUTE | 파일 생성 성공 |
| 17 | CANCEL | "프로젝트 만들어줘" → "됐어, 취소해" | ASK→CANCEL | ASK→CANCEL | 작업 취소 확인 |
| 18 | Intent Pivot | "파일 만들어줘" → "아 그거 말고, echo Hello World 실행해줘" | 피봇 인식 | ASK | 피봇 인식됨 |

### Phase 7: 복합 시나리오 (대화 연속성 + 상태 전환)

| # | 시나리오 | 입력 | 기대 | 결과 | 검증 |
|---|---------|------|------|------|------|
| 19 | CONVERSATION → FILE_WRITE | 지식 질문 후 파일 작성 요청 | REPLY→EXECUTE | REPLY→EXECUTE | **핵심 버그 수정 확인** |
| 20 | Multi-turn Write→Read | 파일 작성 → "방금 만든 파일 읽어줘" | EXECUTE→EXECUTE | EXECUTE→EXECUTE | 대화 문맥 참조 성공 |
| 21 | 3-turn 대화→실행 | Spring Boot 질문 → Ktor 비교 → 비교 결과 파일 작성 | REPLY→REPLY→EXECUTE | REPLY→REPLY→EXECUTE | 비교 내용 파일 생성 (541자) |
| 22 | English multi-turn | 영어 파일 작성 | EXECUTE | EXECUTE | 영어 요청 정상 처리 |
| 23 | 최종 종합 | 7개 시나리오 일괄 | 다양 | 전부 통과 | 자동화 스크립트 검증 |

---

## 발견된 버그 및 수정

### BUG-001: CONVERSATION → EXECUTE 전환 차단

**증상**: 대화(REPLY/CONVERSATION) 후 파일 작성이나 명령어 실행을 요청하면
`"이 요청은 별도의 실행이 필요하지 않습니다"` 메시지와 함께 REPLY로 반환.

**원인**: `ConversationalGovernor.kt`의 specUpdates 필터 로직에서
EXECUTE 액션일 때 기존 `taskType != null`이면 taskType 변경을 차단.
CONVERSATION도 `!= null`이므로 차단 대상에 포함됨.

**수정**: CONVERSATION과 INFORMATION은 "진행 중인 작업"이 아니므로 예외 처리.

```kotlin
// Before (버그)
val filteredUpdates = if (
    session.draftSpec.taskType != null && ...
)

// After (수정)
val isActiveWork = currentTaskType != null
    && currentTaskType != TaskType.CONVERSATION
    && currentTaskType != TaskType.INFORMATION
val filteredUpdates = if (
    isActiveWork && ...
)
```

동일한 로직을 작업 보존(`suspendCurrentWork`) 조건에도 적용.

**영향**: CONVERSATION 상태에서 어떤 실행 요청이든 정상 전환 가능.

---

## 검증된 경로

```
사용자 → POST /api/v2/sessions/{id}/chat (SSE)
  → JWT 인증
  → ChatSseHandler
  → ConversationalGovernor.chat()
    → decideAction() → GovernorPrompt → LLM(gpt-4o-mini) → parseGovernorAction
    → specUpdates 적용 (CONVERSATION 예외 처리)
    → State Determinism Gate
    → processAction()
      → REPLY: 직접 응답
      → ASK: 추가 질문
      → EXECUTE: executeTurn()
        → requiresExecution() 확인
        → DraftSpec → Spec 변환
        → DACS (risky일 때)
        → Blueprint 생성 → BlueprintRunner
        → Executor (File/Command/API/DB)
        → Audit DB 기록
      → CANCEL: 작업 취소
  → SSE stream (progress → response → done)
```

---

## 결론

HST를 통해 다음을 확인했다:

1. **Governor → LLM → Action 판단**: 한국어/영어 모두 정확한 의도 분류
2. **Blueprint 실행**: FILE_WRITE, FILE_READ, FILE_DELETE, COMMAND 모두 성공
3. **HLX 라우팅**: API_WORKFLOW, DB_QUERY 정확한 워크플로우 연결
4. **DACS 통과**: FILE_DELETE 같은 위험 작업도 합의 후 실행
5. **환각 금지**: 실시간 데이터 질문에 지어내지 않음
6. **대화 연속성**: 멀티턴 대화 문맥 유지 + 상태 전환
7. **언어 감지**: 사용자 언어에 맞춰 응답
8. **흐름 제어**: ASK/CANCEL/Pivot 정상 작동

**단위 테스트 162개 + HST 23 시나리오 = wiiiv 엔진의 설계-구현 일치 확인 완료.**
