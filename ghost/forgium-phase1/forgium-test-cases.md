# Forgium Phase 1 — 20개 백엔드 프로젝트 생성 및 운영 테스트

## 목적
forgium(PROJECT_CREATE) 파이프라인의 품질을 검증한다.
- 다양한 도메인의 소규모 백엔드 프로젝트(API ~10개)를 forgium으로 생성
- 생성된 프로젝트를 실제 빌드 → 실행 → API 호출로 검증
- GHOST 방식의 비결정적 대화로 forgium 안정성 확인
- 발견된 버그는 즉시 수정 후 커밋

## 인프라
- **MySQL**: home.skyepub.net:63306 (root/Mako2122!)
- **wiiiv 서버**: localhost:8235
- **생성 프로젝트 포트**: 9101~9120
- **기술 스택**: Spring Boot 4 + Kotlin + JPA + MySQL + JWT

## DB 목록 (20개)
| # | DB | 프로젝트 | 포트 |
|---|-----|---------|------|
| 1 | fg_library | 도서관 대출 관리 | 9101 |
| 2 | fg_bookstore | 온라인 서점 | 9102 |
| 3 | fg_hospital | 병원 예약 시스템 | 9103 |
| 4 | fg_pharmacy | 약국 처방전 관리 | 9104 |
| 5 | fg_restaurant | 식당 주문 관리 | 9105 |
| 6 | fg_delivery | 배달 관리 | 9106 |
| 7 | fg_school | 학교 성적 관리 | 9107 |
| 8 | fg_academy | 학원 수강 관리 | 9108 |
| 9 | fg_hr | 인사 관리 | 9109 |
| 10 | fg_payroll | 급여 관리 | 9110 |
| 11 | fg_parking | 주차장 관리 | 9111 |
| 12 | fg_repair | 차량 정비소 | 9112 |
| 13 | fg_event | 이벤트/티켓 예매 | 9113 |
| 14 | fg_merch | 굿즈 판매 | 9114 |
| 15 | fg_vet | 동물병원 | 9115 |
| 16 | fg_petshop | 펫샵 | 9116 |
| 17 | fg_realestate | 부동산 매물 관리 | 9117 |
| 18 | fg_interior | 인테리어 견적 | 9118 |
| 19 | fg_gym | 헬스장 회원 관리 | 9119 |
| 20 | fg_nutrition | 영양 상담 | 9120 |

---

## FG-01: 도서관 + 서점 시스템

### Project A: library_mgmt (도서관 대출 관리)
- **DB**: fg_library / **Port**: 9101
- **엔티티**: Book, Member, Loan, Category
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/books, GET /api/books/{id}
  - GET/POST /api/members, GET /api/members/{id}
  - POST /api/loans (대출), PUT /api/loans/{id}/return (반납)
  - GET /api/loans/overdue (연체 목록)
- **역할**: ADMIN, LIBRARIAN, MEMBER
- **JWT 인증**: 필수
- **초기 데이터**: 도서 10권, 회원 3명, 대출 2건

### Project B: bookstore (온라인 서점)
- **DB**: fg_bookstore / **Port**: 9102
- **엔티티**: Book, Author, Order, OrderItem, Customer
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/books, GET /api/books/{id}, GET /api/books/search?q=
  - GET/POST /api/orders, GET /api/orders/{id}
  - GET /api/authors, GET /api/authors/{id}/books
  - GET /api/stats/bestsellers
- **역할**: ADMIN, CUSTOMER
- **JWT 인증**: 필수
- **초기 데이터**: 도서 15권, 저자 5명, 고객 3명

### 운영 테스트 시나리오
1. 도서관에서 대출 → 연체 확인 → 반납 처리
2. 서점에서 검색 → 주문 → 주문 내역 조회
3. 두 시스템 모두 JWT 인증 필수 경로 검증
4. 도서관 LIBRARIAN 권한만 대출 처리 가능 확인

### 활용 플러그인
- **DB**: MySQL CRUD 전체
- **JWT**: 로그인 → 토큰 발급 → 인증 API 호출
- **HTTP**: 서점→도서관 재고 확인 크로스 호출

---

## FG-02: 병원 + 약국 시스템

### Project A: hospital (병원 예약 시스템)
- **DB**: fg_hospital / **Port**: 9103
- **엔티티**: Patient, Doctor, Appointment, Department, Prescription
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/patients, GET /api/patients/{id}
  - GET/POST /api/doctors, GET /api/doctors/{id}/schedule
  - POST /api/appointments, PUT /api/appointments/{id}/cancel
  - GET /api/appointments/today
  - POST /api/prescriptions
- **역할**: ADMIN, DOCTOR, NURSE, PATIENT
- **초기 데이터**: 의사 3명, 환자 5명, 진료과 4개

### Project B: pharmacy (약국 처방전 관리)
- **DB**: fg_pharmacy / **Port**: 9104
- **엔티티**: Medicine, Prescription, Dispensing, Stock, Customer
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/medicines, GET /api/medicines/{id}
  - GET/POST /api/prescriptions, PUT /api/prescriptions/{id}/dispense
  - GET /api/stock/low (재고 부족 알림)
  - GET /api/dispensing/history?customerId=
  - PUT /api/stock/{medicineId}/restock
- **역할**: ADMIN, PHARMACIST
- **초기 데이터**: 약품 20종, 재고 정보

### 운영 테스트 시나리오
1. 병원에서 예약 → 진료 → 처방전 발급
2. 약국에서 처방전 조회 → 조제 완료 처리
3. 약국 재고 부족 알림 확인
4. DOCTOR 역할만 처방전 발급 가능 확인

### 활용 플러그인
- **DB**: 복합 쿼리 (진료과별 의사 조회, 재고 부족 조건 검색)
- **JWT**: 역할별 접근 제어 (DOCTOR vs PATIENT)
- **HTTP**: 약국→병원 처방전 조회

---

## FG-03: 식당 + 배달 시스템

### Project A: restaurant (식당 주문 관리)
- **DB**: fg_restaurant / **Port**: 9105
- **엔티티**: Menu, Category, Order, OrderItem, Table
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/menus, PUT /api/menus/{id}, DELETE /api/menus/{id}
  - GET /api/categories
  - POST /api/orders, GET /api/orders/{id}, PUT /api/orders/{id}/status
  - GET /api/tables/available
  - GET /api/stats/daily-sales
- **역할**: ADMIN, STAFF, CUSTOMER
- **초기 데이터**: 메뉴 15개, 카테고리 4개, 테이블 10개

### Project B: delivery (배달 관리)
- **DB**: fg_delivery / **Port**: 9106
- **엔티티**: DeliveryOrder, Driver, DeliveryStatus, Zone, Rating
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/deliveries, GET /api/deliveries/{id}
  - PUT /api/deliveries/{id}/assign (배달원 배정)
  - PUT /api/deliveries/{id}/status (상태 변경: 픽업→배달중→완료)
  - GET /api/drivers, GET /api/drivers/{id}/active
  - POST /api/ratings
  - GET /api/zones
- **역할**: ADMIN, DRIVER, CUSTOMER
- **초기 데이터**: 배달원 5명, 배달 존 3개

### 운영 테스트 시나리오
1. 식당 주문 → 배달 주문 생성 → 배달원 배정 → 상태 변경 → 완료
2. 메뉴 CRUD (ADMIN만 수정/삭제)
3. 일일 매출 통계 조회
4. 배달 평점 등록 및 조회

### 활용 플러그인
- **DB**: 주문 상태 머신 (PENDING→PREPARING→READY→PICKED_UP→DELIVERED)
- **JWT**: 역할별 (DRIVER만 배달 상태 변경)
- **HTTP**: 배달시스템→식당 주문정보 조회

---

## FG-04: 학교 + 학원 시스템

### Project A: school (학교 성적 관리)
- **DB**: fg_school / **Port**: 9107
- **엔티티**: Student, Teacher, Subject, Grade, Classroom
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/students, GET /api/students/{id}
  - GET/POST /api/subjects
  - POST /api/grades, GET /api/grades/student/{studentId}
  - GET /api/grades/subject/{subjectId}/ranking
  - GET /api/classrooms, GET /api/classrooms/{id}/students
  - GET /api/stats/average?subjectId=
- **역할**: ADMIN, TEACHER, STUDENT
- **초기 데이터**: 학생 10명, 교사 3명, 과목 5개, 성적 데이터

### Project B: academy (학원 수강 관리)
- **DB**: fg_academy / **Port**: 9108
- **엔티티**: Student, Course, Enrollment, Instructor, Schedule
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/courses, GET /api/courses/{id}
  - POST /api/enrollments, DELETE /api/enrollments/{id}
  - GET /api/enrollments/student/{studentId}
  - GET /api/schedules/today
  - GET /api/instructors, GET /api/instructors/{id}/courses
  - GET /api/courses/{id}/students
- **역할**: ADMIN, INSTRUCTOR, STUDENT
- **초기 데이터**: 강좌 8개, 강사 4명, 학생 15명

### 운영 테스트 시나리오
1. 학교: 성적 입력 → 과목별 랭킹 조회 → 평균 산출
2. 학원: 수강 신청 → 시간표 확인 → 수강 취소
3. TEACHER만 성적 입력 가능 확인
4. STUDENT는 자기 성적만 조회 가능 확인

### 활용 플러그인
- **DB**: 집계 쿼리 (평균, 랭킹)
- **JWT**: 세밀한 역할 분리 (TEACHER vs STUDENT)
- **FILE**: 성적표 데이터 export 시나리오

---

## FG-05: HR + 급여 시스템

### Project A: hr_mgmt (인사 관리)
- **DB**: fg_hr / **Port**: 9109
- **엔티티**: Employee, Department, Position, LeaveRequest, Attendance
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/employees, GET /api/employees/{id}
  - GET/POST /api/departments, GET /api/departments/{id}/members
  - POST /api/leaves, PUT /api/leaves/{id}/approve, PUT /api/leaves/{id}/reject
  - POST /api/attendance/checkin, POST /api/attendance/checkout
  - GET /api/attendance/monthly?employeeId=&month=
- **역할**: ADMIN, HR_MANAGER, EMPLOYEE
- **초기 데이터**: 직원 10명, 부서 4개, 직급 5개

### Project B: payroll (급여 관리)
- **DB**: fg_payroll / **Port**: 9110
- **엔티티**: PayrollRecord, Bonus, Deduction, TaxInfo, PaySlip
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/payroll, GET /api/payroll/{id}
  - POST /api/payroll/calculate?employeeId=&month=
  - GET /api/payroll/employee/{employeeId}/history
  - POST /api/bonuses, GET /api/bonuses/month/{month}
  - POST /api/deductions
  - GET /api/payslips/{payrollId}
  - GET /api/stats/monthly-total?month=
- **역할**: ADMIN, ACCOUNTANT
- **초기 데이터**: 급여 레코드 10건, 세금 정보

### 운영 테스트 시나리오
1. HR: 출근 체크인 → 퇴근 체크아웃 → 월간 근태 조회
2. HR: 휴가 신청 → 승인/반려 워크플로우
3. 급여: 월급 계산 → 상여금 추가 → 급여 명세서 조회
4. HR_MANAGER만 휴가 승인 가능 확인

### 활용 플러그인
- **DB**: 복합 집계 (월간 근태 통계, 급여 합산)
- **JWT**: HR_MANAGER vs EMPLOYEE 권한 분리
- **HTTP**: 급여→HR 직원정보/근태 조회
- **CRON**: 월말 급여 자동 계산 시나리오

---

## FG-06: 주차장 + 정비소 시스템

### Project A: parking (주차장 관리)
- **DB**: fg_parking / **Port**: 9111
- **엔티티**: Vehicle, ParkingSpot, ParkingRecord, Fee, Subscription
- **API** (~10개):
  - POST /api/auth/login
  - POST /api/parking/enter (입차), POST /api/parking/exit (출차)
  - GET /api/parking/current (현재 주차 차량)
  - GET /api/spots/available
  - GET /api/fees/calculate?vehicleId=
  - GET/POST /api/subscriptions (정기권)
  - GET /api/stats/daily-revenue
  - GET /api/vehicles/{plateNumber}/history
- **역할**: ADMIN, OPERATOR
- **초기 데이터**: 주차 구역 50개, 정기권 5건

### Project B: auto_repair (차량 정비소)
- **DB**: fg_repair / **Port**: 9112
- **엔티티**: Customer, Vehicle, RepairOrder, RepairItem, Part, Invoice
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/customers, GET /api/customers/{id}
  - POST /api/repair-orders, GET /api/repair-orders/{id}
  - PUT /api/repair-orders/{id}/status (접수→진행→완료)
  - GET/POST /api/parts, PUT /api/parts/{id}/stock
  - GET /api/invoices/{repairOrderId}
  - GET /api/vehicles/{id}/repair-history
- **역할**: ADMIN, MECHANIC, RECEPTIONIST
- **초기 데이터**: 부품 30종, 고객 5명

### 운영 테스트 시나리오
1. 주차장: 입차 → 주차 현황 → 출차 → 요금 계산
2. 정기권 등록 → 정기권 차량 입출차 시 요금 면제 확인
3. 정비소: 접수 → 부품 사용 → 완료 → 청구서 발행
4. 부품 재고 관리 (재고 차감, 입고)

### 활용 플러그인
- **DB**: 시간 기반 계산 (주차 시간 → 요금)
- **JWT**: OPERATOR만 입출차 처리
- **CRON**: 일일 매출 정산

---

## FG-07: 이벤트 + 굿즈샵 시스템

### Project A: event (이벤트/티켓 예매)
- **DB**: fg_event / **Port**: 9113
- **엔티티**: Event, Venue, Ticket, Booking, Seat
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/events, GET /api/events/{id}
  - GET /api/events/upcoming
  - POST /api/bookings, GET /api/bookings/{id}, DELETE /api/bookings/{id}
  - GET /api/bookings/my
  - GET /api/venues, GET /api/events/{id}/seats/available
  - GET /api/stats/popular
- **역할**: ADMIN, ORGANIZER, CUSTOMER
- **초기 데이터**: 이벤트 5개, 공연장 2개, 좌석 배치

### Project B: merchandise (굿즈 판매)
- **DB**: fg_merch / **Port**: 9114
- **엔티티**: Product, Category, Order, OrderItem, Review
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/products, GET /api/products/{id}
  - GET /api/products/category/{categoryId}
  - POST /api/orders, GET /api/orders/{id}, GET /api/orders/my
  - POST /api/reviews, GET /api/products/{id}/reviews
  - GET /api/categories
- **역할**: ADMIN, CUSTOMER
- **초기 데이터**: 굿즈 20개, 카테고리 5개

### 운영 테스트 시나리오
1. 이벤트: 이벤트 목록 → 좌석 확인 → 예매 → 취소
2. 굿즈: 카테고리별 상품 조회 → 주문 → 리뷰 작성
3. ORGANIZER만 이벤트 생성 가능 확인
4. 인기 이벤트 통계 조회

### 활용 플러그인
- **DB**: 좌석 가용성 체크 (동시성 고려)
- **JWT**: ORGANIZER vs CUSTOMER 역할 분리
- **HTTP**: 굿즈샵→이벤트 연동 (이벤트별 굿즈 추천)
- **MAIL**: 예매 확인 알림 시나리오

---

## FG-08: 동물병원 + 펫샵 시스템

### Project A: vet_clinic (동물병원)
- **DB**: fg_vet / **Port**: 9115
- **엔티티**: Pet, Owner, Veterinarian, Visit, Vaccination, Treatment
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/pets, GET /api/pets/{id}
  - GET/POST /api/owners, GET /api/owners/{id}/pets
  - POST /api/visits, GET /api/visits/{id}
  - POST /api/vaccinations, GET /api/pets/{id}/vaccinations
  - GET /api/vets, GET /api/vets/{id}/schedule
  - GET /api/visits/today
- **역할**: ADMIN, VET, RECEPTIONIST
- **초기 데이터**: 수의사 3명, 반려동물 10마리, 보호자 7명

### Project B: pet_shop (펫샵)
- **DB**: fg_petshop / **Port**: 9116
- **엔티티**: Product, Category, Order, OrderItem, PetFood, Supply
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/products, GET /api/products/{id}
  - GET /api/products/category/{categoryId}
  - GET /api/products/search?q=&petType=
  - POST /api/orders, GET /api/orders/{id}, GET /api/orders/my
  - GET /api/categories
  - GET /api/stats/top-selling
- **역할**: ADMIN, CUSTOMER
- **초기 데이터**: 상품 25종, 카테고리 6개 (사료/간식/장난감/용품/의류/건강)

### 운영 테스트 시나리오
1. 동물병원: 반려동물 등록 → 방문 기록 → 예방접종 기록
2. 펫샵: 펫 종류별 상품 검색 → 주문
3. VET만 진료/접종 기록 가능 확인
4. 보호자별 반려동물 목록 조회

### 활용 플러그인
- **DB**: 복합 검색 (펫 종류별, 카테고리별)
- **JWT**: VET vs RECEPTIONIST 역할 분리
- **HTTP**: 펫샵→동물병원 반려동물 건강 정보 연동
- **CRON**: 예방접종 리마인더 시나리오

---

## FG-09: 부동산 + 인테리어 시스템

### Project A: real_estate (부동산 매물 관리)
- **DB**: fg_realestate / **Port**: 9117
- **엔티티**: Property, Agent, Client, Showing, Contract
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/properties, GET /api/properties/{id}
  - GET /api/properties/search?type=&minPrice=&maxPrice=&region=
  - GET/POST /api/agents, GET /api/agents/{id}/properties
  - POST /api/showings (방문 예약)
  - POST /api/contracts, GET /api/contracts/{id}
  - GET /api/stats/market-summary
- **역할**: ADMIN, AGENT, CLIENT
- **초기 데이터**: 매물 15건, 중개사 3명, 고객 5명

### Project B: interior (인테리어 견적)
- **DB**: fg_interior / **Port**: 9118
- **엔티티**: Project, Client, Estimate, Material, Worker, Schedule
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/projects, GET /api/projects/{id}
  - PUT /api/projects/{id}/status (견적→시공→완료)
  - POST /api/estimates, GET /api/estimates/{projectId}
  - GET/POST /api/materials, PUT /api/materials/{id}/stock
  - GET /api/workers, GET /api/workers/{id}/schedule
  - GET /api/schedules/week
- **역할**: ADMIN, DESIGNER, WORKER
- **초기 데이터**: 자재 30종, 시공 기사 5명

### 운영 테스트 시나리오
1. 부동산: 매물 검색 (조건 필터) → 방문 예약 → 계약 체결
2. 인테리어: 프로젝트 등록 → 견적서 작성 → 시공 일정 → 완료
3. AGENT만 매물 등록/계약 가능 확인
4. 자재 재고 관리 (사용 시 차감)

### 활용 플러그인
- **DB**: 복합 검색 (가격 범위, 지역, 유형별 매물 필터)
- **JWT**: AGENT vs CLIENT 역할 분리
- **HTTP**: 인테리어→부동산 매물 정보 연동
- **FILE**: 견적서 데이터 export

---

## FG-10: 헬스장 + 영양상담 시스템

### Project A: gym (헬스장 회원 관리)
- **DB**: fg_gym / **Port**: 9119
- **엔티티**: Member, Trainer, Membership, PTSession, Exercise, Attendance
- **API** (~10개):
  - POST /api/auth/login, POST /api/auth/register
  - GET/POST /api/members, GET /api/members/{id}
  - POST /api/memberships, GET /api/memberships/active
  - POST /api/pt-sessions, PUT /api/pt-sessions/{id}/complete
  - GET /api/trainers, GET /api/trainers/{id}/schedule
  - POST /api/attendance/checkin
  - GET /api/stats/attendance-rate
- **역할**: ADMIN, TRAINER, MEMBER
- **초기 데이터**: 트레이너 4명, 회원 15명, 멤버십 10건

### Project B: nutrition (영양 상담)
- **DB**: fg_nutrition / **Port**: 9120
- **엔티티**: Client, Nutritionist, Consultation, MealPlan, FoodItem, DietLog
- **API** (~10개):
  - POST /api/auth/login
  - GET/POST /api/clients, GET /api/clients/{id}
  - POST /api/consultations, GET /api/consultations/{id}
  - POST /api/meal-plans, GET /api/meal-plans/client/{clientId}
  - GET/POST /api/food-items, GET /api/food-items/search?q=
  - POST /api/diet-logs, GET /api/diet-logs/client/{clientId}/weekly
  - GET /api/nutritionists
- **역할**: ADMIN, NUTRITIONIST, CLIENT
- **초기 데이터**: 영양사 3명, 식품 DB 50개, 고객 8명

### 운영 테스트 시나리오
1. 헬스장: 회원 가입 → 멤버십 등록 → PT 예약 → 출석 체크
2. 영양: 상담 예약 → 식단 계획 작성 → 식단 기록 → 주간 리포트
3. TRAINER만 PT 세션 완료 처리 가능 확인
4. NUTRITIONIST만 식단 계획 작성 가능 확인

### 활용 플러그인
- **DB**: 통계 쿼리 (출석률, 주간 식단 합산)
- **JWT**: TRAINER/MEMBER, NUTRITIONIST/CLIENT 역할 분리
- **HTTP**: 영양→헬스장 회원 운동 데이터 연동
- **CRON**: PT 예약 리마인더

---

## 실행 순서

1. FG-01 ~ FG-10 순차 실행
2. 각 케이스마다:
   a. wiiiv에 PROJECT_CREATE 요청 (Project A)
   b. 생성된 코드 빌드 (`./gradlew assemble` 또는 `./gradlew bootJar`)
   c. 프로젝트 실행 (해당 포트)
   d. API 호출 테스트 (curl/httpie)
   e. Project B 동일 반복
   f. 크로스 시스템 테스트
3. 버그 발견 시: 즉시 수정 → `./gradlew assemble` → 커밋
4. 결과 기록: ghost/forgium-phase1/results/

## 성공 기준

- [ ] 20개 프로젝트 모두 생성 완료
- [ ] 20개 프로젝트 모두 빌드 성공 (컴파일 에러 없음)
- [ ] 20개 프로젝트 모두 실행 후 API 호출 정상
- [ ] JWT 인증 전 프로젝트에서 동작 확인
- [ ] 역할별 접근 제어 동작 확인
- [ ] 크로스 시스템 호출 최소 5쌍에서 성공
- [ ] forgium 버그 수정 시 즉시 커밋 완료
