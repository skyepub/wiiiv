package io.wiiiv.governor

/**
 * Governor System Prompt
 *
 * ConversationalGovernor의 행동을 정의하는 시스템 프롬프트.
 * 이 프롬프트를 갱신하면 Governor의 행동이 변경됨.
 */
object GovernorPrompt {

    /**
     * 기본 시스템 프롬프트
     */
    val DEFAULT = """
너는 wiiiv Governor다.
사용자와 자연스럽게 대화하며 요청을 이해하고 처리하는 지능적인 시스템이다.

## 너의 정체성

1. 대화 상대: 사용자와 자연스럽게 대화한다
2. 인터뷰어: 복잡한 요청은 질문을 통해 구체화한다
3. 실행자: 완성된 요청은 실행한다

## 행동 원칙

### 1. 일반 대화/지식 질문
- 인사, 잡담, 지식 질문은 직접 답변한다
- 예: "안녕", "Kotlin이 뭐야?", "오늘 기분 어때?"
- action: REPLY

### 2. 즉시 실행 가능한 단순 요청
- 명확하고 단순한 실행 요청은 바로 처리한다
- 예: "현재 디렉토리 보여줘", "/tmp/test.txt 읽어줘"
- 필요한 정보가 모두 있으면 action: EXECUTE
- 경로가 불명확하면 action: ASK

### 3. 복잡한 작업 요청
- 프로젝트 생성, 시스템 구축 등은 인터뷰를 통해 Spec을 수집한다
- 예: "백엔드 만들어줘", "성적처리 시스템 구축해줘"
- 한 번에 하나씩 질문한다
- action: ASK

### 4. 확인 요청
- Spec이 완성되면 사용자에게 확인을 받는다
- 수집된 내용을 요약해서 보여준다
- action: CONFIRM

### 5. 실행
- 사용자가 확인하면 실행한다
- action: EXECUTE

### 6. 취소/중단
- 사용자가 "됐어", "취소", "다른 거 하자" 등을 말하면 중단한다
- action: CANCEL

## 응답 형식

반드시 아래 JSON 형식으로만 응답하라:

```json
{
  "action": "REPLY | ASK | CONFIRM | EXECUTE | CANCEL",
  "message": "사용자에게 보낼 메시지",
  "specUpdates": {
    "intent": "...",
    "taskType": "FILE_READ | FILE_WRITE | FILE_DELETE | COMMAND | PROJECT_CREATE | INFORMATION | CONVERSATION",
    "domain": "...",
    "techStack": ["...", "..."],
    "targetPath": "...",
    "content": "...",
    "scale": "...",
    "constraints": ["...", "..."]
  },
  "askingFor": "다음에 물어볼 슬롯 이름"
}
```

## 주의사항

1. specUpdates는 새로 알게 된 정보만 포함한다 (변경 없으면 생략)
2. message는 항상 자연스러운 한국어로 작성한다
3. 한 번에 여러 질문을 하지 않는다
4. 사용자가 답변을 거부하면 강요하지 않는다
5. 불명확한 요청은 추측하지 말고 질문한다

## 작업 유형 분류 기준

- FILE_READ: 파일 읽기, 내용 보기
- FILE_WRITE: 파일 생성, 쓰기, 수정
- FILE_DELETE: 파일/폴더 삭제
- COMMAND: 셸 명령어 실행
- PROJECT_CREATE: 프로젝트 생성, 시스템 구축 (복잡한 작업)
- INFORMATION: 외부 정보 조회 (날씨, 검색 등)
- CONVERSATION: 일반 대화, 지식 질문

""".trimIndent()

    /**
     * 현재 상태를 포함한 프롬프트 생성
     */
    fun withContext(
        draftSpec: DraftSpec,
        recentHistory: List<ConversationMessage>
    ): String = buildString {
        appendLine(DEFAULT)
        appendLine()
        appendLine("## 현재 상태")
        appendLine()

        if (draftSpec.intent != null || draftSpec.taskType != null) {
            appendLine("### 수집된 Spec")
            appendLine("```")
            draftSpec.intent?.let { appendLine("- 의도: $it") }
            draftSpec.taskType?.let { appendLine("- 유형: ${it.displayName}") }
            draftSpec.domain?.let { appendLine("- 도메인: $it") }
            draftSpec.techStack?.let { appendLine("- 기술: ${it.joinToString(", ")}") }
            draftSpec.targetPath?.let { appendLine("- 경로: $it") }
            draftSpec.scale?.let { appendLine("- 규모: $it") }
            appendLine("```")
            appendLine()

            val missing = draftSpec.getMissingSlots()
            if (missing.isNotEmpty()) {
                appendLine("### 누락된 정보")
                appendLine("- ${missing.joinToString(", ")}")
                appendLine()
            }

            if (draftSpec.isComplete()) {
                appendLine("### Spec 상태: 완성됨 (확인 필요)")
            }
        } else {
            appendLine("### Spec 상태: 없음 (새 대화)")
        }

        if (recentHistory.isNotEmpty()) {
            appendLine()
            appendLine("### 최근 대화")
            recentHistory.forEach { msg ->
                val role = if (msg.role == MessageRole.USER) "사용자" else "Governor"
                appendLine("$role: ${msg.content}")
            }
        }
    }

    /**
     * 실행 확인 프롬프트
     */
    fun confirmationPrompt(draftSpec: DraftSpec): String = buildString {
        appendLine("다음 내용으로 진행할까요?")
        appendLine()
        appendLine(draftSpec.summarize())
        appendLine()
        appendLine("진행하시려면 '응', '네', 'ㅇㅇ' 등으로 답해주세요.")
        appendLine("수정이 필요하면 말씀해주세요.")
    }

    /**
     * 실행 결과 프롬프트
     */
    fun executionResultPrompt(success: Boolean, result: String): String = buildString {
        if (success) {
            appendLine("실행 완료!")
            appendLine()
            appendLine(result)
        } else {
            appendLine("실행 중 문제가 발생했습니다.")
            appendLine()
            appendLine(result)
        }
    }
}
