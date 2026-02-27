# Phase 4: 백엔드 API 통합 (skymall + skystock)

> **검증 목표**: 자연어 → HLX 워크플로우 → 실제 백엔드 API 호출 → 결과 반환
> **핵심 관심사**: API 호출 정확성, 인증 처리, 크로스 시스템, 에러 핸들링
> **전제**: Phase 2,3 통과, skymall/skystock 기동 확인, RAG에 API 스펙 주입
> **백엔드**: skymall (home.skyepub.net:9090), skystock (home.skyepub.net:9091)

---

## 사전 준비

```bash
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | jq -r '.data.accessToken')

# API 스펙 RAG 주입
curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-wiiiv/phase3/skymall-api-spec-deployed.md"

curl -X POST http://localhost:8235/api/v2/rag/ingest/file \
  -H "Authorization: Bearer $TOKEN" \
  -F "file=@test-wiiiv/phase3/skystock-api-spec-deployed.md"

# 백엔드 생존 확인
curl -s http://home.skyepub.net:9090/api/categories | jq length   # → 8
curl -s -X POST http://home.skyepub.net:9091/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}' | jq .accessToken  # → eyJ...
```

---

## A. skymall 단독 (Case 1~5)

---

### Case 1: 단순 조회 — 인증 불필요 API (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 카테고리 목록 보여줘" | EXECUTE |

**Hard Assert**:
- GET http://home.skyepub.net:9090/api/categories 호출
- 결과에 카테고리 목록 포함

**Soft Assert**:
- Electronics, Clothing 등 카테고리명 표시
- 8개 카테고리
- 인증 없이 호출 (Public API)

**의도**: 가장 단순한 API 호출. 인증 불필요 API를 RAG에서 올바르게 파악하는지

---

### Case 2: 인증 필요 조회 — 로그인 체인 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 주문 목록 보여줘" | EXECUTE |

**Hard Assert**:
- POST /api/auth/login → accessToken 추출 → GET /api/orders (Authorization: Bearer 포함)
- 주문 데이터 반환

**Soft Assert**:
- 계정 정보(jane_smith/pass1234)를 RAG에서 가져옴
- 토큰 필드명 accessToken 올바르게 처리

**의도**: Governor가 인증 필요 여부를 파악하고 로그인 step을 자동 삽입하는지

---

### Case 3: 필터링 조회 — 조건 해석 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 전자제품 중 가장 비싼 상품 3개 알려줘" | EXECUTE |

**Hard Assert**:
- GET /api/categories/1/products (Electronics = ID 1) 또는 GET /api/products + 필터링
- 결과에 전자제품 카테고리 상품 포함

**Soft Assert**:
- 가격 내림차순 정렬, 상위 3개: Laptop Pro($1999.99), Smartphone X($999.99), 4K Smart TV($799.99)

**의도**: 자연어의 "전자제품", "가장 비싼", "3개"를 API 파라미터와 후처리 로직으로 변환하는 능력

---

### Case 4: 집계 조회 — summary API 활용 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 카테고리별 상품 수와 평균 가격을 정리해줘" | EXECUTE |

**Hard Assert**:
- API 호출 성공 (GET /api/categories/summary 또는 각 카테고리 순회)

**Soft Assert**:
- 카테고리명 + 상품 수 + 평균 가격 테이블 형태 응답
- 8개 카테고리 모두 표시

**의도**: 집계 전용 API(summary)의 존재를 RAG에서 발견하고 효율적으로 사용하는지

---

### Case 5: 재고 부족 + 상세 조회 — 데이터 흐름 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 재고 30개 미만인 상품을 찾아서, 각 상품의 상세 정보를 조회해줘" | EXECUTE |

**Hard Assert**:
- 1차: GET /api/products/low-stock?threshold=30
- 2차: 각 상품에 대해 GET /api/products/{id} (반복 호출)

**Soft Assert**:
- Laptop Pro 15 inch (stock: 29), Mountain Bike (stock: 25) 등 표시
- 각 상품의 상세 정보(설명, 카테고리 등) 포함

**의도**: 1차 결과를 파싱하여 2차 호출의 입력으로 사용하는 data flow 능력

---

## B. skystock 단독 (Case 6~9)

---

### Case 6: skystock 공급사 조회 — 인증 필수 확인 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 활성 공급업체 목록 보여줘" | EXECUTE |

**Hard Assert**:
- skystock 로그인: POST http://home.skyepub.net:9091/api/auth/login (admin/admin123)
- GET /api/suppliers/active
- 공급업체 데이터 반환

**Soft Assert**:
- 공급업체명, 연락처, 리드타임 표시
- skymall(9090)이 아닌 skystock(9091)으로 호출
- skystock 계정(admin/admin123) 사용 (skymall 계정과 다름)

**의도**: skymall과 skystock의 인증 체계가 완전 분리됨을 Governor가 인식하는지

---

### Case 7: skystock 발주서 조회 — 상태 필터 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 승인 대기 중인 발주서 목록 보여줘" | EXECUTE |

**Hard Assert**:
- skystock 로그인 → GET /api/purchase-orders/status/REQUESTED
- 발주서 데이터 반환

**Soft Assert**:
- 발주서 ID, 공급사, 금액, 요청일 등 표시
- "승인 대기"를 REQUESTED 상태로 올바르게 매핑

**의도**: 자연어("승인 대기")를 도메인 용어(REQUESTED)로 올바르게 변환하는지

---

### Case 8: skystock 재고알림 조회 — 레벨별 분류 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 CRITICAL 레벨 재고알림을 보여줘" | EXECUTE |

**Hard Assert**:
- skystock 로그인 → GET /api/stock-alerts/level/CRITICAL
- 알림 데이터 반환

**Soft Assert**:
- 상품명, safetyStock, reorderPoint, alertLevel 표시
- CRITICAL 알림만 필터링

**의도**: skystock 고유 도메인(재고알림 레벨)에 대한 RAG 기반 API 선택 정확도

---

### Case 9: skystock 대시보드 — 통계 API (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock 전체 현황을 한눈에 보여줘" | EXECUTE |

**Hard Assert**:
- skystock 로그인 → GET /api/stats/dashboard
- 대시보드 데이터 반환

**Soft Assert**:
- 총 공급사 수, 총 발주 수, 상태별 발주 현황 등 표시
- "전체 현황"을 dashboard API로 매핑

**의도**: 추상적 요청("전체 현황")을 구체적 API 엔드포인트(dashboard)로 연결하는 능력

---

## C. 크로스 시스템 (Case 10~12)

---

### Case 10: 크로스 시스템 — skymall 상품 → skystock 공급사 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 'Laptop Pro' 상품 정보를 찾고, skystock에서 이 상품의 공급사를 확인해줘" | EXECUTE |

**Hard Assert**:
- skymall 로그인 (9090) → GET /api/products/search?keyword=Laptop+Pro
- skystock 로그인 (9091) → GET /api/suppliers/by-product/{skymallProductId}
- 두 시스템 각각 별도 JWT 발급

**Soft Assert**:
- Laptop Pro 15 inch ($1999.99, stock: 29) + 공급사 정보(이름, 리드타임) 통합 표시

**의도**: 핵심 크로스 시스템 API (by-product)를 통한 두 백엔드 연결. JWT 분리 인식

---

### Case 11: 크로스 시스템 — skymall 재고부족 → skystock 발주이력 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 재고 30개 미만인 상품을 찾고, 각 상품의 skystock 최근 발주 이력을 확인해줘" | EXECUTE |

**Hard Assert**:
- skymall: GET /api/products/low-stock?threshold=30
- skystock: 각 상품에 대해 GET /api/suppliers/by-product/{id} → 공급사 확인 → GET /api/purchase-orders/supplier/{supplierId}
- 두 시스템 결과 통합

**Soft Assert**:
- 재고부족 상품별 발주 상태(REQUESTED/APPROVED/SHIPPED 등) 표시

**의도**: 복수 상품에 대해 크로스 시스템 반복 조회가 가능한지

---

### Case 12: 크로스 시스템 — 매출 리포트 + 재고알림 통합 분석 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 매출 리포트(2025년~2026년)와 skystock CRITICAL 재고알림을 조합해서 보여줘" | ASK 또는 EXECUTE |
| 2 | (필요시) "매출 기간은 2025-01-01부터 2026-12-31까지" | EXECUTE |

**Hard Assert**:
- skymall 로그인 → GET /api/orders/report?from=2025-01-01T00:00:00&to=2026-12-31T23:59:59 (ADMIN/MANAGER 필요)
- skystock 로그인 → GET /api/stock-alerts/level/CRITICAL
- 두 데이터 조합

**Soft Assert**:
- 매출 데이터 + 재고알림이 하나의 응답에 통합
- 잘 팔리는데 재고알림이 CRITICAL인 상품이 하이라이트

**의도**: 서로 다른 도메인(매출 vs 재고관리) 데이터를 자연어 한 문장으로 통합 조회

---

## D. 복합 Executor + 에러 처리 (Case 13~16)

---

### Case 13: API + 파일 저장 — skymall (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall 카테고리 목록을 조회해서 /tmp/wiiiv-test-v2/categories.json으로 저장해줘" | EXECUTE |

**Hard Assert**:
- API_CALL + FILE_WRITE 조합
- /tmp/wiiiv-test-v2/categories.json 파일 생성됨

**검증**:
```bash
cat /tmp/wiiiv-test-v2/categories.json | jq length  # → 8
```

**의도**: 서로 다른 Executor(API_CALL + FILE_WRITE) 조합

---

### Case 14: API + 파일 저장 — skystock (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock 공급사 성과 데이터를 조회해서 /tmp/wiiiv-test-v2/supplier-perf.json으로 저장해줘" | EXECUTE |

**Hard Assert**:
- skystock 로그인 → GET /api/stats/supplier-performance → FILE_WRITE
- /tmp/wiiiv-test-v2/supplier-perf.json 파일 생성됨

**검증**:
```bash
cat /tmp/wiiiv-test-v2/supplier-perf.json | jq '.[0] | keys'
# → fulfillmentRate, leadTimeDays, supplierName 등
```

**의도**: skystock 통계 API + 파일 저장 조합

---

### Case 15: API 에러 처리 — 존재하지 않는 엔드포인트 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에서 /api/nonexistent-endpoint 호출해줘" | EXECUTE |

**Hard Assert**:
- 실행 시도 후 에러 처리
- Governor가 에러를 사용자에게 전달
- 시스템 크래시 없음

**Soft Assert**:
- 404 또는 관련 에러 메시지 포함

**Audit Assert**:
- status = FAILED, error 필드에 원인 기록

---

### Case 16: skystock 권한 에러 — VIEWER로 발주 생성 시도 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock에서 viewer1 계정으로 로그인해서 새 발주서를 만들어줘. 공급사 1번, 상품 ID 1, 수량 10개" | EXECUTE |

**Hard Assert**:
- skystock 로그인 (viewer1/viewer1pass) → POST /api/purchase-orders 시도
- 403 Forbidden 에러 발생 (VIEWER는 발주 생성 불가)
- 에러를 사용자에게 전달

**Soft Assert**:
- "권한 부족" 또는 "WAREHOUSE_MANAGER 이상 필요" 등 안내

**Audit Assert**:
- 실행 시도 + 권한 에러 기록

**의도**: 백엔드 권한 체계의 에러를 올바르게 처리하고 사용자에게 전달하는지

---

## E. 멀티턴 탐색 (Case 17~18)

---

### Case 17: skymall 멀티턴 드릴다운 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skymall에 어떤 데이터가 있는지 알려줘" | REPLY 또는 EXECUTE |
| 2 | "그러면 가장 많이 팔린 카테고리 탑3 알려줘" | EXECUTE |
| 3 | "1등 카테고리의 상품 리스트도 보여줘" | EXECUTE |
| 4 | "그 중 가장 비싼 상품의 상세 정보 보여줘" | EXECUTE |

**Hard Assert**:
- Turn 2~4 각각 API 호출 성공

**Soft Assert**:
- Turn 2 결과 → Turn 3 입력으로 자연 연결 (대명사 "1등 카테고리" 해소)
- Turn 3 결과 → Turn 4 입력으로 자연 연결 ("그 중 가장 비싼" 해소)
- 세션 컨텍스트가 대화 전체에서 유지

**의도**: 자연어 대화 속 점진적 드릴다운 패턴

---

### Case 18: skystock 멀티턴 드릴다운 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "skystock 대시보드 보여줘" | EXECUTE |
| 2 | "CRITICAL 알림이 있는 상품들의 상세 알림 정보 보여줘" | EXECUTE |
| 3 | "그 중 첫 번째 상품의 공급사 정보도 확인해줘" | EXECUTE |
| 4 | "그 공급사의 최근 발주 이력도 보여줘" | EXECUTE |

**Hard Assert**:
- Turn 1: GET /api/stats/dashboard
- Turn 2: GET /api/stock-alerts/level/CRITICAL
- Turn 3: GET /api/suppliers/by-product/{skymallProductId}
- Turn 4: GET /api/purchase-orders/supplier/{supplierId}
- 각 턴 API 호출 성공

**Soft Assert**:
- 대화 컨텍스트에서 "그 중 첫 번째", "그 공급사"를 올바르게 해소
- 대시보드 → 알림 → 공급사 → 발주 순으로 자연 드릴다운

**Audit Assert**:
- 4턴 모두 Audit 레코드

**의도**: skystock 도메인 내 엔티티 관계(알림 → 공급사 → 발주)를 따라가는 멀티턴 탐색
