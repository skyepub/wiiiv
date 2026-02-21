# Phase 6 결과 — 코드 생성 워크플로우 (Code Generation)

> 실행일: 2026-02-21
> 서버: localhost:8235 (LLM: gpt-4o-mini, RAG: 526 chunks)

## 총괄

| 결과 | 수 |
|------|---|
| **PASS** | 4 |
| **SOFT PASS** | 3 |
| **SOFT FAIL** | 1 |

---

## Case 1: Python hello world — **PASS**
- EXECUTE → FILE_WRITE 1 step ✅
- `/tmp/wiiiv-test-v2/hello.py` 생성 ✅
- `print('Hello World')` — Python 문법 정상 ✅
- `python3 hello.py` → "Hello World" 출력 ✅

## Case 2: 유틸리티 함수 (피보나치, 팩토리얼, 소수판별) — **PASS**
- Turn 1: ASK (인자/반환값 확인)
- Turn 2: EXECUTE → FILE_WRITE ✅
- 3개 함수 모두 포함: fibonacci, factorial, is_prime ✅
- 검증: fibonacci(10)=55, factorial(5)=120, is_prime(7)=True ✅
- 엣지 케이스: fibonacci(0)=0, factorial(0)=1 ✅

## Case 3: PROJECT_CREATE — CLI 계산기 — **SOFT PASS**
- PROJECT_CREATE 단일 호출이 아닌 FILE_WRITE 2회로 처리
  - Turn 1: calc.py 생성 ✅
  - Turn 2: test_calc.py 생성 ✅
- 파일 구조: /tmp/wiiiv-test-v2/calc-project/{calc.py, test_calc.py} ✅
- `python3 calc.py 3 + 4` → "Result: 7.0" ✅
- pytest 4/4 통과: test_add, test_subtract, test_multiply, test_divide ✅
- **감점**: PROJECT_CREATE taskType 미사용 (FILE_WRITE 2회)

## Case 4: 코드 생성 + 빌드 — **PASS**
- Turn 1: `mylib/__init__.py` 생성 (version='1.0', greet 함수) ✅
- Turn 2: `setup.py` 생성 (setuptools 설정) ✅
- Turn 3: COMMAND `python3 setup.py sdist` 실행 → 성공 ✅
  - dist/mylib-1.0.tar.gz 생성됨
  - 빌드 시간: 129ms
- Audit: DIRECT_BLUEPRINT, COMPLETED ✅

## Case 5: 코드 리뷰 → 수정 사이클 — **PASS**
- Turn 1: FILE_READ → calc.py 코드 표시 ✅
- Turn 2: FILE_WRITE → 0으로 나누기 에러 처리 추가 ✅
  - `if num2 == 0: raise ValueError('Cannot divide by zero...')` 추가
- Turn 3: REPLY (FILE_READ 대신 세션 컨텍스트에서 코드 표시)
- Turn 4: REPLY → "천만에요!" ✅
- **핵심**: 읽기 → 수정 → 확인 → 인사의 실무 사이클 완성

## Case 6: 반복 리파인 (TODO 리스트 CLI) — **SOFT PASS**
- Turn 1: 초기 파일 생성 (add 기능만) ✅
- Turn 2: ASK → "바로 수정해" → FILE_WRITE 실행했으나 **delete 미반영**
- Turn 3: FILE_WRITE → add, delete, list 3개 기능 한꺼번에 반영 ✅
- Turn 4: FILE_WRITE → done 기능 추가 ✅
- Turn 5: REPLY → 최종 코드 표시
- **최종**: add, delete, list, done 4개 기능 모두 포함 ✅
- **감점**: Turn 2에서 수정이 실제 반영 안 됨 (Turn 3에서 누적 반영)

## Case 7: Kotlin/Ktor 프로젝트 — **SOFT PASS**
- Turn 1: Application.kt 생성 ✅
  - Ktor Netty 서버, 포트 8080, GET /hello → "Hello, World!" ✅
  - Kotlin 문법 정상 ✅
- Turn 2: build.gradle.kts 생성 ✅
  - Kotlin 1.9, Ktor 2.3.7, Netty, application plugin ✅
- **감점**: PROJECT_CREATE 미사용 (FILE_WRITE 2회), 실제 빌드 검증 미실행

## Case 8: 코드 생성 + 실행 (데이터 처리) — **SOFT FAIL**
- Turn 1: analyzer.py 생성 ✅
  - API 호출 + 파일 저장 로직 포함
- Turn 2: COMMAND `python3 analyzer.py` → **실행 실패** (exit code 1)
  - KeyError: '이름' — API 필드명이 한국어가 아닌 영문 (category.name, productCount 등)
- **원인**: LLM이 RAG의 한국어 문서를 기반으로 필드명을 한국어로 추측
- **실제 API**: `{"category": {"name": "..."}, "productCount": N, "avgPrice": N}`

---

## 발견된 이슈

### Issue P6-001: PROJECT_CREATE 미사용 (LOW)
- **Cases**: 3, 7
- **증상**: 멀티파일 프로젝트 생성 시 PROJECT_CREATE taskType 대신 FILE_WRITE 반복
- **원인**: Governor가 단일 파일 요청으로 분해하여 처리
- **영향**: 프로젝트 구조는 생성되지만, Audit에서 PROJECT_CREATE로 추적 불가
- **권장**: Governor 프롬프트에 "멀티파일 프로젝트 → PROJECT_CREATE" 규칙 강화

### Issue P6-002: 반복 리파인 시 수정 미반영 (LOW)
- **Case**: 6 Turn 2
- **증상**: FILE_WRITE 실행 성공이지만 기존 내용과 동일 (delete 함수 미추가)
- **원인**: LLM이 기존 파일을 읽고 수정하는 과정에서 변경 누락
- **영향**: 다음 턴에서 누적 반영되므로 최종 결과에는 영향 없음

### Issue P6-003: API 필드명 추측 오류 (MEDIUM)
- **Case**: 8
- **증상**: RAG 한국어 문서 기반으로 API 응답 필드명을 한국어로 추측
- **원인**: RAG에 API 스펙이 한국어 테이블로 작성됨 (이름, 상품수, 평균가격)
- **권장**: API 스펙 RAG에 실제 JSON 필드명 예시 명시

---

## 핵심 성과

- **파일 생성**: FILE_WRITE로 Python/Kotlin 코드 정확 생성 ✅
- **빌드 실행**: COMMAND로 `python3 setup.py sdist` 빌드 성공 ✅
- **코드 리뷰 사이클**: FILE_READ → 수정 요청 → FILE_WRITE → 확인의 실무 패턴 ✅
- **반복 리파인**: 기존 코드 보존하면서 점진적 기능 추가 ✅
- **다중 언어**: Python + Kotlin 코드 생성 모두 정상 ✅
