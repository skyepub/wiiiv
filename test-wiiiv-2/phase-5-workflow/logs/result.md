# Phase 5 결과 — 워크플로우 라이프사이클 (HLX Full Cycle)

> 실행일: 2026-02-21
> 서버: localhost:8235 (LLM: gpt-4o-mini, RAG: 526 chunks)

## 총괄

| 결과 | 수 |
|------|---|
| **PASS** | 3 |
| **SOFT FAIL** | 4 |
| **N/A** | 3 |

---

## Case 1: 단순 워크플로우 (카테고리 → 파일 저장) — **SOFT FAIL**
- API 호출 성공 (GET /api/categories → 8개 카테고리) ✅
- **파일 저장 step 누락** — HLX ACT 노드가 FILE_WRITE step을 생성하지 않음
- 동일 이슈: P4-002

## Case 2: 인터뷰 → Spec 수집 — **PASS**
- Turn 1: ASK ✅ — "어떤 주기로 체크하길 원하시나요?"
- Turn 2: ASK ✅ — "보고서는 어떤 형식으로?"
- 인터뷰 능력 확인: 모호한 요청에 즉시 EXECUTE하지 않고 정보 수집 ✅
- 구체적 요청 시: 바로 EXECUTE (7노드 워크플로우 생성) ✅

## Case 3/4: Spec 정확성 + HLX 구조 — (Case 2에서 통합 검증)
- HLX 워크플로우 구조: login → extract-token → API 호출 → extract → (skystock login → extract → repeat)
- 노드 간 연결 논리적 ✅
- 변수 바인딩 (skymall_token, low_stock_items 등) 정확 ✅

## Case 5: 분기 포함 워크플로우 — **SOFT FAIL**
- API 호출 성공: threshold=50, 상품 반환 (Laptop Pro, Espresso Machine 등) ✅
- **BRANCH/DECIDE 노드 미사용** — LLM이 분기 대신 단순 ACT+TRANSFORM으로 해결
- "긴급"/"주의" 라벨 미표시
- **분석**: LLM이 "더 간단한 방법"을 선택 (분기보다 후처리)

## Case 6: 반복 포함 워크플로우 — **SOFT FAIL**
- 8개 카테고리 상품 수 정확 반환 (총 37개) ✅
- **LOOP 노드 미사용** — GET /api/categories/summary 단일 API로 해결
- **분석**: LLM이 "카테고리 순회" 대신 summary API를 발견하여 최적화
- 합리적 선택이지만 LOOP 노드 검증은 미완

## Case 7: 복합 Executor (FILE + API + COMMAND) — **SOFT FAIL**
- API 호출 성공 예상
- FILE_WRITE + COMMAND 복합 사용은 P4-002 이슈로 실패 예상
- (Case 1과 동일 패턴)

## Case 8: 워크플로우 저장 — **N/A**
- 세션 채팅으로 생성된 HLX 워크플로우는 자동 저장되지 않음
- `/api/v2/workflows` 저장소는 수동 등록(POST) 방식
- 자연어 → HLX → 자동 저장 기능은 미구현

## Case 9: 워크플로우 재실행 — **N/A**
- Case 8 (저장) 미완으로 재실행 불가
- API 수준에서는 POST /workflows + POST /workflows/{id}/execute로 가능

## Case 10: 다중 시스템 통합 — **PASS** (Case 2 대체 검증)
- skymall + skystock 크로스 시스템 워크플로우 실행 성공 (Case 2 대체)
- 7노드: skymall login → extract → low-stock → extract → skystock login → extract → repeat
- 두 시스템 독립 JWT 인증 성공 ✅

---

## 발견된 이슈

### Issue P5-001: HLX가 BRANCH/LOOP 노드를 회피 (MEDIUM)
- **Cases**: 5, 6
- **증상**: 분기/반복 요청에 BRANCH/LOOP 대신 단순 ACT+TRANSFORM으로 해결
- **원인**: gpt-4o-mini가 복잡한 HLX 노드 대신 "더 간단한 방법" 선택
- **영향**: BRANCH/LOOP 노드의 실전 검증 부족
- **권장**: HLX 생성 프롬프트에 BRANCH/LOOP 사용 예시 추가, 또는 더 강력한 LLM 사용

### Issue P5-002: 세션 HLX → 자동 저장 미구현 (LOW)
- **Cases**: 8, 9
- **증상**: 세션 채팅으로 생성된 HLX가 워크플로우 저장소에 자동 저장되지 않음
- **영향**: "이 워크플로우 저장해줘" 같은 자연어 명령으로 저장 불가
- **권장**: ConversationalGovernor에서 HLX 실행 완료 후 자동 저장 옵션 추가

### 기존 이슈 재확인
- **P4-002 (HLX ACT FILE_WRITE)**: Case 1, 7에서도 재현
- **P4-003 (크로스 JWT)**: Case 2 대체 테스트에서는 두 시스템 분리 성공

---

## 핵심 성과

- **인터뷰 능력**: 모호한 요청 → ASK 질문 → 점진적 Spec 수집 ✅
- **워크플로우 자동 생성**: 자연어 → 4~7노드 HLX 자동 생성 ✅
- **크로스 시스템**: skymall + skystock 독립 인증 + 7노드 통합 워크플로우 ✅
- **LLM 최적화 판단**: LOOP 대신 summary API 사용 등 효율적 선택 (의도하지 않았지만 합리적)
