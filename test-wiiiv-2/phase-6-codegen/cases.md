# Phase 6: 코드 생성 워크플로우 (Code Generation)

> **검증 목표**: 자연어 → 인터뷰 → Spec → Blueprint → 코드 생성 → 빌드/테스트
> **핵심 관심사**: 멀티턴 생성, IntegrityAnalyzer, 반복 리파인, PROJECT_CREATE
> **전제**: Phase 2 통과 (FILE_WRITE, COMMAND 작동 확인)

---

## Case 1: 단순 스크립트 생성 — Python hello world (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/hello.py에 'Hello, World!' 출력하는 Python 스크립트 만들어줘" | EXECUTE 또는 CONFIRM |
| 2 | (CONFIRM이면) "만들어" | EXECUTE |

**Hard Assert**:
- FILE_WRITE 실행
- 파일 생성됨
- Python 문법 정상

**검증**:
```bash
python3 /tmp/wiiiv-test-v2/hello.py  # → "Hello, World!"
```

**의도**: 가장 단순한 코드 생성 — 기본 경로 확인

---

## Case 2: 단일 파일 코드 생성 — 유틸리티 함수 (2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/utils.py에 다음 함수들을 만들어줘: 피보나치 수열 계산, 팩토리얼 계산, 소수 판별" | EXECUTE 또는 CONFIRM |
| 2 | (CONFIRM이면) "실행" | EXECUTE |

**Hard Assert**:
- 파일에 3개 함수 모두 포함
- Python 문법 정상

**검증**:
```bash
python3 -c "from utils import fibonacci, factorial, is_prime; print(fibonacci(10), factorial(5), is_prime(7))"
# 또는 직접 파일에서 함수 존재 확인
```

**Soft Assert**:
- 함수에 docstring 또는 주석 포함
- 엣지 케이스 처리 (0, 음수 등)

---

## Case 3: PROJECT_CREATE — 멀티파일 프로젝트 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "간단한 Python CLI 계산기 프로젝트를 /tmp/wiiiv-test-v2/calc-project에 만들어줘" | ASK |
| 2 | "사칙연산(+, -, *, /)을 지원하고, 입력은 커맨드라인 인자로 받아" | ASK 또는 CONFIRM |
| 3 | "테스트 코드도 포함해줘. pytest 사용" | CONFIRM |
| 4 | "만들어줘" | EXECUTE |

**Hard Assert**:
- taskType = PROJECT_CREATE
- 최소 파일 구조:
  - calc.py (또는 main.py)
  - test_calc.py
- 디렉토리 생성됨

**검증**:
```bash
ls /tmp/wiiiv-test-v2/calc-project/
python3 /tmp/wiiiv-test-v2/calc-project/calc.py 3 + 4  # → 7
```

**Audit Assert**:
- executionPath = DIRECT_BLUEPRINT
- taskType = PROJECT_CREATE

**의도**: 인터뷰 → PROJECT_CREATE 전체 경로. 멀티파일 생성 + 구조 검증.

---

## Case 4: 코드 생성 + 빌드 실행 — partial success (3턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/build-test 프로젝트를 만들어줘. Python 패키지 구조로, setup.py 포함해서. 빌드도 해봐" | ASK 또는 CONFIRM |
| 2 | (필요시 상세) "패키지명은 mylib, 버전 1.0" | CONFIRM |
| 3 | "실행" | EXECUTE |

**Hard Assert**:
- 파일 생성 성공 (isSuccess = true)
- COMMAND step (빌드) 결과가 별도 기록

**Soft Assert**:
- 파일 생성은 성공, 빌드는 성공 또는 실패 (partial success)
- 빌드 실패 시에도 파일은 보존

**Audit Assert**:
- Audit 레코드에 cmdSuccess 정보

**의도**: PROJECT_CREATE의 파일/명령 분리 실행 검증. 파일 성공 + 명령 실패 시 partial success 처리.

---

## Case 5: 코드 리뷰 요청 → 수정 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/calc-project/calc.py 코드 좀 봐줘" | EXECUTE (FILE_READ) |
| 2 | "0으로 나누기 에러 처리가 없는데 추가해줘" | EXECUTE (FILE_WRITE) |
| 3 | "수정된 파일 확인해봐" | EXECUTE (FILE_READ) |
| 4 | "잘 됐다 고마워" | REPLY |

**Hard Assert**:
- Turn 1: FILE_READ 실행
- Turn 2: FILE_WRITE 실행 (수정된 내용)
- Turn 3: FILE_READ 실행 (수정 확인)
- Turn 4: REPLY

**Soft Assert**:
- Turn 2에서 ZeroDivisionError 처리 추가
- Turn 3에서 수정된 코드에 에러 처리 포함

**의도**: 코드 읽기 → 리뷰 → 수정 → 확인의 실무 사이클

---

## Case 6: 반복 리파인 — 3회 연속 수정 (6턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/refine.py에 간단한 TODO 리스트 CLI를 만들어줘" | EXECUTE |
| 2 | "추가(add)만 있네. 삭제(delete) 기능도 넣어줘" | EXECUTE |
| 3 | "전체 목록 보기(list) 기능도 추가해" | EXECUTE |
| 4 | "완료 표시(done) 기능도 넣어줘" | EXECUTE |
| 5 | "최종 코드 보여줘" | EXECUTE |
| 6 | "좋다!" | REPLY |

**Hard Assert**:
- Turn 1: 초기 파일 생성
- Turn 2~4: 기존 파일 수정 (덮어쓰기)
- Turn 5: FILE_READ
- 각 수정이 이전 기능을 보존하면서 새 기능 추가

**Soft Assert**:
- Turn 5에서 add, delete, list, done 4개 기능 모두 포함
- 이전 수정에서 추가한 기능이 덮어써지지 않음

**의도**: 반복 리파인에서 기존 코드 보존 + 점진적 기능 추가. LLM의 "전체 재작성" 유혹에 빠지지 않는지.

---

## Case 7: Kotlin 프로젝트 생성 — Gradle 포함 (4턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "Kotlin으로 간단한 REST API 서버 프로젝트를 만들고 싶어" | ASK |
| 2 | "Ktor 프레임워크 사용, 포트 8080, GET /hello 엔드포인트 하나만" | ASK 또는 CONFIRM |
| 3 | "경로는 /tmp/wiiiv-test-v2/ktor-hello, Gradle 빌드 포함" | CONFIRM |
| 4 | "만들어" | EXECUTE |

**Hard Assert**:
- taskType = PROJECT_CREATE
- 파일 구조:
  - build.gradle.kts
  - src/main/kotlin/... (Application.kt 등)
- Kotlin 문법 정상

**Soft Assert**:
- Gradle 빌드 가능
- Ktor 의존성 포함

**의도**: Java/Kotlin 생태계 프로젝트 생성 능력. Python 외 언어 지원 확인.

---

## Case 8: 코드 생성 + 실행 + 결과 확인 — 데이터 처리 (3턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/analyzer.py를 만들어줘. skymall 카테고리별 상품 수를 API로 가져와서 matplotlib 차트로 /tmp/wiiiv-test-v2/chart.png에 저장하는 스크립트" | CONFIRM 또는 EXECUTE |
| 2 | (CONFIRM이면) "실행" | EXECUTE |
| 3 | "스크립트를 실행해봐" | EXECUTE (COMMAND) |

**Hard Assert**:
- Turn 1~2: 파일 생성
- Turn 3: python3 analyzer.py 실행

**Soft Assert**:
- chart.png 생성 (matplotlib 설치 여부에 따라)
- 스크립트에 HTTP 호출 + 차트 생성 로직

**의도**: 코드 생성 → 실행까지의 전체 경로. 생성된 코드가 실제로 동작하는지.
