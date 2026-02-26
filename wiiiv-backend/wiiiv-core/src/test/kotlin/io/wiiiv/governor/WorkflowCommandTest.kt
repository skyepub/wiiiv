package io.wiiiv.governor

import io.wiiiv.dacs.*
import io.wiiiv.hlx.store.WorkflowRecord
import io.wiiiv.hlx.store.WorkflowStore
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * 워크플로우 관리 명령 감지/처리 테스트
 *
 * detectWorkflowCommand() 패턴 매칭, handleWorkflowCommand() 동작,
 * lastExecutedWorkflowId 설정/활용을 검증한다.
 */
class WorkflowCommandTest {

    private lateinit var governor: ConversationalGovernor
    private lateinit var workflowStore: InMemoryWorkflowStore

    @BeforeEach
    fun setup() {
        workflowStore = InMemoryWorkflowStore()
        governor = ConversationalGovernor.create(
            dacs = SimpleDACS.DEFAULT,
            llmProvider = null,
            workflowStore = workflowStore
        )
    }

    // === detectWorkflowCommand 패턴 매칭 테스트 ===

    @Nested
    @DisplayName("detectWorkflowCommand - SAVE patterns")
    inner class SavePatternTests {

        @Test
        fun `Korean - 워크플로우 저장해줘`() {
            val result = governor.detectWorkflowCommand("워크플로우 저장해줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
        }

        @Test
        fun `Korean - 방금 실행한 워크플로우를 저장해줘`() {
            val result = governor.detectWorkflowCommand("방금 실행한 워크플로우를 저장해줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
        }

        @Test
        fun `Korean - 방금 저장해줘`() {
            val result = governor.detectWorkflowCommand("방금 저장해줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
        }

        @Test
        fun `English - save the workflow`() {
            val result = governor.detectWorkflowCommand("save the workflow")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
        }

        @Test
        fun `English - Save workflow`() {
            val result = governor.detectWorkflowCommand("Save workflow")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
        }

        @Test
        fun `name extraction with quotes`() {
            val result = governor.detectWorkflowCommand("""워크플로우를 "날씨 조회" 이름으로 저장해줘""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
            assertEquals("날씨 조회", result.name)
        }

        @Test
        fun `Korean - 워크플로우 이름 변경`() {
            val result = governor.detectWorkflowCommand("""워크플로우 이름을 "주문 처리"로 바꿔줘""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.SAVE, result!!.command)
            assertEquals("주문 처리", result.name)
        }
    }

    @Nested
    @DisplayName("detectWorkflowCommand - LIST patterns")
    inner class ListPatternTests {

        @Test
        fun `Korean - 워크플로우 목록 보여줘`() {
            val result = governor.detectWorkflowCommand("워크플로우 목록 보여줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LIST, result!!.command)
        }

        @Test
        fun `Korean - 저장된 워크플로우 리스트`() {
            val result = governor.detectWorkflowCommand("저장된 워크플로우 리스트 보여줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LIST, result!!.command)
        }

        @Test
        fun `Korean - 워크플로우 뭐가 있어`() {
            val result = governor.detectWorkflowCommand("워크플로우 뭐가 있어?")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LIST, result!!.command)
        }

        @Test
        fun `English - list workflows`() {
            val result = governor.detectWorkflowCommand("list workflows")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LIST, result!!.command)
        }

        @Test
        fun `English - show the workflows`() {
            val result = governor.detectWorkflowCommand("show the workflows")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LIST, result!!.command)
        }
    }

    @Nested
    @DisplayName("detectWorkflowCommand - LOAD patterns")
    inner class LoadPatternTests {

        @Test
        fun `Korean - 워크플로우 불러와줘`() {
            val result = governor.detectWorkflowCommand("""워크플로우 "날씨 조회" 불러와줘""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LOAD, result!!.command)
            assertEquals("날씨 조회", result.name)
        }

        @Test
        fun `Korean - 워크플로우 로드`() {
            val result = governor.detectWorkflowCommand("워크플로우 로드해줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LOAD, result!!.command)
        }

        @Test
        fun `Korean - 워크플로우 실행`() {
            val result = governor.detectWorkflowCommand("""워크플로우 "주문 처리" 실행해줘""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LOAD, result!!.command)
            assertEquals("주문 처리", result.name)
        }

        @Test
        fun `English - load workflow`() {
            val result = governor.detectWorkflowCommand("""load workflow "order-process"""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LOAD, result!!.command)
            assertEquals("order-process", result.name)
        }

        @Test
        fun `English - run workflow`() {
            val result = governor.detectWorkflowCommand("run workflow")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.LOAD, result!!.command)
        }
    }

    @Nested
    @DisplayName("detectWorkflowCommand - DELETE patterns")
    inner class DeletePatternTests {

        @Test
        fun `Korean - 워크플로우 삭제`() {
            val result = governor.detectWorkflowCommand("""워크플로우 "날씨 조회" 삭제해줘""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.DELETE, result!!.command)
            assertEquals("날씨 조회", result.name)
        }

        @Test
        fun `Korean - 워크플로우 지워줘`() {
            val result = governor.detectWorkflowCommand("워크플로우 지워줘")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.DELETE, result!!.command)
        }

        @Test
        fun `English - delete workflow`() {
            val result = governor.detectWorkflowCommand("""delete workflow "test"""")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.DELETE, result!!.command)
            assertEquals("test", result.name)
        }

        @Test
        fun `English - remove workflow`() {
            val result = governor.detectWorkflowCommand("remove workflow")
            assertNotNull(result)
            assertEquals(ConversationalGovernor.WorkflowCommand.DELETE, result!!.command)
        }
    }

    @Nested
    @DisplayName("detectWorkflowCommand - Non-matching patterns")
    inner class NonMatchingTests {

        @Test
        fun `general conversation is not detected`() {
            assertNull(governor.detectWorkflowCommand("안녕하세요"))
        }

        @Test
        fun `file read is not detected`() {
            assertNull(governor.detectWorkflowCommand("/tmp/test.txt 읽어줘"))
        }

        @Test
        fun `API workflow is not detected`() {
            assertNull(governor.detectWorkflowCommand("날씨 API로 조회해줘"))
        }

        @Test
        fun `general workflow mention is not detected`() {
            assertNull(governor.detectWorkflowCommand("워크플로우가 뭐야?"))
        }

        @Test
        fun `empty message is not detected`() {
            assertNull(governor.detectWorkflowCommand(""))
        }
    }

    // === handleWorkflowCommand 통합 테스트 ===

    @Nested
    @DisplayName("handleWorkflowCommand - SAVE")
    inner class SaveHandlerTests {

        @Test
        fun `SAVE with no previous workflow returns error`() = runBlocking {
            val session = governor.startSession()
            val response = governor.chat(session.sessionId, "방금 실행한 워크플로우를 저장해줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("저장할 워크플로우가 없습니다"))
        }

        @Test
        fun `SAVE with existing workflow returns already saved message`() = runBlocking {
            val session = governor.startSession()

            // 워크플로우 저장 시뮬레이션
            val record = WorkflowRecord(
                workflowId = "wf-001",
                name = "weather-check",
                workflowJson = "{}",
                sessionId = session.sessionId,
                userId = "test-user"
            )
            workflowStore.save(record)
            session.context.lastExecutedWorkflowId = "wf-001"

            val response = governor.chat(session.sessionId, "워크플로우 저장해줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("이미 저장되어 있습니다"))
            assertTrue(response.message.contains("weather-check"))
        }

        @Test
        fun `SAVE with name change renames workflow`() = runBlocking {
            val session = governor.startSession()

            val record = WorkflowRecord(
                workflowId = "wf-002",
                name = "auto-generated",
                workflowJson = "{}",
                sessionId = session.sessionId,
                userId = "test-user"
            )
            workflowStore.save(record)
            session.context.lastExecutedWorkflowId = "wf-002"

            val response = governor.chat(session.sessionId, """워크플로우를 "날씨 확인"으로 저장해줘""")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("날씨 확인"))

            // Store에서 업데이트 확인
            val updated = workflowStore.findById("wf-002")
            assertNotNull(updated)
            assertEquals("날씨 확인", updated!!.name)
        }
    }

    @Nested
    @DisplayName("handleWorkflowCommand - LIST")
    inner class ListHandlerTests {

        @Test
        fun `LIST with no workflows returns empty message`() = runBlocking {
            val session = governor.startSession()
            val response = governor.chat(session.sessionId, "워크플로우 목록 보여줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("저장된 워크플로우가 없습니다"))
        }

        @Test
        fun `LIST with workflows returns formatted list`() = runBlocking {
            val session = governor.startSession()

            workflowStore.save(WorkflowRecord(
                workflowId = "wf-a", name = "Weather Check",
                description = "날씨 조회", workflowJson = "{}"
            ))
            workflowStore.save(WorkflowRecord(
                workflowId = "wf-b", name = "Order Process",
                description = "주문 처리", workflowJson = "{}"
            ))

            val response = governor.chat(session.sessionId, "워크플로우 목록 보여줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("Weather Check"))
            assertTrue(response.message.contains("Order Process"))
        }
    }

    @Nested
    @DisplayName("handleWorkflowCommand - DELETE")
    inner class DeleteHandlerTests {

        @Test
        fun `DELETE without name asks for name`() = runBlocking {
            val session = governor.startSession()
            val response = governor.chat(session.sessionId, "워크플로우 삭제해줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("삭제할 워크플로우 이름을 지정"))
        }

        @Test
        fun `DELETE with unknown name returns not found`() = runBlocking {
            val session = governor.startSession()
            val response = governor.chat(session.sessionId, """워크플로우 "없는거" 삭제해줘""")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("찾을 수 없습니다"))
        }

        @Test
        fun `DELETE with valid name removes workflow`() = runBlocking {
            val session = governor.startSession()

            workflowStore.save(WorkflowRecord(
                workflowId = "wf-del", name = "to-delete",
                workflowJson = "{}"
            ))

            val response = governor.chat(session.sessionId, """워크플로우 "to-delete" 삭제해줘""")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("삭제되었습니다"))
            assertNull(workflowStore.findById("wf-del"))
        }
    }

    @Nested
    @DisplayName("handleWorkflowCommand - LOAD")
    inner class LoadHandlerTests {

        @Test
        fun `LOAD without name shows available workflows`() = runBlocking {
            val session = governor.startSession()

            workflowStore.save(WorkflowRecord(
                workflowId = "wf-1", name = "Test Flow",
                workflowJson = "{}"
            ))

            val response = governor.chat(session.sessionId, "워크플로우 로드해줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("어떤 워크플로우를 로드할까요"))
            assertTrue(response.message.contains("Test Flow"))
        }

        @Test
        fun `LOAD with unknown name returns not found`() = runBlocking {
            val session = governor.startSession()
            val response = governor.chat(session.sessionId, """워크플로우 "없는거" 불러와줘""")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("찾을 수 없습니다"))
        }
    }

    @Nested
    @DisplayName("lastExecutedWorkflowId lifecycle")
    inner class LastExecutedWorkflowIdTests {

        @Test
        fun `SessionContext starts with null lastExecutedWorkflowId`() {
            val ctx = SessionContext()
            assertNull(ctx.lastExecutedWorkflowId)
        }

        @Test
        fun `clear resets lastExecutedWorkflowId`() {
            val ctx = SessionContext()
            ctx.lastExecutedWorkflowId = "wf-123"
            ctx.clear()
            assertNull(ctx.lastExecutedWorkflowId)
        }

        @Test
        fun `lastExecutedWorkflowId is set on session context`() {
            val ctx = SessionContext()
            ctx.lastExecutedWorkflowId = "wf-abc"
            assertEquals("wf-abc", ctx.lastExecutedWorkflowId)
        }
    }

    @Nested
    @DisplayName("No WorkflowStore configured")
    inner class NoStoreTests {

        @Test
        fun `workflow command without store returns error`() = runBlocking {
            val noStoreGovernor = ConversationalGovernor.create(
                dacs = SimpleDACS.DEFAULT,
                llmProvider = null,
                workflowStore = null
            )
            val session = noStoreGovernor.startSession()
            val response = noStoreGovernor.chat(session.sessionId, "워크플로우 목록 보여줘")

            assertEquals(ActionType.REPLY, response.action)
            assertTrue(response.message.contains("저장소가 설정되지 않았습니다"))
        }
    }
}

/**
 * 테스트용 인메모리 WorkflowStore 구현
 */
class InMemoryWorkflowStore : WorkflowStore {
    private val store = mutableMapOf<String, WorkflowRecord>()

    override fun save(record: WorkflowRecord) {
        store[record.workflowId] = record
    }

    override fun findById(workflowId: String): WorkflowRecord? {
        return store[workflowId]
    }

    override fun findByName(name: String, projectId: Long?): WorkflowRecord? {
        return store.values.find { it.name == name && (projectId == null || it.projectId == projectId) }
    }

    override fun listByProject(projectId: Long?, limit: Int): List<WorkflowRecord> {
        return store.values
            .filter { projectId == null || it.projectId == projectId }
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    override fun findBySession(sessionId: String): WorkflowRecord? {
        return store.values.find { it.sessionId == sessionId }
    }

    override fun delete(workflowId: String): Boolean {
        return store.remove(workflowId) != null
    }
}
