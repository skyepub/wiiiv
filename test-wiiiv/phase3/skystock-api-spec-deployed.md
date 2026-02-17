# skystock API Specification v1

> 재고/발주 관리 시스템 — skymall(쇼핑몰)과 연계

## 시스템 개요

| 항목 | 값 |
|------|-----|
| 서비스명 | skystock |
| 포트 | 9091 |
| 베이스 URL | `http://home.skyepub.net:9091` |
| DB | MySQL `skystock` (home.skyepub.net:63306) |
| 인증 | JWT Bearer Token (독립 secret) |
| 연계 시스템 | skymall (port 9090, DB `skymall`) — 별도 인증 체계 |

## 인증

skystock은 독립된 JWT 체계를 사용한다. skymall과는 별개의 secret(`skystock-secret-key-must-be-at-least-32-bytes-long`)을 가지며, 두 시스템의 토큰은 호환되지 않는다. 크로스 시스템 호출 시에는 각 시스템에 별도로 로그인하여 토큰을 발급받아야 한다.

### 역할 (Role)

| Role | 설명 |
|------|------|
| ADMIN | 전체 관리 (공급사 CUD, 재고알림 삭제 포함) |
| WAREHOUSE_MANAGER | 발주/입고 관리, 재고알림 CUD |
| VIEWER | 조회만 가능 |

### 인증 엔드포인트

**POST /api/auth/register** — 회원가입 (Public)
```json
// Request
{ "username": "string", "email": "string", "password": "string", "role": "VIEWER" }
// Response 201
{ "accessToken": "jwt...", "userId": 1, "username": "string", "role": "VIEWER" }
```

**POST /api/auth/login** — 로그인 (Public)
```json
// Request
{ "username": "string", "password": "string" }
// Response 200
{ "accessToken": "jwt...", "userId": 1, "username": "string", "role": "ADMIN" }
```

### 샘플 사용자

| username | password | role |
|----------|----------|------|
| admin | admin123 | ADMIN |
| warehouse1 | wm1pass | WAREHOUSE_MANAGER |
| warehouse2 | wm2pass | WAREHOUSE_MANAGER |
| viewer1 | viewer1pass | VIEWER |
| viewer2 | viewer2pass | VIEWER |

---

## 공급사 (Supplier)

### GET /api/suppliers — 전체 조회 (paginated)
- 권한: VIEWER+
- 파라미터: `page`, `size`, `sort`
- 응답: `Page<SupplierResponse>`

### GET /api/suppliers/{id} — 단건 조회
- 권한: VIEWER+

### GET /api/suppliers/active — 활성 공급사 목록
- 권한: VIEWER+
- 응답: `List<SupplierResponse>`

### GET /api/suppliers/search?keyword= — 검색
- 권한: VIEWER+
- 파라미터: `keyword` (이름/이메일), `page`, `size`

### POST /api/suppliers — 생성
- 권한: ADMIN
```json
{ "name": "string", "contactEmail": "string?", "contactPhone": "string?", "address": "string?", "leadTimeDays": 7 }
```
- 응답: 201 `SupplierResponse`

### PATCH /api/suppliers/{id} — 수정
- 권한: ADMIN
```json
{ "name": "string?", "contactEmail": "string?", "contactPhone": "string?", "address": "string?", "leadTimeDays": "int?", "isActive": "boolean?" }
```

### DELETE /api/suppliers/{id} — 삭제
- 권한: ADMIN
- 응답: 204

---

## 공급사 상품 (SupplierProduct)

### GET /api/suppliers/{id}/products — 특정 공급사의 상품 목록
- 권한: VIEWER+

### GET /api/suppliers/by-product/{skymallProductId} — 특정 skymall 상품을 공급하는 공급사 목록
- 권한: VIEWER+

### POST /api/suppliers/{id}/products — 공급사-상품 매핑 생성
- 권한: ADMIN
```json
{ "skymallProductId": 1, "skymallProductName": "상품명", "unitCost": 50000.0 }
```
- 응답: 201 `SupplierProductResponse`

### PATCH /api/suppliers/products/{id} — 공급사-상품 매핑 수정
- 권한: ADMIN
```json
{ "skymallProductName": "string?", "unitCost": "double?", "isActive": "boolean?" }
```

### DELETE /api/suppliers/products/{id} — 공급사-상품 매핑 삭제
- 권한: ADMIN
- 응답: 204

---

## 발주서 (PurchaseOrder) — 핵심

### 상태 머신

```
REQUESTED → APPROVED → SHIPPED → RECEIVED
    ↓           ↓
 CANCELLED  CANCELLED
```

- REQUESTED: 발주 요청 (초기 상태)
- APPROVED: 승인됨
- SHIPPED: 배송 중
- RECEIVED: 입고 완료 (최종 상태)
- CANCELLED: 취소됨 (REQUESTED 또는 APPROVED에서만 가능)

### GET /api/purchase-orders — 전체 조회 (paginated)
- 권한: VIEWER+

### GET /api/purchase-orders/{id} — 단건 조회
- 권한: VIEWER+

### GET /api/purchase-orders/status/{status} — 상태별 조회
- 권한: VIEWER+
- status: REQUESTED, APPROVED, SHIPPED, RECEIVED, CANCELLED

### GET /api/purchase-orders/supplier/{supplierId} — 공급사별 조회
- 권한: VIEWER+

### GET /api/purchase-orders/date-range?from=&to= — 기간별 조회
- 권한: VIEWER+
- 파라미터: `from`, `to` (ISO DateTime)

### POST /api/purchase-orders — 발주 생성
- 권한: WAREHOUSE_MANAGER, ADMIN
```json
{
  "supplierId": 1,
  "expectedDate": "2026-03-01",
  "items": [
    { "skymallProductId": 1, "skymallProductName": "갤럭시 S25 Ultra", "quantity": 20, "unitCost": 990000.0 }
  ]
}
```
- 응답: 201 `PurchaseOrderResponse`
- 발주 생성자(orderedBy)는 JWT 토큰에서 자동 추출

### POST /api/purchase-orders/{id}/approve — 승인
- 권한: WAREHOUSE_MANAGER, ADMIN
- 전이: REQUESTED → APPROVED

### POST /api/purchase-orders/{id}/ship — 배송 시작
- 권한: WAREHOUSE_MANAGER, ADMIN
- 전이: APPROVED → SHIPPED

### POST /api/purchase-orders/{id}/receive — 입고 완료
- 권한: WAREHOUSE_MANAGER, ADMIN
- 전이: SHIPPED → RECEIVED
```json
{ "notes": "입고 메모 (선택)" }
```
- ReceivingLog가 자동 생성됨

### POST /api/purchase-orders/{id}/cancel — 취소
- 권한: WAREHOUSE_MANAGER, ADMIN
- 전이: REQUESTED → CANCELLED 또는 APPROVED → CANCELLED

### GET /api/purchase-orders/{id}/receiving-logs — 입고 기록 조회
- 권한: VIEWER+

---

## 재고 알림 (StockAlert)

skymall 상품별 안전재고, 재발주점, 발주수량, 알림 레벨을 관리한다.
skymallProductId는 unique — 상품당 하나의 알림 설정만 존재.

### AlertLevel

| Level | 설명 |
|-------|------|
| NORMAL | 정상 |
| WARNING | 주의 (재발주점 근접) |
| CRITICAL | 긴급 (안전재고 이하) |

### GET /api/stock-alerts — 전체 조회 (paginated)
- 권한: VIEWER+

### GET /api/stock-alerts/{id} — 단건 조회
- 권한: VIEWER+

### GET /api/stock-alerts/product/{skymallProductId} — 상품별 조회
- 권한: VIEWER+

### GET /api/stock-alerts/level/{level} — 레벨별 조회
- 권한: VIEWER+
- level: NORMAL, WARNING, CRITICAL

### POST /api/stock-alerts — 생성
- 권한: WAREHOUSE_MANAGER, ADMIN
```json
{
  "skymallProductId": 1,
  "skymallProductName": "갤럭시 S25 Ultra",
  "safetyStock": 10,
  "reorderPoint": 20,
  "reorderQuantity": 50,
  "alertLevel": "NORMAL"
}
```
- 응답: 201

### PATCH /api/stock-alerts/{id} — 수정
- 권한: WAREHOUSE_MANAGER, ADMIN
```json
{ "skymallProductName": "string?", "safetyStock": "int?", "reorderPoint": "int?", "reorderQuantity": "int?", "alertLevel": "NORMAL|WARNING|CRITICAL" }
```

### PATCH /api/stock-alerts/{id}/level — 알림 레벨만 변경
- 권한: WAREHOUSE_MANAGER, ADMIN
```json
{ "alertLevel": "CRITICAL" }
```

### DELETE /api/stock-alerts/{id} — 삭제
- 권한: ADMIN
- 응답: 204

---

## 통계 (Stats)

### GET /api/stats/dashboard — 대시보드
- 권한: VIEWER+
```json
{
  "totalSuppliers": 5,
  "activeSuppliers": 5,
  "totalPurchaseOrders": 8,
  "purchaseOrdersByStatus": { "REQUESTED": 1, "APPROVED": 1, "SHIPPED": 1, "RECEIVED": 4, "CANCELLED": 1 },
  "totalPurchaseCostByStatus": { "REQUESTED": 10800000, "RECEIVED": 46555000, ... },
  "stockAlertsByLevel": { "NORMAL": 4, "WARNING": 4, "CRITICAL": 2 },
  "criticalAlerts": 2,
  "warningAlerts": 4
}
```

### GET /api/stats/supplier-performance — 전체 공급사 성과
- 권한: WAREHOUSE_MANAGER, ADMIN
```json
[
  {
    "supplierId": 1,
    "supplierName": "Samsung Electronics",
    "totalOrders": 2,
    "receivedOrders": 1,
    "cancelledOrders": 0,
    "totalSpent": 27300000,
    "fulfillmentRate": 50.0
  }
]
```

### GET /api/stats/supplier-performance/{supplierId} — 특정 공급사 성과
- 권한: WAREHOUSE_MANAGER, ADMIN

---

## 에러 응답

모든 에러는 동일한 형식:
```json
{ "error": "ERROR_CODE", "message": "한글 에러 메시지" }
```

| HTTP Status | error | 설명 |
|-------------|-------|------|
| 400 | BAD_REQUEST | 비즈니스 규칙 위반 |
| 404 | NOT_FOUND | 엔티티 없음 |
| 409 | DUPLICATE | 중복 데이터 |
| 422 | INVALID_STATE_TRANSITION | 잘못된 상태 전이 |

---

## 크로스 시스템 워크플로우

> 두 시스템은 독립된 JWT 체계를 사용하므로, AI 에이전트는 각 시스템에 별도 로그인하여 토큰을 보유해야 한다.

### 사전 준비: 양쪽 시스템 인증
```
1. POST skymall:9090/api/auth/login → skymall_token 획득
2. POST skystock:9091/api/auth/login → skystock_token 획득
```

### 1. skymall 재고부족 → skystock 발주 생성
```
1. GET skymall:9090/api/products/low-stock (skymall_token) → 재고부족 상품 목록
2. GET skystock:9091/api/suppliers/by-product/{productId} (skystock_token) → 공급사 확인
3. POST skystock:9091/api/purchase-orders (skystock_token) → 발주 생성
```

### 2. skystock 입고 완료 → skymall 재고 보충
```
1. POST skystock:9091/api/purchase-orders/{id}/receive (skystock_token) → 입고 완료
2. GET skystock:9091/api/purchase-orders/{id} (skystock_token) → 입고 품목/수량 확인
3. PATCH skymall:9090/api/products/{id}/restock (skymall_token) → 상품별 재고 보충
```

### 3. skymall 매출 리포트 → skystock 안전재고 조정
```
1. GET skymall:9090/api/orders/report?from=&to= (skymall_token) → 매출 리포트
2. (AI 분석: 판매량 기반 적정 안전재고 계산)
3. PATCH skystock:9091/api/stock-alerts/{id} (skystock_token) → 안전재고/재발주점 조정
```

---

## 데이터 모델

### users
| 컬럼 | 타입 | 설명 |
|------|------|------|
| user_id | INT PK AI | 사용자 ID |
| username | VARCHAR(50) UNIQUE | 사용자명 |
| email | VARCHAR(100) UNIQUE | 이메일 |
| password | VARCHAR(255) | BCrypt 해시 |
| role | ENUM | ADMIN, WAREHOUSE_MANAGER, VIEWER |
| is_enabled | BOOLEAN | 활성 여부 |
| created_at | DATETIME | 생성일 |
| last_login_at | DATETIME | 마지막 로그인 |

### suppliers
| 컬럼 | 타입 | 설명 |
|------|------|------|
| supplier_id | INT PK AI | 공급사 ID |
| name | VARCHAR(100) | 공급사명 |
| contact_email | VARCHAR(100) | 담당자 이메일 |
| contact_phone | VARCHAR(30) | 전화번호 |
| address | VARCHAR(255) | 주소 |
| lead_time_days | INT | 납기일수 |
| is_active | BOOLEAN | 활성 여부 |
| created_at | DATETIME | 생성일 |

### supplier_products
| 컬럼 | 타입 | 설명 |
|------|------|------|
| supplier_product_id | INT PK AI | 매핑 ID |
| supplier_id | INT FK | 공급사 |
| skymall_product_id | INT | skymall 상품 ID |
| skymall_product_name | VARCHAR(100) | skymall 상품명 |
| unit_cost | DOUBLE | 공급 단가 |
| is_active | BOOLEAN | 활성 여부 |

### purchase_orders
| 컬럼 | 타입 | 설명 |
|------|------|------|
| purchase_order_id | INT PK AI | 발주 ID |
| supplier_id | INT FK | 공급사 |
| status | ENUM | REQUESTED/APPROVED/SHIPPED/RECEIVED/CANCELLED |
| ordered_by | VARCHAR(50) | 발주자 |
| total_cost | DOUBLE | 총 비용 |
| expected_date | DATE | 예상 도착일 |
| created_at | DATETIME | 생성일 |
| updated_at | DATETIME | 수정일 |

### purchase_items
| 컬럼 | 타입 | 설명 |
|------|------|------|
| purchase_item_id | BIGINT PK AI | 항목 ID |
| purchase_order_id | INT FK | 발주서 |
| skymall_product_id | INT | skymall 상품 ID |
| skymall_product_name | VARCHAR(100) | skymall 상품명 |
| quantity | INT | 수량 |
| unit_cost | DOUBLE | 단가 |
| subtotal | DOUBLE | 소계 |

### stock_alerts
| 컬럼 | 타입 | 설명 |
|------|------|------|
| stock_alert_id | INT PK AI | 알림 ID |
| skymall_product_id | INT UNIQUE | skymall 상품 ID |
| skymall_product_name | VARCHAR(100) | skymall 상품명 |
| safety_stock | INT | 안전재고 |
| reorder_point | INT | 재발주점 |
| reorder_quantity | INT | 재발주수량 |
| alert_level | ENUM | NORMAL/WARNING/CRITICAL |
| created_at | DATETIME | 생성일 |
| updated_at | DATETIME | 수정일 |

### receiving_logs
| 컬럼 | 타입 | 설명 |
|------|------|------|
| receiving_log_id | INT PK AI | 입고기록 ID |
| purchase_order_id | INT FK | 발주서 |
| received_by | VARCHAR(50) | 입고 담당자 |
| received_at | DATETIME | 입고일시 |
| notes | TEXT | 비고 |
