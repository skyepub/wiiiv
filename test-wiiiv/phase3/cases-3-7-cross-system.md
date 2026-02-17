# Phase 3: Cross-System 통합 테스트 — Cases 3-7

> Cross-System (skymall + skystock) 통합 테스트
>
> 전제: 두 백엔드가 배포되어 있음
> - skymall: `http://home.skyepub.net:9090`
> - skystock: `http://home.skyepub.net:9091`

---

## 사전 준비 (모든 케이스 공통)

### 1. RAG 문서 수정

API 스펙의 Base URL을 배포 서버로 변경한 사본 준비:

```
# skymall-api-spec-v1.md 내부
Base URL: http://home.skyepub.net:9090

# skystock-api-spec-v1.md 내부
베이스 URL: http://home.skyepub.net:9091
```

### 2. RAG 문서 Ingest

```
> /rag clear
> /rag ingest test-wiiiv/phase3/skymall-api-spec-deployed.md
> /rag ingest test-wiiiv/phase3/skystock-api-spec-deployed.md
```

### 3. 인증 정보

| 시스템 | username | password | role | 토큰 필드명 |
|--------|----------|----------|------|------------|
| skymall | john_doe | pass1234 | ADMIN | accessToken |
| skystock | admin | admin123 | ADMIN | accessToken |

> **주의**: 양쪽 모두 로그인 응답의 토큰 필드명은 `accessToken`이다 (`token` 아님).
> skymall의 restock API는 `PATCH`이다 (`POST` 아님).

---

## Case 3: 크로스 시스템 재고 보충 파이프라인

### 목적

두 개의 독립 백엔드를 넘나드는 **멀티 시스템 API 워크플로우**를 RAG 문서 기반으로 수행한다.
wiiiv가 RAG에서 양쪽 API 스펙을 검색하여, 인증/호출/데이터 연결을 자율적으로 수행하는지 검증한다.

### 난이도

**High** — 2개 시스템 독립 인증 + 크로스 참조 + 상태 변경

### 사용 컴포넌트

- RAG (skymall-api-spec + skystock-api-spec)
- API_WORKFLOW (ApiExecutor, 멀티 턴)
- ConversationalGovernor (의도 파악 + 계획 수립)

### 테스트 시나리오

```
사용자:
  "skymall에서 재고가 30개 미만인 상품을 찾아서,
   그 중 하나를 골라 skystock에서 공급사를 확인하고 발주를 생성해줘"
```

### 기대 실행 흐름

```
Turn 1: skymall 로그인
  POST http://home.skyepub.net:9090/api/auth/login
  Body: {"username":"john_doe","password":"pass1234"}
  → accessToken 획득

Turn 2: 재고 부족 상품 조회
  GET http://home.skyepub.net:9090/api/products/low-stock?threshold=30
  Header: Authorization: Bearer <skymall_accessToken>
  → 재고 30 미만 상품 목록 (예: Laptop Pro 15 inch stock=28, Mountain Bike stock=24)

Turn 3: skystock 로그인
  POST http://home.skyepub.net:9091/api/auth/login
  Body: {"username":"admin","password":"admin123"}
  → accessToken 획득

Turn 4: 공급사 확인
  GET http://home.skyepub.net:9091/api/suppliers/by-product/{skymallProductId}
  Header: Authorization: Bearer <skystock_accessToken>
  → 해당 상품의 공급사 목록

Turn 5: 발주 생성
  POST http://home.skyepub.net:9091/api/purchase-orders
  Header: Authorization: Bearer <skystock_accessToken>
  Body: {"supplierId": N, "expectedDate": "2026-03-15",
         "items": [{"skymallProductId": X, "skymallProductName": "...", "quantity": 50, "unitCost": ...}]}
  → 발주 ID 반환
```

### 검증 기준

| # | 항목 | PASS 조건 |
|---|------|-----------|
| 1 | 양쪽 인증 | skymall, skystock 각각 별도 로그인 수행 |
| 2 | Base URL 구분 | 9090과 9091을 올바르게 구분하여 호출 |
| 3 | 크로스 참조 | skymall productId로 skystock 공급사 검색 |
| 4 | 발주 생성 | skystock에 실제 PO 생성됨 |
| 5 | 데이터 정확성 | 상품명, 수량, 단가가 합리적 |

### 후속 검증 (수동)

```
curl -s http://home.skyepub.net:9091/api/purchase-orders/status/REQUESTED \
  -H "Authorization: Bearer <skystock_accessToken>" | jq '.content[-1]'
```

---

## Case 4: HLX 재고 건강도 점검 워크플로우

### 목적

HLX 워크플로우가 **실제 API를 호출하여 데이터를 수집**하고, LLM이 분석/결정/보고서를 생성하는 엔드투엔드 파이프라인을 검증한다. Act 노드가 Gate를 통과하여 실제 상태 변경을 수행하는지 확인한다.

### 난이도

**Very High** — HLX 5노드 + 실제 API 2개 시스템 + Decide 분기 + Act 실행

### 사용 컴포넌트

- HLX (Observe × 2 + Transform + Decide + Act)
- RAG (API 스펙 참조)
- Executor (ApiExecutor, Gate 통과)
- HlxRunner (워크플로우 엔진)

### HLX 워크플로우 정의

```json
{
  "$schema": "https://hlx.dev/schema/hlx-v1.0.json",
  "version": "1.0",
  "id": "inventory-health-check",
  "name": "재고 건강도 점검",
  "description": "skystock 재고 알림 현황과 skymall 실제 재고를 비교하여 조치 필요 여부를 결정한다",
  "trigger": { "type": "manual" },
  "nodes": [
    {
      "id": "observe-alerts",
      "type": "observe",
      "description": "skystock에서 CRITICAL 레벨 재고 알림을 조회한다. API: GET http://home.skyepub.net:9091/api/stock-alerts/level/CRITICAL (Authorization: Bearer skystock_token 필요, skystock admin/admin123으로 먼저 로그인)",
      "target": "GET http://home.skyepub.net:9091/api/stock-alerts/level/CRITICAL",
      "output": "criticalAlerts"
    },
    {
      "id": "observe-skymall-stock",
      "type": "observe",
      "description": "skymall에서 재고 50개 이하 상품을 조회한다. API: GET http://home.skyepub.net:9090/api/products/low-stock?threshold=50 (인증 불필요)",
      "target": "GET http://home.skyepub.net:9090/api/products/low-stock?threshold=50",
      "output": "lowStockProducts"
    },
    {
      "id": "transform-gap-analysis",
      "type": "transform",
      "hint": "analyze",
      "description": "CRITICAL 알림 상품과 skymall 실제 재고를 skymallProductId로 매칭한다. 각 상품에 대해: (1) 알림의 safetyStock vs 실제 stock 비교, (2) gap = safetyStock - stock 계산, (3) gap이 양수인 상품은 '즉시 발주 필요'로 분류. 결과를 urgentItems(발주 필요)와 safeItems(여유 있음)로 분류한다.",
      "input": "criticalAlerts,lowStockProducts",
      "output": "gapAnalysis"
    },
    {
      "id": "decide-action",
      "type": "decide",
      "description": "gap 분석 결과에 따라 조치를 결정한다. urgentItems가 1개 이상이면 'reorder-needed', 모두 safeItems이면 'monitoring-only'.",
      "input": "gapAnalysis",
      "branches": {
        "reorder-needed": "generate-report",
        "monitoring-only": "generate-report"
      },
      "output": "actionDecision"
    },
    {
      "id": "generate-report",
      "type": "transform",
      "hint": "summarize",
      "description": "재고 건강도 점검 보고서를 생성한다. 포함 사항: (1) CRITICAL 알림 총 건수, (2) 실제 재고와의 gap 분석 요약, (3) 즉시 발주 필요 품목 목록과 권장 수량, (4) 조치 결정 사유, (5) 종합 평가 (건강/주의/위험).",
      "input": "actionDecision,gapAnalysis",
      "output": "healthReport"
    }
  ]
}
```

### 테스트 시나리오

```
1. /rag ingest 양쪽 API 스펙
2. /hlx create inventory-health-check.hlx
3. /hlx validate <id>
4. /hlx run <id>
5. 결과 확인
```

### 검증 기준

| # | 항목 | PASS 조건 |
|---|------|-----------|
| 1 | Observe 실행 | 두 API 모두 실제 호출되어 데이터 반환 |
| 2 | Transform 정확성 | skymallProductId 기준 매칭이 정확함 |
| 3 | Decide 분기 | 실제 데이터 기반으로 올바른 분기 선택 |
| 4 | 보고서 품질 | 구체적 수치(상품명, 재고수, gap)가 포함됨 |
| 5 | 재실행 일관성 | 2회 실행 시 동일 분기 선택 |

### 주의사항

- Observe 노드에서 인증이 필요한 API(skystock)는 description에 인증 방법을 명시
- skystock CRITICAL 알림이 0건이면 테스트 전 수동으로 알림 생성 필요

---

## Case 5: 다중 턴 크로스 시스템 비즈니스 인텔리전스

### 목적

**연속 대화** 속에서 양쪽 시스템의 데이터를 RAG+API로 교차 분석하며,
이전 턴의 결과를 다음 턴의 입력으로 자연스럽게 연결하는 **컨텍스트 연속성**을 검증한다.

### 난이도

**High** — 5턴 연속 대화 + 양쪽 시스템 + 컨텍스트 누적 + 분석 판단

### 사용 컴포넌트

- RAG (양쪽 API 스펙)
- API_WORKFLOW (다중 턴, 양쪽 시스템)
- ConversationalGovernor (컨텍스트 유지)

### 테스트 시나리오

```
Turn 1:
  "skymall에서 가장 비싼 카테고리 TOP 3를 알려줘"
  → API: GET http://home.skyepub.net:9090/api/categories (인증 불필요)
  → 기대: 카테고리별 상품 가격을 비교하여 TOP 3 도출

Turn 2:
  "그 중 첫 번째 카테고리에서 가장 많이 팔린 상품은 뭐야?"
  → API: GET http://home.skyepub.net:9090/api/categories/{id}/products (인증 불필요)
  → 주문 데이터 기반 분석 필요 시: GET /api/orders (john_doe 인증 필요)
  → 기대: 이전 턴의 Electronics(id=1) 컨텍스트 유지

Turn 3:
  "그 상품의 skystock 재고 알림 설정은 어떻게 되어 있어?"
  → API: GET http://home.skyepub.net:9091/api/stock-alerts/product/{skymallProductId} (skystock admin 인증 필요)
  → 기대: 이전 턴에서 파악한 상품 ID로 skystock 크로스 조회

Turn 4:
  "그 상품을 공급하는 공급사의 납기일수와 성과는?"
  → API: GET http://home.skyepub.net:9091/api/suppliers/by-product/{id} (skystock admin 인증 필요)
  → API: GET http://home.skyepub.net:9091/api/stats/supplier-performance/{supplierId}
  → 기대: 공급사 leadTimeDays + fulfillmentRate 반환

Turn 5:
  "분석 결과를 종합해서, 이 상품의 재고 안전성을 평가해줘"
  → 판단: 현재 재고 vs safetyStock vs 판매 속도 vs 공급사 납기
  → 기대: 구체적 수치 기반의 종합 평가 (API 호출 없이 컨텍스트 기반 분석)
```

### 검증 기준

| # | 항목 | PASS 조건 |
|---|------|-----------|
| 1 | Turn 간 컨텍스트 | "그 카테고리", "그 상품" 등 대명사를 올바르게 해석 |
| 2 | 시스템 전환 | Turn 1-2는 skymall, Turn 3-4는 skystock, 자동 전환 |
| 3 | 크로스 참조 정확성 | skymall productId → skystock 조회 매칭 정확 |
| 4 | 데이터 정확성 | 반환된 수치가 실제 DB 데이터와 일치 |
| 5 | 종합 분석 품질 | Turn 5에서 1-4턴의 데이터를 모두 활용한 분석 |

### 주의사항

- Turn 2에서 "가장 많이 팔린 상품"은 주문 건수 또는 주문 수량 기준 — LLM이 어느 쪽으로 해석하든 일관성만 있으면 PASS
- Turn 3에서 해당 상품에 stock_alert가 없을 수 있음 — 404 처리를 자연스럽게 하면 PASS

---

## Case 6: HLX Repeat 노드 — CRITICAL 알림 일괄 발주

### 목적

HLX의 **Repeat 노드**가 실제 API 데이터 기반으로 반복 실행되며,
각 반복에서 Decide 분기와 Act 실행이 올바르게 작동하는지 검증한다.
wiiiv HLX의 가장 복잡한 노드 조합이다.

### 난이도

**Very High** — Repeat 내부에 Observe + Decide + Act 중첩 + 실제 API 호출

### 사용 컴포넌트

- HLX (Observe + Repeat[Observe + Transform + Decide + Act])
- RAG (양쪽 API 스펙)
- Executor (ApiExecutor)
- HlxRunner (Repeat 루프 실행)

### HLX 워크플로우 정의

```json
{
  "$schema": "https://hlx.dev/schema/hlx-v1.0.json",
  "version": "1.0",
  "id": "batch-reorder-pipeline",
  "name": "CRITICAL 알림 일괄 발주 파이프라인",
  "description": "skystock에서 CRITICAL 재고 알림을 조회하고, 각 알림에 대해 skymall 실재고를 확인한 뒤 발주 필요 시 자동 발주를 생성한다",
  "trigger": { "type": "manual" },
  "nodes": [
    {
      "id": "observe-critical-alerts",
      "type": "observe",
      "description": "skystock에서 CRITICAL 레벨 재고 알림 목록을 조회한다. API: GET http://home.skyepub.net:9091/api/stock-alerts/level/CRITICAL (skystock admin/admin123 인증 필요). 결과에서 각 알림의 skymallProductId, skymallProductName, safetyStock, reorderQuantity를 추출한다.",
      "target": "GET http://home.skyepub.net:9091/api/stock-alerts/level/CRITICAL",
      "output": "criticalAlerts"
    },
    {
      "id": "process-each-alert",
      "type": "repeat",
      "description": "각 CRITICAL 알림에 대해 재고 확인 및 발주 판단을 수행한다",
      "over": "criticalAlerts",
      "as": "alert",
      "body": [
        {
          "id": "check-actual-stock",
          "type": "observe",
          "description": "해당 알림의 skymallProductId로 skymall에서 실제 상품 정보를 조회한다. API: GET http://home.skyepub.net:9090/api/products/{alert.skymallProductId} (인증 불필요). 현재 stock 수량을 확인한다.",
          "target": "GET http://home.skyepub.net:9090/api/products/{alert.skymallProductId}",
          "input": "alert",
          "output": "productInfo"
        },
        {
          "id": "evaluate-need",
          "type": "transform",
          "hint": "analyze",
          "description": "현재 stock과 alert.safetyStock을 비교한다. stock < safetyStock이면 deficit = safetyStock - stock, 발주수량은 alert.reorderQuantity를 사용. stock >= safetyStock이면 deficit = 0.",
          "input": "alert,productInfo",
          "output": "evaluation"
        },
        {
          "id": "decide-reorder",
          "type": "decide",
          "description": "evaluation.deficit > 0이면 'place-order', deficit = 0이면 'skip'으로 결정한다.",
          "input": "evaluation",
          "branches": {
            "place-order": "log-result",
            "skip": "log-result"
          },
          "output": "reorderDecision"
        },
        {
          "id": "log-result",
          "type": "transform",
          "hint": "summarize",
          "description": "이 알림의 처리 결과를 기록한다. 상품명, 현재재고, 안전재고, deficit, 결정(발주/스킵), 발주수량을 포함한다.",
          "input": "reorderDecision,evaluation,alert",
          "output": "alertResult"
        }
      ]
    },
    {
      "id": "generate-batch-report",
      "type": "transform",
      "hint": "summarize",
      "description": "전체 CRITICAL 알림 처리 결과를 종합 보고서로 생성한다. 포함: (1) 총 처리 건수, (2) 발주 필요 건수, (3) 스킵 건수, (4) 각 건별 상세 내역, (5) 권장 발주 총 비용 추정.",
      "input": "criticalAlerts",
      "output": "batchReport"
    }
  ]
}
```

### 테스트 시나리오

```
1. /rag ingest 양쪽 API 스펙
2. skystock에 CRITICAL 알림이 2건 이상 있는지 확인 (없으면 수동 생성)
3. /hlx create batch-reorder-pipeline.hlx
4. /hlx validate <id>
5. /hlx run <id>
6. 결과에서 Repeat 루프가 알림 수만큼 반복되었는지 확인
```

### 검증 기준

| # | 항목 | PASS 조건 |
|---|------|-----------|
| 1 | Repeat 반복 횟수 | CRITICAL 알림 수와 Repeat 실행 횟수 일치 |
| 2 | 크로스 시스템 조회 | 각 반복에서 skymall 상품 조회 성공 |
| 3 | Decide 분기 정확성 | deficit > 0 → place-order, = 0 → skip 올바른 분기 |
| 4 | 종합 보고서 | 모든 알림의 처리 결과가 누락 없이 포함 |
| 5 | 데이터 일관성 | 보고서 내 수치가 실제 API 응답과 일치 |

### 사전 데이터 확인

```bash
# CRITICAL 알림 확인 (현재 8건)
curl -s http://home.skyepub.net:9091/api/stock-alerts/level/CRITICAL \
  -H "Authorization: Bearer <skystock_accessToken>" | jq '.content[] | {skymallProductId, skymallProductName, safetyStock}'
```

---

## Case 7: 장애 복원력 — 부분 실패 시 그레이스풀 디그레이데이션

### 목적

API 호출 실패(인증 오류, 404, 타임아웃)가 발생했을 때 wiiiv가 **전체 중단 없이 부분 결과를 반환**하거나, 에러를 사용자에게 명확히 보고하는지 검증한다. 엔터프라이즈 환경에서 필수적인 장애 허용성 테스트.

### 난이도

**High** — 의도적 실패 유발 + 에러 전파 경로 + 복구 동작

### 사용 컴포넌트

- RAG (API 스펙)
- API_WORKFLOW (실패 시나리오)
- ConversationalGovernor (에러 처리)
- HLX (onError 정책)

### 테스트 시나리오

#### 시나리오 A: 존재하지 않는 상품 조회

```
"skymall에서 상품 ID 9999의 정보를 조회해줘"
```

기대:
- API: GET http://home.skyepub.net:9090/api/products/9999 → 404
- wiiiv 응답: "상품 ID 9999를 찾을 수 없습니다" (에러를 자연어로 전달)
- **전체 세션이 중단되지 않고** 다음 질문 가능

#### 시나리오 B: 잘못된 상태 전이

```
"skystock에서 RECEIVED 상태인 발주서 하나를 찾아서 승인(approve)해줘"
```

기대:
- API: GET /api/purchase-orders/status/RECEIVED → 발주 목록
- API: POST /api/purchase-orders/{id}/approve → 422 INVALID_STATE_TRANSITION
- wiiiv 응답: 상태 전이 규칙 위반 에러를 설명
- **이전 단계(조회)의 결과는 유지**, 실패 단계만 보고

#### 시나리오 C: HLX onError 정책 테스트

```json
{
  "$schema": "https://hlx.dev/schema/hlx-v1.0.json",
  "version": "1.0",
  "id": "resilience-test",
  "name": "장애 복원력 테스트",
  "description": "의도적으로 실패하는 노드를 포함하여 onError 정책을 검증한다",
  "trigger": { "type": "manual" },
  "nodes": [
    {
      "id": "observe-valid",
      "type": "observe",
      "description": "skymall에서 카테고리 목록을 조회한다. API: GET http://home.skyepub.net:9090/api/categories",
      "target": "GET http://home.skyepub.net:9090/api/categories",
      "output": "categories"
    },
    {
      "id": "observe-invalid",
      "type": "observe",
      "description": "존재하지 않는 API를 호출한다. API: GET http://home.skyepub.net:9090/api/nonexistent",
      "target": "GET http://home.skyepub.net:9090/api/nonexistent",
      "output": "shouldFail",
      "onError": "skip"
    },
    {
      "id": "transform-result",
      "type": "transform",
      "hint": "summarize",
      "description": "카테고리 조회 결과를 요약한다. observe-invalid가 실패했더라도 categories 데이터는 사용 가능해야 한다.",
      "input": "categories",
      "output": "summary"
    }
  ]
}
```

기대:
- observe-valid: 성공 (카테고리 8개)
- observe-invalid: 실패 → onError: skip → 다음 노드로 진행
- transform-result: categories 데이터로 정상 요약 생성

### 검증 기준

| # | 항목 | PASS 조건 |
|---|------|-----------|
| 1 | 404 처리 | 에러를 자연어로 보고, 세션 유지 |
| 2 | 422 처리 | 비즈니스 규칙 위반 에러를 설명, 이전 결과 보존 |
| 3 | onError: skip | 실패 노드를 건너뛰고 다음 노드 실행 |
| 4 | 부분 결과 활용 | 성공한 노드의 결과는 후속 노드에서 사용 가능 |
| 5 | 에러 투명성 | 어떤 단계에서 왜 실패했는지 사용자가 파악 가능 |

---

## 평가 매트릭스 (전체 Cases 3-7)

| Case | 주요 검증 | 컴포넌트 | 시스템 | 예상 턴 |
|------|-----------|----------|--------|---------|
| 3 | 크로스 시스템 파이프라인 | RAG + API_WORKFLOW | skymall + skystock | 5-7 |
| 4 | HLX 실제 API 연동 | HLX + Executor + RAG | skystock + skymall | HLX 5노드 |
| 5 | 다중 턴 컨텍스트 연속 | RAG + API_WORKFLOW | skymall → skystock | 5 |
| 6 | HLX Repeat 배치 처리 | HLX(Repeat) + Executor | skystock + skymall | HLX 7+노드 |
| 7 | 장애 허용성 | API_WORKFLOW + HLX(onError) | skymall + skystock | 3 시나리오 |

### 합격 기준

- **5/5 PASS**: A (엔터프라이즈 레디)
- **4/5 PASS**: B+ (핵심 기능 검증 완료)
- **3/5 PASS**: B (기본 크로스 시스템 동작 확인)
- **2/5 이하**: C (크로스 시스템 통합 미흡)

---

## 선행 조건: ApiExecutor 타임아웃 — 완료

~~Case 2에서 확인된 문제: 외부 API 응답이 10초 이상 걸리면 타임아웃.~~

- `ApiCallStep.timeoutMs`: 30초 → **60초** (ExecutionStep.kt)
- `HttpClient.connectTimeout`: 10초 → **30초** (ApiExecutor.kt)

---

*Phase 3 Cases 3-7 / 2026-02-18*
