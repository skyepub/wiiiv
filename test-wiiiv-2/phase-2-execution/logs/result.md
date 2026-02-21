# Phase 2 결과 — 기본 실행

> 실행일: 2026-02-21
> 서버: localhost:8235 (LLM: gpt-4o-mini, Audit: H2 file mode)

## 총괄 (P2-001 수정 후 최종)

| 결과 | 수 |
|------|---|
| **PASS** | 8 |
| **SOFT FAIL** | 1 |
| **HARD FAIL** | 0 |
| **N/A** | 1 |

---

## Case 1: FILE_READ — **PASS**
- EXECUTE ✅, "original content" 반환 ✅
- Audit: DIRECT_BLUEPRINT, COMPLETED, taskType=FILE_READ ✅

## Case 2: FILE_WRITE — **PASS**
- EXECUTE ✅, 파일 생성 확인 ✅
- Audit: DIRECT_BLUEPRINT, COMPLETED, taskType=FILE_WRITE ✅

## Case 3: FILE_WRITE 멀티라인 — **PASS**
- EXECUTE ✅, 3줄 + 한글 + 특수문자 정상 저장 ✅
- Audit: DIRECT_BLUEPRINT, COMPLETED ✅
- **참고**: JSON 특수문자 이스케이프 필요 (첫 시도 실패 후 jq로 해결)

## Case 4: FILE_DELETE — **SOFT FAIL**
- **1차 실행**: EXECUTE → 바로 삭제 (CONFIRM 없이)
- **2차 실행 (재시작 후)**: CANCEL — DACS 거부
  - "보안상 이 요청을 실행할 수 없습니다"
- **이슈**: 삭제에 대한 동작이 일관적이지 않음 (세션에 따라 다름)
- DACS가 FILE_DELETE도 차단하는 경우 발생

## Case 5: COMMAND echo — **PASS** (P2-001 수정 후)
- ~~**CANCEL** — DACS 거부~~ → **수정 후 EXECUTE ✅**
- stdout: `hello wiiiv\n` ✅
- Audit: DIRECT_BLUEPRINT, COMPLETED, taskType=COMMAND ✅
- **수정 내용**: DraftSpec.isRisky() 안전 명령 허용 + CommandExecutor `/bin/sh -c` 실행

## Case 6: COMMAND ls — **PASS** (P2-001 수정 후)
- ~~**CANCEL** — DACS 거부~~ → **수정 후 EXECUTE ✅**
- stdout: 디렉토리 내용 정상 출력 ✅
- Audit: DIRECT_BLUEPRINT, COMPLETED, taskType=COMMAND ✅

## Case 7: FILE_READ 실패 — **PASS**
- EXECUTE 시도 → isSuccess=false ✅
- "실행 중 문제 발생, 성공: 0개, 실패: 1개"
- Audit: DIRECT_BLUEPRINT, **FAILED** ✅ — 실패도 감사 기록됨!

## Case 8: COMMAND 실패 — **PASS**
- REPLY "인식할 수 없는 명령어입니다" ✅
- Governor가 아예 실행 시도하지 않고 거부 (합리적 판단)

## Case 9: FILE_WRITE + FILE_READ 연속 — **PASS**
- Turn 1: EXECUTE (FILE_WRITE) ✅
- Turn 2: EXECUTE (FILE_READ) → "audit test done" 확인 ✅
- 세션 컨텍스트: "방금 쓴 파일" → 정확한 경로 해석 ✅
- Audit: 2개 레코드 (FILE_WRITE + FILE_READ) ✅

## Case 10: Audit 종합 — **PASS**
- DIRECT_BLUEPRINT 레코드: **15개** (P2-001 수정 전/후 포함) ✅
- COMPLETED/FAILED 모두 기록 ✅
- 모든 EXECUTE 턴에 1:1 대응 Audit 레코드 ✅
- CANCEL/REPLY 턴에는 Audit 없음 ✅ (실행되지 않았으므로 정상)
- Audit 필드 확인:
  - executionPath: DIRECT_BLUEPRINT ✅
  - userId: dev-user ✅
  - role: OPERATOR ✅
  - intent: 자연어 요약 ✅
  - governanceApproved: true ✅

---

## 수정 완료된 이슈

### Issue P2-001: DACS가 안전한 COMMAND를 과도하게 차단 — **RESOLVED**
- **수정 1**: `DraftSpec.isRisky()` — COMMAND는 `isDangerousCommand()` 체크
  - 안전 명령 (echo, ls, cat, pwd 등): isRisky=false → DACS 바이패스
  - 위험 명령 (rm, kill, mkfs 등): isRisky=true → DACS 평가
- **수정 2**: `CommandExecutor.executeCommand()` — `/bin/sh -c` 셸 실행
  - LLM이 `command: "echo hello wiiiv"` 전체를 하나로 생성해도 정상 동작
  - 파이프(`|`), 리디렉션(`>`), 변수(`$`) 등 셸 기능 지원
- **수정 3**: `CommandExecutor` — `workingDir` 빈 문자열 처리
  - `step.workingDir?.takeIf { it.isNotBlank() }` 추가
- **검증**: echo ✅, ls ✅, rm -rf → CANCEL ✅

### Issue P2-002: FILE_DELETE 동작 비일관성 — **OPEN** (MEDIUM)
- **Case**: 4
- **증상**: 첫 실행에서는 바로 EXECUTE, 재시작 후에는 CANCEL
- **원인 추정**: LLM 판단 + DACS 판단의 비결정성
- **영향**: 사용자 경험 불일관

---

## 핫픽스 목록

1. **H2 드라이버 로드** — WiiivRegistry.kt에 `Class.forName("org.h2.Driver")` 추가
2. **DraftSpec.isRisky()** — COMMAND 안전 명령 화이트리스트 (`isDangerousCommand()`)
3. **CommandExecutor** — `/bin/sh -c` 셸 실행 + workingDir 빈 문자열 처리

## 변경 파일 요약

| 파일 | 변경 내용 |
|------|-----------|
| `DraftSpec.kt` | isRisky() COMMAND 분기 + isDangerousCommand() 추가 |
| `CommandExecutor.kt` | `/bin/sh -c` 셸 실행 + workingDir 빈문자열 처리 |
| `WiiivRegistry.kt` | H2 JDBC 드라이버 명시 로드 |
| `AuditRecord.kt` | DIRECT_BLUEPRINT enum + fromBlueprintResult() |
| `ConversationalGovernor.kt` | 3개 Audit 훅 삽입 |
