# SkyMall Shopping Mall REST API Specification v1.0

## Overview

SkyMall is a shopping mall backend that provides product catalog management, user management, order processing, and sales reporting through a RESTful API. Built with Spring Boot and secured with JWT authentication.

- **Base URL**: `http://home.skyepub.net:9090`
- **Content-Type**: `application/json`
- **Authentication**: Bearer Token (JWT)

---

## Authentication

SkyMall uses JWT (JSON Web Token) for authentication. Tokens are obtained via the login or register endpoints and must be included in the `Authorization` header for protected endpoints.

**Header format:**
```
Authorization: Bearer <accessToken>
```

Tokens expire after 24 hours.

### User Roles

| Role      | Description                                      |
|-----------|--------------------------------------------------|
| `ADMIN`   | Full access. Can manage users, products, orders.  |
| `MANAGER` | Can manage products and categories. Can view users and reports. |
| `USER`    | Can browse products, place orders, view own orders.|

### Access Control Summary

| Resource                        | GET (Read)        | POST/PATCH (Write) | DELETE             |
|---------------------------------|-------------------|--------------------|--------------------|
| `/api/auth/**`                  | -                 | Public             | -                  |
| `/api/products/**`              | Public            | ADMIN, MANAGER     | ADMIN, MANAGER     |
| `/api/categories/**`            | Public            | ADMIN, MANAGER     | ADMIN, MANAGER     |
| `/api/users/**`                 | ADMIN, MANAGER    | ADMIN, MANAGER     | ADMIN only         |
| `/api/orders/**`                | Authenticated     | Authenticated      | Authenticated      |
| `/api/orders/report`            | ADMIN, MANAGER    | -                  | -                  |

---

## Pagination

List endpoints return paginated results using Spring Data `Page` format.

**Query Parameters:**

| Parameter | Type    | Default | Description                                    |
|-----------|---------|---------|------------------------------------------------|
| `page`    | Integer | 0       | Page number (zero-based)                       |
| `size`    | Integer | 20      | Number of items per page                       |
| `sort`    | String  | -       | Sort field and direction (e.g., `price,desc`)  |

**Example:** `GET /api/products?page=0&size=5&sort=price,desc`

**Paginated Response Structure:**
```json
{
  "content": [ ... ],
  "totalElements": 18,
  "totalPages": 4,
  "number": 0,
  "size": 5,
  "first": true,
  "last": false,
  "empty": false,
  "numberOfElements": 5,
  "pageable": {
    "pageNumber": 0,
    "pageSize": 5,
    "offset": 0,
    "paged": true,
    "sort": { "sorted": true, "unsorted": false, "empty": false }
  },
  "sort": { "sorted": true, "unsorted": false, "empty": false }
}
```

---

## Error Responses

All errors follow a consistent format:

```json
{
  "error": "ERROR_CODE",
  "message": "Human-readable error message"
}
```

| HTTP Status | Error Code           | Description                          |
|-------------|----------------------|--------------------------------------|
| 400         | `BAD_REQUEST`        | Business rule violation              |
| 401         | Unauthorized         | Missing or invalid JWT token         |
| 403         | Forbidden            | Insufficient role/permissions        |
| 404         | `NOT_FOUND`          | Entity not found                     |
| 409         | `DUPLICATE`          | Duplicate entry (username, email, etc.) |
| 422         | `INSUFFICIENT_STOCK` | Product stock is insufficient        |

---

## 1. Auth API

### 1.1 Register

Creates a new user account and returns a JWT token.

- **URL**: `POST /api/auth/register`
- **Auth**: Not required
- **Status**: `201 Created`

**Request Body:**
```json
{
  "username": "john_doe",
  "email": "john@example.com",
  "password": "mypassword",
  "role": "USER"
}
```

| Field      | Type   | Required | Description                               |
|------------|--------|----------|-------------------------------------------|
| `username` | String | Yes      | Unique username (max 50 chars)            |
| `email`    | String | Yes      | Unique email (max 100 chars)              |
| `password` | String | Yes      | Plain-text password (will be BCrypt-hashed)|
| `role`     | String | No       | `ADMIN`, `MANAGER`, or `USER` (default: `USER`) |

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
  "userId": 1,
  "username": "john_doe",
  "role": "USER"
}
```

### 1.2 Login

Authenticates a user and returns a JWT token.

- **URL**: `POST /api/auth/login`
- **Auth**: Not required
- **Status**: `200 OK`

**Request Body:**
```json
{
  "username": "john_doe",
  "password": "mypassword"
}
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzM4NCJ9...",
  "userId": 1,
  "username": "john_doe",
  "role": "USER"
}
```

**Errors:**
- `400 BAD_REQUEST` — Wrong username or password
- `400 BAD_REQUEST` — Account is disabled

---

## 2. Category API

### 2.1 Get All Categories

Returns all categories (not paginated, typically small set).

- **URL**: `GET /api/categories`
- **Auth**: Not required

**Response:**
```json
[
  { "id": 1, "name": "Electronics" },
  { "id": 2, "name": "Clothing" },
  { "id": 3, "name": "Books" },
  { "id": 4, "name": "Home & Kitchen" },
  { "id": 5, "name": "Sports & Outdoors" }
]
```

### 2.2 Get Category by ID

- **URL**: `GET /api/categories/{id}`
- **Auth**: Not required

**Response:**
```json
{ "id": 1, "name": "Electronics" }
```

**Errors:**
- `404 NOT_FOUND` — Category not found

### 2.3 Get Products by Category

Returns paginated products belonging to a specific category.

- **URL**: `GET /api/categories/{id}/products`
- **Auth**: Not required
- **Pagination**: Yes (`page`, `size`, `sort`)

**Example:** `GET /api/categories/1/products?page=0&size=2`

**Response:**
```json
{
  "content": [
    {
      "id": 1,
      "name": "4K Smart TV 65 inch",
      "description": "Stunning 4K display with smart features.",
      "price": 799.99,
      "stock": 47,
      "category": { "id": 1, "name": "Electronics" },
      "createdAt": "2025-09-05T22:01:09"
    },
    {
      "id": 2,
      "name": "Wireless Noise-Cancelling Headphones",
      "description": "Immersive sound experience with industry-leading noise cancellation.",
      "price": 349.99,
      "stock": 120,
      "category": { "id": 1, "name": "Electronics" },
      "createdAt": "2025-09-05T22:01:09"
    }
  ],
  "totalElements": 5,
  "totalPages": 3,
  "number": 0,
  "size": 2
}
```

### 2.4 Get Category Summary

Returns each category with product count and average price.

- **URL**: `GET /api/categories/summary`
- **Auth**: Not required

**Response:**
```json
[
  {
    "category": { "id": 1, "name": "Electronics" },
    "productCount": 5,
    "avgPrice": 847.99
  },
  {
    "category": { "id": 2, "name": "Clothing" },
    "productCount": 4,
    "avgPrice": 104.99
  },
  {
    "category": { "id": 3, "name": "Books" },
    "productCount": 3,
    "avgPrice": 39.99
  }
]
```

### 2.5 Create Category

- **URL**: `POST /api/categories`
- **Auth**: `ADMIN` or `MANAGER`
- **Status**: `201 Created`

**Request Body:**
```json
{ "name": "Toys" }
```

**Response:**
```json
{ "id": 6, "name": "Toys" }
```

**Errors:**
- `409 DUPLICATE` — Category name already exists

### 2.6 Delete Category

- **URL**: `DELETE /api/categories/{id}`
- **Auth**: `ADMIN` or `MANAGER`
- **Status**: `204 No Content`

**Errors:**
- `400 BAD_REQUEST` — Category has associated products (cannot delete)

---

## 3. Product API

### Product Object

```json
{
  "id": 1,
  "name": "4K Smart TV 65 inch",
  "description": "Stunning 4K display with smart features.",
  "price": 799.99,
  "stock": 47,
  "category": { "id": 1, "name": "Electronics" },
  "createdAt": "2025-09-05T22:01:09"
}
```

### 3.1 Get All Products (Paginated)

- **URL**: `GET /api/products`
- **Auth**: Not required
- **Pagination**: Yes (`page`, `size`, `sort`)

**Sortable fields:** `id`, `name`, `price`, `stock`, `createdAt`

**Example:** `GET /api/products?page=0&size=5&sort=price,desc`

**Response:** Paginated `Page<ProductResponse>`

### 3.2 Get Product by ID

- **URL**: `GET /api/products/{id}`
- **Auth**: Not required

**Example:** `GET /api/products/1`

**Response:**
```json
{
  "id": 1,
  "name": "4K Smart TV 65 inch",
  "description": "Stunning 4K display with smart features.",
  "price": 799.99,
  "stock": 47,
  "category": { "id": 1, "name": "Electronics" },
  "createdAt": "2025-09-05T22:01:09"
}
```

### 3.3 Search Products (Paginated)

Searches product name and description by keyword (case-insensitive).

- **URL**: `GET /api/products/search`
- **Auth**: Not required
- **Pagination**: Yes

| Parameter | Type   | Required | Description          |
|-----------|--------|----------|----------------------|
| `keyword` | String | Yes      | Search keyword       |

**Example:** `GET /api/products/search?keyword=laptop&page=0&size=10`

**Response:** Paginated `Page<ProductResponse>`

### 3.4 Get Products by Price Range (Paginated)

- **URL**: `GET /api/products/price-range`
- **Auth**: Not required
- **Pagination**: Yes

| Parameter | Type   | Required | Description    |
|-----------|--------|----------|----------------|
| `min`     | Double | Yes      | Minimum price  |
| `max`     | Double | Yes      | Maximum price  |

**Example:** `GET /api/products/price-range?min=100&max=500&page=0&size=10`

**Response:** Paginated `Page<ProductResponse>`

### 3.5 Get Low Stock Products (Paginated)

Returns products with stock below the given threshold.

- **URL**: `GET /api/products/low-stock`
- **Auth**: Not required
- **Pagination**: Yes

| Parameter   | Type    | Required | Default | Description     |
|-------------|---------|----------|---------|-----------------|
| `threshold` | Integer | No       | 5       | Stock threshold |

**Example:** `GET /api/products/low-stock?threshold=50&page=0&size=10`

### 3.6 Get Unsold Products (Paginated)

Returns products that have never been included in any order.

- **URL**: `GET /api/products/unsold`
- **Auth**: Not required
- **Pagination**: Yes

**Example:** `GET /api/products/unsold?page=0&size=10`

### 3.7 Create Product

- **URL**: `POST /api/products`
- **Auth**: `ADMIN` or `MANAGER`
- **Status**: `201 Created`

**Request Body:**
```json
{
  "name": "Bluetooth Speaker",
  "description": "Portable speaker with 12-hour battery life.",
  "price": 79.99,
  "stock": 100,
  "categoryId": 1
}
```

| Field        | Type    | Required | Description              |
|--------------|---------|----------|--------------------------|
| `name`       | String  | Yes      | Product name (max 100)   |
| `description`| String  | No       | Product description      |
| `price`      | Double  | Yes      | Price (must be positive) |
| `stock`      | Integer | No       | Initial stock (default 0)|
| `categoryId` | Integer | No       | Category ID to assign    |

**Response:** `ProductResponse`

### 3.8 Update Product (Partial)

Updates only the provided fields (null fields are skipped).

- **URL**: `PATCH /api/products/{id}`
- **Auth**: `ADMIN` or `MANAGER`

**Request Body:**
```json
{
  "price": 69.99,
  "stock": 150
}
```

| Field        | Type    | Required | Description              |
|--------------|---------|----------|--------------------------|
| `name`       | String  | No       | New name                 |
| `description`| String  | No       | New description          |
| `price`      | Double  | No       | New price                |
| `stock`      | Integer | No       | New stock quantity       |
| `categoryId` | Integer | No       | New category ID          |

**Response:** Updated `ProductResponse`

### 3.9 Restock Product

Adds quantity to existing stock.

- **URL**: `PATCH /api/products/{id}/restock`
- **Auth**: `ADMIN` or `MANAGER`

**Request Body:**
```json
{ "quantity": 50 }
```

| Field      | Type    | Required | Description                        |
|------------|---------|----------|------------------------------------|
| `quantity` | Integer | Yes      | Quantity to add (must be >= 1)     |

**Response:** Updated `ProductResponse` with new stock value.

**Errors:**
- `400 BAD_REQUEST` — Quantity must be >= 1

### 3.10 Delete Product

- **URL**: `DELETE /api/products/{id}`
- **Auth**: `ADMIN` or `MANAGER`
- **Status**: `204 No Content`

---

## 4. Order API

### Order Object

```json
{
  "id": 1,
  "userId": 1,
  "username": "john_doe",
  "orderDate": "2025-09-05T22:01:28",
  "totalAmount": 1349.98,
  "items": [
    {
      "id": 1,
      "productId": 4,
      "productName": "Smartphone X",
      "quantity": 1,
      "pricePerItem": 999.99,
      "subtotal": 999.99
    },
    {
      "id": 2,
      "productId": 2,
      "productName": "Wireless Noise-Cancelling Headphones",
      "quantity": 1,
      "pricePerItem": 349.99,
      "subtotal": 349.99
    }
  ]
}
```

### 4.1 Create Order

Creates a new order. Validates user, checks product stock, deducts stock, and calculates total — all in a single transaction.

- **URL**: `POST /api/orders`
- **Auth**: Authenticated user
- **Status**: `201 Created`

**Request Body:**
```json
{
  "userId": 1,
  "items": [
    { "productId": 4, "quantity": 1 },
    { "productId": 2, "quantity": 2 }
  ]
}
```

| Field              | Type    | Required | Description           |
|--------------------|---------|----------|-----------------------|
| `userId`           | Integer | Yes      | Ordering user ID      |
| `items`            | Array   | Yes      | List of order items (min 1)|
| `items[].productId`| Integer | Yes      | Product ID            |
| `items[].quantity` | Integer | Yes      | Quantity to order     |

**Response:** `OrderResponse`

**Errors:**
- `400 BAD_REQUEST` — Empty items list
- `400 BAD_REQUEST` — Disabled user
- `404 NOT_FOUND` — User or product not found
- `422 INSUFFICIENT_STOCK` — Not enough stock

### 4.2 Get All Orders (Paginated)

- **URL**: `GET /api/orders`
- **Auth**: Authenticated user
- **Pagination**: Yes

**Example:** `GET /api/orders?page=0&size=5&sort=orderDate,desc`

### 4.3 Get Order by ID

- **URL**: `GET /api/orders/{id}`
- **Auth**: Authenticated user

**Response:** `OrderResponse`

### 4.4 Get My Orders (Paginated)

Returns orders for the currently authenticated user (extracted from JWT token).

- **URL**: `GET /api/orders/my`
- **Auth**: Authenticated user
- **Pagination**: Yes

**Example:** `GET /api/orders/my?page=0&size=10`

### 4.5 Get Orders by User (Paginated)

- **URL**: `GET /api/orders/user/{userId}`
- **Auth**: Authenticated user
- **Pagination**: Yes

**Example:** `GET /api/orders/user/1?page=0&size=5`

### 4.6 Get Orders by Product (Paginated)

Returns all orders that contain the specified product.

- **URL**: `GET /api/orders/product/{productId}`
- **Auth**: Authenticated user
- **Pagination**: Yes

**Example:** `GET /api/orders/product/4?page=0&size=10`

### 4.7 Get High-Value Orders (Paginated)

Returns orders with total amount >= the specified minimum.

- **URL**: `GET /api/orders/high-value`
- **Auth**: Authenticated user
- **Pagination**: Yes

| Parameter   | Type   | Required | Description              |
|-------------|--------|----------|--------------------------|
| `minAmount` | Double | Yes      | Minimum order total      |

**Example:** `GET /api/orders/high-value?minAmount=500&page=0&size=10`

### 4.8 Get Large Orders (Paginated)

Returns orders containing at least the specified number of items.

- **URL**: `GET /api/orders/large`
- **Auth**: Authenticated user
- **Pagination**: Yes

| Parameter  | Type    | Required | Default | Description          |
|------------|---------|----------|---------|----------------------|
| `minItems` | Integer | No       | 3       | Minimum item count   |

**Example:** `GET /api/orders/large?minItems=2&page=0&size=10`

### 4.9 Get Orders by Date Range (Paginated)

- **URL**: `GET /api/orders/date-range`
- **Auth**: Authenticated user
- **Pagination**: Yes

| Parameter | Type             | Required | Description                        |
|-----------|------------------|----------|------------------------------------|
| `from`    | ISO DateTime     | Yes      | Start date (e.g., `2025-01-01T00:00:00`) |
| `to`      | ISO DateTime     | Yes      | End date (e.g., `2025-12-31T23:59:59`)   |

**Example:** `GET /api/orders/date-range?from=2025-01-01T00:00:00&to=2025-12-31T23:59:59&page=0&size=10`

### 4.10 Get User Order Summary

Returns aggregate statistics for a user's orders.

- **URL**: `GET /api/orders/user/{userId}/summary`
- **Auth**: Authenticated user

**Response:**
```json
{
  "totalOrders": 3,
  "totalRevenue": 2415.96,
  "avgOrderAmount": 805.32
}
```

### 4.11 Get Sales Report

Returns sales statistics for a date range. Includes top 5 orders by amount.

- **URL**: `GET /api/orders/report`
- **Auth**: `ADMIN` or `MANAGER`

| Parameter | Type         | Required | Description  |
|-----------|--------------|----------|--------------|
| `from`    | ISO DateTime | Yes      | Start date   |
| `to`      | ISO DateTime | Yes      | End date     |

**Example:** `GET /api/orders/report?from=2025-01-01T00:00:00&to=2025-12-31T23:59:59`

**Response:**
```json
{
  "from": "2025-01-01T00:00:00",
  "to": "2025-12-31T23:59:59",
  "orderCount": 12,
  "totalRevenue": 8542.50,
  "topOrders": [
    {
      "id": 1,
      "userId": 1,
      "username": "john_doe",
      "orderDate": "2025-09-05T22:01:28",
      "totalAmount": 1349.98,
      "items": [ ... ]
    }
  ]
}
```

### 4.12 Cancel Order

Cancels an order and restores product stock.

- **URL**: `DELETE /api/orders/{id}`
- **Auth**: Authenticated user
- **Status**: `204 No Content`

---

## 5. User API

### User Object

```json
{
  "id": 1,
  "username": "john_doe",
  "email": "john.doe@example.com",
  "alias": null,
  "role": "ADMIN",
  "isEnabled": true,
  "createdAt": "2025-09-05T22:00:32"
}
```

### 5.1 Get All Users (Paginated)

- **URL**: `GET /api/users`
- **Auth**: `ADMIN` or `MANAGER`
- **Pagination**: Yes

**Example:** `GET /api/users?page=0&size=10&sort=createdAt,desc`

### 5.2 Get User by ID

- **URL**: `GET /api/users/{id}`
- **Auth**: `ADMIN` or `MANAGER`

**Response:** `UserResponse`

### 5.3 Get User by Username

- **URL**: `GET /api/users/username/{username}`
- **Auth**: `ADMIN` or `MANAGER`

**Example:** `GET /api/users/username/john_doe`

**Response:** `UserResponse`

### 5.4 Get User Profile

Returns user info combined with order statistics (order count, total spent, average order amount).

- **URL**: `GET /api/users/{id}/profile`
- **Auth**: `ADMIN` or `MANAGER`

**Response:**
```json
{
  "user": {
    "id": 1,
    "username": "john_doe",
    "email": "john.doe@example.com",
    "alias": null,
    "role": "ADMIN",
    "isEnabled": true,
    "createdAt": "2025-09-05T22:00:32"
  },
  "orderCount": 3,
  "totalSpent": 2415.96,
  "avgOrderAmount": 805.32
}
```

### 5.5 Update User (Partial)

Updates only the provided fields.

- **URL**: `PATCH /api/users/{id}`
- **Auth**: `ADMIN` or `MANAGER`

**Request Body:**
```json
{
  "alias": "Johnny",
  "role": "MANAGER",
  "isEnabled": false
}
```

| Field       | Type    | Required | Description                |
|-------------|---------|----------|----------------------------|
| `alias`     | String  | No       | Display name               |
| `email`     | String  | No       | New email (must be unique) |
| `role`      | String  | No       | `ADMIN`, `MANAGER`, `USER` |
| `isEnabled` | Boolean | No       | Enable/disable account     |

**Response:** Updated `UserResponse`

### 5.6 Delete User

Deletes a user. Fails if the user has existing orders (must disable instead).

- **URL**: `DELETE /api/users/{id}`
- **Auth**: `ADMIN` only
- **Status**: `204 No Content`

**Errors:**
- `400 BAD_REQUEST` — User has order history (use disable instead)

---

## Data Model

### Entity Relationships

```
User (1) ──< (N) SalesOrder (1) ──< (N) OrderItem (N) >── (1) Product (N) >── (1) Category
```

- A **User** can have many **SalesOrders**
- A **SalesOrder** contains many **OrderItems**
- Each **OrderItem** references one **Product** (with snapshot price)
- A **Product** belongs to one **Category**
- A **Category** contains many **Products**

### Database Tables

| Table          | Primary Key      | Description            |
|----------------|------------------|------------------------|
| `users`        | `user_id` (INT)  | User accounts          |
| `categories`   | `category_id` (INT) | Product categories  |
| `products`     | `product_id` (INT)  | Product catalog     |
| `sales_orders` | `order_id` (INT)    | Customer orders     |
| `order_items`  | `order_item_id` (BIGINT) | Order line items |

---

## Sample Data

The database contains the following live data:

### Categories (8)

| ID | Name              | Products | Avg Price |
|----|-------------------|----------|-----------|
| 1  | Electronics       | 7        | $634.28   |
| 2  | Clothing          | 6        | $80.83    |
| 3  | Books             | 5        | $44.99    |
| 4  | Home & Kitchen    | 4        | $259.99   |
| 5  | Sports & Outdoors | 5        | $227.19   |
| 6  | Beauty            | 4        | $43.49    |
| 7  | Toys & Games      | 3        | $79.99    |
| 8  | Food & Beverages  | 3        | $25.99    |

### Products (37 total)

**Electronics (category 1)**
| ID | Name                                 | Price    | Stock |
|----|--------------------------------------|----------|-------|
| 1  | 4K Smart TV 65 inch                  | 799.99   | 46    |
| 2  | Wireless Noise-Cancelling Headphones | 349.99   | 120   |
| 3  | Laptop Pro 15 inch                   | 1999.99  | 29    |
| 4  | Smartphone X                         | 999.99   | 199   |
| 5  | Bluetooth Speaker                    | 89.99    | 149   |
| 29 | Wireless Gaming Mouse                | 69.99    | 179   |
| 30 | Mechanical Keyboard                  | 129.99   | 89    |

**Clothing (category 2)**
| ID | Name                  | Price  | Stock |
|----|-----------------------|--------|-------|
| 6  | Men's Classic T-Shirt | 19.99  | 300   |
| 7  | Women's Denim Jeans   | 79.99  | 150   |
| 8  | Running Shoes         | 120.00 | 199   |
| 9  | Winter Jacket         | 199.99 | 79    |
| 31 | Wool Scarf            | 34.99  | 248   |
| 32 | Leather Belt          | 29.99  | 199   |

**Books (category 3)**
| ID | Name                           | Price | Stock |
|----|--------------------------------|-------|-------|
| 10 | The Art of SQL                 | 49.99 | 95    |
| 11 | History of the World           | 39.99 | 119   |
| 12 | Science Fiction Anthology      | 29.99 | 249   |
| 33 | Modern Web Development         | 59.99 | 79    |
| 34 | AI and Machine Learning Basics | 44.99 | 119   |

**Home & Kitchen (category 4)**
| ID | Name                   | Price  | Stock |
|----|------------------------|--------|-------|
| 13 | Espresso Machine       | 499.99 | 39    |
| 14 | Robot Vacuum Cleaner   | 299.99 | 59    |
| 15 | Non-Stick Cookware Set | 149.99 | 90    |
| 35 | Air Fryer 5L           | 89.99  | 69    |

**Sports & Outdoors (category 5)**
| ID | Name                   | Price  | Stock |
|----|------------------------|--------|-------|
| 16 | Yoga Mat               | 25.99  | 398   |
| 17 | Camping Tent - 4 Person| 180.00 | 69    |
| 18 | Mountain Bike          | 650.00 | 25    |
| 36 | Adjustable Dumbbell Set| 199.99 | 44    |
| 37 | Hiking Backpack 40L    | 79.99  | 84    |

**Beauty (category 6)**
| ID | Name              | Price | Stock |
|----|-------------------|-------|-------|
| 19 | Vitamin C Serum   | 28.99 | 198   |
| 20 | Moisturizing Cream| 35.99 | 148   |
| 21 | Sunscreen SPF 50+ | 18.99 | 299   |
| 22 | Hair Dryer Pro    | 89.99 | 59    |

**Toys & Games (category 7)**
| ID | Name                     | Price  | Stock |
|----|--------------------------|--------|-------|
| 23 | LEGO City Space Station  | 129.99 | 39    |
| 24 | Board Game - Strategy War| 49.99  | 78    |
| 25 | RC Racing Car            | 59.99  | 98    |

**Food & Beverages (category 8)**
| ID | Name                     | Price | Stock |
|----|--------------------------|-------|-------|
| 26 | Organic Green Tea Set    | 24.99 | 492   |
| 27 | Dark Chocolate Assortment| 32.99 | 197   |
| 28 | Artisan Coffee Beans 1kg | 19.99 | 295   |

### Users (20 registered)

| ID | Username       | Email                        | Role    |
|----|----------------|------------------------------|---------|
| 1  | john_doe       | john.doe@example.com         | ADMIN   |
| 2  | jane_smith     | jane.smith@example.com       | ADMIN   |
| 3  | peter_jones    | peter.jones@example.com      | MANAGER |
| 4  | susan_lee      | susan.lee@example.com        | MANAGER |
| 5  | michael_brown  | michael.brown@example.com    | USER    |
| 6  | emily_davis    | emily.davis@example.com      | USER    |
| 7  | chris_wilson   | chris.wilson@example.com     | USER    |
| 8  | jessica_taylor | jessica.taylor@example.com   | USER    |
| 9  | david_robert   | david.robert@example.com     | USER    |
| 10 | lisa_white     | lisa.white@example.com       | USER    |
| 13 | admin          | admin@test.com               | ADMIN   |
| 18 | anna_kim       | anna.kim@example.com         | USER    |
| 19 | tom_park       | tom.park@example.com         | USER    |
| 20 | mia_choi       | mia.choi@example.com         | MANAGER |
| 21 | ryan_lee       | ryan.lee@example.com         | USER    |
| 22 | sofia_jung     | sofia.jung@example.com       | USER    |

All test user passwords: `pass1234`

### Orders (44 total)

| ID | User           | Total ($) | Items                                                        |
|----|----------------|-----------|--------------------------------------------------------------|
| 1  | john_doe       | 1,349.98  | Smartphone X x1, Wireless Headphones x1                      |
| 2  | jane_smith     | 109.97    | The Art of SQL x1, Science Fiction Anthology x2              |
| 3  | john_doe       | 265.99    | Running Shoes x2, Yoga Mat x1                                |
| 4  | susan_lee      | 649.98    | Espresso Machine x1, Non-Stick Cookware Set x1              |
| 5  | michael_brown  | 650.00    | Mountain Bike x1                                             |
| 6  | jessica_taylor | 139.96    | Men's Classic T-Shirt x3, Women's Denim Jeans x1            |
| 7  | peter_jones    | 2,049.98  | Laptop Pro 15 inch x1, The Art of SQL x1                    |
| 8  | emily_davis    | 599.97    | Robot Vacuum Cleaner x1, Non-Stick Cookware Set x2          |
| 9  | jane_smith     | 231.98    | Camping Tent x1, Yoga Mat x2                                |
| 10 | chris_wilson   | 319.96    | Winter Jacket x1, T-Shirt x2, Denim Jeans x1               |
| 11 | john_doe       | 979.97    | 4K Smart TV x1, Bluetooth Speaker x2                        |
| 13 | testuser       | 1,649.97  | 4K Smart TV x2, The Art of SQL x1                           |
| 14 | anna_kim       | 151.94    | Vitamin C Serum x2, Sunscreen x1, Green Tea Set x3          |
| 15 | tom_park       | 319.96    | Gaming Mouse x1, Mechanical Keyboard x1, RC Car x2          |
| 16 | mia_choi       | 194.97    | Modern Web Dev x1, AI/ML Basics x1, Air Fryer x1            |
| 17 | ryan_lee       | 469.96    | Dumbbell Set x1, Hiking Backpack x1, Running Shoes x1, Wool Scarf x2 |
| 18 | sofia_jung     | 2,799.98  | Laptop Pro 15 inch x1, 4K Smart TV x1                       |
| 19 | john_doe       | 263.90    | Green Tea Set x5, Dark Chocolate x3, Coffee Beans x2        |
| 20 | michael_brown  | 301.96    | Winter Jacket x1, Leather Belt x1, Moisturizing Cream x2    |
| 21 | emily_davis    | 319.96    | LEGO Space Station x1, Board Game x2, Bluetooth Speaker x1  |
| 22 | anna_kim       | 231.98    | Yoga Mat x2, Camping Tent x1                                |
| 23 | david_robert   | 1,059.96  | Smartphone X x1, Coffee Beans x3                            |
| 24 | lisa_white     | 889.97    | Espresso Machine x1, Robot Vacuum x1, Hair Dryer Pro x1     |
| 25 | tom_park       | 169.96    | The Art of SQL x2, History of the World x1, Sci-Fi Anthology x1 |
| 26 | jessica_taylor | 192.95    | Vitamin C Serum x1, Moisturizing Cream x1, Sunscreen x2, Hair Dryer x1 |
| 27 | chris_wilson   | 265.96    | Mechanical Keyboard x1, Gaming Mouse x1, Dark Chocolate x2  |
| 28 | sofia_jung     | 199.94    | Modern Web Dev x1, AI/ML Basics x1, Wool Scarf x1, T-Shirt x3 |
| 29 | jane_smith     | 739.97    | Espresso Machine x1, Air Fryer x1, Non-Stick Cookware Set x1|
| 30 | ryan_lee       | 329.92    | LEGO Space Station x1, RC Car x1, Green Tea Set x4, Coffee Beans x2 |
| 31 | susan_lee      | 1,349.98  | Smartphone X x1, Wireless Headphones x1                     |
| 32 | peter_jones    | 537.95    | Yoga Mat x3, Hiking Backpack x1, Dumbbell Set x1, Camping Tent x1 |
| 33 | anna_kim       | 315.97    | Women's Denim Jeans x1, Winter Jacket x1, Moisturizing Cream x1 |
| 34 | david_robert   | 264.94    | Art of SQL x1, History of World x2, Sci-Fi Anthology x1, AI/ML x1 |
| 35 | mia_choi       | 1,279.96  | 4K Smart TV x1, Robot Vacuum x1, Bluetooth Speaker x2       |
| 36 | tom_park       | 329.97    | Air Fryer x1, Non-Stick Cookware Set x1, Hair Dryer Pro x1  |
| 37 | lisa_white     | 297.94    | Running Shoes x1, Wool Scarf x2, Green Tea Set x3, Dark Chocolate x1 |
| 38 | john_doe       | 379.96    | LEGO Space Station x2, Board Game x1, Gaming Mouse x1       |
| 39 | michael_brown  | 514.80    | Green Tea Set x10, Dark Chocolate x5, Coffee Beans x5       |
| 40 | emily_davis    | 1,999.99  | Laptop Pro 15 inch x1                                        |
| 41 | sofia_jung     | 789.96    | Mountain Bike x1, Hiking Backpack x1, Coffee Beans x3       |
| 42 | jessica_taylor | 1,219.97  | Smartphone X x1, Bluetooth Speaker x1, Mechanical Keyboard x1|
| 43 | ryan_lee       | 469.95    | T-Shirt x2, Denim Jeans x1, Running Shoes x1, Winter Jacket x1, Belt x1 |
| 44 | chris_wilson   | 196.95    | Vitamin C Serum x3, Board Game x1, RC Car x1                |
| 45 | jane_smith     | 1,074.89  | 4K Smart TV x1, Moisturizing Cream x2, Green Tea Set x5, Yoga Mat x3 |

### User Order Summary

| User           | Orders | Total Spent ($) | Avg Order ($) |
|----------------|--------|-----------------|---------------|
| sofia_jung     | 3      | 3,789.88        | 1,263.29      |
| john_doe       | 5      | 3,239.80        | 647.96        |
| emily_davis    | 3      | 2,919.92        | 973.31        |
| peter_jones    | 2      | 2,587.93        | 1,293.97      |
| jane_smith     | 4      | 2,156.81        | 539.20        |
| susan_lee      | 2      | 1,999.96        | 999.98        |
| testuser       | 1      | 1,649.97        | 1,649.97      |
| jessica_taylor | 3      | 1,552.88        | 517.63        |
| mia_choi       | 2      | 1,474.93        | 737.47        |
| michael_brown  | 3      | 1,466.76        | 488.92        |
| david_robert   | 2      | 1,324.90        | 662.45        |
| ryan_lee       | 3      | 1,269.83        | 423.28        |
| lisa_white     | 2      | 1,187.91        | 593.96        |
| tom_park       | 3      | 819.89          | 273.30        |
| chris_wilson   | 3      | 782.87          | 260.96        |
| anna_kim       | 3      | 699.89          | 233.30        |

### Data Statistics

- **Total products**: 37 (across 8 categories)
- **Price range**: $18.99 (Sunscreen SPF 50+) ~ $1,999.99 (Laptop Pro 15 inch)
- **Total users**: 20 (5 ADMIN, 3 MANAGER, 12 USER)
- **Total orders**: 44 (from 16 different users)
- **Order value range**: $109.97 ~ $2,799.98
- **Top spender**: sofia_jung (3 orders, $3,789.88 total)
- **Most orders**: john_doe (5 orders, $3,239.80 total)
- **Highest single order**: sofia_jung — order #18, $2,799.98 (Laptop Pro + 4K Smart TV)
- **Largest item order**: ryan_lee — order #43, 5 different products in one order
- **Bulk buyer**: michael_brown — order #39, 20 items total (Food & Beverages bulk)

---

## Quick Start Example

**Step 1: Register and get token**
```
POST http://home.skyepub.net:9090/api/auth/register
Content-Type: application/json

{
  "username": "newuser",
  "email": "newuser@example.com",
  "password": "secret123",
  "role": "ADMIN"
}
```

**Step 2: Browse products**
```
GET http://home.skyepub.net:9090/api/products?page=0&size=5&sort=price,asc
```

**Step 3: Search products**
```
GET http://home.skyepub.net:9090/api/products/search?keyword=laptop&page=0&size=10
```

**Step 4: Place an order (requires token)**
```
POST http://home.skyepub.net:9090/api/orders
Authorization: Bearer <accessToken>
Content-Type: application/json

{
  "userId": 1,
  "items": [
    { "productId": 3, "quantity": 1 },
    { "productId": 8, "quantity": 2 }
  ]
}
```

**Step 5: Check order history**
```
GET http://home.skyepub.net:9090/api/orders/my?page=0&size=10
Authorization: Bearer <accessToken>
```

**Step 6: View sales report (ADMIN/MANAGER only)**
```
GET http://home.skyepub.net:9090/api/orders/report?from=2025-01-01T00:00:00&to=2025-12-31T23:59:59
Authorization: Bearer <accessToken>
```
