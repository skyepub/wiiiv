package io.wiiiv.governor

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * SessionContext 단위 테스트
 *
 * 세션 컨텍스트의 실행 히스토리 관리, 아티팩트 저장,
 * resetSpec/resetAll 분리 동작을 검증한다.
 */
class SessionContextTest {

    @Nested
    @DisplayName("SessionContext")
    inner class SessionContextTests {

        @Test
        fun `new SessionContext is empty`() {
            val ctx = SessionContext()

            assertTrue(ctx.executionHistory.isEmpty())
            assertTrue(ctx.artifacts.isEmpty())
            assertTrue(ctx.facts.isEmpty())
            assertNull(ctx.pendingAction)
        }

        @Test
        fun `clear resets all fields`() {
            val ctx = SessionContext()
            ctx.executionHistory.add(TurnExecution(1, null, null, "test"))
            ctx.artifacts["key"] = "value"
            ctx.facts["fact"] = "true"
            ctx.pendingAction = PendingAction.ContinueExecution("reason")

            ctx.clear()

            assertTrue(ctx.executionHistory.isEmpty())
            assertTrue(ctx.artifacts.isEmpty())
            assertTrue(ctx.facts.isEmpty())
            assertNull(ctx.pendingAction)
        }

        @Test
        fun `executionHistory accumulates TurnExecutions`() {
            val ctx = SessionContext()

            ctx.executionHistory.add(TurnExecution(1, null, null, "first"))
            ctx.executionHistory.add(TurnExecution(2, null, null, "second"))

            assertEquals(2, ctx.executionHistory.size)
            assertEquals("first", ctx.executionHistory[0].summary)
            assertEquals("second", ctx.executionHistory[1].summary)
        }
    }

    @Nested
    @DisplayName("TurnExecution")
    inner class TurnExecutionTests {

        @Test
        fun `TurnExecution stores turn data`() {
            val turn = TurnExecution(
                turnIndex = 1,
                blueprint = null,
                result = null,
                summary = "Executed GET /api/users"
            )

            assertEquals(1, turn.turnIndex)
            assertNull(turn.blueprint)
            assertNull(turn.result)
            assertEquals("Executed GET /api/users", turn.summary)
            assertTrue(turn.timestamp > 0)
        }
    }

    @Nested
    @DisplayName("PendingAction")
    inner class PendingActionTests {

        @Test
        fun `ContinueExecution stores reasoning`() {
            val action = PendingAction.ContinueExecution("need more API calls")

            assertEquals("need more API calls", action.reasoning)
            assertNull(action.ragContext)
        }

        @Test
        fun `ContinueExecution with ragContext`() {
            val action = PendingAction.ContinueExecution("reason", "API spec context")

            assertEquals("reason", action.reasoning)
            assertEquals("API spec context", action.ragContext)
        }

        @Test
        fun `NeedsConfirmation stores description and blueprint`() {
            val action = PendingAction.NeedsConfirmation(
                description = "Delete all files",
                blueprint = io.wiiiv.blueprint.Blueprint(
                    id = "bp-test",
                    version = "1.0",
                    specSnapshot = io.wiiiv.blueprint.SpecSnapshot(
                        specId = "spec-1",
                        specVersion = "1.0",
                        snapshotAt = "now",
                        governorId = "gov-1",
                        dacsResult = "YES"
                    ),
                    steps = emptyList(),
                    metadata = io.wiiiv.blueprint.BlueprintMetadata(
                        createdAt = "now",
                        createdBy = "gov-1",
                        description = "test",
                        tags = emptyList()
                    )
                )
            )

            assertEquals("Delete all files", action.description)
            assertEquals("bp-test", action.blueprint.id)
        }
    }

    @Nested
    @DisplayName("NextAction")
    inner class NextActionTests {

        @Test
        fun `NextAction enum values`() {
            assertEquals(3, NextAction.entries.size)
            assertNotNull(NextAction.CONTINUE_EXECUTION)
            assertNotNull(NextAction.AWAIT_USER)
            assertNotNull(NextAction.NEEDS_CONFIRMATION)
        }
    }

    @Nested
    @DisplayName("ConversationSession Integration")
    inner class SessionIntegrationTests {

        @Test
        fun `integrateResult preserves across resetSpec`() {
            val session = ConversationSession()

            session.integrateResult(null, null, "first execution")
            session.draftSpec = DraftSpec(intent = "test", taskType = TaskType.COMMAND)

            session.resetSpec()

            // spec cleared
            assertNull(session.draftSpec.intent)
            // context preserved
            assertEquals(1, session.context.executionHistory.size)
            assertEquals("first execution", session.context.executionHistory[0].summary)
        }

        @Test
        fun `integrateResult cleared by resetAll`() {
            val session = ConversationSession()

            session.integrateResult(null, null, "first execution")
            session.context.artifacts["key"] = "value"

            session.resetAll()

            assertTrue(session.context.executionHistory.isEmpty())
            assertTrue(session.context.artifacts.isEmpty())
        }

        @Test
        fun `multiple integrateResult calls increment turnIndex`() {
            val session = ConversationSession()

            session.integrateResult(null, null, "turn 1")
            session.integrateResult(null, null, "turn 2")
            session.integrateResult(null, null, "turn 3")

            assertEquals(3, session.context.executionHistory.size)
            assertEquals(1, session.context.executionHistory[0].turnIndex)
            assertEquals(2, session.context.executionHistory[1].turnIndex)
            assertEquals(3, session.context.executionHistory[2].turnIndex)
        }

        @Test
        fun `context persists across multiple spec resets`() {
            val session = ConversationSession()

            // First task
            session.draftSpec = DraftSpec(intent = "task 1", taskType = TaskType.FILE_READ)
            session.integrateResult(null, null, "task 1 result")
            session.resetSpec()

            // Second task
            session.draftSpec = DraftSpec(intent = "task 2", taskType = TaskType.COMMAND)
            session.integrateResult(null, null, "task 2 result")
            session.resetSpec()

            // Context has both results
            assertEquals(2, session.context.executionHistory.size)
            assertEquals("task 1 result", session.context.executionHistory[0].summary)
            assertEquals("task 2 result", session.context.executionHistory[1].summary)
        }
    }

    // ===== Phase 6: Multi-Task Tests =====

    @Nested
    @DisplayName("TaskSlot")
    inner class TaskSlotTests {

        @Test
        fun `TaskSlot creation with defaults`() {
            val task = TaskSlot(id = "task-1", label = "파일 읽기")

            assertEquals("task-1", task.id)
            assertEquals("파일 읽기", task.label)
            assertNull(task.draftSpec.taskType)
            assertTrue(task.context.executionHistory.isEmpty())
            assertEquals(TaskStatus.ACTIVE, task.status)
            assertTrue(task.createdAt > 0)
        }

        @Test
        fun `TaskSlot with DraftSpec`() {
            val spec = DraftSpec(intent = "test", taskType = TaskType.FILE_READ, targetPath = "/tmp/a.txt")
            val task = TaskSlot(id = "task-2", label = "test task", draftSpec = spec)

            assertEquals("test", task.draftSpec.intent)
            assertEquals(TaskType.FILE_READ, task.draftSpec.taskType)
        }

        @Test
        fun `TaskContext clear removes all data`() {
            val ctx = TaskContext()
            ctx.executionHistory.add(TurnExecution(1, null, null, "test"))
            ctx.artifacts["key"] = "value"
            ctx.facts["fact"] = "true"

            ctx.clear()

            assertTrue(ctx.executionHistory.isEmpty())
            assertTrue(ctx.artifacts.isEmpty())
            assertTrue(ctx.facts.isEmpty())
        }

        @Test
        fun `TaskStatus values`() {
            assertEquals(3, TaskStatus.entries.size)
            assertNotNull(TaskStatus.ACTIVE)
            assertNotNull(TaskStatus.SUSPENDED)
            assertNotNull(TaskStatus.COMPLETED)
        }
    }

    @Nested
    @DisplayName("SessionContext Proxy (Phase 6)")
    inner class SessionContextProxyTests {

        @Test
        fun `executionHistory proxies to fallback when no activeTask`() {
            val ctx = SessionContext()

            ctx.executionHistory.add(TurnExecution(1, null, null, "fallback entry"))

            assertNull(ctx.activeTask)
            assertEquals(1, ctx.executionHistory.size)
            assertEquals("fallback entry", ctx.executionHistory[0].summary)
        }

        @Test
        fun `executionHistory proxies to activeTask when present`() {
            val ctx = SessionContext()
            val task = TaskSlot(id = "task-1", label = "test")
            task.context.executionHistory.add(TurnExecution(1, null, null, "task entry"))
            ctx.tasks["task-1"] = task
            ctx.activeTaskId = "task-1"

            assertEquals(1, ctx.executionHistory.size)
            assertEquals("task entry", ctx.executionHistory[0].summary)
        }

        @Test
        fun `promoteToTask migrates fallback history`() {
            val ctx = SessionContext()

            // Add to fallback
            ctx.executionHistory.add(TurnExecution(1, null, null, "before task"))
            assertEquals(1, ctx.executionHistory.size)

            // Promote to task
            val task = TaskSlot(id = "task-1", label = "promoted")
            ctx.promoteToTask(task)

            // History migrated to task
            assertEquals("task-1", ctx.activeTaskId)
            assertNotNull(ctx.activeTask)
            assertEquals(1, ctx.executionHistory.size)
            assertEquals("before task", ctx.executionHistory[0].summary)
            assertEquals(1, task.context.executionHistory.size)
        }

        @Test
        fun `clear resets tasks and fallback`() {
            val ctx = SessionContext()
            val task = TaskSlot(id = "task-1", label = "test")
            ctx.promoteToTask(task)
            ctx.executionHistory.add(TurnExecution(1, null, null, "in task"))
            ctx.artifacts["key"] = "value"

            ctx.clear()

            assertTrue(ctx.tasks.isEmpty())
            assertNull(ctx.activeTaskId)
            assertNull(ctx.activeTask)
            assertTrue(ctx.executionHistory.isEmpty())
            assertTrue(ctx.artifacts.isEmpty())
        }
    }

    @Nested
    @DisplayName("ConversationSession draftSpec Proxy (Phase 6)")
    inner class DraftSpecProxyTests {

        @Test
        fun `draftSpec uses fallback when no activeTask`() {
            val session = ConversationSession()

            session.draftSpec = DraftSpec(intent = "fallback intent", taskType = TaskType.FILE_READ)

            assertNull(session.context.activeTask)
            assertEquals("fallback intent", session.draftSpec.intent)
            assertEquals(TaskType.FILE_READ, session.draftSpec.taskType)
        }

        @Test
        fun `draftSpec delegates to activeTask when present`() {
            val session = ConversationSession()

            // Set fallback first
            session.draftSpec = DraftSpec(intent = "fallback", taskType = TaskType.COMMAND)

            // Create active task (promotes fallback)
            session.ensureActiveTask("test task")

            // Now draftSpec reads from activeTask
            assertEquals("fallback", session.draftSpec.intent)
            assertEquals(TaskType.COMMAND, session.draftSpec.taskType)

            // Write goes to activeTask
            session.draftSpec = DraftSpec(intent = "updated", taskType = TaskType.FILE_WRITE)
            assertEquals("updated", session.context.activeTask!!.draftSpec.intent)
        }

        @Test
        fun `ensureActiveTask promotes fallback draftSpec`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "original intent", taskType = TaskType.FILE_READ)

            val task = session.ensureActiveTask()

            assertEquals("original intent", task.draftSpec.intent)
            assertEquals("original intent", task.label)
            assertEquals(TaskStatus.ACTIVE, task.status)
            assertNotNull(session.context.activeTask)
            assertEquals(task.id, session.context.activeTaskId)
        }

        @Test
        fun `ensureActiveTask returns existing if already active`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "test", taskType = TaskType.COMMAND)

            val task1 = session.ensureActiveTask()
            val task2 = session.ensureActiveTask()

            assertSame(task1, task2)
            assertEquals(1, session.context.tasks.size)
        }

        @Test
        fun `ensureActiveTask migrates fallback executionHistory`() {
            val session = ConversationSession()
            session.integrateResult(null, null, "before task creation")

            assertEquals(1, session.context.executionHistory.size)

            val task = session.ensureActiveTask("migrated task")

            // History migrated to task
            assertEquals(1, task.context.executionHistory.size)
            assertEquals("before task creation", task.context.executionHistory[0].summary)
            // Proxy still works
            assertEquals(1, session.context.executionHistory.size)
        }
    }

    @Nested
    @DisplayName("Multi-Task Scenario (Phase 6)")
    inner class MultiTaskTests {

        @Test
        fun `create two tasks via ensureActiveTask`() {
            val session = ConversationSession()

            // First task
            session.draftSpec = DraftSpec(intent = "task A", taskType = TaskType.FILE_READ)
            val taskA = session.ensureActiveTask()
            session.integrateResult(null, null, "A result")

            // Suspend first task, create second
            taskA.status = TaskStatus.SUSPENDED
            session.context.activeTaskId = null

            session.draftSpec = DraftSpec(intent = "task B", taskType = TaskType.COMMAND)
            val taskB = session.ensureActiveTask()
            session.integrateResult(null, null, "B result")

            // Verify two separate tasks
            assertEquals(2, session.context.tasks.size)
            assertEquals(1, taskA.context.executionHistory.size)
            assertEquals("A result", taskA.context.executionHistory[0].summary)
            assertEquals(1, taskB.context.executionHistory.size)
            assertEquals("B result", taskB.context.executionHistory[0].summary)
        }

        @Test
        fun `switch between tasks preserves each tasks state`() {
            val session = ConversationSession()

            // Task A: file read
            session.draftSpec = DraftSpec(intent = "read file", taskType = TaskType.FILE_READ, targetPath = "/tmp/a.txt")
            val taskA = session.ensureActiveTask()
            session.integrateResult(null, null, "read /tmp/a.txt")

            // Suspend A, create B
            taskA.status = TaskStatus.SUSPENDED
            session.context.activeTaskId = null

            session.draftSpec = DraftSpec(intent = "run command", taskType = TaskType.COMMAND, content = "ls")
            val taskB = session.ensureActiveTask()
            session.integrateResult(null, null, "executed ls")

            // Switch back to A
            taskB.status = TaskStatus.SUSPENDED
            taskA.status = TaskStatus.ACTIVE
            session.context.activeTaskId = taskA.id

            // Verify A's context is restored
            assertEquals(taskA.id, session.context.activeTaskId)
            assertEquals(1, session.context.executionHistory.size)
            assertEquals("read /tmp/a.txt", session.context.executionHistory[0].summary)
            assertEquals("read file", session.draftSpec.intent)
        }

        @Test
        fun `resetAll clears all tasks`() {
            val session = ConversationSession()

            session.draftSpec = DraftSpec(intent = "task 1", taskType = TaskType.FILE_READ)
            session.ensureActiveTask()
            session.integrateResult(null, null, "result 1")

            session.resetAll()

            assertTrue(session.context.tasks.isEmpty())
            assertNull(session.context.activeTaskId)
            assertNull(session.context.activeTask)
            assertTrue(session.context.executionHistory.isEmpty())
            assertNull(session.draftSpec.intent)
        }

        @Test
        fun `resetSpec on activeTask preserves task context`() {
            val session = ConversationSession()

            session.draftSpec = DraftSpec(intent = "test", taskType = TaskType.COMMAND, content = "echo hi")
            val task = session.ensureActiveTask()
            session.integrateResult(null, null, "executed echo hi")

            session.resetSpec()

            // draftSpec reset but task and history preserved
            assertNull(session.draftSpec.intent)
            assertNull(task.draftSpec.intent)
            assertEquals(1, session.context.executionHistory.size)
            assertEquals(1, session.context.tasks.size)
        }
    }

    @Nested
    @DisplayName("COMPLETED Transition (Phase 6 v1 보완)")
    inner class CompletedTransitionTests {

        @Test
        fun `COMPLETED task remains in tasks map`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "read file", taskType = TaskType.FILE_READ)
            val task = session.ensureActiveTask()

            // Simulate execution completion
            session.integrateResult(null, null, "file read done")
            session.resetSpec()
            task.status = TaskStatus.COMPLETED
            session.context.activeTaskId = null

            // Task is in map but not active
            assertEquals(1, session.context.tasks.size)
            assertEquals(TaskStatus.COMPLETED, task.status)
            assertNull(session.context.activeTaskId)
            assertNull(session.context.activeTask)
        }

        @Test
        fun `after COMPLETED new task gets fresh context`() {
            val session = ConversationSession()

            // First task: complete it
            session.draftSpec = DraftSpec(intent = "task A", taskType = TaskType.COMMAND)
            val taskA = session.ensureActiveTask()
            session.integrateResult(null, null, "A done")
            session.resetSpec()
            taskA.status = TaskStatus.COMPLETED
            session.context.activeTaskId = null

            // Second task: starts fresh
            session.draftSpec = DraftSpec(intent = "task B", taskType = TaskType.FILE_READ)
            val taskB = session.ensureActiveTask()

            // taskB has fresh context, not A's history
            assertEquals(0, taskB.context.executionHistory.size)
            assertEquals(0, session.context.executionHistory.size)
            assertNotEquals(taskA.id, taskB.id)
            assertEquals(2, session.context.tasks.size)
        }

        @Test
        fun `COMPLETED task history is preserved independently`() {
            val session = ConversationSession()

            // Task A: execute and complete
            session.draftSpec = DraftSpec(intent = "task A", taskType = TaskType.COMMAND)
            val taskA = session.ensureActiveTask()
            session.integrateResult(null, null, "A result 1")
            session.integrateResult(null, null, "A result 2")
            taskA.status = TaskStatus.COMPLETED
            session.context.activeTaskId = null

            // Task B: new task
            session.draftSpec = DraftSpec(intent = "task B", taskType = TaskType.FILE_READ)
            val taskB = session.ensureActiveTask()
            session.integrateResult(null, null, "B result")

            // Both tasks have their own history
            assertEquals(2, taskA.context.executionHistory.size)
            assertEquals("A result 1", taskA.context.executionHistory[0].summary)
            assertEquals("A result 2", taskA.context.executionHistory[1].summary)
            assertEquals(1, taskB.context.executionHistory.size)
            assertEquals("B result", taskB.context.executionHistory[0].summary)
        }

        @Test
        fun `suspendCurrentWork saves activeTask as SUSPENDED`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "서버 설정", taskType = TaskType.COMMAND)
            val task = session.ensureActiveTask()
            session.integrateResult(null, null, "executed something")

            val suspended = session.suspendCurrentWork()

            assertNotNull(suspended)
            assertEquals(task.id, suspended!!.id)
            assertEquals(TaskStatus.SUSPENDED, suspended.status)
            assertNull(session.context.activeTaskId)
            assertNull(session.context.activeTask)
            // Task still in map
            assertEquals(1, session.context.tasks.size)
            assertEquals(1, suspended.context.executionHistory.size)
        }

        @Test
        fun `suspendCurrentWork promotes and saves fallback DraftSpec`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "파일 읽기", taskType = TaskType.FILE_READ, targetPath = "/tmp/a.txt")
            // No ensureActiveTask called — DraftSpec is in fallback

            val suspended = session.suspendCurrentWork()

            assertNotNull(suspended)
            assertEquals(TaskStatus.SUSPENDED, suspended!!.status)
            assertEquals("파일 읽기", suspended.label)
            assertEquals("파일 읽기", suspended.draftSpec.intent)
            assertEquals(TaskType.FILE_READ, suspended.draftSpec.taskType)
            assertEquals("/tmp/a.txt", suspended.draftSpec.targetPath)
            assertNull(session.context.activeTaskId)
            assertEquals(1, session.context.tasks.size)
        }

        @Test
        fun `suspendCurrentWork returns null when nothing to save`() {
            val session = ConversationSession()
            // Empty session — no intent, no active task

            val suspended = session.suspendCurrentWork()

            assertNull(suspended)
            assertEquals(0, session.context.tasks.size)
        }

        @Test
        fun `suspendCurrentWork then new task preserves suspended task`() {
            val session = ConversationSession()

            // Task A
            session.draftSpec = DraftSpec(intent = "task A", taskType = TaskType.FILE_READ)
            session.ensureActiveTask()
            session.integrateResult(null, null, "A result")

            // Suspend via suspendCurrentWork
            val suspended = session.suspendCurrentWork()
            assertNotNull(suspended)

            // New task B
            session.draftSpec = DraftSpec(intent = "task B", taskType = TaskType.COMMAND)
            val taskB = session.ensureActiveTask()
            session.integrateResult(null, null, "B result")

            // Both tasks exist
            assertEquals(2, session.context.tasks.size)
            assertEquals(TaskStatus.SUSPENDED, suspended!!.status)
            assertEquals(TaskStatus.ACTIVE, taskB.status)
            assertEquals(1, suspended.context.executionHistory.size)
            assertEquals(1, taskB.context.executionHistory.size)
        }

        @Test
        fun `cancelCurrentTask removes only active task`() {
            val session = ConversationSession()

            // Task A: suspend it
            session.draftSpec = DraftSpec(intent = "task A", taskType = TaskType.FILE_READ)
            val taskA = session.ensureActiveTask()
            session.integrateResult(null, null, "A result")
            taskA.status = TaskStatus.SUSPENDED
            session.context.activeTaskId = null

            // Task B: make it active
            session.draftSpec = DraftSpec(intent = "task B", taskType = TaskType.COMMAND)
            val taskB = session.ensureActiveTask()

            assertEquals(2, session.context.tasks.size)

            // Cancel current (B)
            session.cancelCurrentTask()

            // B removed, A preserved
            assertEquals(1, session.context.tasks.size)
            assertNull(session.context.activeTaskId)
            assertNotNull(session.context.tasks[taskA.id])
            assertNull(session.context.tasks[taskB.id])
            assertEquals(TaskStatus.SUSPENDED, taskA.status)
        }

        @Test
        fun `cancelCurrentTask with no active task clears fallback`() {
            val session = ConversationSession()
            session.draftSpec = DraftSpec(intent = "something", taskType = TaskType.COMMAND)

            session.cancelCurrentTask()

            assertNull(session.draftSpec.intent)
            assertNull(session.draftSpec.taskType)
        }

        @Test
        fun `cancelCurrentTask preserves suspended and completed tasks`() {
            val session = ConversationSession()

            // Task A: completed
            session.draftSpec = DraftSpec(intent = "task A", taskType = TaskType.FILE_READ)
            val taskA = session.ensureActiveTask()
            taskA.status = TaskStatus.COMPLETED
            session.context.activeTaskId = null

            // Task B: suspended
            session.draftSpec = DraftSpec(intent = "task B", taskType = TaskType.COMMAND)
            val taskB = session.ensureActiveTask()
            taskB.status = TaskStatus.SUSPENDED
            session.context.activeTaskId = null

            // Task C: active (cancel this)
            session.draftSpec = DraftSpec(intent = "task C", taskType = TaskType.FILE_WRITE)
            val taskC = session.ensureActiveTask()

            assertEquals(3, session.context.tasks.size)

            session.cancelCurrentTask()

            assertEquals(2, session.context.tasks.size)
            assertEquals(TaskStatus.COMPLETED, taskA.status)
            assertEquals(TaskStatus.SUSPENDED, taskB.status)
            assertNull(session.context.tasks[taskC.id])
        }

        @Test
        fun `draftSpec falls back after COMPLETED`() {
            val session = ConversationSession()

            session.draftSpec = DraftSpec(intent = "original", taskType = TaskType.COMMAND)
            val task = session.ensureActiveTask()
            task.status = TaskStatus.COMPLETED
            session.context.activeTaskId = null

            // draftSpec now reads from fallback (empty)
            assertNull(session.draftSpec.intent)
            assertNull(session.draftSpec.taskType)

            // Setting draftSpec goes to fallback
            session.draftSpec = DraftSpec(intent = "new task", taskType = TaskType.FILE_READ)
            assertEquals("new task", session.draftSpec.intent)
            // Old task unchanged
            assertEquals("original", task.draftSpec.intent)
        }
    }
}
