# SkyStock REST API — 실전 호출 가이드

> **반드시 이 URL을 사용할 것**: `http://home.skyepub.net:9091`
> 프로토콜: **http** (https 아님)
> 호스트: **home.skyepub.net**
> 포트: **9091**
> skymall(9090)과는 **독립된 JWT** — 별도 로그인 필요

---

## 인증

### 로그인 (토큰 발급)

```
POST http://home.skyepub.net:9091/api/auth/login
Content-Type: application/json

{"username":"admin","password":"admin123"}
```

응답:
```json
{"accessToken":"eyJ...","userId":1,"username":"admin","role":"ADMIN"}
```

> 토큰 필드명: **accessToken** (token 아님)
> skymall 토큰과 호환되지 않음 — 각 시스템에 별도 로그인 필수
> 이후 모든 인증 필요 API에 `Authorization: Bearer <accessToken>` 헤더 추가

### 계정 정보

| username | password | role |
|----------|----------|------|
| admin | admin123 | ADMIN |
| warehouse1 | wm1pass | WAREHOUSE_MANAGER |
| warehouse2 | wm2pass | WAREHOUSE_MANAGER |
| viewer1 | viewer1pass | VIEWER |
| viewer2 | viewer2pass | VIEWER |

### 권한

| 역할 | 설명 |
|------|------|
| ADMIN | 전체 관리 (공급사 CUD, 재고알림 삭제) |
| WAREHOUSE_MANAGER | 발주/입고 관리, 재고알림 CUD |
| VIEWER | 조회만 가능 |

---

## 공급사 API (VIEWER+ 인증 필요)

**전체 조회**
```
GET http://home.skyepub.net:9091/api/suppliers?page=0&size=20
Authorization: Bearer <accessToken>
```

**단건 조회**
```
GET http://home.skyepub.net:9091/api/suppliers/{id}
Authorization: Bearer <accessToken>
```

**활성 공급사**
```
GET http://home.skyepub.net:9091/api/suppliers/active
Authorization: Bearer <accessToken>
```

**검색**
```
GET http://home.skyepub.net:9091/api/suppliers/search?keyword=samsung
Authorization: Bearer <accessToken>
```

**특정 skymall 상품의 공급사 목록 (★ 크로스 시스템 핵심)**
```
GET http://home.skyepub.net:9091/api/suppliers/by-product/{skymallProductId}
Authorization: Bearer <accessToken>
```

**공급사 생성** (ADMIN만)
```
POST http://home.skyepub.net:9091/api/suppliers
Authorization: Bearer <accessToken>
Content-Type: application/json

{"name":"공급사명","contactEmail":"email@test.com","contactPhone":"010-1234","address":"주소","leadTimeDays":7}
```

---

## 공급사 상품 매핑 (VIEWER+ 인증 필요)

**특정 공급사의 상품 목록**
```
GET http://home.skyepub.net:9091/api/suppliers/{supplierId}/products
Authorization: Bearer <accessToken>
```

**공급사-상품 매핑 생성** (ADMIN만)
```
POST http://home.skyepub.net:9091/api/suppliers/{supplierId}/products
Authorization: Bearer <accessToken>
Content-Type: application/json

{"skymallProductId":1,"skymallProductName":"갤럭시 S25 Ultra","unitCost":990000.0}
```

---

## 발주서 API (★ 핵심) (VIEWER+ 인증 필요)

### 상태 머신

```
REQUESTED → APPROVED → SHIPPED → RECEIVED
    ↓           ↓
 CANCELLED  CANCELLED
```

**전체 조회**
```
GET http://home.skyepub.net:9091/api/purchase-orders?page=0&size=20
Authorization: Bearer <accessToken>
```

**단건 조회**
```
GET http://home.skyepub.net:9091/api/purchase-orders/{id}
Authorization: Bearer <accessToken>
```

**상태별 조회**
```
GET http://home.skyepub.net:9091/api/purchase-orders/status/{status}
Authorization: Bearer <accessToken>
```
status: REQUESTED, APPROVED, SHIPPED, RECEIVED, CANCELLED

**공급사별 조회**
```
GET http://home.skyepub.net:9091/api/purchase-orders/supplier/{supplierId}
Authorization: Bearer <accessToken>
```

**발주 생성 (★ 크로스 시스템 핵심)** (WAREHOUSE_MANAGER/ADMIN)
```
POST http://home.skyepub.net:9091/api/purchase-orders
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "supplierId": 1,
  "expectedDate": "2026-03-15",
  "items": [
    {"skymallProductId": 1, "skymallProductName": "갤럭시 S25 Ultra", "quantity": 20, "unitCost": 990000.0}
  ]
}
```
응답: 201 PurchaseOrderResponse (발주 ID 포함)

**승인** (WAREHOUSE_MANAGER/ADMIN)
```
POST http://home.skyepub.net:9091/api/purchase-orders/{id}/approve
Authorization: Bearer <accessToken>
```
전이: REQUESTED → APPROVED

**배송 시작** (WAREHOUSE_MANAGER/ADMIN)
```
POST http://home.skyepub.net:9091/api/purchase-orders/{id}/ship
Authorization: Bearer <accessToken>
```
전이: APPROVED → SHIPPED

**입고 완료** (WAREHOUSE_MANAGER/ADMIN)
```
POST http://home.skyepub.net:9091/api/purchase-orders/{id}/receive
Authorization: Bearer <accessToken>
Content-Type: application/json

{"notes":"입고 완료 메모"}
```
전이: SHIPPED → RECEIVED

**취소** (WAREHOUSE_MANAGER/ADMIN)
```
POST http://home.skyepub.net:9091/api/purchase-orders/{id}/cancel
Authorization: Bearer <accessToken>
```
전이: REQUESTED → CANCELLED 또는 APPROVED → CANCELLED

**입고 기록 조회**
```
GET http://home.skyepub.net:9091/api/purchase-orders/{id}/receiving-logs
Authorization: Bearer <accessToken>
```

---

## 재고 알림 API (VIEWER+ 인증 필요)

### 알림 레벨

| Level | 설명 |
|-------|------|
| NORMAL | 정상 |
| WARNING | 주의 (재발주점 근접) |
| CRITICAL | 긴급 (안전재고 이하) |

**전체 조회**
```
GET http://home.skyepub.net:9091/api/stock-alerts?page=0&size=20
Authorization: Bearer <accessToken>
```

**상품별 조회 (★ 크로스 시스템: skymall productId로 조회)**
```
GET http://home.skyepub.net:9091/api/stock-alerts/product/{skymallProductId}
Authorization: Bearer <accessToken>
```

**레벨별 조회 (★ CRITICAL 알림 확인)**
```
GET http://home.skyepub.net:9091/api/stock-alerts/level/CRITICAL
Authorization: Bearer <accessToken>
```

**알림 생성** (WAREHOUSE_MANAGER/ADMIN)
```
POST http://home.skyepub.net:9091/api/stock-alerts
Authorization: Bearer <accessToken>
Content-Type: application/json

{"skymallProductId":1,"skymallProductName":"갤럭시 S25 Ultra","safetyStock":10,"reorderPoint":20,"reorderQuantity":50,"alertLevel":"NORMAL"}
```

**알림 수정** (WAREHOUSE_MANAGER/ADMIN)
```
PATCH http://home.skyepub.net:9091/api/stock-alerts/{id}
Authorization: Bearer <accessToken>
Content-Type: application/json

{"safetyStock":15,"reorderQuantity":60,"alertLevel":"WARNING"}
```

**알림 레벨만 변경** (WAREHOUSE_MANAGER/ADMIN)
```
PATCH http://home.skyepub.net:9091/api/stock-alerts/{id}/level
Authorization: Bearer <accessToken>
Content-Type: application/json

{"alertLevel":"CRITICAL"}
```

---

## 통계 API (VIEWER+ 인증 필요)

**대시보드**
```
GET http://home.skyepub.net:9091/api/stats/dashboard
Authorization: Bearer <accessToken>
```

**전체 공급사 성과**
```
GET http://home.skyepub.net:9091/api/stats/supplier-performance
Authorization: Bearer <accessToken>
```

**특정 공급사 성과**
```
GET http://home.skyepub.net:9091/api/stats/supplier-performance/{supplierId}
Authorization: Bearer <accessToken>
```

---

## 크로스 시스템 워크플로우

> 두 시스템은 독립된 JWT. 각각 별도 로그인 필수.

### 1. skymall 재고부족 → skystock 발주

```
1. POST http://home.skyepub.net:9090/api/auth/login → skymall accessToken
2. GET http://home.skyepub.net:9090/api/products/low-stock?threshold=30 → 재고부족 상품
3. POST http://home.skyepub.net:9091/api/auth/login → skystock accessToken
4. GET http://home.skyepub.net:9091/api/suppliers/by-product/{productId} → 공급사 확인
5. POST http://home.skyepub.net:9091/api/purchase-orders → 발주 생성
```

### 2. skystock 입고 → skymall 재고 보충

```
1. POST http://home.skyepub.net:9091/api/purchase-orders/{id}/receive → 입고 완료
2. GET http://home.skyepub.net:9091/api/purchase-orders/{id} → 입고 품목 확인
3. PATCH http://home.skyepub.net:9090/api/products/{id}/restock → 재고 보충 (★ PATCH)
```

### 3. 매출 분석 → 안전재고 조정

```
1. GET http://home.skyepub.net:9090/api/orders/report?from=...&to=... → 매출 리포트
2. PATCH http://home.skyepub.net:9091/api/stock-alerts/{id} → 안전재고 조정
```

---

## 응답 형식

### 페이지네이션
```json
{"content":[...],"totalElements":10,"totalPages":1,"number":0,"size":20}
```

### 에러
```json
{"error":"INVALID_STATE_TRANSITION","message":"RECEIVED 상태에서 APPROVED로 전이할 수 없습니다"}
```

| HTTP | error | 설명 |
|------|-------|------|
| 400 | BAD_REQUEST | 비즈니스 규칙 위반 |
| 404 | NOT_FOUND | 엔티티 없음 |
| 409 | DUPLICATE | 중복 |
| 422 | INVALID_STATE_TRANSITION | 잘못된 상태 전이 |
