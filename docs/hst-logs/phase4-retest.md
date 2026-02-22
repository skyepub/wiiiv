# Phase 4 API Integration — SOLO RE-TEST (10 Cases)

**Date**: 2026-02-23
**Tester**: hst3@test.com (SOLO — freshly restarted server)
**Server**: wiiiv @ localhost:8235
**Backends**: skymall (home.skyepub.net:9090 — 200 OK), skystock (home.skyepub.net:9091 — 403 OK)

---

## Summary

| # | Case | Action | Status | Hard Assert | Issue |
|---|------|--------|--------|-------------|-------|
| 1 | skymall 카테고리 목록 | EXECUTE | FAILED | FAIL | Connection failed: null (login + get-categories) |
| 2 | skymall 주문 목록 | EXECUTE | OK (hollow) | FAIL | Connection failed: null, "성공" 메시지이나 실제 데이터 없음 |
| 3 | 전자제품 비싼 상품 3개 | EXECUTE | FAILED | FAIL | HTTP 404 — DB Query 경로 사용 (API 아닌 DB 시도) |
| 4 | 카테고리별 상품 수 | ASK | N/A | SOFT FAIL | EXECUTE 기대했으나 ASK로 분류 (추가 정보 요청) |
| 5 | skystock 공급업체 목록 | EXECUTE | FAILED | FAIL | Connection failed: null — DB Query 경로 시도 |
| 6 | 카테고리→파일 저장 | EXECUTE | OK (hollow) | FAIL | Connection failed: null, 파일 미생성 |
| 7 | 재고 30 미만 상품 상세 | EXECUTE | FAILED | FAIL | HTTP 404 — DB Query 경로 시도 |
| 8 | Laptop Pro + skystock 발주 | EXECUTE | OK (hollow) | FAIL | Connection failed: null, 허구 데이터 생성 (hallucination) |
| 9 | nonexistent endpoint | EXECUTE | OK (hollow) | FAIL | Connection failed: null, "성공" 메시지 허위 |
| 10-T1 | skymall 데이터 안내 | REPLY | OK | PASS | 정상 — 일반 안내 응답 |
| 10-T2 | 카테고리 목록 | EXECUTE | FAILED | FAIL | Connection failed: null — DB Query 경로 |
| 10-T3 | 첫번째 카테고리 상품 | EXECUTE | FAILED | FAIL | Connection failed: null — DB Query 경로 |
| 10-T4 | 가장 비싼 상품 상세 | REPLY | N/A | SOFT FAIL | DB 연결 문제 안내 (graceful degradation) |

**Overall: 0/10 PASS (10-T1 REPLY는 기대 행동), 0개 API 호출 성공**

---

## Critical Findings

### 1. "Connection failed: null" — 핵심 장애

모든 EXECUTE 케이스에서 **Connection failed: null** 발생. 이는 HLX ACT 노드의 API Executor가 실제 HTTP 요청을 보낼 때 연결 대상 URL이 `null`임을 의미합니다.

**근본 원인 추정**: HLX 워크플로우가 생성한 Blueprint step에서 `url` 필드가 null로 설정되거나, API Executor가 RAG에서 가져온 base URL(home.skyepub.net:9090)을 제대로 주입하지 못하고 있음.

- 워크플로우 자체는 올바른 구조(login → extract-token → get-data → transform)로 생성됨
- 그러나 **실제 HTTP 호출 시 URL이 null**이라 모든 요청 실패

### 2. 잘못된 라우팅: DB Query 경로

Case 3, 5, 7, 10-T2, 10-T3에서 API 호출이 아닌 **DB Query** 워크플로우로 라우팅됨.
- skymall/skystock은 REST API 시스템 — DB 직접 접근이 아님
- RAG에 API 스펙이 있음에도 Governor가 DB 경로를 선택하는 문제

### 3. Hallucination (허구 데이터)

Case 2, 8, 9에서 **Status: OK**로 보고되나 실제로는:
- 모든 ACT 노드가 FAIL
- TRANSFORM 노드만 OK (LLM이 빈 데이터에서 허구 생성)
- 사용자에게 "성공적으로 조회했습니다" 메시지 전달 — **심각한 신뢰성 문제**

### 4. Case 4 — ASK로 분류

"카테고리별 상품 수를 정리해줘"는 충분히 구체적인 요청이나 ASK로 분류됨.
- Governor가 실행 가능한 요청으로 인식하지 못함

---

## Detailed Case Logs

### Case 1: skymall 카테고리 목록
- **Session**: `a64ea68b-2d2f-48ba-aa66-50c8654cfe8a`
- **Action**: EXECUTE
- **Workflow**: Skymall Category List Retrieval (6 nodes, 10.5s)
- **Result**: FAILED
- **Nodes**:
  - [FAIL] login-skymall (ACT) 2.1s — Connection failed: null
  - [FAIL] login-skymall (ACT) 1.9s — Connection failed: null (retry)
  - [OK] extract-token (TRANSFORM) 0.7s
  - [FAIL] get-categories (ACT) 1.5s — Connection failed: null
  - [FAIL] get-categories (ACT) 1.2s — Connection failed: null (retry)
  - [FAIL] extract-categories (TRANSFORM) 1.1s — JSON parse error
- **Note**: 카테고리 API는 인증 불필요(GET /api/categories = 200). 불필요한 로그인 단계 포함.

### Case 2: skymall 주문 목록
- **Session**: `76624ae3-65db-42f4-8e95-b7d7824c89ba`
- **Action**: EXECUTE
- **Workflow**: Skymall 주문 목록 조회 (6 nodes, 9.6s)
- **Result**: Status OK (허위 — 모든 ACT 노드 FAIL)
- **Message**: "성공적으로 변환되어 제공됩니다" — **hallucination**
- **Nodes**: login FAIL x2, extract-token OK, get-orders FAIL x2

### Case 3: 전자제품 비싼 상품 3개
- **Session**: `21be3089-b2fc-442d-a408-03f514f2afc5`
- **Action**: EXECUTE
- **Workflow**: DB Query (1 node, 1.5s)
- **Result**: FAILED — HTTP 404
- **Issue**: API 대신 DB Query 경로로 잘못 라우팅

### Case 4: 카테고리별 상품 수
- **Session**: `7c167737-c58b-4f4c-8b4c-c432cd69c1f1`
- **Action**: ASK
- **Message**: "어떤 카테고리의 상품 수를 정리해드릴까요?"
- **Issue**: EXECUTE 기대했으나 ASK로 분류

### Case 5: skystock 공급업체 목록
- **Session**: `b7183566-07f1-4a3e-9fe6-fe448b956708`
- **Action**: EXECUTE
- **Workflow**: DB Query (1 node, 1.5s)
- **Result**: FAILED — Connection failed: null
- **Issue**: API 대신 DB Query 경로로 잘못 라우팅

### Case 6: 카테고리→파일 저장
- **Session**: `162cdbbf-16e8-43be-882b-4ee2bb231de6`
- **Action**: EXECUTE
- **Workflow**: Skymall Category List Retrieval and Save (8 nodes, 15.6s)
- **Result**: Status OK (허위 — ACT 노드 전부 FAIL)
- **File**: `/tmp/wiiiv-test-v2/categories.json` — **NOT CREATED**
- **Message**: "카테고리가 없는 상태입니다"

### Case 7: 재고 30 미만 상품 상세
- **Session**: `69df89ef-12b8-4e01-95c1-f3c2cd25cae3`
- **Action**: EXECUTE
- **Workflow**: DB Query (1 node, 1.6s)
- **Result**: FAILED — HTTP 404
- **Issue**: API 대신 DB Query 경로로 잘못 라우팅

### Case 8: Laptop Pro + skystock 발주
- **Session**: `78ed75ff-2655-4f3f-b1fa-b487bce6534c`
- **Action**: EXECUTE
- **Workflow**: Laptop Pro 상품 정보 조회 (6 nodes, 10.5s)
- **Result**: Status OK (허위)
- **Message**: 상세 스펙 나열 (15인치, i7, 16GB, 512GB SSD) — **전부 hallucination**
- **Issue**: skystock 발주 이력 조회 미수행 (단일 시스템만 시도)

### Case 9: nonexistent endpoint
- **Session**: `03fdeb3a-0533-47bc-b994-d9dd1b86128d`
- **Action**: EXECUTE
- **Workflow**: Skymall API Workflow (7 nodes, 10.5s)
- **Result**: Status OK (허위)
- **Message**: "성공적으로 호출했습니다" — **hallucination**
- **Issue**: Connection failed: null이므로 404 에러 핸들링 테스트 불가

### Case 10: Multi-turn (4 turns)
- **Session**: `00020f33-11d4-4807-82a6-8c990158b151`
- **T1**: REPLY — "skymall은 상품, 주문, 고객 정보 등..." (정상 안내)
- **T2**: EXECUTE — DB Query FAILED (Connection failed: null)
- **T3**: EXECUTE — DB Query FAILED (Connection failed: null)
- **T4**: REPLY — "DB 연결 문제" 안내 (graceful degradation)

---

## Root Cause Analysis

### Primary: API Executor URL Resolution Failure

```
Connection failed: null
```

HLX ACT 노드가 API Executor를 호출할 때, 대상 URL이 null로 전달됨. 가능한 원인:

1. **Blueprint step의 url 필드 미설정**: LLM이 워크플로우 생성 시 URL을 placeholder로 남김
2. **RAG context → Blueprint 변환 실패**: RAG에서 `home.skyepub.net:9090` 정보를 가져왔으나 Blueprint step에 주입하지 못함
3. **API Executor config 문제**: baseUrl 설정이 누락되어 null 반환

### Secondary: Incorrect Route Selection (DB Query)

일부 케이스에서 Governor가 API 경로 대신 DB Query 경로를 선택함:
- RAG에 API 스펙이 있음에도 DB 경로 선택
- Governor의 RouteMode 결정 로직 재검토 필요

### Tertiary: Hallucination in TRANSFORM Nodes

ACT 노드가 실패해도 TRANSFORM 노드(LLM 기반)가 빈 데이터에서 그럴듯한 응답을 생성하여 Status: OK로 보고. 이는:
- 사용자가 성공으로 오인할 수 있음
- TRANSFORM 노드에 입력 데이터 유효성 검증 필요

---

## Action Items (Priority Order)

1. **P0 — Connection failed: null 수정**: API Executor가 RAG context의 base URL을 올바르게 주입하도록 수정
2. **P0 — Hallucination 방지**: ACT 노드 전부 FAIL 시 워크플로우를 FAILED로 보고 (TRANSFORM OK만으로 성공 판정 금지)
3. **P1 — Route 결정 개선**: skymall/skystock RAG 문서가 있을 때 DB Query 대신 API 경로 우선 선택
4. **P2 — 불필요한 로그인 제거**: 공개 API(GET /api/categories)에 대해 인증 단계 스킵

---

*Generated by SOLO RE-TEST, 2026-02-23*
