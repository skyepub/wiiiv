# SkyMall REST API — 실전 호출 가이드

> **반드시 이 URL을 사용할 것**: `http://home.skyepub.net:9090`
> 프로토콜: **http** (https 아님)
> 호스트: **home.skyepub.net**
> 포트: **9090**

---

## 인증

### 로그인 (토큰 발급)

```
POST http://home.skyepub.net:9090/api/auth/login
Content-Type: application/json

{"username":"jane_smith","password":"pass1234"}
```

응답:
```json
{"accessToken":"eyJ...","userId":2,"username":"jane_smith","role":"ADMIN"}
```

> 토큰 필드명: **accessToken** (token 아님)
> 이후 모든 인증 필요 API에 `Authorization: Bearer <accessToken>` 헤더 추가

### 계정 정보

| username | password | role |
|----------|----------|------|
| john_doe | pass1234 | ADMIN |
| jane_smith | pass1234 | ADMIN |
| peter_jones | pass1234 | MANAGER |
| susan_lee | pass1234 | MANAGER |
| michael_brown | pass1234 | USER |
| emily_davis | pass1234 | USER |

전체 20명, 기본 비밀번호 모두 `pass1234`

### 권한

| 리소스 | GET | POST/PATCH | DELETE |
|--------|-----|-----------|--------|
| /api/products/** | Public (인증 불필요) | ADMIN, MANAGER | ADMIN, MANAGER |
| /api/categories/** | Public (인증 불필요) | ADMIN, MANAGER | ADMIN, MANAGER |
| /api/orders/** | Authenticated | Authenticated | Authenticated |
| /api/orders/report | ADMIN, MANAGER | - | - |
| /api/users/** | ADMIN, MANAGER | ADMIN, MANAGER | ADMIN |

---

## 카테고리 API (인증 불필요)

**전체 조회**
```
GET http://home.skyepub.net:9090/api/categories
```

**카테고리별 상품**
```
GET http://home.skyepub.net:9090/api/categories/{id}/products?page=0&size=20
```

**카테고리 요약 (상품수, 평균가격)**
```
GET http://home.skyepub.net:9090/api/categories/summary
```

카테고리 목록 (8개):

| ID | 이름 | 상품수 | 평균가격 |
|----|------|--------|---------|
| 1 | Electronics | 7 | $634 |
| 2 | Clothing | 6 | $81 |
| 3 | Books | 5 | $45 |
| 4 | Home & Kitchen | 4 | $260 |
| 5 | Sports & Outdoors | 5 | $227 |
| 6 | Beauty | 4 | $43 |
| 7 | Toys & Games | 3 | $80 |
| 8 | Food & Beverages | 3 | $26 |

---

## 상품 API (GET은 인증 불필요)

**전체 조회 (페이지네이션)**
```
GET http://home.skyepub.net:9090/api/products?page=0&size=20&sort=price,desc
```

**단건 조회**
```
GET http://home.skyepub.net:9090/api/products/{id}
```

**검색**
```
GET http://home.skyepub.net:9090/api/products/search?keyword=laptop&page=0&size=10
```

**가격대 필터**
```
GET http://home.skyepub.net:9090/api/products/price-range?min=100&max=500&page=0&size=10
```

**재고 부족 상품 (★ 중요)**
```
GET http://home.skyepub.net:9090/api/products/low-stock?threshold=30&page=0&size=50
```

**미판매 상품**
```
GET http://home.skyepub.net:9090/api/products/unsold?page=0&size=10
```

**상품 생성** (ADMIN/MANAGER 인증 필요)
```
POST http://home.skyepub.net:9090/api/products
Authorization: Bearer <accessToken>
Content-Type: application/json

{"name":"상품명","description":"설명","price":100.0,"stock":50,"categoryId":1}
```

**상품 수정** (ADMIN/MANAGER 인증 필요)
```
PATCH http://home.skyepub.net:9090/api/products/{id}
Authorization: Bearer <accessToken>
Content-Type: application/json

{"price":89.99,"stock":200}
```

**재고 보충 (★ PATCH임, POST 아님)** (ADMIN/MANAGER 인증 필요)
```
PATCH http://home.skyepub.net:9090/api/products/{id}/restock
Authorization: Bearer <accessToken>
Content-Type: application/json

{"quantity":50}
```

### 상품 데이터 (37개)

**Electronics (category 1)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 1 | 4K Smart TV 65 inch | 799.99 | 46 |
| 2 | Wireless Noise-Cancelling Headphones | 349.99 | 120 |
| 3 | Laptop Pro 15 inch | 1999.99 | 29 |
| 4 | Smartphone X | 999.99 | 199 |
| 5 | Bluetooth Speaker | 89.99 | 149 |
| 29 | Wireless Gaming Mouse | 69.99 | 179 |
| 30 | Mechanical Keyboard | 129.99 | 89 |

**Clothing (category 2)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 6 | Men's Classic T-Shirt | 19.99 | 300 |
| 7 | Women's Denim Jeans | 79.99 | 150 |
| 8 | Running Shoes | 120.00 | 199 |
| 9 | Winter Jacket | 199.99 | 79 |
| 31 | Wool Scarf | 34.99 | 248 |
| 32 | Leather Belt | 29.99 | 199 |

**Books (category 3)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 10 | The Art of SQL | 49.99 | 95 |
| 11 | History of the World | 39.99 | 119 |
| 12 | Science Fiction Anthology | 29.99 | 249 |
| 33 | Modern Web Development | 59.99 | 79 |
| 34 | AI and Machine Learning Basics | 44.99 | 119 |

**Home & Kitchen (category 4)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 13 | Espresso Machine | 499.99 | 39 |
| 14 | Robot Vacuum Cleaner | 299.99 | 59 |
| 15 | Non-Stick Cookware Set | 149.99 | 90 |
| 35 | Air Fryer 5L | 89.99 | 69 |

**Sports & Outdoors (category 5)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 16 | Yoga Mat | 25.99 | 398 |
| 17 | Camping Tent - 4 Person | 180.00 | 69 |
| 18 | Mountain Bike | 650.00 | 25 |
| 36 | Adjustable Dumbbell Set | 199.99 | 44 |
| 37 | Hiking Backpack 40L | 79.99 | 84 |

**Beauty (category 6)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 19 | Vitamin C Serum | 28.99 | 198 |
| 20 | Moisturizing Cream | 35.99 | 148 |
| 21 | Sunscreen SPF 50+ | 18.99 | 299 |
| 22 | Hair Dryer Pro | 89.99 | 59 |

**Toys & Games (category 7)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 23 | LEGO City Space Station | 129.99 | 39 |
| 24 | Board Game - Strategy War | 49.99 | 78 |
| 25 | RC Racing Car | 59.99 | 98 |

**Food & Beverages (category 8)**

| ID | 상품명 | 가격 | 재고 |
|----|--------|------|------|
| 26 | Organic Green Tea Set | 24.99 | 492 |
| 27 | Dark Chocolate Assortment | 32.99 | 197 |
| 28 | Artisan Coffee Beans 1kg | 19.99 | 295 |

---

## 주문 API (전부 인증 필요)

**전체 주문 조회**
```
GET http://home.skyepub.net:9090/api/orders?page=0&size=10&sort=orderDate,desc
Authorization: Bearer <accessToken>
```

**주문 생성**
```
POST http://home.skyepub.net:9090/api/orders
Authorization: Bearer <accessToken>
Content-Type: application/json

{"userId":1,"items":[{"productId":3,"quantity":1},{"productId":8,"quantity":2}]}
```

**내 주문 조회**
```
GET http://home.skyepub.net:9090/api/orders/my?page=0&size=10
Authorization: Bearer <accessToken>
```

**상품별 주문 조회**
```
GET http://home.skyepub.net:9090/api/orders/product/{productId}?page=0&size=10
Authorization: Bearer <accessToken>
```

**매출 리포트** (ADMIN/MANAGER만)
```
GET http://home.skyepub.net:9090/api/orders/report?from=2025-01-01T00:00:00&to=2026-12-31T23:59:59
Authorization: Bearer <accessToken>
```

총 주문 44건, 16명의 사용자가 주문.

---

## 사용자 API (ADMIN/MANAGER 인증 필요)

**전체 사용자 조회**
```
GET http://home.skyepub.net:9090/api/users?page=0&size=20
Authorization: Bearer <accessToken>
```

**사용자 프로필 (주문 통계 포함)**
```
GET http://home.skyepub.net:9090/api/users/{id}/profile
Authorization: Bearer <accessToken>
```

---

## 응답 형식

### 페이지네이션
리스트 API는 `Page` 형태로 응답:
```json
{
  "content": [...],
  "totalElements": 37,
  "totalPages": 2,
  "number": 0,
  "size": 20
}
```

### 에러
```json
{"error":"NOT_FOUND","message":"상품을 찾을 수 없습니다: 9999"}
```

| HTTP | error | 설명 |
|------|-------|------|
| 400 | BAD_REQUEST | 비즈니스 규칙 위반 |
| 404 | NOT_FOUND | 엔티티 없음 |
| 409 | DUPLICATE | 중복 |
| 422 | INSUFFICIENT_STOCK | 재고 부족 |
