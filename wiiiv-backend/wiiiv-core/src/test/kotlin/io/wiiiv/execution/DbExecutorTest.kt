package io.wiiiv.execution

import io.wiiiv.execution.impl.*
import io.wiiiv.testutil.TestConnectionProvider
import io.wiiiv.runner.ExecutionRunner
import io.wiiiv.runner.RunnerStatus
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.*

/**
 * DbExecutor Tests
 *
 * 데이터베이스 작업 Executor 테스트
 *
 * H2 인메모리 데이터베이스 사용
 */
class DbExecutorTest {

    private lateinit var connectionProvider: SimpleConnectionProvider
    private lateinit var executor: DbExecutor
    private lateinit var context: ExecutionContext
    private lateinit var connection: Connection

    @BeforeTest
    fun setup() {
        // H2 인메모리 DB 사용
        connectionProvider = SimpleConnectionProvider.h2InMemory("test_${System.currentTimeMillis()}")
        executor = DbExecutor.create(connectionProvider)
        context = ExecutionContext.create(
            executionId = "test-exec",
            blueprintId = "test-bp",
            instructionId = "test-inst"
        )

        // Create test table
        connection = connectionProvider.getConnection(null)
        connection.createStatement().execute("""
            CREATE TABLE users (
                id INT AUTO_INCREMENT PRIMARY KEY,
                name VARCHAR(100) NOT NULL,
                email VARCHAR(100),
                age INT,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
        """.trimIndent())

        // Insert test data
        connection.createStatement().execute("""
            INSERT INTO users (name, email, age) VALUES
            ('Alice', 'alice@example.com', 30),
            ('Bob', 'bob@example.com', 25),
            ('Charlie', 'charlie@example.com', 35)
        """.trimIndent())
    }

    @AfterTest
    fun cleanup() {
        connection.close()
        connectionProvider.close()
    }

    // ==================== Basic Tests ====================

    @Test
    fun `should handle DbStep`() {
        val step = ExecutionStep.DbStep(
            stepId = "db-1",
            sql = "SELECT 1",
            mode = DbMode.QUERY
        )
        assertTrue(executor.canHandle(step))
    }

    @Test
    fun `should not handle other step types`() {
        val step = ExecutionStep.FileStep(
            stepId = "file-1",
            action = FileAction.READ,
            path = "/tmp/test.txt"
        )
        assertFalse(executor.canHandle(step))
    }

    @Test
    fun `should return error for invalid step type`() = runBlocking {
        val step = ExecutionStep.CommandStep(
            stepId = "cmd-1",
            command = "echo"
        )
        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.CONTRACT_VIOLATION, result.error.category)
        assertEquals("INVALID_STEP_TYPE", result.error.code)
    }

    // ==================== Query Tests ====================

    @Test
    fun `should execute SELECT query`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "select-1",
            sql = "SELECT * FROM users ORDER BY id",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals("QUERY", output.json["mode"]?.jsonPrimitive?.content)
        assertEquals(3, output.json["rowCount"]?.jsonPrimitive?.int)

        val rows = output.json["rows"]?.jsonArray
        assertNotNull(rows)
        assertEquals(3, rows.size)

        val firstRow = rows[0].jsonObject
        assertEquals("Alice", firstRow["NAME"]?.jsonPrimitive?.content)
    }

    @Test
    fun `should return column names`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "columns-1",
            sql = "SELECT name, email FROM users LIMIT 1",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        val columns = output.json["columns"]?.jsonArray
        assertNotNull(columns)
        assertTrue(columns.any { it.jsonPrimitive.content == "NAME" })
        assertTrue(columns.any { it.jsonPrimitive.content == "EMAIL" })
    }

    @Test
    fun `should handle empty result set`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "empty-1",
            sql = "SELECT * FROM users WHERE id = -1",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals(0, output.json["rowCount"]?.jsonPrimitive?.int)
        val rows = output.json["rows"]?.jsonArray
        assertNotNull(rows)
        assertTrue(rows.isEmpty())
    }

    @Test
    fun `should respect maxRows parameter`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "maxrows-1",
            sql = "SELECT * FROM users",
            mode = DbMode.QUERY,
            params = mapOf("maxRows" to "2")
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals(2, output.json["rowCount"]?.jsonPrimitive?.int)
        assertEquals(true, output.json["hasMore"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `should handle WHERE clause`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "where-1",
            sql = "SELECT * FROM users WHERE age > 28",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals(2, output.json["rowCount"]?.jsonPrimitive?.int)
    }

    // ==================== Mutation Tests ====================

    @Test
    fun `should execute INSERT`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "insert-1",
            sql = "INSERT INTO users (name, email, age) VALUES ('David', 'david@example.com', 40)",
            mode = DbMode.MUTATION
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals("MUTATION", output.json["mode"]?.jsonPrimitive?.content)
        assertEquals(1, output.json["affectedRows"]?.jsonPrimitive?.int)

        // Verify insert
        val verifyStep = ExecutionStep.DbStep(
            stepId = "verify-1",
            sql = "SELECT COUNT(*) as cnt FROM users",
            mode = DbMode.QUERY
        )
        val verifyResult = executor.execute(verifyStep, context)
        assertTrue(verifyResult is ExecutionResult.Success)
        val cnt = verifyResult.output.json["rows"]?.jsonArray
            ?.get(0)?.jsonObject?.get("CNT")?.jsonPrimitive?.int
        assertEquals(4, cnt)
    }

    @Test
    fun `should execute UPDATE`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "update-1",
            sql = "UPDATE users SET age = 31 WHERE name = 'Alice'",
            mode = DbMode.MUTATION
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals(1, output.json["affectedRows"]?.jsonPrimitive?.int)

        // Verify update
        val verifyStep = ExecutionStep.DbStep(
            stepId = "verify-2",
            sql = "SELECT age FROM users WHERE name = 'Alice'",
            mode = DbMode.QUERY
        )
        val verifyResult = executor.execute(verifyStep, context)
        assertTrue(verifyResult is ExecutionResult.Success)
        val age = verifyResult.output.json["rows"]?.jsonArray
            ?.get(0)?.jsonObject?.get("AGE")?.jsonPrimitive?.int
        assertEquals(31, age)
    }

    @Test
    fun `should execute DELETE`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "delete-1",
            sql = "DELETE FROM users WHERE name = 'Charlie'",
            mode = DbMode.MUTATION
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals(1, output.json["affectedRows"]?.jsonPrimitive?.int)

        // Verify delete
        val verifyStep = ExecutionStep.DbStep(
            stepId = "verify-3",
            sql = "SELECT COUNT(*) as cnt FROM users",
            mode = DbMode.QUERY
        )
        val verifyResult = executor.execute(verifyStep, context)
        assertTrue(verifyResult is ExecutionResult.Success)
        val cnt = verifyResult.output.json["rows"]?.jsonArray
            ?.get(0)?.jsonObject?.get("CNT")?.jsonPrimitive?.int
        assertEquals(2, cnt)
    }

    @Test
    fun `should report zero affected rows for non-matching UPDATE`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "update-nomatch",
            sql = "UPDATE users SET age = 99 WHERE name = 'NonExistent'",
            mode = DbMode.MUTATION
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals(0, output.json["affectedRows"]?.jsonPrimitive?.int)
    }

    // ==================== DDL Tests ====================

    @Test
    fun `should execute CREATE TABLE`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "create-1",
            sql = "CREATE TABLE products (id INT PRIMARY KEY, name VARCHAR(100))",
            mode = DbMode.DDL
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals("DDL", output.json["mode"]?.jsonPrimitive?.content)
        assertEquals(true, output.json["success"]?.jsonPrimitive?.boolean)

        // Verify table exists
        val verifyStep = ExecutionStep.DbStep(
            stepId = "verify-ddl",
            sql = "SELECT * FROM products",
            mode = DbMode.QUERY
        )
        val verifyResult = executor.execute(verifyStep, context)
        assertTrue(verifyResult is ExecutionResult.Success)
    }

    @Test
    fun `should execute DROP TABLE`() = runBlocking {
        // First create a table
        connection.createStatement().execute("CREATE TABLE temp_table (id INT)")

        val step = ExecutionStep.DbStep(
            stepId = "drop-1",
            sql = "DROP TABLE temp_table",
            mode = DbMode.DDL
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
    }

    @Test
    fun `should execute ALTER TABLE`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "alter-1",
            sql = "ALTER TABLE users ADD COLUMN status VARCHAR(20)",
            mode = DbMode.DDL
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)

        // Verify column exists
        val verifyStep = ExecutionStep.DbStep(
            stepId = "verify-alter",
            sql = "SELECT status FROM users LIMIT 1",
            mode = DbMode.QUERY
        )
        val verifyResult = executor.execute(verifyStep, context)
        assertTrue(verifyResult is ExecutionResult.Success)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `should return error for invalid SQL`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "invalid-1",
            sql = "SELECT * FROM nonexistent_table",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, result.error.category)
    }

    @Test
    fun `should return error for syntax error`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "syntax-1",
            sql = "SELEC * FORM users",  // Typo
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
    }

    @Test
    fun `should handle connection failure`() = runBlocking {
        val mockProvider = TestConnectionProvider()
        mockProvider.setFailure("Connection refused")

        val failingExecutor = DbExecutor.create(mockProvider)
        val step = ExecutionStep.DbStep(
            stepId = "conn-fail",
            sql = "SELECT 1",
            mode = DbMode.QUERY
        )

        val result = failingExecutor.execute(step, context)

        assertTrue(result is ExecutionResult.Failure)
        assertEquals(ErrorCategory.EXTERNAL_SERVICE_ERROR, result.error.category)
        assertEquals("CONNECTION_FAILED", result.error.code)
    }

    // ==================== Output Tests ====================

    @Test
    fun `output should be added to context`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "context-test",
            sql = "SELECT name FROM users WHERE id = 1",
            mode = DbMode.QUERY
        )

        executor.execute(step, context)

        val storedOutput = context.getStepOutput("context-test")
        assertNotNull(storedOutput)
        assertEquals("QUERY", storedOutput.json["mode"]?.jsonPrimitive?.content)
    }

    @Test
    fun `output artifacts should include rowCount`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "artifacts-1",
            sql = "SELECT * FROM users",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val output = result.output

        assertEquals("3", output.artifacts["rowCount"])
    }

    // ==================== Data Type Tests ====================

    @Test
    fun `should handle NULL values`() = runBlocking {
        connection.createStatement().execute(
            "INSERT INTO users (name, email, age) VALUES ('NoEmail', NULL, NULL)"
        )

        val step = ExecutionStep.DbStep(
            stepId = "null-1",
            sql = "SELECT * FROM users WHERE name = 'NoEmail'",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val row = result.output.json["rows"]?.jsonArray?.get(0)?.jsonObject
        assertNotNull(row)
        assertTrue(row["EMAIL"] is JsonNull || row["EMAIL"]?.jsonPrimitive?.contentOrNull == null)
    }

    @Test
    fun `should handle numeric values`() = runBlocking {
        val step = ExecutionStep.DbStep(
            stepId = "numeric-1",
            sql = "SELECT age FROM users WHERE name = 'Alice'",
            mode = DbMode.QUERY
        )

        val result = executor.execute(step, context)

        assertTrue(result is ExecutionResult.Success)
        val row = result.output.json["rows"]?.jsonArray?.get(0)?.jsonObject
        val age = row?.get("AGE")?.jsonPrimitive?.int
        assertEquals(30, age)
    }

    // ==================== Integration with Runner ====================

    @Test
    fun `Runner should execute multiple DB steps`() = runBlocking {
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.DbStep(
                stepId = "step-1-select",
                sql = "SELECT COUNT(*) as cnt FROM users",
                mode = DbMode.QUERY
            ),
            ExecutionStep.DbStep(
                stepId = "step-2-insert",
                sql = "INSERT INTO users (name, email, age) VALUES ('Eve', 'eve@example.com', 28)",
                mode = DbMode.MUTATION
            ),
            ExecutionStep.DbStep(
                stepId = "step-3-verify",
                sql = "SELECT COUNT(*) as cnt FROM users",
                mode = DbMode.QUERY
            )
        )

        val runnerContext = ExecutionContext.create("workflow", "bp", "inst")
        val result = runner.execute(steps, runnerContext)

        assertEquals(RunnerStatus.COMPLETED, result.status)
        assertEquals(3, result.successCount)
        assertTrue(result.isAllSuccess)

        // Verify counts changed
        val cnt1 = runnerContext.getStepOutput("step-1-select")?.json
            ?.get("rows")?.jsonArray?.get(0)?.jsonObject?.get("CNT")?.jsonPrimitive?.int
        val cnt2 = runnerContext.getStepOutput("step-3-verify")?.json
            ?.get("rows")?.jsonArray?.get(0)?.jsonObject?.get("CNT")?.jsonPrimitive?.int

        assertEquals(3, cnt1)
        assertEquals(4, cnt2)
    }

    @Test
    fun `Runner should fail-fast on SQL error`() = runBlocking {
        val runner = ExecutionRunner.create(executor)

        val steps = listOf(
            ExecutionStep.DbStep(
                stepId = "good-query",
                sql = "SELECT 1",
                mode = DbMode.QUERY
            ),
            ExecutionStep.DbStep(
                stepId = "bad-query",
                sql = "SELECT * FROM nonexistent",
                mode = DbMode.QUERY
            ),
            ExecutionStep.DbStep(
                stepId = "never-run",
                sql = "SELECT 2",
                mode = DbMode.QUERY
            )
        )

        val runnerContext = ExecutionContext.create("workflow", "bp", "inst")
        val result = runner.execute(steps, runnerContext)

        assertEquals(RunnerStatus.FAILED, result.status)
        assertEquals(1, result.successCount)
        assertEquals(1, result.failureCount)
    }

    // ==================== MultiConnectionProvider Tests ====================

    @Test
    fun `MultiConnectionProvider should manage multiple connections`() {
        val multiProvider = MultiConnectionProvider.builder()
            .addConnection("db1", SimpleConnectionProvider.h2InMemory("db1"))
            .addConnection("db2", SimpleConnectionProvider.h2InMemory("db2"))
            .setDefault("db1")
            .build()

        // Should get connection by ID
        val conn1 = multiProvider.getConnection("db1")
        assertNotNull(conn1)
        assertFalse(conn1.isClosed)

        val conn2 = multiProvider.getConnection("db2")
        assertNotNull(conn2)
        assertFalse(conn2.isClosed)

        // Should get default connection
        val defaultConn = multiProvider.getConnection(null)
        assertNotNull(defaultConn)

        multiProvider.releaseConnection(conn1)
        multiProvider.releaseConnection(conn2)
        multiProvider.releaseConnection(defaultConn)
        multiProvider.close()
    }

    @Test
    fun `MultiConnectionProvider should throw for unknown connection ID`() {
        val multiProvider = MultiConnectionProvider.builder()
            .addConnection("db1", SimpleConnectionProvider.h2InMemory("db1"))
            .build()

        assertFailsWith<java.sql.SQLException> {
            multiProvider.getConnection("unknown")
        }

        multiProvider.close()
    }
}
