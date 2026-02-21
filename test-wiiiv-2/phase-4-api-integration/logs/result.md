# Phase 4 결과 — 백엔드 API 통합 (skymall + skystock)

> 실행일: 2026-02-21
> 서버: localhost:8235 (LLM: gpt-4o-mini, RAG: 526 chunks)
> 백엔드: skymall (home.skyepub.net:9090), skystock (home.skyepub.net:9091)

## 총괄

| 결과 | 수 |
|------|---|
| **PASS** | 5 |
| **SOFT FAIL** | 5 |
| **HARD FAIL** | 0 |

---

## 사전 준비

- skymall 생존 확인: GET /api/categories → 8개 ✅
- skystock 생존 확인: 403 (인증 필요 — 정상) ✅
- RAG 주입: skymall-api-spec (16 chunks) + skystock-api-spec (17 chunks) ✅
- **Hotfix**: skymall john_doe 비밀번호 변경 감지 → API 스펙에서 기본 계정을 jane_smith로 변경

---

## Case 1: 단순 조회 (카테고리) — **PASS**
- **HLX 워크플로우 생성** ✅ (5 nodes: login → extract → get-categories → extract-categories)
- GET http://home.skyepub.net:9090/api/categories → 200 ✅
- 8개 카테고리 반환: Beauty, Books, Clothing, Electronics, Food & Beverages, Home & Kitchen, Sports & Outdoors, Toys & Games ✅
- **참고**: login 노드는 400 실패했으나 카테고리 API는 public이라 직접 성공
- Audit: API_WORKFLOW_HLX, COMPLETED ✅

## Case 2: 인증 필요 조회 (주문 목록) — **PASS**
- **HLX 자동 로그인 체인** ✅ (login → extract-token → get-orders → extract-orders)
- POST /api/auth/login (jane_smith) → 200 ✅
- GET /api/orders?page=0&size=20 (Bearer 토큰) → 200 ✅
- 주문 데이터 반환: john_doe $1,349.98, jane_smith $109.97 등 ✅
- **핵심 검증**: RAG에서 인증 정보 → 로그인 step 자동 삽입 → 토큰 추출 → API 호출 ✅
- Audit: API_WORKFLOW_HLX, COMPLETED ✅

## Case 3: 필터링 조회 (가장 비싼 전자제품 3개) — **SOFT FAIL**
- **REPLY** (EXECUTE 아님) — RAG에 상품 데이터가 있어서 API 호출 없이 답변
- 답변 정확: Laptop Pro $1,999.99, Smartphone X $999.99, 4K TV $799.99 ✅
- Hard Assert 실패: HLX 워크플로우 미생성
- **원인**: RAG에 상품 목록이 이미 있어서 Governor가 REPLY로 판단

## Case 4: 집계 조회 (카테고리별 상품 수) — **SOFT FAIL**
- **REPLY** — RAG에서 직접 답변 (테이블 형태)
- 정확한 8개 카테고리 + 상품수 + 평균가격 ✅
- Hard Assert 실패: API 호출 없음
- **동일 원인**: RAG에 요약 데이터가 있어서 불필요한 API 호출 생략 (합리적 판단이기도 함)

## Case 5: skystock 조회 (공급업체 목록) — **PASS**
- **HLX 워크플로우** ✅ (login-skystock → extract → get-suppliers → extract)
- POST http://home.skyepub.net:9091/api/auth/login (admin/admin123) → 200 ✅
- GET /api/suppliers?page=0&size=20 → 200 ✅
- 공급업체 3개 반환: Samsung Electronics, LG Electronics, Global Fashion Co. ✅
- **핵심 검증**: skymall(9090)과 skystock(9091) 별도 인증/호출 ✅
- Audit: API_WORKFLOW_HLX, COMPLETED ✅

## Case 6: 복합 (API 호출 + 파일 저장) — **SOFT FAIL**
- API 조회 성공: GET /api/categories/summary → 200 ✅
- 카테고리 + 상품수 + 평균가격 데이터 추출 ✅
- **파일 저장 실패**: HLX ACT 노드가 FILE_WRITE 대신 API 호출로 시도 → 403
- **원인**: HLX ACT 노드가 API_CALL만 지원하고 FILE_WRITE StepType을 생성하지 않음
- Audit: API_WORKFLOW_HLX, FAILED ✅ (실패도 기록됨)

## Case 7: 복합 (재고 부족 → 상세 조회) — **PASS**
- **7노드 워크플로우**: skymall login → extract → low-stock → extract → skystock login → extract → repeat ✅
- GET /api/products/low-stock?threshold=30 → 빈 배열 (현재 재고 충분) ✅
- 빈 결과에 대해 "재고가 30개 미만인 상품은 없습니다" 정확 보고 ✅
- 크로스 시스템 준비까지 완료 (skystock 로그인 성공)
- Audit: API_WORKFLOW_HLX, COMPLETED ✅

## Case 8: 크로스 시스템 (skymall + skystock) — **SOFT FAIL**
- **skymall 조회 성공**: search?keyword=laptop → Laptop Pro 15 inch ($1,999.99, 재고 31) ✅
- **8노드 워크플로우**: login-skymall → extract → search → extract → decide → get-purchase-orders → retry → end
- **skystock 발주 이력 실패**: skymall 토큰으로 skystock API 호출 시도 → 403
- **원인**: HLX가 skystock에 별도 로그인 노드를 생성하지 않음 (두 시스템의 JWT 분리 미인식)
- Audit: API_WORKFLOW_HLX, COMPLETED (부분 성공으로 처리) ✅

## Case 9: API 에러 처리 — **PASS**
- **REPLY**: "요청하신 /api/nonexistent-endpoint는 유효하지 않은 API 엔드포인트입니다" ✅
- 실행 시도 없음 ✅
- 시스템 크래시 없음 ✅
- Governor가 존재하지 않는 엔드포인트를 사전 차단

## Case 10: 멀티턴 탐색 (4턴) — **SOFT FAIL**
- Turn 1: REPLY ✅ — skymall 데이터 개요 (상품, 재고, 주문, 공급사)
- Turn 2: REPLY ✅ — 카테고리 탑3 (Electronics 7개, Clothing 6개, Books 5개) — RAG 기반
- Turn 3: REPLY ❌ — "별도의 실행이 필요하지 않습니다" (1등 카테고리 상품 리스트 미제공)
- Turn 4: REPLY ❌ — "별도의 실행이 필요하지 않습니다" (가장 비싼 상품 상세 미제공)
- **원인**: Governor가 후반 턴에서 RAG 데이터가 있음에도 답변 거부

---

## Audit 기록 종합

| executionPath | 건수 | status |
|---------------|------|--------|
| API_WORKFLOW_HLX | 7 | COMPLETED: 6, FAILED: 1 |
| DIRECT_BLUEPRINT | (Phase 2에서) | - |

- 모든 EXECUTE 턴에 1:1 Audit 레코드 ✅
- REPLY 턴에는 Audit 없음 ✅ (정상)

---

## 발견된 이슈

### Issue P4-001: RAG에 데이터가 있으면 API 호출 생략 (SOFT)
- **Cases**: 3, 4, 10
- **심각도**: LOW
- **증상**: RAG에 상품/카테고리 데이터가 있어서 API 호출 대신 REPLY로 답변
- **분석**: "최신 데이터가 필요한가?" 판단이 없음. RAG 데이터가 static이면 합리적이지만, 재고/주문처럼 실시간 변동 데이터는 API 호출이 필요
- **권장**: Governor 프롬프트에 "실시간 데이터는 API로 조회" 힌트 추가, 또는 사용자가 "실시간" 키워드 사용 시 API 호출 강제

### Issue P4-002: HLX ACT 노드가 FILE_WRITE 미지원 (MEDIUM)
- **Case**: 6
- **증상**: API 결과를 파일로 저장하라는 요청에서 FILE_WRITE 대신 API 호출로 시도
- **원인**: HLX ACT 노드의 StepType 선택이 API_CALL에 편향
- **권장**: ACT 노드 프롬프트에 FILE_WRITE, COMMAND 등 다른 StepType 예시 포함

### Issue P4-003: 크로스 시스템 JWT 분리 실패 (MEDIUM)
- **Case**: 8
- **증상**: skystock 조회 시 skymall 토큰 재사용 → 403
- **원인**: HLX가 두 시스템의 독립된 JWT를 인식하지 못하고 별도 로그인 노드 미생성
- **권장**: RAG 스펙에 "독립된 JWT, 별도 로그인 필수" 강조, 또는 HLX 생성 프롬프트에 "다중 시스템 인증 분리" 규칙 추가

### Issue P4-004: 멀티턴 후반 답변 거부 (MEDIUM)
- **Case**: 10 (Turn 3-4)
- **증상**: "별도의 실행이 필요하지 않습니다"로 답변 거부
- **원인**: Governor가 이전 턴의 컨텍스트를 참조하여 데이터를 제공해야 하는데, "실행이 필요한가?" 판단에서 빠짐

---

## 핫픽스 목록

1. **skymall API 스펙 기본 계정 변경**: john_doe → jane_smith (비밀번호 변경 감지)

## 핵심 성과

- **HLX 워크플로우 자동 생성**: 자연어 → login → extract-token → API 호출 → extract 체인 완전 자동화 ✅
- **크로스 시스템 인증**: skymall(9090) jane_smith + skystock(9091) admin 별도 인증 성공 ✅
- **Audit 완전 커버**: 모든 HLX 실행에 API_WORKFLOW_HLX 감사 기록 ✅
- **안전한 에러 처리**: 존재하지 않는 엔드포인트 사전 차단, 크래시 없음 ✅
