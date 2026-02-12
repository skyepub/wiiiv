package io.wiiiv.cli

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import java.io.File

/**
 * CredentialStore 단위 테스트
 *
 * 시스템 프로퍼티를 임시 디렉토리로 교체하여 실제 ~/.wiiiv에 영향을 주지 않는다.
 */
class CredentialStoreTest {

    @TempDir
    lateinit var tempDir: File

    private fun withTempHome(block: () -> Unit) {
        val original = System.getProperty("user.home")
        try {
            System.setProperty("user.home", tempDir.absolutePath)
            block()
        } finally {
            System.setProperty("user.home", original)
        }
    }

    @Test
    fun `save and load token`() = withTempHome {
        CredentialStore.saveToken("localhost:8235", "admin", "tok-123")

        val loaded = CredentialStore.getToken("localhost:8235")
        assertNotNull(loaded)
        assertEquals("tok-123", loaded.token)
        assertEquals("admin", loaded.username)
    }

    @Test
    fun `multiple hosts`() = withTempHome {
        CredentialStore.saveToken("localhost:8235", "admin", "tok-local")
        CredentialStore.saveToken("10.0.0.1:9000", "user", "tok-remote")

        val local = CredentialStore.getToken("localhost:8235")
        val remote = CredentialStore.getToken("10.0.0.1:9000")

        assertNotNull(local)
        assertEquals("tok-local", local.token)
        assertNotNull(remote)
        assertEquals("tok-remote", remote.token)
    }

    @Test
    fun `remove token`() = withTempHome {
        CredentialStore.saveToken("localhost:8235", "admin", "tok-123")
        CredentialStore.removeToken("localhost:8235")

        assertNull(CredentialStore.getToken("localhost:8235"))
    }

    @Test
    fun `missing host returns null`() = withTempHome {
        assertNull(CredentialStore.getToken("nonexistent:1234"))
    }

    @Test
    fun `corrupted file returns empty`() = withTempHome {
        val credFile = File(tempDir, ".wiiiv/credentials")
        credFile.parentFile.mkdirs()
        credFile.writeText("NOT VALID JSON!!!")

        assertNull(CredentialStore.getToken("localhost:8235"))
    }

    @Test
    fun `overwrite existing token`() = withTempHome {
        CredentialStore.saveToken("localhost:8235", "admin", "old-token")
        CredentialStore.saveToken("localhost:8235", "admin", "new-token")

        val loaded = CredentialStore.getToken("localhost:8235")
        assertNotNull(loaded)
        assertEquals("new-token", loaded.token)
    }
}
