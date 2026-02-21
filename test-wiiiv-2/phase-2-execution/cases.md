# Phase 2: 기본 실행 (Blueprint Direct Path)

> **검증 목표**: Blueprint 직행 경로가 정확하게 작동하는가?
> **핵심 관심사**: 각 StepType 실행, 에러 처리, Audit 기록 완전성
> **전제**: Phase 1 통과, 서버 기동

---

## 사전 준비

```bash
# 테스트용 디렉토리
mkdir -p /tmp/wiiiv-test-v2
echo "original content" > /tmp/wiiiv-test-v2/read-target.txt
```

---

## Case 1: FILE_READ — 존재하는 파일 읽기 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/read-target.txt 파일 내용 보여줘" | EXECUTE |

**Hard Assert**:
- action = EXECUTE
- blueprint.steps[0].type = FILE_READ
- executionResult.isSuccess = true

**Soft Assert**:
- 응답에 "original content" 포함

**Audit Assert**:
- Audit 레코드 존재
- executionPath = DIRECT_BLUEPRINT
- status = COMPLETED
- taskType = FILE_READ

---

## Case 2: FILE_WRITE — 새 파일 생성 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/new-file.txt에 '테스트 데이터 입니다' 라고 써줘" | EXECUTE 또는 CONFIRM |

**Hard Assert**:
- blueprint.steps[0].type = FILE_WRITE
- 실행 후 파일이 실제로 존재

**검증 명령**:
```bash
cat /tmp/wiiiv-test-v2/new-file.txt
# → "테스트 데이터 입니다"
```

**Audit Assert**:
- executionPath = DIRECT_BLUEPRINT
- status = COMPLETED

---

## Case 3: FILE_WRITE — 한글 + 특수문자 + 멀티라인 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/multiline.txt에 다음 내용을 써줘:\n첫째 줄: 가나다\n둘째 줄: ABC 123\n셋째 줄: !@#$%^&*()" | EXECUTE 또는 CONFIRM |

**Hard Assert**:
- 실행 성공
- 파일에 3줄 모두 존재

**검증 명령**:
```bash
wc -l /tmp/wiiiv-test-v2/multiline.txt  # → 3
cat /tmp/wiiiv-test-v2/multiline.txt     # → 3줄 내용 확인
```

**의도**: 특수문자, 한글, 멀티라인이 Blueprint 파라미터를 깨뜨리지 않는지

---

## Case 4: FILE_DELETE — 파일 삭제 (1~2턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/new-file.txt 삭제해줘" | CONFIRM 또는 ASK |
| 2 | "응 삭제해" | EXECUTE |

**Hard Assert**:
- Turn 1: 즉시 EXECUTE 금지 (삭제는 확인 필요)
- blueprint.steps[0].type = FILE_DELETE
- 실행 후 파일이 실제로 삭제됨

**Audit Assert**:
- status = COMPLETED
- dacsConsensus 기록 (삭제는 risky)

**의도**: 위험 작업(삭제)에 대한 확인 절차 + DACS 트리거 검증

---

## Case 5: COMMAND — 안전한 명령 실행 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "echo 'hello wiiiv' 실행해줘" | EXECUTE |

**Hard Assert**:
- action = EXECUTE
- blueprint.steps[0].type = COMMAND
- 응답에 "hello wiiiv" 포함

**Audit Assert**:
- executionPath = DIRECT_BLUEPRINT
- status = COMPLETED
- taskType 포함 (COMMAND 또는 FILE_READ 등)

---

## Case 6: COMMAND — 디렉토리 목록 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "ls -la /tmp/wiiiv-test-v2 실행해서 결과 보여줘" | EXECUTE |

**Hard Assert**:
- EXECUTE
- 응답에 파일 목록 포함

**Soft Assert**:
- multiline.txt, read-target.txt 등 이전 케이스에서 만든 파일 표시

---

## Case 7: FILE_READ 실패 — 존재하지 않는 파일 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/nonexistent-file-xyz.txt 읽어줘" | EXECUTE |

**Hard Assert**:
- EXECUTE 시도
- executionResult.isSuccess = false (파일 없음)
- Governor가 에러를 사용자 친화적으로 전달

**Soft Assert**:
- "파일이 존재하지 않" 또는 "찾을 수 없" 포함

**Audit Assert**:
- status = FAILED
- error 필드에 원인 기록

**의도**: 실패 경로가 깨지지 않고 정상 처리되는지

---

## Case 8: COMMAND 실패 — 존재하지 않는 명령 (1턴)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "nonexistent_command_xyz 실행해줘" | EXECUTE |

**Hard Assert**:
- 실행 시도 후 실패 처리
- 에러 메시지 사용자에게 전달

**Audit Assert**:
- status = FAILED 또는 ERROR
- error 필드 기록

---

## Case 9: 복합 실행 — FILE_WRITE + 확인 읽기 (2턴, 같은 세션)

| Turn | 입력 | 기대 Action |
|------|------|-------------|
| 1 | "/tmp/wiiiv-test-v2/combo-test.txt에 'step one done' 이라고 써줘" | EXECUTE |
| 2 | "방금 쓴 파일 내용 확인해줘" | EXECUTE |

**Hard Assert**:
- Turn 1: FILE_WRITE, isSuccess = true
- Turn 2: FILE_READ, 응답에 "step one done" 포함

**Soft Assert**:
- Turn 2에서 Governor가 "방금 쓴 파일"을 이전 턴의 /tmp/wiiiv-test-v2/combo-test.txt로 해석

**Audit Assert**:
- Audit 레코드 2개 (각 턴마다 1개)
- 둘 다 DIRECT_BLUEPRINT

**의도**: 세션 내 컨텍스트가 실행 간에도 유지되는지 + 연속 실행 정상 동작

---

## Case 10: Audit 종합 검증 (Phase 2 전체)

> 이 케이스는 독립 실행이 아닌 Phase 2 완료 후 확인 작업

**절차**:
```bash
curl -s http://localhost:8235/api/v2/audit \
  -H "Authorization: Bearer $TOKEN" | jq '.data[] | {executionPath, status, taskType}'
```

**Hard Assert**:
- DIRECT_BLUEPRINT 레코드가 최소 5개 이상
- COMPLETED 레코드 존재
- FAILED 레코드 존재 (Case 7, 8)

**Audit Assert**:
- 모든 EXECUTE 턴에 대응하는 Audit 레코드 존재 (1:1 매핑)
- REPLY 턴에는 Audit 레코드 없음

**통계 확인**:
```bash
curl -s http://localhost:8235/api/v2/audit/stats \
  -H "Authorization: Bearer $TOKEN" | jq
```

**의도**: Phase E-2에서 구현한 DIRECT_BLUEPRINT Audit이 실제로 작동하는지 종합 확인
