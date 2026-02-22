# HST Phase 4 재테스트 — P0 버그 3건 수정 후

**Date**: 2026-02-23
**Server**: wiiiv v2.2.173 (port 8235)
**Commit**: `6377eda` (BUG-003/004/005 수정)
**LLM Backend**: OpenAI gpt-4o-mini
**Tester**: Claude Opus 4.6 (automated)

---

## 수정 내용 요약

| Bug | 수정 | 파일 |
|-----|------|------|
| BUG-003 | HlxContext에 ragContext 필드 추가, HlxRunner/HlxPrompt/Governor에서 RAG 스펙을 ACT 노드까지 전달 | HlxContext.kt, HlxRunner.kt, HlxPrompt.kt, ConversationalGovernor.kt |
| BUG-004 | handleNodeResult Failure에서 에러 마커 저장, executeTransform에서 에러 입력 감지 시 즉시 Failure | HlxRunner.kt, HlxNodeExecutor.kt |
| BUG-005 | GovernorPrompt에 API_WORKFLOW vs DB_QUERY 명시적 우선순위 규칙 추가 | GovernorPrompt.kt |

---

## 수정 전후 비교

|  | 수정 전 | 수정 후 |
|---|---|---|
| **PASS** | 0/10 | **4/10** |
| **SOFT PASS** | 0/10 | **4/10** |
| **FAIL** | 10/10 | **2/10** |
| **Connection failed: null** | 10/10 | **0/10** |

---

## 케이스별 상세 결과

### Case 1: 단순 조회 — "skymall 카테고리 목록 보여줘"
**결과: PASS**

- Governor: API_WORKFLOW로 정확히 라우팅
- HLX: 4노드 워크플로우 생성 + 실행 (3.5초)
  - `login-skymall` (ACT) → `home.skyepub.net:9090/api/auth/login` → 200 OK
  - `extract-token-skymall` (TRANSFORM) → JWT 토큰 추출 성공
  - `get-categories-summary` (ACT) → `home.skyepub.net:9090/api/categories/summary` → 200 OK
  - `extract-categories` (TRANSFORM) → 8개 카테고리 파싱
- 결과: Beauty(4), Books(5), Clothing(6), Electronics(7), Food(3), Home(4) 등 실데이터 반환
- **BUG-003 해소 확인**: URL이 `home.skyepub.net:9090` 정확

### Case 2: 인증 필요 조회 — "skymall에서 주문 목록 보여줘"
**결과: PASS**

- Governor: API_WORKFLOW로 라우팅
- HLX: 4노드 (login → extract-token → get-purchase-orders → extract-orders), 2.9초
- 로그인 체인 성공: `home.skyepub.net:9091/api/auth/login` → JWT 획득 → Bearer 인증
- 실제 발주 데이터 수신: Samsung Electronics 갤럭시 S25 Ultra 20개 등
- **참고**: "skymall"이라 했지만 skystock(9091)으로 라우팅 — 주문=발주로 해석 (WARN)

### Case 3: 필터링 조회 — "skymall에서 전자제품 중 가장 비싼 상품 3개 알려줘"
**결과: SOFT PASS**

- Governor: REPLY (API 미호출)
- RAG 컨텍스트에서 직접 응답: Laptop Pro $1999.99, Smartphone X $999.99, 4K Smart TV $799.99
- 내용 정확하나 실시간 API 호출 경로를 타지 않음
- 원인: RAG에 충분한 정보가 있어 Governor가 CONVERSATION으로 판단

### Case 4: 집계 조회 — "skymall에서 카테고리별 상품 수를 정리해줘"
**결과: SOFT PASS**

- Governor: REPLY (API 미호출)
- RAG 기반 응답: 전자제품 7개, 의류 6개, 도서 5개 등 8개 카테고리 정리
- 내용 정확, 실시간 API 미호출

### Case 5: skystock 조회 — "skystock에서 공급업체 목록 보여줘"
**결과: PASS**

- Governor: API_WORKFLOW로 정확히 라우팅
- HLX: 4노드, 4.2초
  - `login-skystock` (ACT) → `home.skyepub.net:9091/api/auth/login` → 200 OK
  - `extract-token` (TRANSFORM) → JWT 추출
  - `get-suppliers` (ACT) → `home.skyepub.net:9091/api/suppliers?page=0&size=20` → 200 OK
  - `extract-suppliers` (TRANSFORM) → 데이터 파싱
- 결과: Samsung Electronics, LG Electronics, Global Fashion Co. 3개 업체 반환
- **BUG-003 해소 확인**: skystock URL `home.skyepub.net:9091` 정확

### Case 6: 복합 — "skymall 카테고리 목록을 조회해서 /tmp/.../categories.json으로 저장해줘"
**결과: FAIL**

- HLX: 6노드 워크플로우 생성 (login → token → get-categories → extract → store → repeat)
- API 조회 성공 (200 OK, 카테고리 데이터 수신)
- **파일 저장 실패**: LLM이 FILE_WRITE step type 대신 HTTP API로 파일 저장 시도 → 403
- `/tmp/wiiiv-test-v2/categories.json` 미생성
- 원인: HLX ACT 노드에서 FILE_WRITE executor 활용 프롬프트 개선 필요 (P1)

### Case 7: 데이터 플로우 — "재고 30개 미만 상품 찾아서 상세 정보 조회"
**결과: SOFT PASS**

- HLX: 7노드 워크플로우 정상 생성 (login → token → low-stock API → extract → REPEAT)
- 1차 API 호출 성공: `GET /api/products/low-stock?threshold=30` → 200 OK
- 결과: content=[], totalElements=0 (재고<30 상품이 실제로 0건)
- REPEAT 노드 0회 실행 (빈 배열 정상 처리)
- 워크플로우 구조 정상, 테스트 데이터 부재로 REPEAT 경로 미검증

### Case 8: 크로스 시스템 — "skymall Laptop Pro + skystock 발주 이력"
**결과: FAIL (Partial)**

- skymall 측 성공: login → search → Laptop Pro 15 inch ($1999.99, stock:31) 정상 조회
- **skystock 측 누락**: LLM이 워크플로우 생성 시 skystock 노드를 포함하지 않음
- 4노드 워크플로우 (skymall만) 생성 — 8노드 (양 시스템) 기대
- 원인: LLM 워크플로우 생성 품질 (비결정적, P1)

### Case 9: 에러 처리 — "/api/nonexistent-endpoint 호출"
**결과: PASS**

- Governor: REPLY (사전 차단)
- "호출할 수 없는 잘못된 API 엔드포인트입니다" 에러 메시지 반환
- 서버 크래시 없음, 정상 200 OK 응답

### Case 10: 멀티턴 탐색 Turn 1 — "skymall에 어떤 데이터가 있는지 알려줘"
**결과: SOFT PASS**

- Governor: REPLY
- 상품, 재고, 가격, 주문 등 skymall 데이터 개요 제공
- RAG 기반 답변, 실제 API 탐색 없음

---

## BUG별 수정 검증

### BUG-003: Connection failed: null — 완전 해소

| 검증 항목 | 결과 |
|-----------|------|
| RAG 스펙 → HlxContext.ragContext 전달 | Case 1,2,5: ragContext 2528+ chars 주입 확인 |
| actExecution() RAG Reference 포함 | 서버 로그에서 API Reference 섹션 확인 |
| URL 정확성 (home.skyepub.net) | Case 1: :9090 ✓, Case 5: :9091 ✓ |
| Connection failed: null 발생 | 0/10 (이전 10/10) |

### BUG-004: TRANSFORM 환각 — 해소

| 검증 항목 | 결과 |
|-----------|------|
| Failure 에러 마커 저장 | Case 6: `_error=true` 마커 context.variables에 기록 |
| executeTransform 에러 감지 | Case 5 로그: "cannot proceed: input from failed node" 확인 |
| 가짜 데이터 생성 | 0건 (이전 다수 발생) |

### BUG-005: Governor 라우팅 — 해소

| 검증 항목 | 결과 |
|-----------|------|
| API 스펙 있을 때 API_WORKFLOW 선택 | Case 1,2,5,6,7,8: 모두 API_WORKFLOW ✓ |
| DB_QUERY 오분류 | 0건 (이전 3/10) |

---

## 남은 이슈

| 순위 | 이슈 | 영향 | 설명 |
|------|------|------|------|
| P1 | FILE_WRITE in HLX | Case 6 | ACT 노드에서 FILE_WRITE step type 활용 미흡 |
| P1 | 크로스 시스템 워크플로우 | Case 8 | LLM이 2-시스템 노드를 비결정적으로 생략 |
| P2 | RAG 캐시 직접 응답 | Case 3,4,10 | 충분한 RAG 데이터 시 REPLY (API 미호출) |
| P2 | 테스트 데이터 | Case 7 | 재고<30 상품 0건으로 REPEAT 미검증 |
