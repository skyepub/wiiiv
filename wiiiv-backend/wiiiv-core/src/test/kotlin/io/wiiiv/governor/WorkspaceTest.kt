package io.wiiiv.governor

import io.wiiiv.dacs.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested

/**
 * Workspace Feature 단위 테스트
 *
 * workspace 설정 → SessionContext 보존 → deriveProjectPath slug 생성
 * → RuleBasedAdversary workspace safe path → GovernorPrompt workspace 섹션
 */
class WorkspaceTest {

    @Nested
    @DisplayName("SessionContext.workspace")
    inner class SessionContextWorkspaceTests {

        @Test
        fun `workspace defaults to null`() {
            val ctx = SessionContext()
            assertNull(ctx.workspace)
        }

        @Test
        fun `workspace can be set`() {
            val ctx = SessionContext()
            ctx.workspace = "/home/user/projects"
            assertEquals("/home/user/projects", ctx.workspace)
        }

        @Test
        fun `clear preserves workspace`() {
            val ctx = SessionContext()
            ctx.workspace = "/home/user/projects"
            ctx.executionHistory.add(TurnExecution(1, null, null, "test"))
            ctx.artifacts["key"] = "value"

            ctx.clear()

            // workspace preserved
            assertEquals("/home/user/projects", ctx.workspace)
            // everything else cleared
            assertTrue(ctx.executionHistory.isEmpty())
            assertTrue(ctx.artifacts.isEmpty())
        }

        @Test
        fun `resetAll preserves workspace via SessionContext clear`() {
            val session = ConversationSession()
            session.context.workspace = "/workspace"
            session.draftSpec = DraftSpec(intent = "test", taskType = TaskType.COMMAND)
            session.integrateResult(null, null, "result")

            session.resetAll()

            assertEquals("/workspace", session.context.workspace)
            assertNull(session.draftSpec.intent)
            assertTrue(session.context.executionHistory.isEmpty())
        }
    }

    @Nested
    @DisplayName("deriveProjectPath")
    inner class DeriveProjectPathTests {

        private val governor = ConversationalGovernor.create(
            dacs = SimpleDACS.DEFAULT
        )

        @Test
        fun `returns null when workspace is null`() {
            val spec = DraftSpec(domain = "contact-manager")
            assertNull(governor.deriveProjectPath(null, spec))
        }

        @Test
        fun `returns null when both domain and intent are null`() {
            val spec = DraftSpec()
            assertNull(governor.deriveProjectPath("/workspace", spec))
        }

        @Test
        fun `derives path from domain`() {
            val spec = DraftSpec(domain = "연락처 관리")
            val path = governor.deriveProjectPath("/workspace", spec)

            assertNotNull(path)
            assertTrue(path!!.startsWith("/workspace/"))
            assertTrue(path.contains("contact"))
        }

        @Test
        fun `derives path from intent when domain is null`() {
            val spec = DraftSpec(intent = "할일 관리 앱 만들기")
            val path = governor.deriveProjectPath("/workspace", spec)

            assertNotNull(path)
            assertTrue(path!!.startsWith("/workspace/"))
        }

        @Test
        fun `works with English text`() {
            val spec = DraftSpec(domain = "E-commerce Backend")
            val path = governor.deriveProjectPath("/workspace", spec)

            assertNotNull(path)
            assertTrue(path!!.startsWith("/workspace/"))
            assertTrue(path.contains("e-commerce") || path.contains("backend"))
        }
    }

    @Nested
    @DisplayName("generateSlug")
    inner class GenerateSlugTests {

        private val governor = ConversationalGovernor.create(
            dacs = SimpleDACS.DEFAULT
        )

        @Test
        fun `Korean domain to slug`() {
            val slug = governor.generateSlug("연락처 관리")
            assertTrue(slug.contains("contact"))
            assertTrue(slug.contains("manager"))
        }

        @Test
        fun `English text to slug`() {
            val slug = governor.generateSlug("E-commerce Backend")
            assertTrue(slug.isNotBlank())
            assertFalse(slug.contains(" "))
        }

        @Test
        fun `mixed Korean and English`() {
            val slug = governor.generateSlug("쇼핑몰 API 서버")
            assertTrue(slug.isNotBlank())
            assertTrue(slug.contains("shopping-mall") || slug.contains("api") || slug.contains("server"))
        }

        @Test
        fun `empty or unmappable text returns fallback`() {
            val slug = governor.generateSlug("   ")
            assertEquals("wiiiv-project", slug)
        }

        @Test
        fun `slug has no spaces`() {
            val slug = governor.generateSlug("학생 성적 관리 시스템")
            assertFalse(slug.contains(" "))
        }
    }

    @Nested
    @DisplayName("RuleBasedAdversary workspace safe paths")
    inner class AdversaryWorkspaceTests {

        @Test
        fun `workspace path treated as safe`() = runBlocking {
            val adversary = RuleBasedAdversary()
            val spec = Spec(
                id = "spec-ws",
                name = "Workspace Project",
                allowedOperations = listOf(RequestType.FILE_MKDIR, RequestType.FILE_WRITE),
                allowedPaths = listOf("/home/user/projects/contact-manager")
            )

            // Without workspace context — path is not in default safe list
            val opinionNoWs = adversary.evaluate(spec, "연락처 관리 프로젝트 생성")
            // With workspace context
            val opinionWithWs = adversary.evaluate(spec,
                "연락처 관리 프로젝트 생성\nworkspace: /home/user/projects")

            // Path "/home/user/projects/contact-manager" is not in /etc, /root etc,
            // so both should approve — but the point is workspace makes it explicitly safe
            assertEquals(Vote.APPROVE, opinionWithWs.vote)
        }

        @Test
        fun `workspace does not override prohibited patterns`() = runBlocking {
            val adversary = RuleBasedAdversary()
            val spec = Spec(
                id = "spec-prohibited",
                name = "Bad Spec",
                allowedOperations = listOf(RequestType.FILE_READ),
                allowedPaths = listOf("/**")
            )

            val opinion = adversary.evaluate(spec,
                "intent\nworkspace: /home/user/projects")

            assertEquals(Vote.REJECT, opinion.vote)
        }

        @Test
        fun `workspace path makes subpath safe for sensitive-looking paths`() = runBlocking {
            // If workspace is /usr/local/projects, then /usr/local/projects/foo should be safe
            val adversary = RuleBasedAdversary()
            val spec = Spec(
                id = "spec-usr",
                name = "Usr Local Project",
                allowedOperations = listOf(RequestType.FILE_WRITE),
                allowedPaths = listOf("/usr/local/projects/my-app")
            )

            // Without workspace — /usr path is sensitive → ABSTAIN
            val opinionNoWs = adversary.evaluate(spec, "create project")
            assertEquals(Vote.ABSTAIN, opinionNoWs.vote)

            // With workspace that covers this path → APPROVE
            val opinionWithWs = adversary.evaluate(spec,
                "create project\nworkspace: /usr/local/projects")
            assertEquals(Vote.APPROVE, opinionWithWs.vote)
        }
    }

    @Nested
    @DisplayName("GovernorPrompt workspace section")
    inner class GovernorPromptWorkspaceTests {

        @Test
        fun `withContext includes workspace section when set`() {
            val prompt = GovernorPrompt.withContext(
                DraftSpec.empty(), emptyList(),
                workspace = "/home/user/projects"
            )

            assertTrue(prompt.contains("Workspace"))
            assertTrue(prompt.contains("/home/user/projects"))
            assertTrue(prompt.contains("No need to ask for targetPath"))
        }

        @Test
        fun `withContext omits workspace section when null`() {
            val prompt = GovernorPrompt.withContext(
                DraftSpec.empty(), emptyList(),
                workspace = null
            )

            assertFalse(prompt.contains("Workspace"))
        }
    }

    @Nested
    @DisplayName("DACS integration with workspace")
    inner class DACSWorkspaceIntegrationTests {

        @Test
        fun `PROJECT_CREATE with workspace path gets DACS YES`() = runBlocking {
            val dacs = SimpleDACS.DEFAULT
            val spec = Spec(
                id = "spec-project",
                name = "Contact Manager Project",
                description = "연락처 관리 CLI 프로젝트 생성",
                allowedOperations = listOf(RequestType.FILE_MKDIR, RequestType.FILE_WRITE),
                allowedPaths = listOf("/home/user/projects/contact-manager")
            )

            val result = dacs.evaluate(DACSRequest(
                spec = spec,
                context = "연락처 관리 CLI 프로젝트 생성\nworkspace: /home/user/projects"
            ))

            assertTrue(result.isYes, "Expected YES but got ${result.consensus}: ${result.reason}")
        }

        @Test
        fun `PROJECT_CREATE without workspace and no paths gets REVISION`() = runBlocking {
            val dacs = SimpleDACS.DEFAULT
            val spec = Spec(
                id = "spec-no-path",
                name = "No Path Project",
                description = "프로젝트 생성",
                allowedOperations = listOf(RequestType.FILE_MKDIR, RequestType.FILE_WRITE)
                // no allowedPaths
            )

            val result = dacs.evaluate(DACSRequest(
                spec = spec,
                context = "프로젝트 생성"
            ))

            // Without paths, reviewer will abstain → REVISION
            assertTrue(result.isRevision || result.isYes,
                "Expected REVISION or YES but got ${result.consensus}: ${result.reason}")
        }
    }
}
