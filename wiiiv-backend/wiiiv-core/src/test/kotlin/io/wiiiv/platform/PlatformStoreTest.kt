package io.wiiiv.platform

import io.wiiiv.execution.impl.SimpleConnectionProvider
import io.wiiiv.platform.model.ProjectPolicy
import io.wiiiv.platform.model.ProjectRole
import io.wiiiv.platform.store.JdbcPlatformStore
import java.util.UUID
import kotlin.test.*

/**
 * PlatformStoreTest — JdbcPlatformStore 전체 CRUD 단위 테스트
 *
 * H2 인메모리 DB, 외부 의존 제로
 */
class PlatformStoreTest {

    private fun freshStore(): JdbcPlatformStore {
        val db = SimpleConnectionProvider.h2InMemory("ps-${UUID.randomUUID()}")
        return JdbcPlatformStore(db)
    }

    // ════════════════════════════════════════════
    //  1. User CRUD
    // ════════════════════════════════════════════

    @Test
    fun `createUser — persist and return with generated userId`() {
        val store = freshStore()
        val user = store.createUser("alice@test.com", "Alice", "hash123")
        assertTrue(user.userId > 0)
        assertEquals("alice@test.com", user.email)
        assertEquals("Alice", user.displayName)
        assertTrue(user.isActive)
    }

    @Test
    fun `findUserByEmail — case insensitive lookup`() {
        val store = freshStore()
        store.createUser("Alice@Test.COM", "Alice", "hash")
        val found = store.findUserByEmail("alice@test.com")
        assertNotNull(found)
        assertEquals("alice@test.com", found.email)
    }

    @Test
    fun `findUserByEmail — not found returns null`() {
        val store = freshStore()
        assertNull(store.findUserByEmail("nobody@test.com"))
    }

    @Test
    fun `duplicate email — throws exception`() {
        val store = freshStore()
        store.createUser("dup@test.com", "First", "hash")
        assertFailsWith<Exception> {
            store.createUser("dup@test.com", "Second", "hash")
        }
    }

    @Test
    fun `updateUser — change displayName and isActive`() {
        val store = freshStore()
        val user = store.createUser("upd@test.com", "Before", "hash")
        store.updateUser(user.userId, displayName = "After", isActive = false)
        val updated = store.findUserById(user.userId)!!
        assertEquals("After", updated.displayName)
        assertFalse(updated.isActive)
        assertNotNull(updated.updatedAt)
    }

    // ════════════════════════════════════════════
    //  2. Project Lifecycle
    // ════════════════════════════════════════════

    @Test
    fun `createProject — auto-adds owner as OWNER member`() {
        val store = freshStore()
        val user = store.createUser("owner@test.com", "Owner", "hash")
        val project = store.createProject("My Project", "desc", user.userId, "gpt-4o-mini")

        assertTrue(project.projectId > 0)
        val member = store.findMember(project.projectId, user.userId)
        assertNotNull(member)
        assertEquals(ProjectRole.OWNER, member.role)
    }

    @Test
    fun `createProject — auto-creates default policy with null maxRequestsPerDay`() {
        val store = freshStore()
        val user = store.createUser("own@test.com", "Owner", "hash")
        val project = store.createProject("Proj", null, user.userId, "gpt-4o-mini")

        val policy = store.getPolicy(project.projectId)
        assertNotNull(policy)
        assertNull(policy.maxRequestsPerDay)
        assertEquals("[]", policy.allowedStepTypes)
    }

    @Test
    fun `createProject — transaction rollback on failure`() {
        val store = freshStore()
        // Non-existent user → FK violation → rollback
        assertFailsWith<Exception> {
            store.createProject("Bad", null, 999999L, "gpt-4o-mini")
        }
    }

    @Test
    fun `listProjectsForUser — only returns joined projects`() {
        val store = freshStore()
        val alice = store.createUser("alice@test.com", "Alice", "hash")
        val bob = store.createUser("bob@test.com", "Bob", "hash")

        store.createProject("Alice-1", null, alice.userId, "gpt-4o-mini")
        store.createProject("Alice-2", null, alice.userId, "gpt-4o-mini")
        store.createProject("Bob-1", null, bob.userId, "gpt-4o-mini")

        val aliceProjects = store.listProjectsForUser(alice.userId)
        assertEquals(2, aliceProjects.size)
        assertTrue(aliceProjects.all { it.ownerUserId == alice.userId })
    }

    @Test
    fun `updateProject — modifies name and description and defaultModel`() {
        val store = freshStore()
        val user = store.createUser("u@test.com", "U", "hash")
        val project = store.createProject("Old", "Old desc", user.userId, "gpt-4o-mini")

        store.updateProject(project.projectId, name = "New", description = "New desc", defaultModel = "gpt-4o")
        val updated = store.findProjectById(project.projectId)!!
        assertEquals("New", updated.name)
        assertEquals("New desc", updated.description)
        assertEquals("gpt-4o", updated.defaultModel)
    }

    @Test
    fun `deleteProject — only original owner can delete`() {
        val store = freshStore()
        val owner = store.createUser("owner@test.com", "Owner", "hash")
        val other = store.createUser("other@test.com", "Other", "hash")
        val project = store.createProject("Proj", null, owner.userId, "gpt-4o-mini")
        store.addMember(project.projectId, other.userId, ProjectRole.MEMBER)

        // non-owner cannot delete
        assertFalse(store.deleteProject(project.projectId, other.userId))
        // owner can delete
        assertTrue(store.deleteProject(project.projectId, owner.userId))
        assertNull(store.findProjectById(project.projectId))
    }

    // ════════════════════════════════════════════
    //  3. Membership
    // ════════════════════════════════════════════

    @Test
    fun `addMember — creates membership with correct role`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")
        val bob = store.createUser("bob@test.com", "Bob", "hash")

        val member = store.addMember(project.projectId, bob.userId, ProjectRole.MEMBER)
        assertEquals(ProjectRole.MEMBER, member.role)
        assertEquals(bob.userId, member.userId)
    }

    @Test
    fun `removeMember — succeeds for non-owner`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")
        val bob = store.createUser("bob@test.com", "Bob", "hash")
        store.addMember(project.projectId, bob.userId, ProjectRole.MEMBER)

        assertTrue(store.removeMember(project.projectId, bob.userId))
        assertNull(store.findMember(project.projectId, bob.userId))
    }

    @Test
    fun `removeMember — fails for original project owner`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        assertFalse(store.removeMember(project.projectId, owner.userId))
        assertNotNull(store.findMember(project.projectId, owner.userId))
    }

    @Test
    fun `updateMemberRole — changes MEMBER to VIEWER`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")
        val bob = store.createUser("bob@test.com", "Bob", "hash")
        store.addMember(project.projectId, bob.userId, ProjectRole.MEMBER)

        assertTrue(store.updateMemberRole(project.projectId, bob.userId, ProjectRole.VIEWER))
        val updated = store.findMember(project.projectId, bob.userId)!!
        assertEquals(ProjectRole.VIEWER, updated.role)
    }

    @Test
    fun `listMembers — returns all members ordered by joinedAt`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")
        val bob = store.createUser("bob@test.com", "Bob", "hash")
        val carol = store.createUser("carol@test.com", "Carol", "hash")
        store.addMember(project.projectId, bob.userId, ProjectRole.MEMBER)
        store.addMember(project.projectId, carol.userId, ProjectRole.VIEWER)

        val members = store.listMembers(project.projectId)
        assertEquals(3, members.size) // owner + bob + carol
    }

    // ════════════════════════════════════════════
    //  4. API Key
    // ════════════════════════════════════════════

    @Test
    fun `createApiKey — returns wiiiv_ prefixed raw key`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val (rawKey, record) = store.createApiKey(owner.userId, project.projectId, "test-key", null)
        assertTrue(rawKey.startsWith("wiiiv_"))
        assertTrue(record.keyId > 0)
        assertEquals("test-key", record.label)
    }

    @Test
    fun `createApiKey — SHA-256 hash stored, raw key not stored`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val (rawKey, record) = store.createApiKey(owner.userId, project.projectId, null, null)
        val expectedHash = JdbcPlatformStore.sha256Hex(rawKey)
        assertEquals(expectedHash, record.apiKeyHash)
        assertNotEquals(rawKey, record.apiKeyHash)
    }

    @Test
    fun `findByApiKeyHash — matches correctly`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val (rawKey, _) = store.createApiKey(owner.userId, project.projectId, null, null)
        val hash = JdbcPlatformStore.sha256Hex(rawKey)
        val found = store.findByApiKeyHash(hash)
        assertNotNull(found)
        assertEquals(owner.userId, found.userId)
        assertEquals(project.projectId, found.projectId)
    }

    @Test
    fun `revokeApiKey — makes key unfindable`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val (rawKey, record) = store.createApiKey(owner.userId, project.projectId, null, null)
        store.revokeApiKey(record.keyId)

        val hash = JdbcPlatformStore.sha256Hex(rawKey)
        assertNull(store.findByApiKeyHash(hash))
    }

    @Test
    fun `createApiKey — non-member throws exception`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val outsider = store.createUser("out@test.com", "Out", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        assertFailsWith<IllegalArgumentException> {
            store.createApiKey(outsider.userId, project.projectId, null, null)
        }
    }

    // ════════════════════════════════════════════
    //  5. Policy
    // ════════════════════════════════════════════

    @Test
    fun `getPolicy — new project returns default with null maxRequests`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val policy = store.getPolicy(project.projectId)
        assertNotNull(policy)
        assertNull(policy.maxRequestsPerDay)
    }

    @Test
    fun `upsertPolicy — updates maxRequestsPerDay`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val policy = store.getPolicy(project.projectId)!!
        store.upsertPolicy(policy.copy(maxRequestsPerDay = 100))

        val updated = store.getPolicy(project.projectId)!!
        assertEquals(100, updated.maxRequestsPerDay)
    }

    @Test
    fun `upsertPolicy — preserves other fields when updating one`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")

        val policy = store.getPolicy(project.projectId)!!
        store.upsertPolicy(policy.copy(
            allowedStepTypes = "[\"FILE\"]",
            maxRequestsPerDay = 50
        ))
        // Now update only maxRequestsPerDay
        val current = store.getPolicy(project.projectId)!!
        store.upsertPolicy(current.copy(maxRequestsPerDay = 200))

        val final = store.getPolicy(project.projectId)!!
        assertEquals(200, final.maxRequestsPerDay)
        assertEquals("[\"FILE\"]", final.allowedStepTypes)
    }

    @Test
    fun `getPolicy — returns null for non-existent project`() {
        val store = freshStore()
        assertNull(store.getPolicy(999999L))
    }

    // ════════════════════════════════════════════
    //  6. Multi-User Isolation
    // ════════════════════════════════════════════

    @Test
    fun `user A sees only own projects, user B sees only own`() {
        val store = freshStore()
        val alice = store.createUser("alice@test.com", "Alice", "hash")
        val bob = store.createUser("bob@test.com", "Bob", "hash")

        store.createProject("A-1", null, alice.userId, "gpt-4o-mini")
        store.createProject("B-1", null, bob.userId, "gpt-4o-mini")
        store.createProject("B-2", null, bob.userId, "gpt-4o-mini")

        assertEquals(1, store.listProjectsForUser(alice.userId).size)
        assertEquals(2, store.listProjectsForUser(bob.userId).size)
    }

    @Test
    fun `isMember — returns false for non-member`() {
        val store = freshStore()
        val alice = store.createUser("alice@test.com", "Alice", "hash")
        val bob = store.createUser("bob@test.com", "Bob", "hash")
        val project = store.createProject("A", null, alice.userId, "gpt-4o-mini")

        assertTrue(store.isMember(project.projectId, alice.userId))
        assertFalse(store.isMember(project.projectId, bob.userId))
    }

    @Test
    fun `member of project A is not member of project B`() {
        val store = freshStore()
        val alice = store.createUser("alice@test.com", "Alice", "hash")
        val bob = store.createUser("bob@test.com", "Bob", "hash")

        val projA = store.createProject("A", null, alice.userId, "gpt-4o-mini")
        val projB = store.createProject("B", null, bob.userId, "gpt-4o-mini")

        assertTrue(store.isMember(projA.projectId, alice.userId))
        assertFalse(store.isMember(projB.projectId, alice.userId))
        assertFalse(store.isMember(projA.projectId, bob.userId))
        assertTrue(store.isMember(projB.projectId, bob.userId))
    }

    @Test
    fun `addMember as VIEWER — canView true, canExecute false, canManage false`() {
        val store = freshStore()
        val owner = store.createUser("own@test.com", "Own", "hash")
        val project = store.createProject("P", null, owner.userId, "gpt-4o-mini")
        val viewer = store.createUser("view@test.com", "Viewer", "hash")
        store.addMember(project.projectId, viewer.userId, ProjectRole.VIEWER)

        val member = store.findMember(project.projectId, viewer.userId)!!
        assertTrue(member.role.canView())
        assertFalse(member.role.canExecute())
        assertFalse(member.role.canManage())
    }

    @Test
    fun `3 users, 2 projects — membership matrix correct`() {
        val store = freshStore()
        val alice = store.createUser("alice@test.com", "Alice", "hash")
        val bob = store.createUser("bob@test.com", "Bob", "hash")
        val carol = store.createUser("carol@test.com", "Carol", "hash")

        val projAlpha = store.createProject("Alpha", null, alice.userId, "gpt-4o-mini")
        val projBeta = store.createProject("Beta", null, bob.userId, "gpt-4o-mini")

        store.addMember(projAlpha.projectId, bob.userId, ProjectRole.MEMBER)
        store.addMember(projBeta.projectId, carol.userId, ProjectRole.VIEWER)

        // Alice: Alpha(OWNER), Beta(no)
        assertTrue(store.isMember(projAlpha.projectId, alice.userId))
        assertFalse(store.isMember(projBeta.projectId, alice.userId))

        // Bob: Alpha(MEMBER), Beta(OWNER)
        assertTrue(store.isMember(projAlpha.projectId, bob.userId))
        assertTrue(store.isMember(projBeta.projectId, bob.userId))

        // Carol: Alpha(no), Beta(VIEWER)
        assertFalse(store.isMember(projAlpha.projectId, carol.userId))
        assertTrue(store.isMember(projBeta.projectId, carol.userId))

        // Verify roles
        assertEquals(ProjectRole.MEMBER, store.findMember(projAlpha.projectId, bob.userId)!!.role)
        assertEquals(ProjectRole.VIEWER, store.findMember(projBeta.projectId, carol.userId)!!.role)
    }
}
