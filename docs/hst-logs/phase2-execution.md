# HST Phase 2 — Basic Execution (Blueprint Direct Path)

**Date**: 2026-02-22  
**Tester**: Claude Agent (Automated HST)  
**Server**: localhost:8235  
**User**: hst3@test.com  
**Test Directory**: /tmp/wiiiv-test-v2  

---

## Setup

- Test directory prepared: `/tmp/wiiiv-test-v2/`
- `read-target.txt` created with content "original content"
- JWT token obtained successfully via `/api/v2/platform/login`

---

## Case 1: FILE_READ — Read existing file

**Input**: `/tmp/wiiiv-test-v2/read-target.txt 파일 내용 보여줘`  
**Session**: `65ee5362-a49f-4000-8b44-e703d5c4e77a`

**SSE Response**:
- Phases: LLM_THINKING → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: FILE_READ, path=/tmp/wiiiv-test-v2/read-target.txt, success=true
- Message: `실행 완료! 성공: 1개 step` — response includes "original content"
- Blueprint ID: `bp-53220e32-27af-44e5-a9f1-f67b97d685bb`

**Hard Assert**: **PASS** — action=EXECUTE, response contains "original content"  
**Soft Assert**: **PASS** — 빠른 응답, 단일 step 정확 실행

---

## Case 2: FILE_WRITE — Create new file

**Input**: `/tmp/wiiiv-test-v2/new-file.txt에 '테스트 데이터 입니다' 라고 써줘`  
**Session**: `15acfd23-7961-4a78-9881-1ef1c92ab216`

**SSE Response**:
- Phases: LLM_THINKING → LLM_THINKING (기존 파일 분석 중) → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: FILE_WRITE, path=/tmp/wiiiv-test-v2/new-file.txt, content="테스트 데이터 입니다", success=true, durationMs=1
- Artifacts: written_file=/tmp/wiiiv-test-v2/new-file.txt

**File Verification**: `cat /tmp/wiiiv-test-v2/new-file.txt` → "테스트 데이터 입니다" (정확 일치)

**Hard Assert**: **PASS** — action=EXECUTE, file created with correct content  
**Soft Assert**: **PASS** — 한글 콘텐츠 정확히 기록됨

---

## Case 3: FILE_WRITE — Korean + special chars + multiline

**Input**: `/tmp/wiiiv-test-v2/multiline.txt에 다음 내용을 써줘: 첫째 줄: 가나다, 둘째 줄: ABC 123, 셋째 줄: 특수문자 테스트`  
**Session**: `12195e20-457e-4a49-ad2e-a01c5b0d9d3f`

**SSE Response**:
- Phases: LLM_THINKING → LLM_THINKING (기존 파일 분석 중) → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: FILE_WRITE, content="첫째 줄: 가나다\n둘째 줄: ABC 123\n셋째 줄: 특수문자 테스트", success=true

**File Verification**:
```
첫째 줄: 가나다
둘째 줄: ABC 123
셋째 줄: 특수문자 테스트
```
3줄 정확히 기록됨. 쉼표 구분을 개행으로 올바르게 해석.

**Hard Assert**: **PASS** — file created with 3 separate lines  
**Soft Assert**: **PASS** — LLM이 "," 구분을 줄바꿈으로 정확히 해석

---

## Case 4: FILE_DELETE — Delete file

**Pre-condition**: `echo "delete me" > /tmp/wiiiv-test-v2/delete-target.txt` (파일 생성 확인)  
**Input**: `/tmp/wiiiv-test-v2/delete-target.txt 삭제해줘`  
**Session**: `8d67c277-0d07-4558-a75f-83ea7471ef87`

**SSE Response (Turn 1)**:
- Phases: LLM_THINKING → **DACS_EVALUATING** → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: FILE_DELETE, path=/tmp/wiiiv-test-v2/delete-target.txt, success=true

**File Verification**: `ls /tmp/wiiiv-test-v2/delete-target.txt` → "No such file or directory" (삭제 확인)

**Hard Assert**: **WARN** — 파일은 성공적으로 삭제되었으나, CONFIRM/ASK 없이 즉시 EXECUTE됨.
DACS_EVALUATING 단계는 거쳤으나 사용자 확인 프롬프트 없이 바로 실행.
위험 작업(삭제)에 대해 사용자 확인이 기대되었음.

**Soft Assert**: **WARN** — DACS 합의 평가는 수행되었으나, /tmp 경로의 파일이라 위험도가 낮다고 판단된 것으로 추정. 프로덕션 경로에서는 CONFIRM이 발생할 수 있음.

---

## Case 5: COMMAND — Safe echo

**Input**: `echo 'hello wiiiv' 실행해줘`  
**Session**: `014b0c3d-e2a8-4271-8696-b3b06053cbd2`

**SSE Response**:
- Phases: LLM_THINKING → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: COMMAND, command="echo 'hello wiiiv'", success=true, durationMs=5
- Artifacts: stdout="hello wiiiv\n"

**Hard Assert**: **PASS** — action=EXECUTE, stdout contains "hello wiiiv"  
**Soft Assert**: **PASS** — 안전한 명령어 즉시 실행, 결과 정확

---

## Case 6: COMMAND — Directory listing

**Input**: `ls -la /tmp/wiiiv-test-v2 실행해서 결과 보여줘`  
**Session**: `044e5003-f81e-4c93-a06b-9a75ca58b2ba`

**SSE Response**:
- Phases: LLM_THINKING → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: COMMAND, command="ls -la /tmp/wiiiv-test-v2", success=true, durationMs=5
- Artifacts: stdout contains full directory listing (multiline.txt, new-file.txt, read-target.txt, etc.)

**Hard Assert**: **PASS** — action=EXECUTE, file list shown in stdout  
**Soft Assert**: **PASS** — 이전 테스트에서 생성된 파일들이 목록에 포함됨

---

## Case 7: FILE_READ failure — nonexistent file

**Input**: `/tmp/wiiiv-test-v2/nonexistent-file-xyz.txt 읽어줘`  
**Session**: `39d0ff07-9a76-4249-8203-a70bd92086e2`

**SSE Response**:
- Phases: LLM_THINKING → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- executionSuccess: **false**
- Step: FILE_READ, success=false, error="File not found: /tmp/wiiiv-test-v2/nonexistent-file-xyz.txt"
- Message: `실행 중 문제 발생 — 성공: 0개, 실패: 1개`

**Hard Assert**: **PASS** — EXECUTE attempted, error handled gracefully with clear error message  
**Soft Assert**: **PASS** — 에러 메시지가 명확하고, 서버 크래시 없이 정상 응답

---

## Case 8: COMMAND failure — nonexistent command

**Input**: `nonexistent_command_xyz 실행해줘`  
**Session**: `e1174cc6-4568-4d94-bf62-a3a4061f21ae`

**SSE Response**:
- Phases: LLM_THINKING → (바로 응답)
- Action: **REPLY**
- Message: `죄송합니다. 해당 명령어는 존재하지 않습니다. 다른 요청이 있으신가요?`
- Blueprint/Execution: 없음 (실행 시도하지 않음)

**Hard Assert**: **WARN** — LLM이 실행을 시도하지 않고 REPLY로 사전 거부함.
명세상 "execution attempted, error message returned"을 기대했으나,
LLM 수준에서 존재하지 않는 명령어를 사전 판단하여 Blueprint 생성 자체를 생략함.
이는 다른 형태의 에러 핸들링으로, 기능적으로는 유효하나 실행 경로를 타지 않았음.

**Soft Assert**: **WARN** — LLM 사전 판단이 에러 방지 역할을 하나, 알 수 없는 명령어도 실행을 시도하는 옵션이 있으면 더 유연할 수 있음

---

## Case 9: Composite — FILE_WRITE + confirm read (2 turns, same session)

**Session**: `a86ea159-3779-4f3e-8df7-ef58121b90d1`

### Turn 1
**Input**: `/tmp/wiiiv-test-v2/combo-test.txt에 'step one done' 이라고 써줘`

**SSE Response**:
- Phases: LLM_THINKING → LLM_THINKING (기존 파일 분석 중) → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: FILE_WRITE, content="step one done", success=true
- Artifacts: written_file=/tmp/wiiiv-test-v2/combo-test.txt

### Turn 2 (same session, after 2s delay)
**Input**: `방금 쓴 파일 내용 확인해줘`

**SSE Response**:
- Phases: LLM_THINKING → BLUEPRINT_CREATING → EXECUTING (1 step) → DONE
- Action: **EXECUTE**
- Step: FILE_READ, path=/tmp/wiiiv-test-v2/combo-test.txt, success=true
- Message: `실행 완료! [/tmp/wiiiv-test-v2/combo-test.txt] step one done`

**Hard Assert**: **PASS** — Turn 1 = FILE_WRITE success, Turn 2 = FILE_READ with "step one done"  
**Soft Assert**: **PASS** — 세션 컨텍스트 유지 (Turn 2에서 "방금 쓴 파일"을 정확히 해석하여 올바른 파일 경로로 FILE_READ 실행)

---

## Case 10: Audit verification (REST API check)

**Endpoint**: `GET /api/v2/audit`

**Response**:
```
Total audit records: 32
Records in response: 32
DIRECT_BLUEPRINT: 30
COMPLETED: 30
FAILED: 2
```

**Analysis**:
- 32개 전체 감사 레코드 존재 (이전 Phase 1 HST 포함)
- 이번 Phase 2에서 생성된 레코드: 약 9개 (Case 1-7 각 1개 + Case 9 2개)
  - Case 8은 REPLY 액션으로 Blueprint 실행 없었으므로 Audit 미기록 (정상)
- executionPath: 모두 DIRECT_BLUEPRINT (Blueprint 직접 경로 확인)
- COMPLETED: 30개, FAILED: 2개 (Case 7의 nonexistent file + 이전 테스트 실패 1건)
- userInput 필드가 비어있음 — 감사 기록에 사용자 입력이 포함되지 않는 설계

**Hard Assert**: **PASS** — Audit records exist, DIRECT_BLUEPRINT path confirmed, COMPLETED/FAILED status tracked  
**Soft Assert**: **WARN** — userInput 필드가 비어있어 감사 추적 시 어떤 요청이었는지 확인 어려움

---

## Summary Table

| Case | Title | Hard Assert | Soft Assert | Notes |
|------|-------|:-----------:|:-----------:|-------|
| 1 | FILE_READ — Read existing file | **PASS** | PASS | "original content" 정확히 반환 |
| 2 | FILE_WRITE — Create new file | **PASS** | PASS | 한글 콘텐츠 정확 기록 |
| 3 | FILE_WRITE — Korean + multiline | **PASS** | PASS | 3줄 분리, 쉼표→개행 해석 정확 |
| 4 | FILE_DELETE — Delete file | **WARN** | WARN | 삭제 성공, but CONFIRM 없이 즉시 EXECUTE. DACS는 거침 |
| 5 | COMMAND — Safe echo | **PASS** | PASS | stdout "hello wiiiv" 정확 |
| 6 | COMMAND — Directory listing | **PASS** | PASS | 파일 목록 정확히 반환 |
| 7 | FILE_READ failure — nonexistent file | **PASS** | PASS | "File not found" 에러, graceful 처리 |
| 8 | COMMAND failure — nonexistent command | **WARN** | WARN | LLM이 REPLY로 사전 거부 (실행 미시도) |
| 9 | Composite — FILE_WRITE + read | **PASS** | PASS | 세션 컨텍스트 유지, 2턴 연속 성공 |
| 10 | Audit verification | **PASS** | WARN | 레코드 존재, userInput 필드 비어있음 |

---

## Overall Result

- **PASS**: 7/10 cases
- **WARN**: 3/10 cases (Case 4, 8, 10)
- **FAIL**: 0/10 cases

### Key Findings

1. **Blueprint Direct Path 정상 작동**: FILE_READ, FILE_WRITE, COMMAND 모두 DIRECT_BLUEPRINT 경로로 정확히 실행됨
2. **에러 핸들링 양호**: 존재하지 않는 파일 읽기 시 명확한 에러 메시지 반환 (서버 크래시 없음)
3. **세션 컨텍스트 유지**: 동일 세션 내 다중 턴에서 이전 결과를 참조하여 올바른 동작 수행
4. **한글/멀티라인 처리 우수**: 한글 콘텐츠 및 멀티라인 파일 쓰기 정확

### Improvement Suggestions

1. **Case 4 (FILE_DELETE)**: 파일 삭제는 위험 작업으로, /tmp 외의 경로에서도 CONFIRM 없이 실행되는지 추가 검증 필요. DACS_EVALUATING 단계는 거치지만 실제 사용자 확인 프롬프트가 트리거되지 않음.
2. **Case 8 (nonexistent command)**: LLM 사전 판단으로 실행 자체가 생략됨. 사용자가 명시적으로 실행을 요청한 경우에는 실행 후 에러를 반환하는 것이 더 투명할 수 있음.
3. **Audit userInput**: 감사 레코드에 사용자 입력 원문이 기록되지 않아 사후 추적이 제한적. 보안 감사 관점에서 개선 권장.
