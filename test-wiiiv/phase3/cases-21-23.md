# Phase 3: 프로젝트 생성 → 빌드 → 테스트 E2E (Cases 21-23)

> Phase 1-2에서 대화/인터뷰 능력 검증 완료.
> Phase 3는 **실제로 프로젝트를 생성하고, 빌드하고, 테스트까지 수행**하는 전체 파이프라인 검증.

---

## 핵심 변경사항

| 항목 | Phase 1-2 | Phase 3 |
|------|-----------|---------|
| BlueprintRunner | null (Blueprint 생성만) | CompositeExecutor(FileExecutor, CommandExecutor) 연결 |
| PROJECT_CREATE | mkdir + README.md (2 steps) | LLM 기반 멀티파일 생성 (FILE_MKDIR + FILE_WRITE + COMMAND) |
| maxTokens | 1000 고정 | 프로젝트 생성 시 4096 |
| 로그 | println만 | `test-wiiiv/phase3/logs/caseN.log` 파일 로그 |

## 공통 원칙

- **외부 의존성 최소화**: 3개 Case 모두 외부 라이브러리 없이 (순수 Kotlin/Python/Node.js)
- **LLM 실패 fallback**: 프로젝트 파일 생성 파싱 실패 시 기존 mkdir+README로 fallback

---

## Case 21: Kotlin + Gradle - 학생 성적 관리 (8턴)

### 인터뷰 흐름

| Turn | 메시지 | 기대 |
|------|--------|------|
| 1 | "프로젝트 하나 만들어줘" | ASK |
| 2 | "학생 성적 관리 시스템이야" | ASK |
| 3 | "Kotlin으로 만들고 Gradle 빌드. 외부 프레임워크 없이 순수 Kotlin으로" | ASK/CONFIRM |
| 4 | "Student 데이터 클래스, GradeService 서비스, JUnit5 테스트 포함해줘" | ASK/CONFIRM |
| 5 | "프로젝트 경로는 /tmp/wiiiv-phase3-case21 로 해줘" | CONFIRM |
| 6 | "응 진행해" | EXECUTE |
| 7 | "결과 보여줘" | REPLY |
| 8 | "고마워" | REPLY |

### 기대 생성 파일 (~6개)

- `settings.gradle.kts`
- `build.gradle.kts`
- `src/main/kotlin/model/Student.kt`
- `src/main/kotlin/service/GradeService.kt`
- `src/test/kotlin/service/GradeServiceTest.kt`

### 검증

| 유형 | 검증 항목 |
|------|-----------|
| **Hard** | EXECUTE 시 blueprint ≥ 5 steps |
| **Hard** | 프로젝트 디렉토리 존재 |
| **Hard** | build.gradle.kts 존재 |
| **Soft** | .kt 소스 3개 이상 |
| **Soft** | gradle build 성공 |

---

## Case 22: Python - 도서관 관리 (7턴)

### 인터뷰 흐름

| Turn | 메시지 | 기대 |
|------|--------|------|
| 1 | "Python으로 프로젝트 하나 만들어줘" | ASK |
| 2 | "도서관 도서 관리 시스템이야" | ASK |
| 3 | "도서 CRUD + 대출/반납 기능. 외부 라이브러리 없이 순수 Python" | ASK/CONFIRM |
| 4 | "unittest로 테스트 포함. 프로젝트 경로 /tmp/wiiiv-phase3-case22" | CONFIRM |
| 5 | "소규모 단순하게" | CONFIRM |
| 6 | "진행해" | EXECUTE |
| 7 | "고마워" | REPLY |

### 기대 생성 파일 (~5개)

- `models.py`
- `library_service.py`
- `test_library.py`
- `README.md`

### 검증

| 유형 | 검증 항목 |
|------|-----------|
| **Hard** | EXECUTE 시 blueprint ≥ 3 steps |
| **Hard** | .py 파일 2개 이상 존재 |
| **Soft** | `python3 -c "import ast; ast.parse(...)"` 구문 검증 |
| **Soft** | unittest 통과 |

---

## Case 23: Node.js - 할일 관리 API (7턴)

### 인터뷰 흐름

| Turn | 메시지 | 기대 |
|------|--------|------|
| 1 | "Node.js로 REST API 프로젝트 만들어줘" | ASK |
| 2 | "할일(Todo) 관리 API야" | ASK |
| 3 | "외부 패키지 없이 순수 Node.js http 모듈. 인메모리 저장소" | ASK/CONFIRM |
| 4 | "CRUD 엔드포인트 + assert 기반 테스트. 경로 /tmp/wiiiv-phase3-case23" | CONFIRM |
| 5 | "소규모 간단하게" | CONFIRM |
| 6 | "진행해" | EXECUTE |
| 7 | "고마워" | REPLY |

### 기대 생성 파일 (~5개)

- `package.json`
- `app.js` 또는 `server.js`
- `routes/todos.js`
- `test/todo.test.js`

### 검증

| 유형 | 검증 항목 |
|------|-----------|
| **Hard** | EXECUTE 시 blueprint ≥ 3 steps |
| **Hard** | package.json 또는 app.js 존재 |
| **Soft** | `node --check app.js` 구문 검증 |
| **Soft** | 테스트 통과 |

---

## 로그 형식

각 Case별 `test-wiiiv/phase3/logs/caseN.log`:

```
=== Case 21: Kotlin Gradle - 학생 성적 관리 ===
[Time] Session: session-id

--- Interview Phase ---
[Turn 1] User: 프로젝트 하나 만들어줘
[Turn 1] Governor (ASK): 어떤 프로젝트를 만들까요?
          DraftSpec: taskType=PROJECT_CREATE

--- Execution Phase ---
  [step-mkdir-xxxx] FILE_MKDIR /tmp/.../
  [step-write-xxxx] FILE_WRITE build.gradle.kts

--- Execution Result ---
  Success: true
  Steps: success=8, failure=0

--- Generated Files ---
  build.gradle.kts (234 bytes)
  src/main/kotlin/model/Student.kt (180 bytes)

=== RESULT: PASS (8 steps, execution=OK) ===
```
