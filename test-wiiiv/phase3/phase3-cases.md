# Phase 3: 자연어 테스트 케이스

> 모든 테스트는 wiiiv Governor에 자연어로 입력한다.
> 코드 경로 `[HLX-CODE]` 로그로 AGGREGATE/SORT/FILTER/MAP 확인.
>
> - skymall: `http://home.skyepub.net:9090`
> - skystock: `http://home.skyepub.net:9091`

---

## 사전 준비

```bash
# 1. RAG 등록
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')
curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-wiiiv/phase3/skymall-api-spec-deployed.md"
curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-wiiiv/phase3/skystock-api-spec-deployed.md"

# 2. 확인
curl -s http://localhost:8235/api/v2/rag/size -H "Authorization: Bearer $TOKEN"
# → size: 33
```

---

## Level 1: 단순 단일 턴 (워밍업)

### Case 1-1: 카테고리 목록 조회

```
skymall 카테고리 목록 보여줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/categories (인증 불필요) |
| HLX 노드 | act → transform(extract) |
| 코드 경로 | `[HLX-CODE]` extract |
| 결과 | 8개 카테고리 (Electronics, Clothing, ...) |

### Case 1-2: 단일 상품 조회

```
skymall에서 Laptop Pro 15 inch 검색해줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/products/search?keyword=laptop |
| HLX 노드 | act(login) → transform(extract-token) → act(search) → transform(extract) |
| 결과 | Laptop Pro 15 inch ($1999.99, 재고 29) |

### Case 1-3: 재고 부족 상품

```
skymall에서 재고가 30개 미만인 상품 알려줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/products/low-stock?threshold=30 |
| 결과 | Laptop Pro (29), Mountain Bike (25) 등 |

---

## Level 2: 코드 경로 단일 턴 (AGGREGATE/SORT/FILTER)

### Case 2-1: SORT — 가격순 정렬

```
skymall Electronics 카테고리 상품을 가격 비싼 순으로 정렬해서 보여줘
```

| 항목 | 기대 |
|------|------|
| HLX 노드 | act(login) → transform(extract-token) → act(get products) → transform(extract) → **transform(sort)** |
| 코드 경로 | `[HLX-CODE] Sort: N items by 'price' descending` |
| 결과 순서 | Laptop Pro ($1999) → Smartphone X ($999) → 4K Smart TV ($799) → ... |
| 결정론 | 3회 동일 순서 |

### Case 2-2: AGGREGATE — 주문 집계

```
전체 주문에서 상품별 총 주문 수량을 집계해줘
```

| 항목 | 기대 |
|------|------|
| HLX 노드 | act(login) → transform(extract-token) → act(get orders) → transform(extract) → **transform(aggregate)** |
| 코드 경로 | `[HLX-CODE] Resolved nested items` + `[HLX-CODE] Aggregate: N items → M groups` |
| 결과 | 상품별 totalQuantity (예: Bluetooth Speaker:2, 4K TV:3) |
| 결정론 | 3회 동일 집계 |

### Case 2-3: AGGREGATE + SORT — 집계 후 정렬

```
전체 주문에서 상품별 총 주문 수량을 집계해서 많이 팔린 순으로 보여줘
```

| 항목 | 기대 |
|------|------|
| HLX 노드 | ...extract → **transform(aggregate)** → **transform(sort)** |
| 코드 경로 | `Aggregate` + `Sort by totalQuantity descending` |
| 결정론 | 3회 동일 순서 |

### Case 2-4: FILTER — 가격 필터

```
skymall에서 $100 이상 $500 이하 상품만 보여줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/products/price-range?min=100&max=500 또는 전체 조회 후 FILTER |
| 코드 경로 (filter 사용 시) | `[HLX-CODE] Filter: N items → M where 'price' >= 100` |
| 비고 | API에 price-range 엔드포인트가 있으므로 API 직접 호출도 정답 |

### Case 2-5: skystock 공급사 성과 정렬

```
skystock에서 전체 공급사 성과를 이행률 높은 순으로 정렬해줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/stats/supplier-performance (skystock 인증 필요) |
| 코드 경로 | `[HLX-CODE] Sort by fulfillmentRate descending` |
| 결정론 | 3회 동일 |

---

## Level 3: 다중 턴 — 점진적 탐색

### Case 3-1: 카테고리 탐색 → 상품 → 주문 (3턴)

```
Turn 1: "skymall 카테고리 요약 보여줘"
Turn 2: "그 중 상품수가 가장 많은 카테고리의 상품 목록 보여줘"
Turn 3: "그 상품들 중에서 재고가 50개 미만인 것만 알려줘"
```

| 항목 | 기대 |
|------|------|
| Turn 1 | GET /api/categories/summary → 8개 카테고리 |
| Turn 2 | 컨텍스트 유지 — Electronics(7개) → GET /api/categories/1/products |
| Turn 3 | FILTER 또는 LLM 분석 — 재고 50 미만 필터링 |
| 핵심 검증 | "그 중", "그 상품들" 대명사 해석 |

### Case 3-2: skymall → skystock 크로스 시스템 (4턴)

```
Turn 1: "skymall에서 재고가 30개 미만인 상품 찾아줘"
Turn 2: "그 중 가장 비싼 상품의 skystock 공급사를 확인해줘"
Turn 3: "그 공급사의 납기일수와 성과는?"
Turn 4: "분석 결과를 종합해서 이 상품의 재고 보충 시급도를 평가해줘"
```

| 항목 | 기대 |
|------|------|
| Turn 1 | skymall GET /api/products/low-stock?threshold=30 |
| Turn 2 | 시스템 전환 — skystock 로그인 → GET /api/suppliers/by-product/{id} |
| Turn 3 | GET /api/stats/supplier-performance/{supplierId} |
| Turn 4 | API 호출 없이 컨텍스트 기반 종합 분석 (REPLY) |
| 핵심 검증 | 시스템 간 전환 + 대명사 + 컨텍스트 누적 |

### Case 3-3: 매출 분석 → 안전재고 조정 제안 (3턴)

```
Turn 1: "skymall 매출 리포트 보여줘"
Turn 2: "매출 상위 상품의 skystock 재고 알림 설정은 어떻게 되어 있어?"
Turn 3: "판매량 대비 안전재고가 부족한 상품이 있으면 조정을 제안해줘"
```

| 항목 | 기대 |
|------|------|
| Turn 1 | skymall GET /api/orders/report (인증 필요) |
| Turn 2 | skystock GET /api/stock-alerts/product/{id} (크로스 시스템) |
| Turn 3 | 분석 기반 제안 (REPLY 또는 PATCH 제안) |

---

## Level 4: 복잡한 단일 턴 — 긴 요구사항 한 번에

### Case 4-1: 재고 부족 분석 + 발주 생성

```
skymall에서 재고가 30개 미만인 상품을 조회하고, 각 상품의 skystock 공급사를 확인해서, 공급사가 있는 상품에 대해 자동으로 50개씩 발주를 생성해줘. 발주 예정일은 2026-03-15로 해줘.
```

| 항목 | 기대 |
|------|------|
| 시스템 | skymall + skystock 양쪽 |
| HLX 노드 수 | 8~12개 (양쪽 인증 + 조회 + 크로스 참조 + repeat 발주) |
| writeIntent | true |
| 핵심 검증 | 단일 턴에서 크로스 시스템 파이프라인 완성 |
| 데이터 변경 | skystock에 실제 발주 생성됨 |

### Case 4-2: 카테고리별 평균 가격 집계 + 정렬

```
skymall 전체 상품을 카테고리별로 평균 가격을 집계하고, 평균 가격이 높은 카테고리부터 낮은 순서로 정렬해서 카테고리 이름과 평균 가격을 보여줘.
```

| 항목 | 기대 |
|------|------|
| API | GET /api/categories/summary (이미 평균가격 포함) 또는 GET /api/products 후 집계 |
| 코드 경로 (집계 시) | AGGREGATE + SORT |
| 결과 | Electronics($634) → Home & Kitchen($260) → Sports($227) → ... |
| 비고 | categories/summary API를 바로 쓰면 SORT만 필요 |

### Case 4-3: 주문 데이터 심층 분석

```
skymall의 전체 주문 데이터를 조회해서, 사용자별로 총 주문금액을 집계하고, 주문금액이 높은 순으로 정렬한 뒤, 상위 5명의 사용자 이름과 총 주문금액을 보여줘.
```

| 항목 | 기대 |
|------|------|
| API | GET /api/orders (페이지네이션 — 여러 페이지 필요할 수 있음) |
| 코드 경로 | AGGREGATE by userId summing totalAmount → SORT by totalTotalAmount descending |
| 결정론 | 3회 동일 순서, 동일 금액 |
| 난이도 | 중첩 없이 order 레벨 집계 — 깔끔하게 동작해야 함 |

### Case 4-4: 크로스 시스템 대시보드

```
skystock 대시보드 정보와 skymall 카테고리 요약을 한꺼번에 조회해서, skystock의 총 발주 건수와 skymall의 총 상품 수를 비교하는 요약을 만들어줘.
```

| 항목 | 기대 |
|------|------|
| API | skystock GET /api/stats/dashboard + skymall GET /api/categories/summary |
| HLX 노드 | 양쪽 인증 + 양쪽 조회 + transform(summarize) |
| 결과 | 양쪽 수치를 포함한 비교 요약 |

---

## Level 5: 복잡한 다중 턴 — 긴 초기 요청 + 실질적 후속

### Case 5-1: 재고 건강도 종합 점검 (4턴, 긴 요청)

```
Turn 1:
"skystock에서 CRITICAL 레벨 재고 알림을 전부 조회하고, 각 알림의 skymallProductId로 skymall에서 실제 재고를 확인해서, 안전재고 대비 부족한 상품 목록을 정리해줘. 부족량(안전재고 - 현재재고)도 함께 계산해줘."

Turn 2:
"부족량이 가장 큰 상품부터 정렬해줘"

Turn 3:
"상위 3개 상품의 공급사 정보와 납기일수를 확인해줘"

Turn 4:
"그 결과를 바탕으로, 긴급도(부족량 × 납기일수)를 계산해서 가장 시급한 상품부터 발주 우선순위를 제안해줘"
```

| 턴 | 시스템 | 코드 경로 | 핵심 |
|----|--------|-----------|------|
| 1 | skystock + skymall | extract + (LLM 분석) | 크로스 시스템 + 복잡한 초기 요청 |
| 2 | - | SORT | 이전 결과에 정렬 적용 |
| 3 | skystock | extract | 크로스 참조 |
| 4 | - | REPLY (분석) | 컨텍스트 누적 + 종합 판단 |

### Case 5-2: 공급사 분석 → 발주 최적화 (4턴)

```
Turn 1:
"skystock의 전체 공급사 성과를 조회해서, 이행률(fulfillmentRate)이 90% 이상인 우수 공급사와 70% 미만인 부진 공급사로 나눠줘. 각 그룹의 평균 납기일수도 계산해줘."

Turn 2:
"우수 공급사 중에서 납기일수가 가장 짧은 공급사가 공급하는 skymall 상품 목록을 보여줘"

Turn 3:
"그 상품들 중 skymall에서 재고가 50개 미만인 상품이 있으면 알려줘"

Turn 4:
"해당 상품에 대해 50개씩 발주를 생성해줘. 발주 예정일은 2026-04-01로."
```

| 턴 | 시스템 | 코드 경로 | 핵심 |
|----|--------|-----------|------|
| 1 | skystock | FILTER + (LLM 분석) | 복잡한 초기 요청 (필터 + 집계) |
| 2 | skystock + skymall | extract | 크로스 시스템 참조 |
| 3 | skymall | FILTER | 재고 필터링 |
| 4 | skystock | act (POST 발주) | 실제 데이터 변경 (writeIntent: true) |

### Case 5-3: 매출 기반 수요 예측 + 안전재고 조정 (5턴)

```
Turn 1:
"skymall의 매출 리포트를 조회해서 상품별 총 판매량을 집계하고, 판매량 상위 10개 상품을 정렬해서 보여줘."

Turn 2:
"그 상위 10개 상품 각각의 현재 재고와 일평균 판매량(총 판매량 ÷ 운영일수 180일)을 계산해서, 현재 재고로 몇 일 버틸 수 있는지 재고 소진 예상일을 알려줘."

Turn 3:
"재고 소진 예상일이 30일 미만인 상품의 skystock 재고 알림 설정을 확인해줘"

Turn 4:
"안전재고가 현재 일평균 판매량 × 14일보다 낮게 설정된 상품이 있으면 알려줘"

Turn 5:
"해당 상품들의 안전재고를 일평균 판매량 × 21일로 조정하는 것을 제안해줘. 구체적인 수치와 변경 전후 비교를 포함해줘."
```

| 턴 | 시스템 | 코드 경로 | 핵심 |
|----|--------|-----------|------|
| 1 | skymall | AGGREGATE + SORT | 주문 집계 + 정렬 |
| 2 | - | LLM 분석 | 컨텍스트 기반 계산 |
| 3 | skystock | extract | 크로스 시스템 |
| 4 | - | LLM 분석 | 비교 판단 |
| 5 | - | REPLY | 종합 제안 (수치 기반) |

---

## Level 6: 에지 케이스

### Case 6-1: 존재하지 않는 데이터

```
skymall에서 상품 ID 9999를 조회해줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/products/9999 → 404 |
| 결과 | 자연어로 "찾을 수 없다" 보고, 세션 유지 |

### Case 6-2: 빈 결과 집계

```
skymall에서 가격이 $5000 이상인 상품을 조회해서 카테고리별로 집계해줘
```

| 항목 | 기대 |
|------|------|
| 결과 | 해당 상품 없음 → 빈 결과 보고 |
| 핵심 검증 | 빈 배열에 AGGREGATE 적용 시 크래시 없이 빈 결과 반환 |

### Case 6-3: 잘못된 상태 전이

```
skystock에서 RECEIVED 상태인 발주서를 하나 찾아서 승인해줘
```

| 항목 | 기대 |
|------|------|
| API | GET /api/purchase-orders/status/RECEIVED → POST /{id}/approve → 422 |
| 결과 | RECEIVED → APPROVED 전이 불가 에러를 자연어로 설명 |

### Case 6-4: 대명사 없는 후속 (새 세션과 동일)

```
Turn 1: "skymall 카테고리 목록 보여줘"
Turn 2: "skystock 공급사 목록 보여줘"
```

| 항목 | 기대 |
|------|------|
| 핵심 검증 | Turn 2가 Turn 1과 무관한 독립 쿼리임을 인식 |
| 결과 | skystock에 새로 로그인 후 공급사 목록 반환 |

---

## 결정론 검증 프로토콜

코드 경로(AGGREGATE/SORT/FILTER/MAP)가 포함된 모든 케이스는 **3회 반복 테스트**.

### 자동 테스트 스크립트

```bash
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')
QUERY="테스트할 쿼리"

for i in 1 2 3; do
  SESSION_ID=$(curl -s -X POST http://localhost:8235/api/v2/sessions \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d '{}' | jq -r '.data.sessionId')

  curl -s -X POST "http://localhost:8235/api/v2/sessions/$SESSION_ID/chat" \
    -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
    -d "{\"message\": \"$QUERY\", \"autoContinue\": true, \"maxContinue\": 15}" \
    --max-time 300 | grep "^data:" | tail -1 | sed 's/^data://' | jq '.message' > /tmp/result-$i.txt
done

# 결과 비교
diff /tmp/result-1.txt /tmp/result-2.txt && diff /tmp/result-2.txt /tmp/result-3.txt && echo "PASS: 3회 동일" || echo "FAIL: 결과 차이 있음"
```

### 서버 로그 코드 경로 확인

```bash
grep '\[HLX-CODE\]' /tmp/wiiiv-server.log | tail -20
```

---

## 합격 기준

| 레벨 | 케이스 수 | 합격 조건 |
|------|-----------|-----------|
| Level 1 (단순) | 3 | 3/3 정상 응답 |
| Level 2 (코드 경로) | 5 | 5/5 `[HLX-CODE]` 로그 확인 + 3회 결정론 |
| Level 3 (다중 턴) | 3 | 대명사 해석 + 시스템 전환 성공 |
| Level 4 (복잡 단일) | 4 | 3/4 이상 워크플로우 완주 |
| Level 5 (복잡 다중) | 3 | 2/3 이상 전체 턴 완주 |
| Level 6 (에지) | 4 | 크래시 없이 에러 보고 |

### 종합 등급

- **A**: Level 1~4 전부 + Level 5 2/3 + Level 6 3/4
- **B+**: Level 1~3 전부 + Level 4 3/4
- **B**: Level 1~2 전부 + Level 3 2/3
- **C**: Level 1만 통과

---

*Phase 3 자연어 테스트 케이스 / 2026-02-18*
