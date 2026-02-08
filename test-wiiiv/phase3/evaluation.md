# Phase 3 객관적 평가 보고서

> 실행일: 2026-02-08
> 모델: gpt-4o-mini
> Governor: ConversationalGovernor + CompositeExecutor(FileExecutor, CommandExecutor)

---

## 1. 총괄 결과

| Case | Hard Assert | Soft Assert | 파일 생성 | 구문 정합 | 빌드/테스트 | 판정 |
|------|:-----------:|:-----------:|:---------:|:---------:|:-----------:|:----:|
| 21 (Kotlin) | PASS | PARTIAL | PASS | PASS | FAIL | **B** |
| 22 (Python) | PASS | PASS | PASS | PASS | PASS | **A** |
| 23 (Node.js) | PASS | PARTIAL | PASS | PASS | FAIL | **B** |

**종합: B+ (핵심 파이프라인 동작 확인, COMMAND 실행 단계에서 일관된 실패)**

---

## 2. Case별 상세 분석

### Case 21: Kotlin + Gradle - 학생 성적 관리

#### 인터뷰 품질 (A)

| Turn | 기대 | 실제 | 평가 |
|------|------|------|------|
| 1 "프로젝트 하나 만들어줘" | ASK | ASK | OK - projectType 질문 |
| 2 "학생 성적 관리 시스템이야" | ASK | ASK | OK - techStack 질문 |
| 3 Kotlin/Gradle 지정 | ASK/CONFIRM | CONFIRM | OK - 즉시 요약 확인 |
| 4 Student/GradeService/JUnit5 | ASK/CONFIRM | CONFIRM | OK - 상세 요약 |
| 5 경로 지정 | CONFIRM | CONFIRM | OK - 경로 포함 요약 |
| 6 "응 진행해" | EXECUTE | EXECUTE | OK |
| 8 "고마워" | REPLY | REPLY | OK |

#### 파일 생성 품질 (A-)

- **Blueprint**: 10 steps (FILE_MKDIR x4, FILE_WRITE x4, COMMAND x2)
- **생성 파일**:
  - `build.gradle.kts` (351B) - Kotlin 1.5.31, JUnit 5.8.1
  - `Student.kt` (149B) - data class, averageScore() 메서드
  - `StudentService.kt` (317B) - addStudent, getStudentAverages
  - `StudentServiceTest.kt` (727B) - 2개 테스트 케이스, 백틱 함수명
- **GradeService → StudentService 변경**: LLM이 자체 판단으로 이름 변경 (허용 범위)
- **settings.gradle.kts 누락**: Gradle 빌드에 필수는 아니나 관례상 있어야 함

#### 코드 품질 (B+)

- Student data class: 올바른 Kotlin 관용구 (data class + averageScore)
- StudentService: 간단하지만 완전한 CRUD 중 add/list만 구현 (update/delete 없음)
- Test: JUnit5 정상, 2개 테스트, assertEquals 올바름
- **이슈**: Kotlin 1.5.31은 낡은 버전, test { useJUnitPlatform() }에서 `test`가 tasks.named("test")가 아닌 top-level → 빌드 스크립트 문법 미세 오류 가능

#### 실행 결과 (C)

- **파일 생성**: 8/8 성공 (FILE_MKDIR 4 + FILE_WRITE 4)
- **COMMAND 실행**: 1 실패 (fail-fast로 2번째 COMMAND 미도달 추정)
- **실패 원인**: `sh -c ./gradlew build` → Gradle Wrapper(gradlew) 미포함. LLM이 `./gradlew`를 빌드 명령어로 지정했으나, gradlew 파일을 생성하지 않음
- **근본 원인**: `PROJECT_GENERATION` 프롬프트에 "Gradle Wrapper 포함" 지시 없음

---

### Case 22: Python - 도서관 관리

#### 인터뷰 품질 (A)

| Turn | 기대 | 실제 | 평가 |
|------|------|------|------|
| 1 "Python으로 프로젝트" | ASK | ASK | OK |
| 2 "도서관 도서 관리" | ASK | ASK | OK |
| 3 CRUD + 순수 Python | ASK/CONFIRM | CONFIRM | OK - 즉시 요약 |
| 4 unittest + 경로 | CONFIRM | CONFIRM | OK |
| 5 "소규모 단순하게" | CONFIRM | CONFIRM | OK - 규모 반영 |
| 6 "진행해" | EXECUTE | EXECUTE | OK |
| 7 "고마워" | REPLY | REPLY | OK |

#### 파일 생성 품질 (B)

- **Blueprint**: 6 steps (FILE_MKDIR x3, FILE_WRITE x2, COMMAND x1)
- **생성 파일**:
  - `src/book_manager.py` (564B) - Book 클래스, Library 클래스 (add_book, list_books)
  - `tests/test_book_manager.py` (400B) - 1개 테스트 케이스
- **부족한 점**:
  - "도서 CRUD + 대출/반납"을 요청했으나 add+list만 구현 (update/delete/대출/반납 없음)
  - 테스트 1개만 (최소 3-4개 기대)
  - README.md 미생성

#### 코드 품질 (A-)

- Python 구문 100% 정상 (ast.parse 통과)
- Book/Library 클래스 구조 깔끔
- `__main__` 블록 포함 (실행 가능)

#### 실행 결과 (B+)

- **파일 생성**: 5/5 성공
- **COMMAND 실행**: 1 실패
- **unittest 수동 실행**: **PASS** (1 test, 0.000s)
- **실패 원인**: `sh -c` 명령어에서 경로 문제 또는 PYTHONPATH 설정 부재 (`from src.book_manager import` 시 경로)
- **수동 검증**: `cd /tmp/wiiiv-phase3-case22 && python3 -m unittest discover -s tests` → **성공**

---

### Case 23: Node.js - 할일 관리 API

#### 인터뷰 품질 (A)

| Turn | 기대 | 실제 | 평가 |
|------|------|------|------|
| 1 "Node.js REST API" | ASK | ASK | OK - 기능 질문 |
| 2 "할일 관리 API" | ASK | ASK | OK - techStack 질문 |
| 3 순수 Node.js http | ASK/CONFIRM | CONFIRM | OK |
| 4 CRUD + assert + 경로 | CONFIRM | CONFIRM | OK |
| 5 "소규모 간단하게" | CONFIRM | CONFIRM | OK |
| 6 "진행해" | EXECUTE | EXECUTE | OK |
| 7 "고마워" | REPLY | REPLY | OK |

#### 파일 생성 품질 (B-)

- **Blueprint**: 9 steps (FILE_MKDIR x3, FILE_WRITE x4, COMMAND x2)
- **생성 파일**:
  - `src/index.js` (1585B) - http.createServer, CRUD 4개 엔드포인트
  - `test/api.test.js` (1332B) - jest 기반, 2개 테스트
  - `package.json` (161B) - jest devDependency
  - `jest.config.js` (49B)
- **문제점**:
  - **"외부 패키지 없이 순수 Node.js"** 지시를 위반: jest(외부 패키지) 의존
  - assert 기반 테스트를 요청했으나 jest 사용
  - package.json에 `"devDependencies": {"jest": "^27.0.6"}` → npm install 필요

#### 코드 품질 (B+)

- Node.js 구문 100% 정상 (node --check 통과)
- REST API 구현: GET/POST/PUT/DELETE 4개 엔드포인트 완성
- 인메모리 배열 저장소 사용 (요구 충족)
- URL 파싱, JSON 처리 올바름

#### 실행 결과 (C)

- **파일 생성**: 7/7 성공
- **COMMAND 실행**: 1 실패 (2개 중 1개)
- **실패 원인**: `npm install` 또는 `jest` 명령어 실행 실패 (jest 미설치)
- **근본 원인**: 프롬프트 지시("외부 패키지 없이") 위반 → npm install이 필요한 구조

---

## 3. 공통 패턴 분석

### 성공한 것

| 항목 | 평가 |
|------|------|
| **인터뷰 품질** | A - 3개 Case 모두 자연스러운 ASK→CONFIRM→EXECUTE 흐름 |
| **DraftSpec 수집** | A - taskType, domain, techStack, targetPath 정확히 수집 |
| **LLM 기반 멀티파일 생성** | A - JSON 파싱, 디렉토리 구조, 파일 내용 모두 정상 |
| **FileExecutor 연동** | A - 100% 성공 (mkdir + write) |
| **구문 정합성** | A - Kotlin/Python/JS 모두 구문 오류 없음 |
| **Hard Assert 전원 통과** | A - blueprint ≥ 5/3/3 steps 충족, 파일 존재 확인 |

### 실패한 것

| 항목 | 원인 | 심각도 |
|------|------|--------|
| **COMMAND 실행 공통 실패** | `sh -c <command>` 형식에서 args 전달 문제 또는 빌드 도구 부재 | **높음** |
| **Gradle Wrapper 미포함** (Case 21) | LLM이 gradlew 생성 안 함 / 프롬프트에 명시 없음 | 중간 |
| **외부 의존성 지시 위반** (Case 23) | jest 사용 = 외부 패키지. 프롬프트 강제력 부족 | 중간 |
| **기능 축소 생성** (Case 22) | CRUD 중 add/list만, 대출/반납 미구현 | 낮음 |

### CommandExecutor `sh -c` args 문제

`BlueprintStep`에서 COMMAND step 구조:
```
command: "sh"
args: "-c ./gradlew build"
```

이것이 `CommandStep`으로 변환될 때 `ProcessBuilder(["sh", "-c ./gradlew build"])` → `sh`는 `-c` 다음 인자를 하나의 명령으로 인식하므로 **`-c`와 `./gradlew build`가 분리되어야 함**. 현재는 args가 하나의 문자열로 합쳐져 있어 실행 실패 가능.

---

## 4. 점수표

| 평가 항목 | 배점 | 득점 | 비율 |
|-----------|:----:|:----:|:----:|
| 인터뷰 흐름 (ASK→CONFIRM→EXECUTE) | 20 | 19 | 95% |
| DraftSpec 수집 정확도 | 15 | 14 | 93% |
| LLM 멀티파일 생성 (JSON 파싱) | 15 | 15 | 100% |
| 파일 시스템 실행 (mkdir + write) | 15 | 15 | 100% |
| 생성 코드 구문 정합성 | 10 | 10 | 100% |
| 생성 코드 기능 완성도 | 10 | 6 | 60% |
| COMMAND 실행 (빌드/테스트) | 10 | 1 | 10% |
| 프롬프트 지시 준수 (제약 조건) | 5 | 3 | 60% |
| **합계** | **100** | **83** | **83%** |

---

## 5. 개선 권고

### P0 (즉시 수정)

1. **CommandStep args 분리 문제**: `"args": "-c ./gradlew build"` → args를 리스트로 분리하거나 `command: "sh", args: ["-c", "./gradlew build"]` 형태로 변경. 현재 BlueprintStep.toExecutionStep()에서 args를 공백 split하는지 확인 필요.

### P1 (다음 이터레이션)

2. **PROJECT_GENERATION 프롬프트 보강**: "Gradle Wrapper(gradlew, gradle-wrapper.jar)를 생성하거나 빌드 명령어에 gradle 직접 사용" 지시 추가
3. **외부 의존성 제약 강화**: "constraints에 '외부 라이브러리 없이'가 있으면 devDependencies도 금지" 명시
4. **기능 완성도 지시**: "요구된 기능(CRUD 전체, 대출/반납 등)을 모두 구현하라" 강조

### P2 (선택적)

5. **빌드 도구 자동 감지**: Kotlin→Gradle이면 settings.gradle.kts 자동 포함
6. **COMMAND 실패 시 재시도**: build 실패 → 에러 로그를 LLM에게 전달 → 수정 코드 재생성
