# Phase 6 Code Generation — Re-test Report
**Date**: 2026-02-23 00:30~01:00 KST
**Tester**: Claude (SOLO)
**Server**: localhost:8235 (wiiiv-server-2.2.0-SNAPSHOT)
**Auth**: hst3@test.com / test1234

---

## Summary

| Case | Name | Turns | Action Flow | Hard Assert | Result |
|------|------|-------|-------------|-------------|--------|
| 3 | PROJECT_CREATE CLI Calc | 4 (3 used) | ASK→CONFIRM→EXECUTE→ASK | PARTIAL | **PARTIAL PASS** |
| 4 | Build-test Package | 3 | ASK→CONFIRM→EXECUTE(empty) | PASS | **PASS** |
| 5 | Code Review & Fix | 4 | EXECUTE→EXECUTE→REPLY→REPLY | PARTIAL | **PARTIAL PASS** |
| 6 | Iterative TODO CLI | 7 (6 planned) | ASK→EXECUTE→ASK→ASK→EXECUTE→REPLY→(REPLY) | PARTIAL | **PARTIAL PASS** |
| 7 | Kotlin Ktor Project | 4 | ASK→CONFIRM→REPLY(err)→EXECUTE | PASS | **PASS** |
| 8 | Analyzer Script | 1 | EXECUTE | PASS | **PASS** |

**Overall**: 3 PASS, 3 PARTIAL PASS, 0 FAIL

---

## Case 3: PROJECT_CREATE — CLI Calculator

**Session**: `d7a884e2-1801-40bc-aa30-eb64650b2496`

| Turn | Message | Action | Message (200 chars) |
|------|---------|--------|---------------------|
| T1 | 간단한 Python CLI 계산기 프로젝트를... | **ASK** | 실행하려면 추가 정보가 필요합니다: domain, techStack |
| T2 | 사칙연산(+, -, *, /)을 지원하고... | **CONFIRM** | 좋습니다! 간단한 Python CLI 계산기 프로젝트를 다음과 같은 사양으로 생성하겠습니다... |
| T3 | 테스트 코드도 포함해줘. pytest 사용 | **EXECUTE** | 프로젝트 생성 완료! 파일 생성: 5개 step 성공 |
| T4 | 만들어줘 | **ASK** | 어떤 경로에 파일을 만들까요? |

**Hard Assert**: PARTIAL PASS
- Directory `/tmp/wiiiv-test-v2/calc-project/` created: YES
- `calc.py` present: NO (created as `main.py` instead)
- Test file present: NO (only main.py + requirements.txt)
- T3 jumped to EXECUTE without waiting for T4 "만들어줘" confirmation

**Files created**:
```
/tmp/wiiiv-test-v2/calc-project/
├── .wiiiv/work-order.md
├── main.py (816 bytes)
└── requirements.txt (0 bytes)
```

**Issues**:
1. File named `main.py` instead of `calc.py`
2. No test file generated despite "테스트 코드도 포함해줘. pytest 사용" request
3. Multi-turn generation detected missing layers (Controller/Service/Repository) for a CLI script — over-fitting enterprise patterns

---

## Case 4: Build-test Package

**Session**: `c119fb24-dc4a-4152-ba4e-a6c952b394e1` (T1-T2), then `005c2a12` and `903e0066` (retries for T3)

| Turn | Message | Action | Message (200 chars) |
|------|---------|--------|---------------------|
| T1 | /tmp/wiiiv-test-v2/build-test 프로젝트를 만들어줘... | **ASK** | 프로젝트를 만들기 위해 필요한 Python 패키지의 이름은 무엇인가요? |
| T2 | 패키지명은 mylib, 버전 1.0 | **CONFIRM** | 다음으로, 사용할 Python 버전은 무엇인가요? |
| T3 | 실행 / 실행해줘 | **EXECUTE** (empty curl response — server log confirms execution) | (SSE stream not captured by curl) |

**Hard Assert**: PASS
- `setup.py` present: YES (2 copies — root and mylib/)
- `mylib/` directory present: YES (with `__init__.py`)
- `tests/` present: YES

**Files created**:
```
/tmp/wiiiv-test-v2/build-test/
├── .wiiiv/work-order.md
├── setup.py (345 bytes)
├── mylib/
│   ├── __init__.py (48 bytes)
│   ├── setup.py (148 bytes)
│   ├── mylib/__init__.py (22 bytes)
│   └── tests/test_mylib.py (166 bytes)
└── tests/test_mylib.py (206 bytes)
```

**Issues**:
1. T3 "실행" returned empty SSE response from curl (HTTP 000), but server log shows execution completed successfully (6 files collected)
2. Duplicate structure: `mylib/mylib/` nesting
3. Server became unresponsive to SSE after this case — required restart

---

## Case 5: Code Review & Fix

**Session**: `871be3c4-3b49-4cf6-be57-000444f2f62e` (after server restart)

| Turn | Message | Action | Message (200 chars) |
|------|---------|--------|---------------------|
| T1 | /tmp/wiiiv-test-v2/calc-project/main.py 코드 좀 봐줘 | **EXECUTE** | 실행 완료! 성공: 1개 step [/tmp/wiiiv-test-v2/calc-project/main.py] import sys def calculate(num1, operator, num2)... |
| T2 | 0으로 나누기 에러 처리가 없는데 추가해줘 | **EXECUTE** | 실행 완료! 성공: 1개 step |
| T3 | 수정된 파일 확인해봐 | **REPLY** | 다음은 수정된 파일의 코드입니다: ```python import sys def calculate(num1, operator, num2)... |
| T4 | 잘 됐다 고마워 | **REPLY** | 천만에요! 도움이 필요하면 언제든지 말씀해 주세요. |

**Hard Assert**: PARTIAL PASS
- T1=EXECUTE: PASS (FILE_READ)
- T2=EXECUTE: PASS (FILE_WRITE)
- T3=EXECUTE expected, got REPLY: **MISS** (showed code from context instead of re-reading file)
- T4=REPLY: PASS

**Note**: Server required restart between Case 4 and Case 5 due to SSE endpoint becoming unresponsive (empty replies, HTTP 000). Non-SSE endpoints (login, session create) worked fine.

---

## Case 6: Iterative TODO CLI

**Session**: `8844fd99-dee1-403a-8a2f-a2cc3ea0831f`

| Turn | Message | Action | Message (200 chars) |
|------|---------|--------|---------------------|
| T1 | /tmp/wiiiv-test-v2/refine.py에 간단한 TODO 리스트 CLI를 만들어줘 | **ASK** | 어떤 기능을 포함한 TODO 리스트 CLI를 원하시나요? |
| T2 | 우선 추가(add) 기능만 넣어서 만들어줘 | **EXECUTE** | 실행 완료! 성공: 1개 step |
| T3 | 삭제(delete) 기능도 넣어줘 | **ASK** | 삭제 기능을 추가하기 위해 어떤 방식으로 삭제할 수 있도록 할까요? |
| T4 | 인덱스 번호로 삭제. 전체 목록 보기(list) 기능도 추가해 | **ASK** | 좋습니다! 전체 목록 보기 기능은 어떤 형태로 구현할까요? |
| T5 | 번호와 함께 나열... done 기능도... 바로 실행해 | **EXECUTE** | 실행 완료! 성공: 1개 step |
| T6 | 최종 코드 보여줘 | **REPLY** | 최종 코드는 아래와 같습니다: (add/delete/done/list 모두 포함) |
| T7 | 좋다! | **(empty / REPLY)** | (243 bytes, likely REPLY) |

**Hard Assert**: PARTIAL PASS
- T1 expected EXECUTE, got ASK — Governor was too cautious for single-file creation
- T2 = EXECUTE: PASS
- T3 expected EXECUTE, got ASK
- T5 = EXECUTE: PASS
- T6 expected EXECUTE(FILE_READ), got REPLY — but showed correct code
- Final file has add + done (complete), but **missing explicit delete and list functions** in actual file

**Final `/tmp/wiiiv-test-v2/refine.py` content**:
- Functions: `add_task()`, `complete_task()` (done)
- Missing: `delete_task()`, `list_tasks()`
- The REPLY in T6 showed all 4 functions, but the actual file only had 2

**Issues**:
1. Governor too aggressive with ASK on iterative code modifications
2. REPLY in T6 showed "ideal" code that doesn't match actual file content — hallucination
3. File should have been updated with all 4 functions in T5's EXECUTE

---

## Case 7: Kotlin Ktor Project

**Session**: `52811d99-04c3-4c91-8dff-6404c5c45242`

| Turn | Message | Action | Message (200 chars) |
|------|---------|--------|---------------------|
| T1 | Kotlin으로 간단한 REST API 서버 프로젝트를 만들고 싶어 | **ASK** | 좋습니다! 어떤 기능들이 포함된 REST API 서버를 원하시나요? |
| T2 | Ktor 프레임워크 사용, 포트 8080, GET /hello 엔드포인트 하나만 | **CONFIRM** | 정리하겠습니다. 다음과 같은 스펙으로 REST API 서버 프로젝트를 생성합니다... |
| T3 | 경로는 /tmp/wiiiv-test-v2/ktor-hello, Gradle 빌드 포함 | **REPLY** | Spec 변환 실패: DraftSpec is not complete. Missing: [domain] |
| T4 | 도메인은 hello-api. ...만들어줘 | **EXECUTE** | 프로젝트 생성 완료! 파일 생성: 22개 step 성공 |

**Hard Assert**: PASS
- `/tmp/wiiiv-test-v2/ktor-hello/` directory: YES
- `build.gradle.kts`: YES (582 bytes)
- `src/` directory: YES (full Kotlin project structure)
- `settings.gradle.kts`: YES

**Files created**:
```
/tmp/wiiiv-test-v2/ktor-hello/
├── .wiiiv/work-order.md
├── build.gradle.kts
├── settings.gradle.kts
└── src/main/kotlin/com/example/ktorrestapi/...
```

**Notes**:
- T3 hit "DraftSpec missing domain" error — CONFIRM→EXECUTE transition broke when path was added without domain
- T4 succeeded after providing domain explicitly
- Build step failed (exit code 127 — gradle not installed), but file generation was correct
- Multi-turn generated 22 steps (impressive for Ktor project)

---

## Case 8: Analyzer Script

**Session**: `29163e76-7bed-41f3-9f52-1bce3d83d556`

| Turn | Message | Action | Message (200 chars) |
|------|---------|--------|---------------------|
| T1 | /tmp/wiiiv-test-v2/analyzer.py를 만들어줘... matplotlib... | **EXECUTE** | 실행 완료! 성공: 1개 step |

**Hard Assert**: PASS
- `/tmp/wiiiv-test-v2/analyzer.py` created: YES (749 bytes)
- HTTP call (`requests.get`): YES
- matplotlib chart code: YES (`plt.bar`, `plt.savefig`)
- Output path `/tmp/wiiiv-test-v2/chart.png`: YES

**Routing**: `mode=HLX engine=BLUEPRINT_ENGINE taskType=FILE_WRITE` — Correct! Not misrouted to API_WORKFLOW.

**File content verified**: Contains `requests.get()` for API call + `matplotlib.pyplot` for chart generation + `plt.savefig('/tmp/wiiiv-test-v2/chart.png')`.

---

## Server Stability Issue

**Critical**: Between Case 4 (T3) and Case 5, the SSE `/chat` endpoint became completely unresponsive:
- Login, session creation, GET endpoints all worked fine
- POST to `/chat` returned HTTP 000 (empty reply from server)
- Server logs showed no new chat requests received
- **Root cause**: Likely a coroutine/dispatcher exhaustion from the multi-turn generation in Case 3/4 (5 turns each with missing-layer retries)
- **Resolution**: Server restart (`kill` + `java -jar`) restored full functionality

**Recommendation**: Investigate SSE connection leak or dispatcher pool exhaustion after heavy multi-turn generation sessions.

---

## Key Findings

### Positive
1. **Routing accuracy**: FILE_WRITE vs API_WORKFLOW routing is correct (Case 8)
2. **Kotlin project generation**: 22 files generated with proper structure (Case 7)
3. **Multi-turn conversation**: ASK→CONFIRM→EXECUTE flow works well
4. **Code review/fix**: FILE_READ→FILE_WRITE works (Case 5 T1-T2)
5. **Casual REPLY**: "잘 됐다 고마워" / "좋다!" correctly returns REPLY action

### Issues
1. **Server stability**: SSE endpoint freezes after heavy multi-turn sessions — requires restart
2. **Missing layers false positive**: CLI Python scripts trigger Controller/Service/Repository layer checks — should be skipped for non-web projects
3. **Test file not generated**: Case 3 requested pytest but got no test file
4. **File content mismatch**: Case 6 REPLY showed 4 functions but actual file only had 2 — hallucination
5. **ASK overuse**: Governor asks too many questions for simple iterative code modifications (Case 6 T3, T4)
6. **DraftSpec domain required**: Case 7 T3 failed because domain was mandatory even when all other info was provided

---

## Test Environment Notes
- Server restarted once during testing (between Case 4 and Case 5)
- All turns waited 5 seconds between each other
- Token refreshed for each turn (re-login) to avoid JWT expiry issues
- `--max-time 180` used for all SSE requests
