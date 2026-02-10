package io.wiiiv.governor

import io.wiiiv.blueprint.BlueprintRunner
import io.wiiiv.dacs.AlwaysYesDACS
import io.wiiiv.execution.CompositeExecutor
import io.wiiiv.execution.impl.CommandExecutor
import io.wiiiv.execution.impl.FileExecutor
import io.wiiiv.execution.impl.NoopExecutor
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Workspace Sandbox 실행 테스트
 *
 * 실제 파일 시스템에 프로젝트를 생성하여
 * workspace → deriveProjectPath → Blueprint → FileExecutor → 파일 생성 흐름을 검증한다.
 */
@DisplayName("Workspace Sandbox Execution")
class WorkspaceSandboxTest {

    @Test
    fun `PROJECT_CREATE with workspace generates files in sandbox`(@TempDir tempDir: File) = runBlocking {
        // Given: Real executors + AlwaysYesDACS (LLM 없이)
        val executor = CompositeExecutor(
            executors = listOf(
                FileExecutor(),
                CommandExecutor(),
                NoopExecutor(handleAll = false)
            )
        )
        val blueprintRunner = BlueprintRunner.create(executor)

        val governor = ConversationalGovernor.create(
            dacs = AlwaysYesDACS(),
            llmProvider = null,          // LLM 없음 → fallback project (mkdir + README)
            blueprintRunner = blueprintRunner
        )

        val session = governor.startSession()

        // Set workspace to temp directory
        session.context.workspace = tempDir.absolutePath

        // Pre-populate a complete PROJECT_CREATE DraftSpec
        session.draftSpec = DraftSpec(
            intent = "연락처 관리 CLI 프로젝트 생성",
            taskType = TaskType.PROJECT_CREATE,
            domain = "연락처 관리",
            techStack = listOf("Python")
        )
        session.ensureActiveTask()

        // When: Governor processes EXECUTE (simulated — directly call chat with confirmation)
        // Since no LLM, chat returns "LLM 없음" — so we must trigger executeTurn indirectly.
        // The simplest way: set confirmed and pendingAction to ContinueExecution
        session.confirmed = true
        session.context.pendingAction = PendingAction.ContinueExecution(
            reasoning = "User confirmed PROJECT_CREATE"
        )

        val response = governor.chat(session.sessionId, "진행해줘")

        // Then: Check that files were created under the workspace
        val projectDir = tempDir.listFiles()?.firstOrNull { it.isDirectory }

        println("=== Sandbox Contents ===")
        println("Workspace: ${tempDir.absolutePath}")
        tempDir.walkTopDown().forEach { file ->
            val rel = file.relativeTo(tempDir).path
            val marker = if (file.isDirectory) "[DIR]" else "[FILE ${file.length()}B]"
            println("  $marker $rel")
        }
        println("========================")

        // Verify: project directory was created
        assertNotNull(projectDir, "Project directory should exist under workspace")

        // Verify: README.md was created (fallback without LLM)
        val readme = File(projectDir, "README.md")
        assertTrue(readme.exists(), "README.md should exist in project directory")

        val readmeContent = readme.readText()
        assertTrue(readmeContent.contains("연락처 관리"), "README should mention domain")

        // Verify: response indicates execution
        println("Response action: ${response.action}")
        println("Response message: ${response.message}")
        assertEquals(ActionType.EXECUTE, response.action)
    }

    @Test
    fun `derived path uses correct slug for Korean domain`(@TempDir tempDir: File) = runBlocking {
        val governor = ConversationalGovernor.create(
            dacs = AlwaysYesDACS()
        )

        val draftSpec = DraftSpec(
            domain = "연락처 관리",
            intent = "연락처 관리 CLI"
        )

        val derivedPath = governor.deriveProjectPath(tempDir.absolutePath, draftSpec)
        assertNotNull(derivedPath)

        // Path should end with a meaningful slug
        assertTrue(derivedPath!!.endsWith("contact-manager"),
            "Expected path ending with 'contact-manager' but got: $derivedPath")
    }

    @Test
    fun `real sandbox directory gets project files`() = runBlocking {
        // This test uses the actual ~/projects/sandbox directory
        val sandboxDir = File(System.getProperty("user.home"), "projects/sandbox")
        if (!sandboxDir.exists()) sandboxDir.mkdirs()

        val executor = CompositeExecutor(
            executors = listOf(
                FileExecutor(),
                CommandExecutor(),
                NoopExecutor(handleAll = false)
            )
        )
        val blueprintRunner = BlueprintRunner.create(executor)

        val governor = ConversationalGovernor.create(
            dacs = AlwaysYesDACS(),
            llmProvider = null,
            blueprintRunner = blueprintRunner
        )

        val session = governor.startSession()
        session.context.workspace = sandboxDir.absolutePath

        session.draftSpec = DraftSpec(
            intent = "연락처 관리 CLI 프로젝트 생성",
            taskType = TaskType.PROJECT_CREATE,
            domain = "연락처 관리",
            techStack = listOf("Python"),
            constraints = listOf("파일에 저장")
        )
        session.ensureActiveTask()
        session.confirmed = true
        session.context.pendingAction = PendingAction.ContinueExecution(
            reasoning = "User confirmed"
        )

        val response = governor.chat(session.sessionId, "진행해줘")

        // Print what was created
        println("=== ~/projects/sandbox Contents ===")
        sandboxDir.walkTopDown().forEach { file ->
            val rel = file.relativeTo(sandboxDir).path
            val marker = if (file.isDirectory) "[DIR]" else "[FILE ${file.length()}B]"
            if (rel.isNotBlank()) println("  $marker $rel")
        }
        println("===================================")
        println("Response: ${response.action} - ${response.message.take(200)}")

        // Verify project was created
        val projectDir = File(sandboxDir, "contact-manager")
        assertTrue(projectDir.exists(), "contact-manager directory should exist in sandbox")

        val readme = File(projectDir, "README.md")
        assertTrue(readme.exists(), "README.md should exist")

        println("\n=== README.md ===")
        println(readme.readText())
    }
}
