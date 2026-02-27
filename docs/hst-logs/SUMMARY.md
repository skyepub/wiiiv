# HST v2 종합 리포트

**Date**: 2026-02-23
**Server**: wiiiv v2.2.174 (port 8235)
**LLM Backend**: OpenAI gpt-4o-mini
**Tester**: Claude Opus 4.6 (automated)
**Commit**: `5a0ca36` (P0 수정 + Phase 4 프롬프트 개선 포함)

---

## 전체 결과 요약

| Phase | 총 Cases | PASS | PARTIAL/WARN | FAIL/BLOCKED | 비고 |
|-------|---------|------|-------------|-------------|------|
| 1 대화 지능 | 10 | **10** | 0 | 0 | BUG-002 수정 후 전원 통과 |
| 2 기본 실행 | 10 | 7 | 3 WARN | 0 | 설계 동작 확인 |
| 3 RAG 통합 | 8 | **8** | 0 | 0 | 환각 방지 완벽 |
| 4 API 통합 | 10 | **7** | 1 SOFT + 2 PARTIAL | **0** | P0+프롬프트 수정 → FAIL 0건 |
| 5 워크플로우 | 10 | **8** | 2 PARTIAL | 0 | BUG-003 수정 후 재테스트 완료 |
| 6 코드 생성 | 8 | 5 | 3 PARTIAL | 0 | 단일파일 안정, 멀티턴 불안정 |
| 7 거버넌스 | 8 | **8** | 0 | 0 | DACS/Audit/세션격리 완벽 |
| **합계** | **64** | **53** | **9** | **2→0** | **82.8% PASS** |

---

## HST v3 (Latest)

> 상세: [hst-v3-report.md](hst-v3-report.md)

| Phase | v2 | v3 | 변화 |
|-------|-----|-----|------|
| 1 대화 지능 | 10/10 | **10/10** | 유지 |
| 2 기본 실행 | 7/10 | **10/10** | UTF-8+stdout 수정 |
| 3 RAG 통합 | 8/8 | **10/10** | +2, 소스 전환 |
| 4 API 통합 | 7/10 | **10/10** | PARTIAL 해소 |
| 5 워크플로우 | 8/10 | **10/10** | FAIL/BLOCKED 해소 |
| 6 코드 생성 | 5/8 | **10/10** | +5, PARTIAL 해소 |
| 7 거버넌스 | 8/8 | **10/10** | +2, 인트로스펙션 추가 |
| **합계** | **53/64 (82.8%)** | **70/70 (100%)** | **+17.2%p** |

### v3 주요 수정
- UTF-8 SSE 파서 (한글 멀티바이트 버퍼링)
- executionSummary stdout 검증 (response → artifacts.stdout)
- Audit API 파싱 (data.records[] + /stats 폴백)
- RAG 소스 전환 (보험 PDF → skymall+skystock API 스펙)

---

## 발견된 버그 및 수정

### BUG-002: 실행 후 이전 대화 주제 복귀 ASK 반환 (수정완료)

**Phase**: 1, Case 6
**증상**: "코루틴 설명해줘" → REPLY → "파일 써줘" → EXECUTE → "아까 coroutine 이야기 계속해줘" → **ASK** (기대: REPLY)
**원인**: GovernorPrompt에 "이전 대화 주제 이어가기 → REPLY" 가이드 부재
**수정**: GovernorPrompt.kt에 명시적 규칙 + Few-Shot 예시 6 추가
**커밋**: `320693f`
**재테스트**: PASS

### BUG-003: HLX API URL "Connection failed: null" (수정완료)

**Phase**: 4, 5
**증상**: HLX ACT 노드에서 외부 API 호출 시 URL이 null
**원인**: RAG에서 추출한 API 스펙이 HLX 워크플로우 실행 시 ACT 노드에 전달되지 않음
**수정**: HlxContext에 ragContext 필드 추가, HlxRunner.run()→HlxPrompt.actExecution()까지 전달
**커밋**: `6377eda`
**재테스트**: Phase 4 전체 10케이스 URL 정확, Connection failed **0건**

### BUG-004: TRANSFORM 노드 환각 (수정완료)

**Phase**: 4
**증상**: 모든 ACT 노드가 FAIL인데도 TRANSFORM 노드가 가짜 데이터 생성
**원인**: Failure 시 output 변수에 아무것도 쓰지 않아 후속 노드가 stale 데이터로 환각
**수정**: handleNodeResult Failure에서 에러 마커 저장 + executeTransform에서 에러 입력 감지 시 즉시 Failure
**커밋**: `6377eda`
**재테스트**: "cannot proceed: input from failed node" 에러 전파 확인, 환각 **0건**

### BUG-005: Governor API vs DB_QUERY 잘못된 라우팅 (수정완료)

**Phase**: 4
**증상**: RAG에 API 스펙이 있음에도 DB_QUERY 경로 선택 (3/10 케이스)
**원인**: GovernorPrompt의 패턴 매칭에서 "조회", "검색" 키워드가 DB_QUERY로 먼저 매칭
**수정**: GovernorPrompt에 API_WORKFLOW vs DB_QUERY 명시적 우선순위 규칙 추가
**커밋**: `6377eda`
**재테스트**: 10/10 케이스 모두 올바른 라우팅 확인, DB_QUERY 오분류 **0건**

### BUG-006: 라이브 데이터 요청을 REPLY로 잘못 라우팅 (수정완료)

**Phase**: 4, Case 3,4,10
**증상**: RAG에 API 스펙이 있는 시스템의 실제 데이터 요청을 REPLY로 처리 (API 미호출)
**원인**: GovernorPrompt에 "라이브 데이터 vs API 스펙 구분" 규칙 부재
**수정**: 라이브 데이터 라우팅 규칙 + 탐색적 질문 few-shot 예시 추가
**커밋**: `5a0ca36`
**재테스트**: Case 3,4 PASS (EXECUTE), Case 10 SOFT PASS (EXECUTE)

### BUG-007: HLX FILE_WRITE 경로 무시 (수정완료)

**Phase**: 4, Case 6
**증상**: 사용자 지정 파일 경로를 무시하고 `/tmp/output/`에 저장
**원인**: HLX 워크플로우 생성 시 사용자 경로를 LLM에 충분히 전달하지 않음
**수정**: hlxApiGenerationPrompt에 targetPath 파라미터 추가, FILE_WRITE 경로 충실도 가이드 강화
**커밋**: `5a0ca36`
**재테스트**: `/tmp/wiiiv-test-v2/categories.json` 정확 생성 (670B)

### ISSUE-001: SSE heartbeat 미전송 (미수정 — P2)

**Phase**: 6
**증상**: 장시간 LLM 처리(10초+) 시 SSE 연결 끊김 (curl exit 52)
**원인**: LLM 응답 대기 중 heartbeat/keep-alive 미전송
**영향**: 멀티턴 코드 생성의 클라이언트 측 타임아웃

---

*wiiiv / 하늘나무 / SKYTREE*
