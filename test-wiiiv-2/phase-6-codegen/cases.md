# Phase 6: 코드 생성 워크플로우 (Code Generation) — v2

> **검증 목표**: 자연어 → 인터뷰 → Spec → WorkOrder → 코드 생성 → 빌드/실행
> **핵심 관심사**: PROJECT_CREATE 풀 인터뷰, WorkOrder 품질, 멀티턴 리파인, 실행 검증
> **전제**: Phase 2 통과 (FILE_WRITE, COMMAND 작동 확인)
> **백엔드**: skymall (home.skyepub.net:9090), skystock (home.skyepub.net:9091)
> **자동화**: `/tmp/hst-phase6.py`

---

## 케이스 구조 (20 cases)

| 섹션 | 범위 | 케이스 수 | 핵심 |
|------|------|-----------|------|
| A | 단일 파일 코드 생성 | 2 | 기본 경로 확인 |
| B | 실서버 연동 스크립트 | 5 | skymall/skystock API 호출 |
| C | PROJECT_CREATE — 경량 | 2 | Python CLI, Kotlin Ktor |
| **D** | **PROJECT_CREATE — skymall** | **1** | **풀 인터뷰 6턴, WorkOrder 검증** |
| **E** | **PROJECT_CREATE — skystock** | **1** | **풀 인터뷰 6턴, WorkOrder 검증** |
| **F** | **PROJECT_CREATE — 블로그** | **1** | **풀 인터뷰 5턴, WorkOrder 검증** |
| G | 반복 리파인 + 코드 리뷰 | 2 | 점진적 기능 추가 |
| H | 빌드 + 실행 검증 | 2 | 생성 → 실행 전체 경로 |
| I | 크로스 시스템 통합 | 2 | skymall↔skystock 연동 |
| J | WorkOrder 수정/보완 | 2 | WorkOrder 피드백 루프 |

---

## A. 단일 파일 코드 생성 (기본)

### Case 1: Python Hello World (1~2턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/hello.py에 'Hello, World!' 출력하는 Python 스크립트 만들어줘" | EXECUTE 또는 CONFIRM |
| 2 | (CONFIRM이면) "만들어" | EXECUTE |

**Hard Assert**: 파일 생성됨, `print("Hello` 포함
**검증**: `python3 /tmp/wiiiv-test-v2/hello.py` → "Hello, World!"

---

### Case 2: 유틸리티 함수 모음 (1~2턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/mathlib.py에 피보나치, 팩토리얼, 소수 판별 함수를 만들어줘" | EXECUTE 또는 CONFIRM |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**: fibonacci, factorial, is_prime 함수 3개 포함
**검증**: `python3 -c "from mathlib import *; print(fibonacci(10), factorial(5), is_prime(7))"`

---

## B. 실서버 연동 스크립트

### Case 3: skymall 카테고리 보고서 (2턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/skymall_report.py를 만들어줘. skymall API(home.skyepub.net:9090)에서 카테고리별 상품 수와 평균 가격을 조회해서 표로 출력" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "만들어" | EXECUTE |

**Hard Assert**: `GET /api/categories/summary` 호출, URL `home.skyepub.net:9090`
**검증**: `python3 /tmp/wiiiv-test-v2/skymall_report.py`

---

### Case 4: skymall 매출 차트 (3턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/sales_chart.py — skymall에 로그인해서 매출 리포트를 조회하고 matplotlib으로 카테고리별 매출 막대 차트를 sales.png에 저장" | ASK 또는 CONFIRM |
| 2 | "계정 jane_smith/pass1234, API home.skyepub.net:9090" | CONFIRM |
| 3 | "만들어" | EXECUTE |

**Hard Assert**: `POST /api/auth/login`, `GET /api/orders/report`, matplotlib savefig 로직

---

### Case 5: skystock 공급사 성과 CSV (3턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "skystock 공급사 성과 데이터를 CSV로 만드는 스크립트" | ASK |
| 2 | "admin/admin123, home.skyepub.net:9091, 전체 공급사 성과 조회 → /tmp/wiiiv-test-v2/supplier_performance.csv" | CONFIRM |
| 3 | "만들어" | EXECUTE |

**Hard Assert**: `POST /api/auth/login`, `GET /api/stats/supplier-performance`, CSV 저장

---

### Case 6: skystock 발주 상태 대시보드 (2턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/po_dashboard.py — skystock(home.skyepub.net:9091)에 admin/admin123으로 로그인, 발주서 상태별(REQUESTED/APPROVED/SHIPPED/RECEIVED/CANCELLED) 건수 조회, 텍스트 대시보드 출력" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "만들어" | EXECUTE |

**Hard Assert**: 로그인 + 상태별 조회 + 콘솔 출력

---

### Case 7: skystock CRITICAL 알림기 (2턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/critical_alerts.py — skystock에서 CRITICAL 재고 알림만 조회, 있으면 '⚠ CRITICAL: {상품ID}' 출력, 없으면 '모든 재고 정상'. admin/admin123, home.skyepub.net:9091" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**: `GET /api/stock-alerts/level/CRITICAL`, 조건 분기 로직

---

## C. PROJECT_CREATE — 경량 프로젝트

### Case 8: Python CLI 계산기 (3~4턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "간단한 Python CLI 계산기 프로젝트를 /tmp/wiiiv-test-v2/calc-project에 만들어줘" | ASK |
| 2 | "사칙연산(+,-,*,/) 지원, 커맨드라인 인자 입력, 0 나누기 에러 처리" | ASK 또는 CONFIRM |
| 3 | "pytest 테스트 코드도 포함. 만들어줘" | CONFIRM 또는 EXECUTE |
| 4 | (CONFIRM이면) "만들어" | EXECUTE |

**Hard Assert**: taskType=PROJECT_CREATE, calc.py + test_calc.py 생성
**검증**: `python3 calc.py 3 + 4` → 7

---

### Case 9: Kotlin Ktor REST API (3~4턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "Kotlin으로 간단한 REST API 서버 프로젝트를 만들고 싶어" | ASK |
| 2 | "Ktor 프레임워크, 포트 8080, GET /hello 엔드포인트 하나" | ASK 또는 CONFIRM |
| 3 | "/tmp/wiiiv-test-v2/ktor-hello, Gradle 빌드 포함. 만들어" | CONFIRM 또는 EXECUTE |
| 4 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**: build.gradle.kts + src/main/kotlin/Application.kt
**Soft Assert**: Ktor 의존성 포함, Gradle 빌드 가능

---

## D. PROJECT_CREATE — skymall 쇼핑몰 ⚡ (풀 인터뷰)

### Case 10: skymall 쇼핑몰 백엔드 시스템 (6턴)

> **난이도**: ★★★★★
> **목표**: 실제 production skymall과 동일 수준의 WorkOrder + 코드 생성
> **기대 파일 수**: 30~40개

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
프로젝트 하나 만들어줘
```
**기대**: ASK (어떤 프로젝트인지 질문)

**Turn 2 — 도메인 + 기술스택**
```
쇼핑몰 백엔드야. 상품 관리, 주문, 결제 기능이 필요해. Kotlin + Spring Boot 4.0.1 + JPA + MySQL. Gradle 빌드. 이름은 skymall, 패키지 com.skytree.skymall, 포트 9090.
```
**기대**: ASK (엔티티/기능 상세 질문)

**Turn 3 — 엔티티 전체**
```
엔티티를 정리해줄게.

1. User: username(VARCHAR 50, UNIQUE), email(VARCHAR 100, UNIQUE), password(VARCHAR 255, BCrypt), alias(VARCHAR 100, nullable), role(ENUM: ADMIN/MANAGER/USER, 기본 USER), isEnabled(BOOLEAN, 기본 true), createdAt(DATETIME), lastLoginAt(DATETIME nullable), refreshToken(VARCHAR 512, nullable)

2. Category: name(VARCHAR 50, UNIQUE)

3. Product: name(VARCHAR 100), description(TEXT nullable), price(DOUBLE), stock(INT 기본 0), category FK(ManyToOne nullable), createdAt(DATETIME)

4. SalesOrder: user FK(ManyToOne nullable), orderDate(DATETIME), totalAmount(DOUBLE). OneToMany → OrderItem (CASCADE ALL, orphanRemoval)

5. OrderItem: salesOrder FK(ManyToOne), product FK(ManyToOne), quantity(INT), pricePerItem(DOUBLE, 주문 시점 가격 스냅샷)

관계: Category(1:N)Product, User(1:N)SalesOrder, SalesOrder(1:N)OrderItem, Product(1:N)OrderItem. 주문 시 재고 차감, 주문 취소 시 재고 복구.
```
**기대**: ASK 또는 CONFIRM (API/보안 질문 또는 WorkOrder 제안)

**Turn 4 — API 전체 + 보안**
```
API와 보안을 정리해줄게.

[Auth] /api/auth (Public):
- POST /register: 회원가입(username, email, password, role 선택) → TokenResponse{accessToken, userId, username, role} (201)
- POST /login: 로그인 → TokenResponse

[Users] /api/users (ADMIN/MANAGER):
- GET / (페이징), GET /{id}, GET /{id}/profile (주문통계 포함: orderCount, totalSpent, avgOrderAmount)
- GET /username/{username}
- PATCH /{id} (alias, email, role, isEnabled), DELETE /{id} (ADMIN, 주문 있으면 삭제 불가 → isEnabled=false)

[Categories] /api/categories (읽기: Public, 쓰기: ADMIN/MANAGER):
- GET / (List), GET /{id}, GET /{id}/products (페이징)
- GET /summary (카테고리별 상품수, 평균가격)
- POST / (201), DELETE /{id} (상품 있으면 삭제 불가)

[Products] /api/products (읽기: Public, 쓰기: ADMIN/MANAGER):
- GET / (페이징), GET /{id}, GET /search?keyword= (이름/설명 검색, 페이징)
- GET /price-range?min=&max= (페이징), GET /low-stock?threshold=5 (페이징), GET /unsold (주문 없는 상품)
- POST / (201), PATCH /{id} (부분 수정), PATCH /{id}/restock (재고 추가), DELETE /{id} (204)

[Orders] /api/orders (인증 필요):
- POST / (201, body: {userId, items:[{productId, quantity}]}, 재고 차감, totalAmount 서버 계산)
- GET / (페이징), GET /{id}, GET /my (내 주문), GET /user/{userId}
- GET /product/{productId}, GET /high-value?minAmount=, GET /large?minItems=3
- GET /date-range?from=&to= (ISO DateTime)
- GET /user/{userId}/summary (totalOrders, totalRevenue, avgOrderAmount)
- GET /report?from=&to= (ADMIN/MANAGER: orderCount, totalRevenue, topOrders)
- DELETE /{id} (204, 재고 복구)

[보안]
- JWT: 시크릿 skymall-secret-key-must-be-at-least-32-bytes-long, 만료 86400000ms
- JwtAuthFilter(OncePerRequestFilter), JwtProvider(생성/검증), BCryptPasswordEncoder
- SecurityConfig: STATELESS, /api/auth/** + GET /api/products/** + GET /api/categories/** public
- ROLE_ADMIN: 전체, ROLE_MANAGER: GET + 카탈로그 쓰기, ROLE_USER: 주문 + GET

[예외]
- GlobalExceptionHandler(@RestControllerAdvice)
- 응답: {"error":"ERROR_CODE","message":"한글메시지"}
- BusinessException(open) → EntityNotFoundException(404), DuplicateException(409), InsufficientStockException(422)
```
**기대**: CONFIRM (WorkOrder 제시) 또는 ASK (초기데이터 질문)

**Turn 5 — 초기 데이터 + 설정**
```
마지막으로 초기 데이터와 설정.

[application.yaml]
server.port: 9090
spring.datasource: jdbc:mysql://home.skyepub.net:63306/skymall?serverTimezone=Asia/Seoul&characterEncoding=UTF-8, root/Mako2122!
spring.jpa: hibernate.ddl-auto=update, show-sql=true, format_sql=true, dialect=MySQLDialect

[DataInitializer] CommandLineRunner, userRepo.count()==0 일 때만:
- 사용자 5명: admin/admin123(ADMIN), manager1/mgr1pass(MANAGER), manager2/mgr2pass(MANAGER), user1/user1pass(USER), user2/user2pass(USER)
- 카테고리 5개: Electronics, Clothing, Books, Home & Kitchen, Sports
- 상품 15개: 카테고리당 3개, price 10000~200000, stock 10~200
- 주문 8건: user1 4건, user2 4건, 각 1~3개 아이템

[Repository 커스텀 쿼리]
- ProductRepo: findByCategoryId, searchByKeyword(JPQL name/description LIKE), findByStockLessThan, findByPriceBetween, findUnsoldProducts(@Query NOT IN OrderItem), avgPriceByCategoryId
- SalesOrderRepo: findByUserId, findByOrderDateBetween, findByTotalAmountGreaterThanEqual, findByProductId(JPQL JOIN), findByMinItemCount(JPQL SIZE), sumTotalAmountByUserId, countByOrderDateBetween, findTopByTotalAmount
- UserRepo: findByUsername, findByEmail, findByRole
- CategoryRepo: findByName

[build.gradle 의존성]
- spring-boot-starter-data-jpa, spring-boot-starter-validation, spring-boot-starter-webmvc, spring-boot-starter-security
- kotlin-reflect, tools.jackson.module:jackson-module-kotlin
- jjwt-api/impl/jackson:0.12.6
- mysql-connector-j (runtimeOnly)

이제 전체 작업지시서를 완성해줘.
```
**기대**: CONFIRM + WorkOrder

**Turn 6 — WorkOrder 확인 또는 수정**
- WorkOrder가 만족스러우면: `"좋아, 만들어줘"` → EXECUTE
- WorkOrder에 빠진 내용이 있으면: 수정 요청 → CONFIRM (수정된 WorkOrder) → `"만들어"` → EXECUTE

#### WorkOrder 품질 체크 (16항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skymall | `"skymall" in wo` |
| 2 | com.skytree.skymall | `"com.skytree.skymall" in wo` |
| 3 | 9090 | `"9090" in wo` |
| 4 | Category | `"Category" in wo` |
| 5 | Product | `"Product" in wo` |
| 6 | SalesOrder | `"SalesOrder" in wo` |
| 7 | OrderItem | `"OrderItem" in wo` |
| 8 | MANAGER | `"MANAGER" in wo` |
| 9 | BCrypt | `"BCrypt" in wo or "bcrypt" in wo.lower()` |
| 10 | DataInitializer | `"DataInitializer" in wo or "초기" in wo` |
| 11 | /api/orders | `"/api/orders" in wo` |
| 12 | /api/categories | `"/api/categories" in wo` |
| 13 | /api/products | `"/api/products" in wo` |
| 14 | InsufficientStock | `"InsufficientStock" in wo or "재고 부족" in wo or "재고 차감" in wo` |
| 15 | searchByKeyword | `"search" in wo.lower() and "keyword" in wo.lower()` |
| 16 | findUnsoldProducts | `"unsold" in wo.lower() or "미판매" in wo` |

**PASS 기준**: 14/16 이상

#### 생성 코드 검증

```bash
# 빌드
cd /tmp/wiiiv-gen/skymall && ./gradlew assemble

# 서버 실행 (테스트용 — 실제 skymall이 9090이므로 다른 포트 필요)
java -jar build/libs/skymall-*.jar --server.port=19090

# API 테스트
curl -s -X POST http://localhost:19090/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'
# → {"accessToken":"...", "userId":1, "username":"admin", "role":"ADMIN"}
```

---

## E. PROJECT_CREATE — skystock 재고/발주 ⚡ (풀 인터뷰)

### Case 11: skystock 재고/발주 관리 시스템 (6턴)

> **난이도**: ★★★★★
> **목표**: 실제 production skystock과 동일 수준의 WorkOrder + 코드 생성
> **기대 파일 수**: 35~45개

#### 턴별 스크립트

**Turn 1 — 막연한 시작**
```
프로젝트 하나 만들어줘
```
**기대**: ASK

**Turn 2 — 도메인**
```
재고/발주 관리 시스템이야. 쇼핑몰이랑 연동돼.
```
**기대**: ASK

**Turn 3 — 기술스택 + 기본정보**
```
Kotlin + Spring Boot 4.0.1 + JPA + MySQL. Gradle 빌드. 이름은 skystock, 패키지 com.skytree.skystock, 포트 9091.
```
**기대**: ASK

**Turn 4 — 엔티티 전체**
```
엔티티를 정리해줄게. 이 시스템은 자체 상품 DB가 없고 skymall이라는 외부 쇼핑몰의 productId를 정수로 참조만 해.

1. User: username(VARCHAR 50, UNIQUE), email(VARCHAR 100, UNIQUE), password(VARCHAR 255, BCrypt), role(ENUM: ADMIN/WAREHOUSE_MANAGER/VIEWER, 기본 VIEWER), isEnabled(BOOLEAN, 기본 true), createdAt(DATETIME), lastLoginAt(DATETIME nullable)

2. Supplier: name(VARCHAR 100), contactEmail(VARCHAR 100), contactPhone(VARCHAR 30), address(VARCHAR 255), leadTimeDays(INT, 기본 7), isActive(BOOLEAN, 기본 true), createdAt. OneToMany → SupplierProduct (CASCADE ALL, orphanRemoval)

3. SupplierProduct: supplier FK(ManyToOne LAZY), skymallProductId(INT), skymallProductName(VARCHAR 100), unitCost(DOUBLE), isActive(BOOLEAN, 기본 true)

4. PurchaseOrder: supplier FK(ManyToOne LAZY), status(ENUM: REQUESTED→APPROVED→SHIPPED→RECEIVED, REQUESTED/APPROVED→CANCELLED), orderedBy(VARCHAR 50), totalCost(DOUBLE), expectedDate(DATE), createdAt, updatedAt. OneToMany → PurchaseItem, ReceivingLog (CASCADE ALL, orphanRemoval)

5. PurchaseItem: purchaseOrder FK(ManyToOne LAZY), skymallProductId(INT), skymallProductName(VARCHAR 100), quantity(INT), unitCost(DOUBLE), subtotal(DOUBLE, quantity*unitCost 서버 계산). PK BIGINT.

6. ReceivingLog: purchaseOrder FK(ManyToOne LAZY), receivedBy(VARCHAR 50, JWT), receivedAt(DATETIME), notes(TEXT nullable)

7. StockAlert (독립): skymallProductId(INT, UNIQUE), skymallProductName(VARCHAR 100), safetyStock(INT, 기본10), reorderPoint(INT, 기본20), reorderQuantity(INT, 기본50), alertLevel(ENUM: NORMAL/WARNING/CRITICAL, 기본 NORMAL), createdAt, updatedAt

관계: Supplier(1:N)SupplierProduct, Supplier(1:N)PurchaseOrder, PurchaseOrder(1:N)PurchaseItem, PurchaseOrder(1:N)ReceivingLog. User,StockAlert 독립.
```
**기대**: CONFIRM (WorkOrder 제시, 4턴 이상이므로)

**Turn 5 — API 전체 + 보안 + 예외**
```
API와 보안을 정리해줄게.

[Auth] /api/auth (Public):
- POST /register: 회원가입(username, email, password, role 선택)
- POST /login: 로그인 → TokenResponse{accessToken, userId, username, role}

[Supplier] /api/suppliers:
- GET / (페이징), GET /{id}, GET /active (List), GET /search?keyword= (페이징)
- POST / (ADMIN, 201), PATCH /{id} (ADMIN), DELETE /{id} (ADMIN, 204)
- GET /{id}/products, GET /by-product/{skymallProductId}
- POST /{id}/products (ADMIN, 201), PATCH /products/{id} (ADMIN), DELETE /products/{id} (ADMIN, 204)

[PurchaseOrder] /api/purchase-orders:
- GET / (페이징), GET /{id}, GET /status/{status}, GET /supplier/{supplierId}, GET /date-range?from=&to= (ISO DateTime)
- POST / (WM이상, 201, body: {supplierId, expectedDate, items:[{skymallProductId, skymallProductName, quantity, unitCost}]}), orderedBy=JWT, totalCost=서버계산
- POST /{id}/approve, POST /{id}/ship, POST /{id}/receive (body: {notes?}, ReceivingLog 자동), POST /{id}/cancel
- GET /{id}/receiving-logs

[StockAlert] /api/stock-alerts:
- GET / (페이징), GET /{id}, GET /product/{skymallProductId}, GET /level/{level}
- POST / (WM이상, 201, UNIQUE), PATCH /{id} (WM이상), PATCH /{id}/level (WM이상), DELETE /{id} (ADMIN, 204)

[Stats] /api/stats:
- GET /dashboard (VIEWER이상): totalSuppliers, activeSuppliers, totalPurchaseOrders, purchaseOrdersByStatus(Map), totalPurchaseCostByStatus(Map), stockAlertsByLevel(Map), criticalAlerts, warningAlerts
- GET /supplier-performance (WM이상): [{supplierId, supplierName, totalOrders, receivedOrders, cancelledOrders, totalSpent, fulfillmentRate(%)}]
- GET /supplier-performance/{supplierId} (WM이상)

[보안]
- JWT: 시크릿 skystock-secret-key-must-be-at-least-32-bytes-long, 만료 86400000ms
- JwtAuthFilter(OncePerRequestFilter), JwtProvider(생성/검증), BCryptPasswordEncoder
- SecurityConfig: STATELESS, /api/auth/** public, 나머지 인증
- ROLE_ADMIN: 전체, ROLE_WAREHOUSE_MANAGER: GET + 발주/알림 POST/PATCH, ROLE_VIEWER: GET만

[예외]
- GlobalExceptionHandler(@RestControllerAdvice)
- 응답: {"error":"ERROR_CODE","message":"한글메시지"}
- BusinessException(open) → EntityNotFoundException(404), DuplicateException(409), InvalidStateTransitionException(422)
```
**기대**: CONFIRM (WorkOrder 업데이트)

**Turn 6 — 초기 데이터 + 설정**
```
마지막으로 초기 데이터와 설정.

[application.yaml]
server.port: 9091
spring.datasource: jdbc:mysql://home.skyepub.net:63306/skystock?serverTimezone=Asia/Seoul&characterEncoding=UTF-8, root/Mako2122!
spring.jpa: hibernate.ddl-auto=update, show-sql=true, format_sql=true, dialect=MySQLDialect

[DataInitializer] CommandLineRunner, userRepo.count()==0 일 때만:
- 사용자 5명: admin/admin123(ADMIN), warehouse1/wm1pass(WM), warehouse2/wm2pass(WM), viewer1/viewer1pass(VIEWER), viewer2/viewer2pass(VIEWER)
- 공급사 5개: Samsung Electronics(lead 3), LG Electronics(4), Global Fashion Co.(7), BookWorld Distribution(2), Home Essentials Ltd.(5)
- 공급사-상품 매핑 15개: 공급사당 3개, skymallProductId 1~15
- 발주서 8건: RECEIVED 3, REQUESTED 2, APPROVED 1, SHIPPED 1, CANCELLED 1
- 입고기록 3건: RECEIVED 발주에 대해
- 재고알림 10개: CRITICAL 2, WARNING 4, NORMAL 4

[Repository 커스텀 쿼리]
- SupplierRepo: findByIsActiveTrue(), searchByKeyword(JPQL, name/contactEmail LIKE)
- PurchaseOrderRepo: findByStatus, findBySupplierId, findByCreatedAtBetween (모두 Pageable), countByStatus, countBySupplierId, countBySupplierIdAndStatus, sumTotalCostByStatus(@Query JPQL), sumTotalCostBySupplierIdAndStatus
- StockAlertRepo: findBySkymallProductId, findByAlertLevel(Pageable), countByAlertLevel
- SupplierProductRepo: findBySupplierId, findBySkymallProductId, findBySupplierIdAndIsActiveTrue
- ReceivingLogRepo: findByPurchaseOrderId
- UserRepo: findByUsername, findByEmail

[build.gradle 의존성]
- spring-boot-starter-data-jpa, spring-boot-starter-validation, spring-boot-starter-webmvc, spring-boot-starter-security
- kotlin-reflect, tools.jackson.module:jackson-module-kotlin
- jjwt-api/impl/jackson:0.12.6
- mysql-connector-j (runtimeOnly)

이제 전체 작업지시서를 완성해줘.
```
**기대**: CONFIRM + WorkOrder

#### WorkOrder 품질 체크 (14항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | skystock | `"skystock" in wo` |
| 2 | com.skytree.skystock | `"com.skytree.skystock" in wo` |
| 3 | 9091 | `"9091" in wo` |
| 4 | SupplierProduct | `"SupplierProduct" in wo` |
| 5 | PurchaseItem | `"PurchaseItem" in wo` |
| 6 | ReceivingLog | `"ReceivingLog" in wo` |
| 7 | StockAlert | `"StockAlert" in wo` |
| 8 | WAREHOUSE_MANAGER | `"WAREHOUSE_MANAGER" in wo` |
| 9 | BCrypt | `"BCrypt" in wo or "bcrypt" in wo.lower()` |
| 10 | DataInitializer | `"DataInitializer" in wo or "초기" in wo` |
| 11 | /api/purchase-orders | `"/api/purchase-orders" in wo` |
| 12 | /api/stats | `"/api/stats" in wo` |
| 13 | REQUESTED | `"REQUESTED" in wo` |
| 14 | fulfillmentRate | `"fulfillmentRate" in wo or "fulfillment" in wo.lower()` |

**PASS 기준**: 12/14 이상

#### 생성 코드 검증

```bash
cd /tmp/wiiiv-gen/skystock && ./gradlew assemble
java -jar build/libs/skystock-*.jar --server.port=19091

curl -s -X POST http://localhost:19091/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"admin123"}'

TOKEN=<위 결과에서 accessToken>
curl -s http://localhost:19091/api/stats/dashboard \
  -H "Authorization: Bearer $TOKEN"
# → totalSuppliers, purchaseOrdersByStatus, stockAlertsByLevel 등
```

---

## F. PROJECT_CREATE — 블로그 시스템 ⚡ (풀 인터뷰)

### Case 12: SimpleBlog 블로그 백엔드 (5턴)

> **난이도**: ★★★★
> **목표**: 중간 복잡도의 PROJECT_CREATE — skymall/skystock과 다른 도메인으로 다양성 확보
> **기대 파일 수**: 25~35개

#### 턴별 스크립트

**Turn 1 — 시작**
```
블로그 시스템을 만들고 싶어
```
**기대**: ASK

**Turn 2 — 기술스택 + 기본정보**
```
Kotlin + Spring Boot 4.0.1 + JPA + MySQL. Gradle 빌드. 이름은 simpleblog, 패키지 com.skytree.simpleblog, 포트 9092. DB는 home.skyepub.net:63306/simpleblog, root/Mako2122!
```
**기대**: ASK (기능/엔티티 질문)

**Turn 3 — 엔티티 + 기능**
```
엔티티와 핵심 기능을 정리해줄게.

1. User: username(VARCHAR 50, UNIQUE), email(VARCHAR 100, UNIQUE), password(VARCHAR 255, BCrypt), displayName(VARCHAR 100), bio(TEXT nullable), role(ENUM: ADMIN/AUTHOR/READER, 기본 READER), isEnabled(BOOLEAN, 기본 true), createdAt(DATETIME)

2. Post: title(VARCHAR 200), content(TEXT), slug(VARCHAR 200, UNIQUE, URL용), status(ENUM: DRAFT/PUBLISHED/ARCHIVED, 기본 DRAFT), author FK(ManyToOne LAZY → User), viewCount(INT, 기본 0), createdAt, updatedAt, publishedAt(DATETIME nullable). ManyToMany → Tag

3. Comment: post FK(ManyToOne LAZY), author FK(ManyToOne LAZY → User), content(TEXT), isApproved(BOOLEAN, 기본 false), createdAt

4. Tag: name(VARCHAR 50, UNIQUE). ManyToMany → Post (중간 테이블 post_tags)

핵심 로직:
- DRAFT→PUBLISHED 시 publishedAt 자동 설정
- slug는 title에서 자동 생성 (한글→영문 변환 불필요, 그냥 숫자+타임스탬프)
- Comment는 ADMIN/AUTHOR가 승인해야 공개 (isApproved)
- viewCount는 GET /posts/{slug} 호출 시 자동 증가
```
**기대**: ASK 또는 CONFIRM

**Turn 4 — API + 보안**
```
API와 보안.

[Auth] /api/auth (Public):
- POST /register (201), POST /login → TokenResponse{accessToken, userId, username, role}

[Posts] /api/posts:
- GET / (페이징, PUBLISHED만), GET /{slug} (PUBLISHED, viewCount++), GET /drafts (AUTHOR 본인것)
- GET /search?keyword= (title/content LIKE), GET /tag/{tagName} (페이징), GET /author/{authorId}
- POST / (AUTHOR이상, 201), PATCH /{id} (본인 또는 ADMIN)
- POST /{id}/publish (DRAFT→PUBLISHED), POST /{id}/archive (PUBLISHED→ARCHIVED)
- DELETE /{id} (본인 또는 ADMIN, 204)

[Comments] /api/posts/{postId}/comments:
- GET / (승인된 것만, 페이징), GET /pending (ADMIN/AUTHOR)
- POST / (인증, 201), PATCH /{commentId}/approve (ADMIN/AUTHOR)
- DELETE /{commentId} (본인 또는 ADMIN, 204)

[Tags] /api/tags:
- GET / (List), GET /{id}, GET /popular (상위 10개, 게시물 수 기준)
- POST / (AUTHOR이상, 201), DELETE /{id} (ADMIN, 204)

[Stats] /api/stats (ADMIN):
- GET /dashboard: totalPosts, publishedPosts, draftPosts, totalComments, pendingComments, totalUsers, topAuthors(List), popularTags(List)

[보안] JWT, BCrypt, STATELESS. /api/auth/** + GET /api/posts/** + GET /api/tags/** public.
ROLE_ADMIN: 전체, ROLE_AUTHOR: 글쓰기+댓글관리, ROLE_READER: 읽기+댓글

[예외] GlobalExceptionHandler, BusinessException → EntityNotFoundException(404), DuplicateException(409), UnauthorizedAccessException(403)

[DataInitializer] userRepo.count()==0:
- 사용자 4명: admin/admin123(ADMIN), author1/author1pass(AUTHOR), author2/author2pass(AUTHOR), reader1/reader1pass(READER)
- 태그 8개: Kotlin, Spring, JPA, DevOps, Frontend, Backend, Tutorial, Review
- 게시글 10개: PUBLISHED 7, DRAFT 3. 각 1~3개 태그
- 댓글 15개: 승인 10, 미승인 5

이제 작업지시서를 만들어줘.
```
**기대**: CONFIRM + WorkOrder

**Turn 5 — WorkOrder 확인**
- `"좋아, 만들어줘"` → EXECUTE

#### WorkOrder 품질 체크 (12항목)

| # | 체크 항목 | 확인 방법 |
|---|-----------|-----------|
| 1 | simpleblog | `"simpleblog" in wo` |
| 2 | com.skytree.simpleblog | `"com.skytree.simpleblog" in wo` |
| 3 | 9092 | `"9092" in wo` |
| 4 | Post | `"Post" in wo` |
| 5 | Comment | `"Comment" in wo` |
| 6 | Tag | `"Tag" in wo` |
| 7 | AUTHOR | `"AUTHOR" in wo` |
| 8 | slug | `"slug" in wo` |
| 9 | DRAFT/PUBLISHED | `"DRAFT" in wo and "PUBLISHED" in wo` |
| 10 | isApproved | `"isApproved" in wo or "approve" in wo.lower()` |
| 11 | /api/posts | `"/api/posts" in wo` |
| 12 | viewCount | `"viewCount" in wo or "view" in wo.lower()` |

**PASS 기준**: 10/12 이상

---

## G. 반복 리파인 + 코드 리뷰

### Case 13: 반복 리파인 — TODO CLI (4턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/todo.py에 간단한 TODO 리스트 CLI를 만들어줘. add 기능만" | EXECUTE |
| 2 | "삭제(delete) 기능도 넣어줘" | EXECUTE |
| 3 | "목록 보기(list)와 완료 표시(done) 기능도 추가해" | EXECUTE |
| 4 | "최종 코드 보여줘" | EXECUTE (FILE_READ) 또는 REPLY |

**Hard Assert**:
- Turn 3 이후: add, delete, list, done 4개 기능 모두 포함
- 이전 기능이 덮어써지지 않음

---

### Case 14: 코드 리뷰 + 버그 수정 (3턴)

사전 준비: 버그가 있는 파일 생성
```python
# /tmp/wiiiv-test-v2/buggy.py
def divide(a, b):
    return a / b

result = divide(10, 0)
print(result)
```

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/buggy.py 코드 봐줘" | EXECUTE (FILE_READ) 또는 REPLY |
| 2 | "divide 함수에 0 나누기 예외 처리 추가해서 수정해줘" | EXECUTE (FILE_WRITE) |
| 3 | "수정된 파일 확인" | EXECUTE (FILE_READ) |

**Hard Assert**: try/except 또는 guard clause 추가됨

---

## H. 빌드 + 실행 검증

### Case 15: 코드 생성 + 실행 — skystock 알림 요약 (3턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/alert_summary.py — skystock에서 전체 재고 알림 조회, 레벨별(CRITICAL/WARNING/NORMAL) 건수 출력, CRITICAL 있으면 exit code 1. admin/admin123, home.skyepub.net:9091" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "만들어" | EXECUTE |
| 3 | "스크립트 실행해봐" | EXECUTE (COMMAND) |

**Hard Assert**: 파일 생성 + COMMAND 실행 기록

---

### Case 16: Python 패키지 구조 + 빌드 (3턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "/tmp/wiiiv-test-v2/mypackage 프로젝트. Python 패키지 구조로 setup.py 포함. 패키지명 mylib, 버전 1.0" | ASK 또는 CONFIRM |
| 2 | (필요시 답변 후) "만들어" | EXECUTE |
| 3 | "빌드 해봐" | EXECUTE (COMMAND) |

**Hard Assert**: setup.py + __init__.py 생성, COMMAND step

---

## I. 크로스 시스템 통합

### Case 17: skymall↔skystock 재고→공급사 매핑 (3턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "skymall에서 재고 부족 상품을 조회하고, skystock에서 공급사를 찾아서 '상품명 → 공급사명' 매핑 표를 만드는 스크립트" | ASK |
| 2 | "skymall: home.skyepub.net:9090 (인증 불필요), skystock: home.skyepub.net:9091 (admin/admin123). /tmp/wiiiv-test-v2/stock_supplier_map.py" | CONFIRM |
| 3 | "만들어" | EXECUTE |

**Hard Assert**:
- skymall `GET /api/products/low-stock`
- skystock `POST /api/auth/login` + `GET /api/suppliers/by-product/{id}`
- 두 URL 분리 (9090/9091)

---

### Case 18: 자동 발주 배치 스크립트 (4턴)

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | "재고 부족 상품에 대해 자동 발주서를 생성하는 배치 스크립트" | ASK |
| 2 | "skymall에서 재고 20개 이하 상품 → skystock에서 공급사 조회 → 발주서(수량 100) 자동 생성" | ASK 또는 CONFIRM |
| 3 | "skymall(home.skyepub.net:9090), skystock(home.skyepub.net:9091, admin/admin123). /tmp/wiiiv-test-v2/auto_reorder.py. --dry-run 옵션" | CONFIRM |
| 4 | "만들어" | EXECUTE |

**Hard Assert**:
- `GET /api/products/low-stock?threshold=20`
- skystock 로그인 + `POST /api/purchase-orders`
- `--dry-run` 옵션 (argparse)

---

## J. WorkOrder 수정/보완 루프

### Case 19: WorkOrder 부분 수정 — 엔티티 추가 (Case 11 연속)

> 이 케이스는 Case 11(skystock)의 WorkOrder가 생성된 이후, 같은 세션에서 수정을 요청하는 시나리오

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | (Case 11 Turn 6 이후) "잠깐, StockAlert에 lastNotifiedAt(DATETIME nullable) 필드를 추가하고, /api/stock-alerts/{id}/notify POST 엔드포인트도 추가해줘. 호출하면 alertLevel을 한 단계 올리고 lastNotifiedAt을 현재 시각으로 업데이트" | CONFIRM (수정된 WorkOrder) |
| 2 | "좋아, 만들어" | EXECUTE |

**Hard Assert**:
- WorkOrder에 lastNotifiedAt 포함
- /api/stock-alerts/{id}/notify 엔드포인트 포함
- 이전 WorkOrder 내용이 유실되지 않음 (SupplierProduct, PurchaseItem 등 그대로)

---

### Case 20: WorkOrder 거부 + 재생성 — 기술스택 변경 (Case 12 연속)

> 이 케이스는 Case 12(blog)의 WorkOrder에서 기술스택 변경을 요청하는 시나리오

| Turn | 입력 | 기대 |
|------|------|------|
| 1 | (Case 12 Turn 4 이후) "잠깐, Spring Boot 대신 Ktor로 변경해줘. ktor-server-netty, ktor-serialization-kotlinx-json, exposed ORM 사용. 나머지 기능은 동일" | CONFIRM (기술스택 변경된 WorkOrder) |
| 2 | WorkOrder 확인 후 "역시 Spring Boot로 가자. 원래대로 돌려줘" | CONFIRM (원래 WorkOrder) |
| 3 | "만들어" | EXECUTE |

**Hard Assert**:
- Turn 1: WorkOrder에 Ktor/Exposed 포함, Spring Boot 제거
- Turn 2: WorkOrder에 Spring Boot 복원
- Turn 3: 최종 코드가 Spring Boot 기반

---

## 수동 테스트 가이드

### 준비

```bash
# 1. wiiiv 서버 확인
curl -s http://localhost:8235/api/v2/system/health | python3 -m json.tool

# 2. 토큰 발급
TOKEN=$(curl -s http://localhost:8235/api/v2/auth/auto-login | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['data']['accessToken'])")

# 3. 세션 생성
SID=$(curl -s -X POST http://localhost:8235/api/v2/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" -d '{}' | \
  python3 -c "import sys,json; print(json.load(sys.stdin)['data']['sessionId'])")
echo "Session: $SID"
```

### 대화

```bash
# 메시지 전송 (SSE)
curl -N -X POST "http://localhost:8235/api/v2/sessions/$SID/chat" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"message": "프로젝트 하나 만들어줘"}'
```

### 대화 이력 확인

```bash
curl -s "http://localhost:8235/api/v2/sessions/$SID/history" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

### 세션 상태 확인 (DraftSpec, WorkOrder)

```bash
curl -s "http://localhost:8235/api/v2/sessions/$SID/state" \
  -H "Authorization: Bearer $TOKEN" | python3 -m json.tool
```

---

## 자동화 HST

```bash
# 전체 실행
python3 /tmp/hst-phase6.py

# 특정 케이스만
python3 /tmp/hst-phase6.py --case 10   # skymall만
python3 /tmp/hst-phase6.py --case 11   # skystock만
python3 /tmp/hst-phase6.py --case 12   # blog만

# PROJECT_CREATE만
python3 /tmp/hst-phase6.py --section D E F

# 결과 확인
cat /tmp/hst6-results.json | python3 -m json.tool
```
