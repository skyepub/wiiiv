#!/usr/bin/env python3
"""
Forgium Phase 1 - Automated Project Generator
wiiiv의 PROJECT_CREATE 파이프라인을 자동으로 실행하여 백엔드 프로젝트를 생성합니다.
"""

import json
import sys
import time
import urllib.request
import urllib.error

WIIIV_HOST = "http://localhost:8235"

def login():
    req = urllib.request.Request(f"{WIIIV_HOST}/api/v2/auth/auto-login")
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return data["data"]["accessToken"]

def create_session(token, name):
    req = urllib.request.Request(
        f"{WIIIV_HOST}/api/v2/sessions",
        data=json.dumps({"name": name}).encode(),
        headers={"Authorization": f"Bearer {token}", "Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req) as resp:
        data = json.loads(resp.read())
        return data["data"]["sessionId"]

def chat(token, session_id, message, timeout=300):
    """Send message and read SSE response"""
    req = urllib.request.Request(
        f"{WIIIV_HOST}/api/v2/sessions/{session_id}/chat",
        data=json.dumps({
            "message": message,
            "autoContinue": True,
            "maxContinue": 10
        }).encode(),
        headers={
            "Authorization": f"Bearer {token}",
            "Content-Type": "application/json",
            "Accept": "text/event-stream"
        }
    )

    events = []
    final_response = ""
    action = ""

    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            buffer = ""
            for line in resp:
                line = line.decode("utf-8", errors="replace")
                buffer += line

                if line.strip() == "":
                    # Parse accumulated event
                    event_type = ""
                    event_data = ""
                    for bline in buffer.strip().split("\n"):
                        if bline.startswith("event:"):
                            event_type = bline[6:].strip()
                        elif bline.startswith("data:"):
                            event_data = bline[5:].strip()

                    if event_type and event_data:
                        try:
                            parsed = json.loads(event_data)
                            events.append({"event": event_type, "data": parsed})

                            if event_type == "response":
                                action = parsed.get("action", "")
                                final_response = parsed.get("message", "")
                            elif event_type == "done":
                                break
                            elif event_type == "error":
                                print(f"  ERROR: {parsed}")
                                break
                        except json.JSONDecodeError:
                            pass

                    buffer = ""
    except Exception as e:
        print(f"  Connection error: {e}")

    return action, final_response, events

def generate_project(project_spec):
    """Generate a single project through forgium"""
    name = project_spec["name"]
    print(f"\n{'='*60}")
    print(f"  Generating: {name}")
    print(f"{'='*60}")

    token = login()
    session_id = create_session(token, f"FG: {name}")
    print(f"  Session: {session_id}")

    # Turn 1: Describe the project with all details
    print(f"  Turn 1: Sending project specification...")
    action1, resp1, _ = chat(token, session_id, project_spec["message1"])
    print(f"  Action: {action1}")
    print(f"  Response: {resp1[:200]}...")

    # Turn 2: Confirm and proceed
    if action1 == "ASK":
        print(f"  Turn 2: Confirming and requesting work order...")
        action2, resp2, _ = chat(token, session_id, project_spec.get("message2",
            "추가사항 없어. 바로 작업지시서 만들고 코드 생성해줘. 빠르게 진행해."))
        print(f"  Action: {action2}")
        print(f"  Response: {resp2[:200]}...")

        # Turn 3: If still ASK, push harder
        if action2 == "ASK":
            print(f"  Turn 3: Pushing for execution...")
            action3, resp3, _ = chat(token, session_id, "네, 진행해주세요. 코드를 생성해주세요.")
            print(f"  Action: {action3}")

            if action3 == "CONFIRM":
                print(f"  Turn 4: Confirming work order...")
                action4, resp4, events4 = chat(token, session_id, "네, 이대로 진행해주세요. 코드를 생성해주세요.")
                print(f"  Action: {action4}")
                return action4 == "EXECUTE", events4
        elif action2 == "CONFIRM":
            print(f"  Turn 3: Confirming work order...")
            action3, resp3, events3 = chat(token, session_id, "네, 이대로 진행해주세요. 코드를 생성해주세요.")
            print(f"  Action: {action3}")
            return action3 == "EXECUTE", events3
        elif action2 == "EXECUTE":
            return True, []
    elif action1 == "CONFIRM":
        print(f"  Turn 2: Confirming work order...")
        action2, resp2, events2 = chat(token, session_id, "네, 이대로 진행해주세요. 코드를 생성해주세요.")
        print(f"  Action: {action2}")
        return action2 == "EXECUTE", events2
    elif action1 == "EXECUTE":
        return True, []

    return False, []


# ── Project Specifications ──

PROJECTS = [
    {
        "id": "FG-01B",
        "name": "bookstore (온라인 서점)",
        "message1": """온라인 서점 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL 백엔드야.
DB: jdbc:mysql://home.skyepub.net:63306/fg_bookstore (root/Mako2122!). 포트 9102.
패키지: com.forgium.bookstore. 메인클래스: BookstoreApplication. 프로젝트명: bookstore.
엔티티: Book(title,author,isbn,price,stock,publishedDate), Author(name,bio,email),
Order(customer,orderDate,totalAmount,status[PENDING/CONFIRMED/SHIPPED/DELIVERED/CANCELLED]),
OrderItem(order,book,quantity,price), Customer(name,email,password,role[ADMIN/CUSTOMER]).
API: POST /api/auth/login + register, GET/POST /api/books, GET /api/books/{id},
GET /api/books/search?q=, GET /api/authors, GET /api/authors/{id}/books,
GET/POST /api/orders, GET /api/orders/{id}, GET /api/orders/my,
GET /api/stats/bestsellers.
역할: ADMIN, CUSTOMER. JWT 인증 (jjwt 0.12.6). BCrypt 비밀번호.
초기 데이터: 도서 15권, 저자 5명, 고객 3명(admin/customer1/customer2).
application.yml에 server.port=9102 명시. com.mysql:mysql-connector-j 사용."""
    },
    {
        "id": "FG-02A",
        "name": "hospital (병원 예약 시스템)",
        "message1": """병원 예약 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_hospital (root/Mako2122!). 포트 9103.
패키지: com.forgium.hospital. 메인클래스: HospitalApplication. 프로젝트명: hospital.
엔티티: Patient(name,email,phone,birthDate,password,role), Doctor(name,email,specialty,department,phone),
Appointment(patient,doctor,appointmentDate,appointmentTime,status[SCHEDULED/CONFIRMED/CANCELLED/COMPLETED]),
Department(name,description,location), Prescription(patient,doctor,medication,dosage,notes,prescribedDate).
API: POST /api/auth/login + register, GET/POST /api/patients + GET /{id},
GET/POST /api/doctors + GET /{id}/schedule, POST /api/appointments + PUT /{id}/cancel,
GET /api/appointments/today, POST /api/prescriptions.
역할: ADMIN, DOCTOR, NURSE, PATIENT. JWT(jjwt 0.12.6).
초기 데이터: 의사 3명, 환자 5명, 진료과 4개.
@JsonIgnore를 양방향 @OneToMany 컬렉션에 필수 추가."""
    },
    {
        "id": "FG-02B",
        "name": "pharmacy (약국 처방전 관리)",
        "message1": """약국 처방전 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_pharmacy (root/Mako2122!). 포트 9104.
패키지: com.forgium.pharmacy. 메인클래스: PharmacyApplication. 프로젝트명: pharmacy.
엔티티: Medicine(name,manufacturer,price,stock,requiresPrescription),
Prescription(patientName,doctorName,medication,dosage,notes,receivedDate,dispensed),
Dispensing(prescription,medicine,quantity,dispensedDate,pharmacist),
Stock(medicine,quantity,lastRestocked), Customer(name,email,phone,password,role).
API: POST /api/auth/login, GET/POST /api/medicines + GET /{id},
GET/POST /api/prescriptions + PUT /{id}/dispense, GET /api/stock/low,
GET /api/dispensing/history?customerId=, PUT /api/stock/{medicineId}/restock.
역할: ADMIN, PHARMACIST. JWT.
초기 데이터: 약품 20종, 재고 정보."""
    },
    {
        "id": "FG-03A",
        "name": "restaurant (식당 주문 관리)",
        "message1": """식당 주문 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_restaurant (root/Mako2122!). 포트 9105.
패키지: com.forgium.restaurant. 메인클래스: RestaurantApplication. 프로젝트명: restaurant.
엔티티: Menu(name,description,price,category,available), Category(name,description),
Order(tableNumber,orderDate,totalAmount,status[PENDING/PREPARING/READY/SERVED/PAID]),
OrderItem(order,menu,quantity,subtotal), DiningTable(number,capacity,status[AVAILABLE/OCCUPIED/RESERVED]),
Staff(name,email,password,role).
API: POST /api/auth/login, GET/POST/PUT/DELETE /api/menus, GET /api/categories,
POST /api/orders + GET /{id} + PUT /{id}/status, GET /api/tables/available,
GET /api/stats/daily-sales.
역할: ADMIN, STAFF, CUSTOMER. JWT.
초기 데이터: 메뉴 15개, 카테고리 4개, 테이블 10개."""
    },
    {
        "id": "FG-03B",
        "name": "delivery (배달 관리)",
        "message1": """배달 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_delivery (root/Mako2122!). 포트 9106.
패키지: com.forgium.delivery. 메인클래스: DeliveryApplication. 프로젝트명: delivery.
엔티티: DeliveryOrder(customerName,address,restaurantName,orderAmount,status[PENDING/ASSIGNED/PICKED_UP/DELIVERING/DELIVERED],createdAt),
Driver(name,email,phone,password,role,vehicleType,available),
Rating(deliveryOrder,driver,score,comment,createdAt), Zone(name,description).
API: POST /api/auth/login, GET/POST /api/deliveries + GET /{id},
PUT /api/deliveries/{id}/assign + PUT /{id}/status,
GET /api/drivers + GET /{id}/active, POST /api/ratings, GET /api/zones.
역할: ADMIN, DRIVER, CUSTOMER. JWT.
초기 데이터: 배달원 5명, 배달 존 3개."""
    },
    {
        "id": "FG-04A",
        "name": "school (학교 성적 관리)",
        "message1": """학교 성적 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_school (root/Mako2122!). 포트 9107.
패키지: com.forgium.school. 메인클래스: SchoolApplication. 프로젝트명: school.
엔티티: Student(name,email,password,role,classroom), Teacher(name,email,password,role,subject),
Subject(name,description,teacher), Grade(student,subject,score,semester,examType[MIDTERM/FINAL]),
Classroom(name,grade,section).
API: POST /api/auth/login, GET/POST /api/students + GET /{id},
GET/POST /api/subjects, POST /api/grades + GET /student/{studentId},
GET /api/grades/subject/{subjectId}/ranking, GET /api/classrooms + GET /{id}/students,
GET /api/stats/average?subjectId=.
역할: ADMIN, TEACHER, STUDENT. JWT.
초기 데이터: 학생 10명, 교사 3명, 과목 5개, 성적 데이터."""
    },
    {
        "id": "FG-04B",
        "name": "academy (학원 수강 관리)",
        "message1": """학원 수강 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_academy (root/Mako2122!). 포트 9108.
패키지: com.forgium.academy. 메인클래스: AcademyApplication. 프로젝트명: academy.
엔티티: Student(name,email,phone,password,role), Course(name,description,instructor,fee,maxStudents),
Enrollment(student,course,enrolledDate,status[ACTIVE/COMPLETED/DROPPED]),
Instructor(name,email,specialty,password,role), Schedule(course,dayOfWeek,startTime,endTime,room).
API: POST /api/auth/login + register, GET/POST /api/courses + GET /{id},
POST /api/enrollments + DELETE /{id}, GET /api/enrollments/student/{studentId},
GET /api/schedules/today, GET /api/instructors + GET /{id}/courses,
GET /api/courses/{id}/students.
역할: ADMIN, INSTRUCTOR, STUDENT. JWT.
초기 데이터: 강좌 8개, 강사 4명, 학생 15명."""
    },
    {
        "id": "FG-05A",
        "name": "hr_mgmt (인사 관리)",
        "message1": """인사 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_hr (root/Mako2122!). 포트 9109.
패키지: com.forgium.hr. 메인클래스: HrApplication. 프로젝트명: hr-mgmt.
엔티티: Employee(name,email,phone,password,role,department,position,hireDate),
Department(name,description,manager), Position(title,level,baseSalary),
LeaveRequest(employee,leaveType[ANNUAL/SICK/PERSONAL],startDate,endDate,status[PENDING/APPROVED/REJECTED],reason),
Attendance(employee,date,checkIn,checkOut).
API: POST /api/auth/login, GET/POST /api/employees + GET /{id},
GET/POST /api/departments + GET /{id}/members,
POST /api/leaves + PUT /{id}/approve + PUT /{id}/reject,
POST /api/attendance/checkin + POST /checkout, GET /api/attendance/monthly?employeeId=&month=.
역할: ADMIN, HR_MANAGER, EMPLOYEE. JWT.
초기 데이터: 직원 10명, 부서 4개, 직급 5개."""
    },
    {
        "id": "FG-05B",
        "name": "payroll (급여 관리)",
        "message1": """급여 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_payroll (root/Mako2122!). 포트 9110.
패키지: com.forgium.payroll. 메인클래스: PayrollApplication. 프로젝트명: payroll.
엔티티: PayrollRecord(employeeId,employeeName,month,baseSalary,bonus,deduction,netSalary,paidDate),
Bonus(employeeId,amount,reason,bonusDate), Deduction(employeeId,amount,type[TAX/INSURANCE/PENSION],month),
TaxInfo(employeeId,taxRate,annualIncome,year).
API: POST /api/auth/login, GET/POST /api/payroll + GET /{id},
POST /api/payroll/calculate?employeeId=&month=,
GET /api/payroll/employee/{employeeId}/history, POST /api/bonuses + GET /month/{month},
POST /api/deductions, GET /api/stats/monthly-total?month=.
역할: ADMIN, ACCOUNTANT. JWT.
초기 데이터: 급여 레코드 10건, 세금 정보."""
    },
    {
        "id": "FG-06A",
        "name": "parking (주차장 관리)",
        "message1": """주차장 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_parking (root/Mako2122!). 포트 9111.
패키지: com.forgium.parking. 메인클래스: ParkingApplication. 프로젝트명: parking.
엔티티: Vehicle(plateNumber,vehicleType[CAR/SUV/MOTORCYCLE],ownerName),
ParkingSpot(spotNumber,floor,type[REGULAR/COMPACT/HANDICAP],occupied),
ParkingRecord(vehicle,spot,entryTime,exitTime,fee,paid),
Subscription(vehicle,startDate,endDate,monthlyFee,active),
Operator(name,email,password,role).
API: POST /api/auth/login, POST /api/parking/enter + POST /exit,
GET /api/parking/current, GET /api/spots/available,
GET /api/fees/calculate?vehicleId=, GET/POST /api/subscriptions,
GET /api/stats/daily-revenue, GET /api/vehicles/{plateNumber}/history.
역할: ADMIN, OPERATOR. JWT.
초기 데이터: 주차 구역 50개, 정기권 5건."""
    },
    {
        "id": "FG-06B",
        "name": "auto_repair (차량 정비소)",
        "message1": """차량 정비소 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_repair (root/Mako2122!). 포트 9112.
패키지: com.forgium.repair. 메인클래스: RepairApplication. 프로젝트명: auto-repair.
엔티티: Customer(name,email,phone,password,role), Vehicle(customer,make,model,year,plateNumber),
RepairOrder(vehicle,customer,description,status[RECEIVED/IN_PROGRESS/COMPLETED/DELIVERED],createdAt,completedAt,totalCost),
RepairItem(repairOrder,description,laborCost,partsCost),
Part(name,partNumber,price,stock), Staff(name,email,password,role).
API: POST /api/auth/login, GET/POST /api/customers + GET /{id},
POST /api/repair-orders + GET /{id} + PUT /{id}/status,
GET/POST /api/parts + PUT /{id}/stock, GET /api/invoices/{repairOrderId},
GET /api/vehicles/{id}/repair-history.
역할: ADMIN, MECHANIC, RECEPTIONIST. JWT.
초기 데이터: 부품 30종, 고객 5명."""
    },
    {
        "id": "FG-07A",
        "name": "event (이벤트/티켓 예매)",
        "message1": """이벤트/티켓 예매 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_event (root/Mako2122!). 포트 9113.
패키지: com.forgium.event. 메인클래스: EventApplication. 프로젝트명: event.
엔티티: Event(name,description,venue,eventDate,eventTime,ticketPrice,totalSeats,availableSeats,organizer),
Venue(name,address,capacity), Booking(event,customer,seatCount,totalPrice,status[CONFIRMED/CANCELLED],bookedAt),
Customer(name,email,password,role).
API: POST /api/auth/login + register, GET/POST /api/events + GET /{id},
GET /api/events/upcoming, POST /api/bookings + GET /{id} + DELETE /{id},
GET /api/bookings/my, GET /api/venues, GET /api/stats/popular.
역할: ADMIN, ORGANIZER, CUSTOMER. JWT.
초기 데이터: 이벤트 5개, 공연장 2개."""
    },
    {
        "id": "FG-07B",
        "name": "merchandise (굿즈 판매)",
        "message1": """굿즈 판매 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_merch (root/Mako2122!). 포트 9114.
패키지: com.forgium.merch. 메인클래스: MerchApplication. 프로젝트명: merchandise.
엔티티: Product(name,description,price,stock,category,imageUrl), Category(name,description),
Order(customer,orderDate,totalAmount,status[PENDING/CONFIRMED/SHIPPED/DELIVERED]),
OrderItem(order,product,quantity,subtotal), Customer(name,email,password,role),
Review(product,customer,rating,comment,createdAt).
API: POST /api/auth/login + register, GET/POST /api/products + GET /{id},
GET /api/products/category/{categoryId}, POST /api/orders + GET /{id} + GET /my,
POST /api/reviews + GET /api/products/{id}/reviews, GET /api/categories.
역할: ADMIN, CUSTOMER. JWT.
초기 데이터: 굿즈 20개, 카테고리 5개."""
    },
    {
        "id": "FG-08A",
        "name": "vet_clinic (동물병원)",
        "message1": """동물병원 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_vet (root/Mako2122!). 포트 9115.
패키지: com.forgium.vet. 메인클래스: VetApplication. 프로젝트명: vet-clinic.
엔티티: Pet(name,species,breed,age,owner), Owner(name,email,phone,password,role),
Veterinarian(name,email,specialty,password,role), Visit(pet,vet,visitDate,diagnosis,treatment,fee),
Vaccination(pet,vaccineName,vaccinationDate,nextDueDate).
API: POST /api/auth/login, GET/POST /api/pets + GET /{id},
GET/POST /api/owners + GET /{id}/pets, POST /api/visits + GET /{id},
POST /api/vaccinations + GET /api/pets/{id}/vaccinations,
GET /api/vets + GET /{id}/schedule, GET /api/visits/today.
역할: ADMIN, VET, RECEPTIONIST. JWT.
초기 데이터: 수의사 3명, 반려동물 10마리, 보호자 7명."""
    },
    {
        "id": "FG-08B",
        "name": "pet_shop (펫샵)",
        "message1": """펫샵 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_petshop (root/Mako2122!). 포트 9116.
패키지: com.forgium.petshop. 메인클래스: PetShopApplication. 프로젝트명: pet-shop.
엔티티: Product(name,description,price,stock,category,petType[DOG/CAT/BIRD/FISH/OTHER]),
Category(name,description), Order(customer,orderDate,totalAmount,status),
OrderItem(order,product,quantity,subtotal), Customer(name,email,password,role).
API: POST /api/auth/login + register, GET/POST /api/products + GET /{id},
GET /api/products/category/{categoryId}, GET /api/products/search?q=&petType=,
POST /api/orders + GET /{id} + GET /my, GET /api/categories,
GET /api/stats/top-selling.
역할: ADMIN, CUSTOMER. JWT.
초기 데이터: 상품 25종, 카테고리 6개."""
    },
    {
        "id": "FG-09A",
        "name": "real_estate (부동산 매물 관리)",
        "message1": """부동산 매물 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_realestate (root/Mako2122!). 포트 9117.
패키지: com.forgium.realestate. 메인클래스: RealEstateApplication. 프로젝트명: real-estate.
엔티티: Property(title,description,propertyType[APARTMENT/HOUSE/OFFICE/LAND],price,area,address,agent,status[AVAILABLE/SOLD/RENTED]),
Agent(name,email,phone,password,role,licenseNumber), Client(name,email,phone,password,role),
Showing(property,client,agent,showingDate,notes), Contract(property,client,agent,contractType[SALE/RENT],amount,contractDate).
API: POST /api/auth/login, GET/POST /api/properties + GET /{id},
GET /api/properties/search?type=&minPrice=&maxPrice=&region=,
GET/POST /api/agents + GET /{id}/properties, POST /api/showings,
POST /api/contracts + GET /{id}, GET /api/stats/market-summary.
역할: ADMIN, AGENT, CLIENT. JWT.
초기 데이터: 매물 15건, 중개사 3명, 고객 5명."""
    },
    {
        "id": "FG-09B",
        "name": "interior (인테리어 견적)",
        "message1": """인테리어 견적 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_interior (root/Mako2122!). 포트 9118.
패키지: com.forgium.interior. 메인클래스: InteriorApplication. 프로젝트명: interior.
엔티티: Project(client,title,description,status[ESTIMATE/IN_PROGRESS/COMPLETED],startDate,endDate,totalCost),
Client(name,email,phone,password,role), Estimate(project,description,materialCost,laborCost,totalCost),
Material(name,unit,unitPrice,stock), Worker(name,phone,specialty,dailyRate),
Schedule(project,worker,workDate,description).
API: POST /api/auth/login, GET/POST /api/projects + GET /{id} + PUT /{id}/status,
POST /api/estimates + GET /{projectId}, GET/POST /api/materials + PUT /{id}/stock,
GET /api/workers + GET /{id}/schedule, GET /api/schedules/week.
역할: ADMIN, DESIGNER, WORKER. JWT.
초기 데이터: 자재 30종, 시공 기사 5명."""
    },
    {
        "id": "FG-10A",
        "name": "gym (헬스장 회원 관리)",
        "message1": """헬스장 회원 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_gym (root/Mako2122!). 포트 9119.
패키지: com.forgium.gym. 메인클래스: GymApplication. 프로젝트명: gym.
엔티티: Member(name,email,phone,password,role,membershipType),
Trainer(name,email,specialty,password,role),
Membership(member,type[MONTHLY/QUARTERLY/YEARLY],startDate,endDate,fee,active),
PTSession(member,trainer,sessionDate,sessionTime,completed,notes),
Attendance(member,checkInTime,checkOutTime).
API: POST /api/auth/login + register, GET/POST /api/members + GET /{id},
POST /api/memberships + GET /active, POST /api/pt-sessions + PUT /{id}/complete,
GET /api/trainers + GET /{id}/schedule, POST /api/attendance/checkin,
GET /api/stats/attendance-rate.
역할: ADMIN, TRAINER, MEMBER. JWT.
초기 데이터: 트레이너 4명, 회원 15명, 멤버십 10건."""
    },
    {
        "id": "FG-10B",
        "name": "nutrition (영양 상담)",
        "message1": """영양 상담 관리 시스템을 만들어줘. Spring Boot 3.4.1 + Kotlin + JPA + MySQL.
DB: jdbc:mysql://home.skyepub.net:63306/fg_nutrition (root/Mako2122!). 포트 9120.
패키지: com.forgium.nutrition. 메인클래스: NutritionApplication. 프로젝트명: nutrition.
엔티티: Client(name,email,password,role,age,weight,height),
Nutritionist(name,email,specialty,password,role),
Consultation(client,nutritionist,consultationDate,notes,recommendation),
MealPlan(client,nutritionist,planName,startDate,endDate,dailyCalories),
FoodItem(name,calories,protein,carbs,fat,category),
DietLog(client,foodItem,quantity,logDate,mealType[BREAKFAST/LUNCH/DINNER/SNACK]).
API: POST /api/auth/login, GET/POST /api/clients + GET /{id},
POST /api/consultations + GET /{id}, POST /api/meal-plans + GET /client/{clientId},
GET/POST /api/food-items + GET /search?q=,
POST /api/diet-logs + GET /client/{clientId}/weekly, GET /api/nutritionists.
역할: ADMIN, NUTRITIONIST, CLIENT. JWT.
초기 데이터: 영양사 3명, 식품 DB 50개, 고객 8명."""
    }
]


if __name__ == "__main__":
    if len(sys.argv) > 1:
        # Run specific project by ID
        target_id = sys.argv[1]
        project = next((p for p in PROJECTS if p["id"] == target_id), None)
        if project:
            success, events = generate_project(project)
            print(f"\n{'='*60}")
            print(f"  Result: {'SUCCESS' if success else 'NEEDS MANUAL FIX'}")
            print(f"{'='*60}")
        else:
            print(f"Unknown project ID: {target_id}")
            print(f"Available: {', '.join(p['id'] for p in PROJECTS)}")
    else:
        # Run all
        results = []
        for project in PROJECTS:
            try:
                success, events = generate_project(project)
                results.append((project["id"], project["name"], success))
            except Exception as e:
                print(f"  FAILED: {e}")
                results.append((project["id"], project["name"], False))
            time.sleep(2)

        print(f"\n\n{'='*60}")
        print("  SUMMARY")
        print(f"{'='*60}")
        for pid, name, success in results:
            status = "✓ SUCCESS" if success else "✗ NEEDS FIX"
            print(f"  {pid}: {name} — {status}")
