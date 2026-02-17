# wiiiv Shell Command Specification v1.0

> Shell REPL 슬래시 명령어 설계서
>
> wiiiv v2.1 / 하늘나무 / SKYTREE

---

## 1. 설계 원칙

```
슬래시 명령은 Governor를 우회한다.
Governor에게 말을 거는 것이 아니라, Shell에게 직접 지시하는 것이다.
```

| 원칙 | 설명 |
|------|------|
| **Governor 우회** | `/` 명령은 LLM을 호출하지 않는다. Shell이 직접 처리한다 |
| **읽기 우선** | 대부분의 명령은 상태를 조회한다. 변경은 Shell 설정에 한한다 |
| **비용 제로** | 슬래시 명령은 API 호출 없이 즉시 응답한다 |
| **계층 존중** | Governor/DACS/Gate의 내부를 변경하지 않는다 |

---

## 2. 명령어 분류

### 2.1 Tier 1 — 필수 (MVP)

사용자가 즉시 필요로 하는 핵심 명령.

| 명령 | 설명 |
|------|------|
| `/help` | 사용 가능한 명령 목록 |
| `/status` | 현재 상태 종합 (세션, LLM, DACS, 활성 task) |
| `/history [N]` | 최근 N개 대화 기록 (기본 10) |
| `/tasks` | 모든 task 목록 (active/suspended/completed) |
| `/clear` | 화면 지우기 |
| `/reset` | 현재 작업 초기화 (세션 유지) |

### 2.2 Tier 2 — 검사 (Inspection)

진행 중인 작업의 상세 상태를 확인.

| 명령 | 설명 |
|------|------|
| `/spec` | 현재 DraftSpec 상태 (슬롯 채움 현황) |
| `/blueprint` | 마지막 실행된 Blueprint 상세 |
| `/result [N]` | N번째 턴의 실행 결과 상세 (기본: 마지막) |
| `/artifacts` | 생성된 아티팩트 목록 |

### 2.3 Tier 3 — 제어 (Control)

Task 전환 및 세션 관리.

| 명령 | 설명 |
|------|------|
| `/switch <id>` | 지정 task로 전환 (현재 task 자동 suspend) |
| `/cancel` | 현재 task 취소 |
| `/cancel all` | 모든 task 취소 + 세션 초기화 |

### 2.4 Tier 4 — 설정 (Configuration)

Shell 동작을 런타임에 변경.

| 명령 | 설명 |
|------|------|
| `/set` | 현재 설정 전체 표시 |
| `/set autocontinue <on\|off>` | 자동 계속 실행 토글 |
| `/set maxcontinue <N>` | 자동 계속 최대 횟수 (기본 10) |
| `/set verbose <on\|off>` | 상세 출력 모드 |
| `/set color <on\|off>` | ANSI 색상 토글 |

---

## 3. 명령별 상세 설계

### 3.1 `/help`

```
사용 가능한 명령:

  /help                 이 도움말
  /status               현재 상태 종합
  /history [N]          최근 N개 대화 기록
  /tasks                Task 목록
  /spec                 현재 DraftSpec 상태
  /blueprint            마지막 Blueprint 상세
  /result [N]           실행 결과 상세
  /artifacts            아티팩트 목록
  /switch <id>          Task 전환
  /cancel [all]         Task 취소
  /reset                작업 초기화
  /set [key] [value]    설정 변경
  /clear                화면 지우기
  exit                  종료
```

---

### 3.2 `/status`

세션, LLM, DACS, 현재 task 정보를 한 화면에 종합.

**데이터 소스:**
- `session.sessionId` — 세션 ID
- `session.createdAt` — 세션 시작 시간
- `session.history.size` — 대화 턴 수
- `session.context.activeTask` — 활성 task
- `session.context.tasks` — 전체 task map
- `session.context.executionHistory` — 실행 기록
- `session.context.declaredWriteIntent` — 쓰기 의도 선언 여부
- `llmProvider != null` — LLM 활성 여부
- `model` — 모델명
- DACS 타입 (HybridDACS / SimpleDACS)

**출력 예시:**
```
  [SESSION] gov-shell / abc-123-def
  Uptime: 5m 23s | Turns: 12

  [LLM] gpt-4o-mini (OpenAI)
  [DACS] HybridDACS (3 rule + 3 LLM)

  [TASK] #1 "Read config file" (ACTIVE)
    Type: FILE_READ | Path: /tmp/config.json
    Spec: 3/3 slots filled (complete)
    Turns: 2 | Write intent: none

  [TASKS] 1 active, 1 suspended, 0 completed
```

---

### 3.3 `/history [N]`

최근 N개 대화 메시지 표시 (기본 10).

**데이터 소스:**
- `session.getRecentHistory(count)` → `List<ConversationMessage>`
- 각 메시지: `role` (USER / GOVERNOR / SYSTEM), `content`, `timestamp`

**출력 예시:**
```
  [HISTORY] Last 5 messages

  14:32:01  USER      /tmp/test.txt 읽어줘
  14:32:02  GOVERNOR  파일을 읽겠습니다. 확인해주세요.
  14:32:05  USER      확인
  14:32:06  GOVERNOR  실행 완료. 파일 내용: Hello World
  14:32:10  USER      고마워
```

**규칙:**
- SYSTEM 메시지는 verbose 모드에서만 표시
- 긴 메시지는 80자에서 truncate + `...`
- 타임스탬프는 `HH:mm:ss` 형식

---

### 3.4 `/tasks`

모든 TaskSlot 목록.

**데이터 소스:**
- `session.context.tasks` → `Map<TaskId, TaskSlot>`
- `session.context.activeTaskId` — 현재 활성 task
- 각 TaskSlot: `id`, `label`, `status`, `draftSpec.taskType`, `createdAt`

**출력 예시:**
```
  [TASKS] 3 total

  * #1  "Read config file"      ACTIVE     FILE_READ    14:32
    #2  "Deploy to staging"     SUSPENDED  COMMAND      14:28
    #3  "Check API status"      COMPLETED  API_WORKFLOW 14:25
```

**규칙:**
- `*` 표시: 현재 활성 task
- status 색상: ACTIVE=cyan, SUSPENDED=yellow, COMPLETED=dim

---

### 3.5 `/spec`

현재 DraftSpec 슬롯 채움 현황.

**데이터 소스:**
- `session.draftSpec` → DraftSpec
- `draftSpec.taskType`, `intent`, `domain`, `targetPath`, `content`, `techStack`, `scale`, `constraints`
- `draftSpec.getRequiredSlots()`, `getFilledSlots()`, `getMissingSlots()`
- `draftSpec.isComplete()`, `isRisky()`

**출력 예시:**
```
  [SPEC] Draft #draft-abc

  Task type:  FILE_WRITE
  Intent:     Create a hello world file
  Complete:   No (2/3 slots)
  Risky:      No

  Slots:
    [v] intent      "Create a hello world file"
    [v] targetPath  "/tmp/hello.txt"
    [ ] content     (missing)
```

---

### 3.6 `/blueprint`

마지막 실행 Blueprint 상세.

**데이터 소스:**
- `session.context.executionHistory.lastOrNull()?.blueprint` → Blueprint
- `blueprint.id`, `steps`, `metadata`, `specSnapshot`

**출력 예시:**
```
  [BLUEPRINT] bp-xyz-123

  Spec:   spec-abc (v1.0)
  DACS:   YES
  Steps:  3

    1. FILE_MKDIR   path=/tmp/project
    2. FILE_WRITE   path=/tmp/project/main.kt  content=(142 bytes)
    3. COMMAND      cmd="chmod +x /tmp/project/main.kt"
```

---

### 3.7 `/result [N]`

N번째 턴 실행 결과 (기본: 마지막 턴).

**데이터 소스:**
- `session.context.executionHistory[n-1]` → TurnExecution
- `turnExecution.result` → BlueprintExecutionResult
- `result.isSuccess`, `successCount`, `failureCount`
- `result.context.stepOutputs` → 각 step의 stdout/stderr/exitCode/artifacts

**출력 예시:**
```
  [RESULT] Turn #2

  Blueprint:  bp-xyz-123
  Success:    Yes (3/3 steps)
  Duration:   1.2s

  Steps:
    1. FILE_MKDIR   OK     12ms
    2. FILE_WRITE   OK     5ms   artifacts: main.kt
    3. COMMAND      OK     1180ms
       stdout: "Build successful"
```

**규칙:**
- 실패한 step은 stderr 표시
- verbose 모드: 모든 stdout/stderr 전체 표시

---

### 3.8 `/artifacts`

생성된 아티팩트 목록.

**데이터 소스:**
- `session.context.artifacts` → `Map<String, String>`
- 각 task의 `taskSlot.context.artifacts`

**출력 예시:**
```
  [ARTIFACTS] 2 items

  Task #1 "Read config file":
    config.json     /tmp/config.json

  Session:
    (none)
```

---

### 3.9 `/switch <id>`

task 전환.

**동작:**
1. 현재 active task → `session.suspendCurrentWork()` (SUSPENDED)
2. 지정 task → `status = ACTIVE`, `context.activeTaskId = id`
3. DraftSpec proxy 자동 전환

**출력:**
```
  Task #1 "Read config file" suspended.
  Switched to Task #2 "Deploy to staging".
```

**에러:**
- task ID 없음: `Task #99 not found. Use /tasks to see available tasks.`
- 이미 활성: `Task #1 is already active.`

---

### 3.10 `/cancel [all]`

**`/cancel`** — 현재 task만 취소:
- `session.cancelCurrentTask()`
- 활성 task 없으면: `No active task to cancel.`

**`/cancel all`** — 전체 초기화 (확인 필수):
- Shell 레벨에서 확인 프롬프트: `Cancel all tasks and reset session? (y/N)`
- `y` 입력 시: `session.resetAll()` → `All tasks cancelled. Session reset.`
- 그 외: `Cancelled.`
- Governor를 거치지 않는 Shell 직접 확인이므로 LLM 비용 없음

---

### 3.11 `/reset`

현재 작업 상태만 초기화 (세션과 대화 기록은 유지).

**동작:**
- `session.resetSpec()` — DraftSpec 초기화, context 유지

**출력:**
```
  Current spec cleared. Session preserved.
```

---

### 3.12 `/set`

Shell 레벨 설정. Governor/DACS 내부는 변경 불가.

**설정 항목:**

| 키 | 타입 | 기본값 | 설명 |
|----|------|--------|------|
| `autocontinue` | on/off | on | API workflow 자동 계속 실행 |
| `maxcontinue` | 1-50 | 10 | 자동 계속 최대 횟수 |
| `verbose` | on/off | off | 상세 출력 (SYSTEM 메시지, 전체 stdout) |
| `color` | on/off | on | ANSI 색상 출력 |

**`/set` (인자 없음) 출력:**
```
  [SETTINGS]

  autocontinue  on
  maxcontinue   10
  verbose       off
  color         on
```

**`/set verbose on` 출력:**
```
  verbose = on
```

---

### 3.13 `/clear`

화면 지우기.

**동작:**
- ANSI escape: `\033[2J\033[H` (화면 지우기 + 커서 홈)

---

## 4. 구현 설계

### 4.1 파서 구조

```kotlin
// Shell REPL에서 "/" 로 시작하는 입력을 분기
if (input.startsWith("/")) {
    handleSlashCommand(input, session, shellConfig)
    continue  // Governor로 보내지 않음
}
```

### 4.2 ShellConfig 데이터 클래스

```kotlin
data class ShellConfig(
    var autoContinue: Boolean = true,
    var maxContinue: Int = 10,
    var verbose: Boolean = false,
    var color: Boolean = true
)
```

### 4.3 명령 디스패치

```kotlin
fun handleSlashCommand(
    input: String,
    session: ConversationSession,
    governor: ConversationalGovernor,
    config: ShellConfig,
    // ANSI color strings
    colors: ShellColors
) {
    val parts = input.removePrefix("/").split(" ", limit = 3)
    val cmd = parts[0].lowercase()
    val args = parts.drop(1)

    when (cmd) {
        "help"      -> cmdHelp(colors)
        "status"    -> cmdStatus(session, governor, colors)
        "history"   -> cmdHistory(session, args, config, colors)
        "tasks"     -> cmdTasks(session, colors)
        "spec"      -> cmdSpec(session, colors)
        "blueprint" -> cmdBlueprint(session, colors)
        "result"    -> cmdResult(session, args, config, colors)
        "artifacts" -> cmdArtifacts(session, colors)
        "switch"    -> cmdSwitch(session, args, colors)
        "cancel"    -> cmdCancel(session, args, colors)
        "reset"     -> cmdReset(session, colors)
        "set"       -> cmdSet(config, args, colors)
        "clear"     -> cmdClear()
        else        -> println("Unknown command: /$cmd. Type /help for available commands.")
    }
}
```

### 4.4 파일 구조

```
wiiiv-shell/src/main/kotlin/io/wiiiv/shell/
├── Main.kt              — REPL loop (기존)
├── ShellConfig.kt        — 설정 데이터 클래스
├── ShellColors.kt        — ANSI 색상 상수
└── commands/
    ├── SlashCommandHandler.kt  — 디스패치 + 공통 유틸
    ├── InfoCommands.kt         — /help, /status, /history
    ├── InspectCommands.kt      — /spec, /blueprint, /result, /artifacts
    ├── ControlCommands.kt      — /switch, /cancel, /reset
    └── ConfigCommands.kt       — /set, /clear
```

---

## 5. 색상 규칙

| 요소 | 색상 |
|------|------|
| 명령 헤더 `[STATUS]` | Bright Cyan |
| 키/라벨 | White |
| 값/데이터 | Default (reset) |
| 경고 `[WARN]` | Yellow |
| 에러 `[ERROR]` | Red |
| dim 정보 (timestamp, ID) | Dim |
| 활성 task `*` | Cyan |
| 중단 task | Yellow |
| 완료 task | Dim |
| 성공 `OK` | Green |
| 실패 `FAIL` | Red |

---

## 6. 향후 확장 (v1.1+)

| 명령 | 설명 | 의존성 |
|------|------|--------|
| `/export <path>` | 세션 전체를 JSON으로 내보내기 | 파일 직렬화 |
| `/replay <path>` | 저장된 세션 재생 | import 파서 |
| `/model <name>` | LLM 모델 런타임 변경 | Governor 재생성 필요 |
| `/dacs <mode>` | DACS 전략 런타임 변경 | Governor 재생성 필요 |
| `/alias <name> <cmd>` | 명령 별칭 | alias registry |
| `/script <path>` | 슬래시 명령 배치 실행 | 파일 읽기 + 순차 실행 |
| `/log [level]` | 로그 레벨 변경 | Logger 통합 |

---

*wiiiv Shell Command Specification v1.0 / 하늘나무 / SKYTREE*
