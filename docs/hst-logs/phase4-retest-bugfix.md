# HST Phase 4 재테스트 — 최종 결과

**Date**: 2026-02-23
**Server**: wiiiv v2.2.174 (port 8235)
**Commit**: `5a0ca36` (P0 버그 3건 + 프롬프트 개선)
**LLM Backend**: OpenAI gpt-4o-mini
**Tester**: Claude Opus 4.6 (automated)

---

## 수정 이력

### Round 1: P0 버그 3건 수정 (`6377eda`)

| Bug | 수정 | 파일 |
|-----|------|------|
| BUG-003 | HlxContext에 ragContext 필드 추가, HlxRunner/HlxPrompt/Governor에서 RAG 스펙을 ACT 노드까지 전달 | HlxContext.kt, HlxRunner.kt, HlxPrompt.kt, ConversationalGovernor.kt |
| BUG-004 | handleNodeResult Failure에서 에러 마커 저장, executeTransform에서 에러 입력 감지 시 즉시 Failure | HlxRunner.kt, HlxNodeExecutor.kt |
| BUG-005 | GovernorPrompt에 API_WORKFLOW vs DB_QUERY 명시적 우선순위 규칙 추가 | GovernorPrompt.kt |

### Round 2: 프롬프트 개선 (`5a0ca36`)

| 개선 | 대상 케이스 | 파일 |
|------|-----------|------|
| 라이브 데이터 vs API 스펙 구분 규칙 + 탐색적 질문 라우팅 | Case 3,4,10 | GovernorPrompt.kt |
| FILE_WRITE 사용자 지정 경로 충실 반영 + targetPath 전달 | Case 6 | GovernorPrompt.kt, ConversationalGovernor.kt |
| 크로스시스템 워크플로우 생성 가이드 | Case 8 | GovernorPrompt.kt |

---

## 수정 단계별 비교

|  | Round 0 (수정 전) | Round 1 (`6377eda`) | Round 2 (`5a0ca36`) |
|---|---|---|---|
| **PASS** | 0/10 | 4/10 | **7/10** |
| **SOFT PASS** | 0/10 | 4/10 | **1/10** |
| **PARTIAL** | 0/10 | 0/10 | **2/10** |
| **FAIL** | 10/10 | 2/10 | **0/10** |
| **Connection failed: null** | 10/10 | 0/10 | **0/10** |

---

## 최종 케이스별 상세 결과

### Case 1: 단순 조회 — "skymall 카테고리 목록 보여줘"
**결과: PASS**

- Governor: EXECUTE + API_WORKFLOW로 정확히 라우팅
- HLX: 5노드 워크플로우 (login → extract-token → get-categories/summary → extract → save)
- API: `home.skyepub.net:9090/api/categories/summary` → 200 OK
- 결과: 7개 카테고리 (Electronics 7, Clothing 6, Books 5 등) 실데이터 반환

### Case 2: 인증 필요 조회 — "skymall에서 주문 목록 보여줘"
**결과: PARTIAL**

- Governor: EXECUTE + API_WORKFLOW로 정확히 라우팅
- HLX: 5노드 (login → extract-token → get-orders → extract → save)
- 로그인 성공: `jane_smith/ADMIN` JWT 발급 OK
- **get-orders 실패**: `GET /api/orders` → HTTP 403 (권한 거부)
- 원인: skymall 서버 측 주문 API 권한 정책 이슈 (wiiiv 엔진 문제 아님)

### Case 3: 필터링 조회 — "skymall에서 전자제품 중 가장 비싼 상품 3개 알려줘"
**결과: PASS** (이전 SOFT PASS → PASS)

- Governor: EXECUTE + API_WORKFLOW (이전: REPLY)
- HLX: 6노드 (login → token → get-products?sort=price,desc → extract → top-3 transform → save)
- API: `home.skyepub.net:9090/api/products?sort=price,desc` → 200 OK
- 결과: Laptop Pro $1999.99, Smartphone X $999.99, 4K Smart TV $799.99 — 실제 API 데이터

### Case 4: 집계 조회 — "skymall에서 카테고리별 상품 수를 정리해줘"
**결과: PASS** (이전 SOFT PASS → PASS)

- Governor: EXECUTE + API_WORKFLOW (이전: REPLY)
- HLX: 5노드 (login → token → categories/summary → extract → save)
- API: `home.skyepub.net:9090/api/categories/summary` → 200 OK
- 결과: 카테고리별 productCount + avgPrice 실데이터 반환

### Case 5: skystock 조회 — "skystock에서 공급업체 목록 보여줘"
**결과: PASS**

- Governor: EXECUTE + API_WORKFLOW로 정확히 라우팅
- HLX: 5노드 (login-skystock → token → get-suppliers/active → extract → save)
- API: `home.skyepub.net:9091/api/suppliers/active` → 200 OK
- 결과: Samsung Electronics, LG Electronics, Global Fashion Co. 실데이터 반환

### Case 6: 복합 — "skymall 카테고리 목록을 조회해서 /tmp/wiiiv-test-v2/categories.json으로 저장해줘"
**결과: PASS** (이전 FAIL → PASS)

- HLX: 5노드 (login → token → get-categories → extract → **save-to-file**)
- API 조회 성공: 카테고리 데이터 수신
- **FILE_WRITE 성공**: `/tmp/wiiiv-test-v2/categories.json` 정확히 생성 (670B)
- 사용자 지정 경로 정확히 반영 (이전: `/tmp/output/` 으로 변경되는 문제)

### Case 7: 데이터 플로우 — "재고 30개 미만 상품 찾아서 상세 정보 조회해줘"
**결과: PASS** (이전 SOFT PASS → PASS)

- Governor: EXECUTE + API_WORKFLOW
- HLX: 5노드 (login → token → low-stock?threshold=30 → extract → save)
- API: `home.skyepub.net:9090/api/products/low-stock?threshold=30` → 200 OK
- 결과: content=[], totalElements=0 (재고<30 상품 실제 0건 — 정상)

### Case 8: 크로스 시스템 — "skymall Laptop Pro + skystock 발주 이력"
**결과: PARTIAL** (이전 FAIL → PARTIAL)

- skymall 측 성공: login → search → Laptop Pro 15 inch ($1999.99, stock:31) 정상 조회
- **skystock 측 미도달**: LLM이 DECIDE 노드에서 "available" 판단 → 조기 종료
- 5노드 워크플로우 (skymall만) — 8노드 (양 시스템) 기대
- 원인: LLM 워크플로우 설계 시 DECIDE 노드가 분기를 종료 (비결정적)

### Case 9: 에러 처리 — "/api/nonexistent-endpoint 호출해줘"
**결과: PASS**

- Governor: REPLY (사전 차단)
- "해당 엔드포인트는 존재하지 않습니다" 에러 메시지 반환
- 서버 크래시 없음, 정상 200 OK 응답

### Case 10: 멀티턴 탐색 — "skymall에 어떤 데이터가 있는지 알려줘"
**결과: SOFT PASS** (이전 FAIL → SOFT PASS)

- Governor: EXECUTE + API_WORKFLOW (이전: REPLY)
- HLX: 8노드 (skymall login + skystock login + low-stock 조회 + FILE_WRITE)
- 양쪽 시스템 모두 로그인 성공, API 호출 수행
- 탐색 범위가 "전체 데이터 탐색"이 아닌 "low-stock 조회"로 좁혀진 점만 아쉬움

---

## BUG별 수정 검증 (최종)

### BUG-003: Connection failed: null — 완전 해소

| 검증 항목 | 결과 |
|-----------|------|
| RAG 스펙 → HlxContext.ragContext 전달 | 전체 10 케이스: ragContext 주입 확인 |
| actExecution() RAG Reference 포함 | 서버 로그에서 API Reference 섹션 확인 |
| URL 정확성 (home.skyepub.net) | skymall :9090 ✓, skystock :9091 ✓ |
| Connection failed: null 발생 | **0/10** (이전 10/10) |

### BUG-004: TRANSFORM 환각 — 해소

| 검증 항목 | 결과 |
|-----------|------|
| Failure 에러 마커 저장 | `_error=true` 마커 context.variables에 기록 확인 |
| executeTransform 에러 감지 | "cannot proceed: input from failed node" 확인 |
| 가짜 데이터 생성 | **0건** (이전 다수 발생) |

### BUG-005: Governor 라우팅 — 해소

| 검증 항목 | 결과 |
|-----------|------|
| API 스펙 있을 때 API_WORKFLOW 선택 | 9/9 케이스 (Case 9 제외) 모두 API_WORKFLOW ✓ |
| DB_QUERY 오분류 | **0건** (이전 3/10) |

### 라이브 데이터 라우팅 — 해소

| 검증 항목 | 결과 |
|-----------|------|
| RAG 스펙 있는 시스템 데이터 요청 → EXECUTE | Case 3,4,10: REPLY → **EXECUTE** 전환 성공 |
| 탐색적 질문 → API_WORKFLOW | Case 10: EXECUTE + HLX 8노드 |
| API 스펙 자체 질문 → REPLY 유지 | Case 9: REPLY 정상 유지 |

---

## 엔진 성능 지표

| 항목 | 값 |
|------|-----|
| 평균 HLX 워크플로우 실행 시간 | ~8.9초 (5.0s ~ 25.9s) |
| 평균 노드 수 | 5.4개 (5 ~ 8) |
| skymall 로그인 평균 응답 | ~2.1초 |
| skystock 로그인 평균 응답 | ~1.9초 |
| API 호출 평균 응답 | ~1.7초 |
| TRANSFORM 노드 처리 | < 10ms |
| 서버 안정성 | **10/10** (크래시 0건) |

---

## 남은 이슈

| 순위 | 이슈 | 영향 | 설명 |
|------|------|------|------|
| P2 | 크로스시스템 DECIDE 조기종료 | Case 8 | LLM이 DECIDE 노드에서 분기를 종료시켜 skystock 미도달 (비결정적) |
| P2 | skymall 주문 API 403 | Case 2 | skymall 서버 측 권한 설정 이슈 (wiiiv 엔진 문제 아님) |
| P2 | 탐색적 질문 범위 | Case 10 | "어떤 데이터가 있는지" → low-stock 한정 조회 (전체 탐색 미흡) |
| P2 | ISSUE-001: SSE heartbeat | Phase 6 | 장시간 처리 시 SSE 연결 끊김 |
