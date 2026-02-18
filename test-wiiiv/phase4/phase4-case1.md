# Phase 4 Case 1: skystock 백엔드 생성 — 대화형 블록 테스트

> wiiiv의 상부 토대 검증: 대화 블록을 순차적으로 전달하여
> 완전한 백엔드를 점진적으로 생성할 수 있는지 테스트한다.

---

## 테스트 대상

skystock과 동일한 구조의 재고/발주 관리 백엔드를 7개 대화 블록으로 생성.
각 블록은 이전 블록의 결과 위에 누적되어야 한다.

## 사전 조건

- wiiiv 서버 실행 중 (localhost:8235)
- RAG에 API 스펙 미등록 (순수 대화만으로 생성)
- 새 세션으로 시작

---

## Block 1: 초기 의도 + 기술 스택

```
재고/발주 관리 백엔드를 만들려고 해.
Spring Boot 3.4 + Kotlin 1.9, JPA + H2 인메모리 DB, JWT 인증으로.
localhost:9091에서 실행되게 해줘.
Java 17 기반이고, 프로젝트명은 skystock, 패키지는 com.skytree.skystock으로.
```

### 검증

- [ ] build.gradle.kts 생성 (Spring Boot 4, Kotlin, JPA, H2, JWT 의존성)
- [ ] application.yml 생성 (H2 설정, 포트 9091)
- [ ] 메인 클래스 생성 (com.skytree.skystock.SkystockApplication)
- [ ] 프로젝트 구조 (entity/, dto/, controller/, service/, repository/, config/)

---

## Block 2: 역할과 인증

```
사용자 역할은 3단계야.
- ADMIN: 전체 권한 (공급사 생성/삭제 포함)
- WAREHOUSE_MANAGER: 발주서 생성, 재고 알림 관리
- VIEWER: 조회만 가능

JWT는 HS384 알고리즘, 24시간 만료.
회원가입(POST /api/auth/register)과 로그인(POST /api/auth/login)이 필요해.
로그인하면 accessToken, userId, username, role을 반환해줘.
CSRF는 비활성화하고, 세션은 STATELESS로.
```

### 검증

- [ ] User 엔티티 (username, email, password, role, isEnabled, lastLoginAt)
- [ ] UserRole enum (ADMIN, WAREHOUSE_MANAGER, VIEWER)
- [ ] AuthController (register, login)
- [ ] JwtProvider (HS384, 24h)
- [ ] JwtAuthFilter
- [ ] SecurityConfig (STATELESS, CSRF disabled)
- [ ] TokenResponse DTO

---

## Block 3: 공급사 관리

```
공급사(Supplier) 엔티티가 필요해.
name, contactEmail, contactPhone, address, leadTimeDays(기본7일), isActive 필드.

공급사는 여러 상품을 공급하는데, SupplierProduct로 관리해.
SupplierProduct에는 supplier(FK), skymallProductId(외부 시스템 상품ID),
skymallProductName, unitCost, isActive 필드가 있어.

공급사 API는 /api/suppliers로:
- GET /, /{id}, /active, /search?keyword (VIEWER 이상)
- POST /, PATCH /{id}, DELETE /{id} (ADMIN만)
- GET /{id}/products, /by-product/{skymallProductId} (VIEWER 이상)
- POST /{id}/products (ADMIN), PATCH /products/{id}, DELETE /products/{id}
```

### 검증

- [ ] Supplier 엔티티 + SupplierProduct 엔티티
- [ ] 1:N 관계 (Supplier → SupplierProduct)
- [ ] SupplierController (12개 엔드포인트)
- [ ] SupplierService (CRUD + 검색)
- [ ] Create/Update DTO + Response DTO
- [ ] 역할별 권한 (GET: VIEWER+, CUD: ADMIN)

---

## Block 4: 발주서와 상태 머신

```
발주서(PurchaseOrder) 엔티티를 만들어줘.
supplier(FK), status, orderedBy(발주자 username), totalCost(자동계산),
expectedDate, createdAt, updatedAt 필드.

발주 항목(PurchaseItem)은 purchaseOrder(FK), skymallProductId,
skymallProductName, quantity, unitCost, subtotal(quantity×unitCost) 필드.

입고 기록(ReceivingLog)은 purchaseOrder(FK), receivedBy, receivedAt, notes.

상태 머신이 핵심이야:
REQUESTED → APPROVED → SHIPPED → RECEIVED
REQUESTED/APPROVED에서만 CANCELLED 가능. SHIPPED 이후 취소 불가.
잘못된 상태 전이 시 422(InvalidStateTransitionException) 반환.

API는 /api/purchase-orders로:
- GET /, /{id}, /status/{status}, /supplier/{supplierId}, /date-range?from&to
- POST / (WAREHOUSE_MANAGER 이상) — items 필수, totalCost 자동계산
- POST /{id}/approve, /ship, /receive(notes옵션), /cancel
- GET /{id}/receiving-logs
```

### 검증

- [ ] PurchaseOrder + PurchaseItem + ReceivingLog 엔티티
- [ ] PurchaseOrderStatus enum + canTransitionTo() 메서드
- [ ] 상태 전이: REQUESTED→APPROVED→SHIPPED→RECEIVED
- [ ] 취소: REQUESTED/APPROVED→CANCELLED만 허용
- [ ] InvalidStateTransitionException → 422
- [ ] totalCost 자동 계산 (items subtotal 합산)
- [ ] receive 시 ReceivingLog 자동 생성
- [ ] PurchaseOrderController (조회 5개 + 생성 1개 + 상태전이 4개 + 입고기록 1개)

---

## Block 5: 재고 알림

```
재고 알림(StockAlert) 엔티티를 추가해줘.
skymallProductId(UNIQUE), skymallProductName, safetyStock(기본10),
reorderPoint(기본20), reorderQuantity(기본50), alertLevel.

alertLevel은 3단계: CRITICAL(긴급), WARNING(경고), NORMAL(정상).

API는 /api/stock-alerts로:
- GET /, /{id}, /product/{skymallProductId}, /level/{level} (VIEWER 이상)
- POST /, PATCH /{id}, PATCH /{id}/level (WAREHOUSE_MANAGER 이상)
- DELETE /{id} (ADMIN만)
```

### 검증

- [ ] StockAlert 엔티티 (skymallProductId UNIQUE)
- [ ] AlertLevel enum (CRITICAL, WARNING, NORMAL)
- [ ] StockAlertController (조회 4개 + CUD 4개)
- [ ] 중복 생성 시 DuplicateException → 409

---

## Block 6: 통계 대시보드

```
통계 API를 /api/stats에 만들어줘.

GET /dashboard (VIEWER 이상):
- totalSuppliers, activeSuppliers
- totalPurchaseOrders, 상태별 발주 건수와 총액
- 알림 레벨별 건수, criticalAlerts, warningAlerts

GET /supplier-performance (WAREHOUSE_MANAGER 이상):
- 공급사별 totalOrders, receivedOrders, cancelledOrders
- totalSpent (RECEIVED 상태만 합산)
- fulfillmentRate = receivedOrders/totalOrders × 100

GET /supplier-performance/{supplierId}도 지원해줘.
```

### 검증

- [ ] StatsController (3개 엔드포인트)
- [ ] StatsService (집계 로직)
- [ ] DashboardResponse DTO
- [ ] SupplierPerformanceResponse DTO
- [ ] fulfillmentRate 계산 (0건이면 0.0%)
- [ ] totalSpent는 RECEIVED만 합산

---

## Block 7: 예외처리 + 초기 데이터

```
예외처리 체계를 만들어줘.
- EntityNotFoundException → 404
- DuplicateException → 409
- InvalidStateTransitionException → 422
- BusinessException → 400
ErrorResponse는 {error, message} 형태로.

초기 데이터(DataInitializer)도 넣어줘:
- 사용자 5명: admin/admin123(ADMIN), warehouse1/wm1pass, warehouse2/wm2pass(WAREHOUSE_MANAGER), viewer1/viewer1pass, viewer2/viewer2pass(VIEWER)
- 공급사 5개: 삼성전자(리드3일), LG전자(4일), Global Fashion(7일), BookWorld(2일), Home Essentials(5일)
- 각 공급사에 상품 3개씩
- 발주서 8건 (RECEIVED 3건, SHIPPED 1건, APPROVED 1건, REQUESTED 2건, CANCELLED 1건)
- 입고기록 3건, 재고알림 10개
```

### 검증

- [ ] BusinessException 계층 (4개 예외 클래스)
- [ ] GlobalExceptionHandler (@RestControllerAdvice)
- [ ] DataInitializer (CommandLineRunner)
- [ ] 초기 데이터: 사용자 5, 공급사 5, 상품 15, 발주 8, 입고 3, 알림 10

---

## 평가 기준

### 블록별 평가

| 등급 | 기준 |
|------|------|
| PASS | 해당 블록의 검증 항목 80% 이상 충족 |
| PARTIAL | 50~79% 충족 |
| FAIL | 50% 미만 |

### 종합 평가

| 등급 | 기준 |
|------|------|
| A | 7/7 블록 PASS + 빌드 성공 + 서버 기동 + API 테스트 통과 |
| B | 5/7 블록 PASS + 빌드 성공 |
| C | 3/7 블록 PASS |
| D | 2블록 이하 PASS |

### 핵심 검증

1. **누적 빌드**: 각 블록이 이전 결과 위에 정확히 누적되는가?
2. **컴파일**: 전체 블록 완료 후 `./gradlew build` 성공하는가?
3. **실행**: localhost:9091에서 서버 기동되는가?
4. **API 동작**: 로그인 → 공급사 조회 → 발주 생성 → 상태 전이가 동작하는가?

---

*Phase 4 Case 1: skystock 백엔드 대화형 생성 테스트 / 2026-02-18*
