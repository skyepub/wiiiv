package io.wiiiv.execution.impl

import io.wiiiv.execution.*
import kotlinx.serialization.json.*
import java.sql.*
import java.time.Instant

/**
 * DB Executor - 데이터베이스 작업 Executor
 *
 * Canonical: Executor 정의서 v1.0, Executor Interface Spec v1.0
 *
 * ## Executor 원칙 준수
 *
 * - 판단하지 않는다: SQL이 위험한지, 적절한지 판단하지 않음
 * - 해석하지 않는다: 쿼리 결과의 의미를 해석하지 않음
 * - Blueprint를 신뢰한다: 정합성과 합법성이 이미 검증되었다고 가정
 *
 * ## 지원 기능
 *
 * - DbMode.QUERY: SELECT 쿼리 실행, 결과 반환
 * - DbMode.MUTATION: INSERT/UPDATE/DELETE 실행, 영향받은 행 수 반환
 * - DbMode.DDL: CREATE/ALTER/DROP 실행
 *
 * ## 오류 처리
 *
 * - 연결 실패 → Failure (EXTERNAL_SERVICE_ERROR)
 * - SQL 오류 → Failure (EXTERNAL_SERVICE_ERROR)
 * - 타임아웃 → Failure (TIMEOUT)
 *
 * ## 보안 주의사항
 *
 * DbExecutor는 판단하지 않는다. SQL Injection 등 보안 검증은 Governor와 Gate의 책임이다.
 * Blueprint에 포함된 SQL은 이미 검증되었다고 가정한다.
 */
class DbExecutor(
    private val connectionProvider: DbConnectionProvider
) : Executor {

    override suspend fun execute(
        step: ExecutionStep,
        context: ExecutionContext
    ): ExecutionResult {
        // Type check
        if (step !is ExecutionStep.DbStep) {
            return ExecutionResult.contractViolation(
                stepId = step.stepId,
                code = "INVALID_STEP_TYPE",
                message = "DbExecutor can only handle DbStep, got: ${step::class.simpleName}"
            )
        }

        val startedAt = Instant.now()

        return try {
            val result = executeDb(step)

            val endedAt = Instant.now()

            when (result) {
                is DbResult.Success -> {
                    val output = result.output
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.connectionId ?: "default")
                    )

                    // Add to context
                    context.addStepOutput(step.stepId, output)

                    ExecutionResult.Success(output = output, meta = meta)
                }
                is DbResult.Error -> {
                    val meta = ExecutionMeta.of(
                        stepId = step.stepId,
                        startedAt = startedAt,
                        endedAt = endedAt,
                        resourceRefs = listOf(step.connectionId ?: "default")
                    )

                    ExecutionResult.Failure(
                        error = result.error,
                        partialOutput = result.partialOutput,
                        meta = meta
                    )
                }
            }
        } catch (e: SQLException) {
            ExecutionResult.Failure(
                error = ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "SQL_ERROR",
                    message = "SQL error: ${e.message} (SQLState: ${e.sqlState})"
                ),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.connectionId ?: "default")
                )
            )
        } catch (e: Exception) {
            ExecutionResult.Failure(
                error = ExecutionError.unknown("Unexpected error: ${e.message}"),
                meta = ExecutionMeta.of(
                    stepId = step.stepId,
                    startedAt = startedAt,
                    endedAt = Instant.now(),
                    resourceRefs = listOf(step.connectionId ?: "default")
                )
            )
        }
    }

    /**
     * DB 작업 실행
     */
    private fun executeDb(step: ExecutionStep.DbStep): DbResult {
        // Get connection
        val connection = try {
            connectionProvider.getConnection(step.connectionId)
        } catch (e: SQLException) {
            return DbResult.Error(
                ExecutionError(
                    category = ErrorCategory.EXTERNAL_SERVICE_ERROR,
                    code = "CONNECTION_FAILED",
                    message = "Failed to get database connection: ${e.message}"
                )
            )
        }

        return try {
            when (step.mode) {
                DbMode.QUERY -> executeQuery(step, connection)
                DbMode.MUTATION -> executeMutation(step, connection)
                DbMode.DDL -> executeDdl(step, connection)
            }
        } finally {
            // Return connection to pool (or close if not pooled)
            try {
                connectionProvider.releaseConnection(connection)
            } catch (e: Exception) {
                // Ignore release errors
            }
        }
    }

    /**
     * SELECT 쿼리 실행
     */
    private fun executeQuery(step: ExecutionStep.DbStep, connection: Connection): DbResult {
        val statement = connection.createStatement()

        // Set query timeout if specified
        step.params["timeoutSeconds"]?.toIntOrNull()?.let {
            statement.queryTimeout = it
        }

        val resultSet = try {
            statement.executeQuery(step.sql)
        } catch (e: SQLTimeoutException) {
            return DbResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "QUERY_TIMEOUT",
                    message = "Query timed out"
                )
            )
        }

        // Extract metadata
        val metaData = resultSet.metaData
        val columnCount = metaData.columnCount
        val columns = (1..columnCount).map { metaData.getColumnName(it) }

        // Extract rows
        val rows = mutableListOf<Map<String, Any?>>()
        var rowCount = 0
        val maxRows = step.params["maxRows"]?.toIntOrNull() ?: MAX_ROWS

        // Important: Check rowCount BEFORE resultSet.next() to preserve cursor position
        while (rowCount < maxRows && resultSet.next()) {
            val row = mutableMapOf<String, Any?>()
            for (i in 1..columnCount) {
                val columnName = metaData.getColumnName(i)
                val value = resultSet.getObject(i)
                row[columnName] = convertToSerializable(value)
            }
            rows.add(row)
            rowCount++
        }

        // Check if there are more rows beyond maxRows
        val hasMore = if (rowCount >= maxRows) resultSet.next() else false

        resultSet.close()
        statement.close()

        // Build output
        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("mode", JsonPrimitive("QUERY"))
                put("sql", JsonPrimitive(step.sql))
                put("rowCount", JsonPrimitive(rows.size))
                put("hasMore", JsonPrimitive(hasMore))
                putJsonArray("columns") {
                    columns.forEach { add(JsonPrimitive(it)) }
                }
                putJsonArray("rows") {
                    rows.forEach { row ->
                        addJsonObject {
                            row.forEach { (key, value) ->
                                put(key, toJsonElement(value))
                            }
                        }
                    }
                }
            },
            artifacts = mapOf(
                "rowCount" to rows.size.toString(),
                "columns" to columns.joinToString(",")
            )
        )

        return DbResult.Success(output)
    }

    /**
     * INSERT/UPDATE/DELETE 실행
     */
    private fun executeMutation(step: ExecutionStep.DbStep, connection: Connection): DbResult {
        val statement = connection.createStatement()

        // Set query timeout if specified
        step.params["timeoutSeconds"]?.toIntOrNull()?.let {
            statement.queryTimeout = it
        }

        val affectedRows = try {
            statement.executeUpdate(step.sql)
        } catch (e: SQLTimeoutException) {
            return DbResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "MUTATION_TIMEOUT",
                    message = "Mutation timed out"
                )
            )
        }

        // Get generated keys if available
        val generatedKeys = mutableListOf<Long>()
        try {
            val keys = statement.generatedKeys
            while (keys.next()) {
                generatedKeys.add(keys.getLong(1))
            }
            keys.close()
        } catch (e: SQLException) {
            // Generated keys not supported or not available
        }

        statement.close()

        // Build output
        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("mode", JsonPrimitive("MUTATION"))
                put("sql", JsonPrimitive(step.sql))
                put("affectedRows", JsonPrimitive(affectedRows))
                if (generatedKeys.isNotEmpty()) {
                    putJsonArray("generatedKeys") {
                        generatedKeys.forEach { add(JsonPrimitive(it)) }
                    }
                }
            },
            artifacts = mapOf(
                "affectedRows" to affectedRows.toString()
            )
        )

        return DbResult.Success(output)
    }

    /**
     * DDL (CREATE/ALTER/DROP) 실행
     */
    private fun executeDdl(step: ExecutionStep.DbStep, connection: Connection): DbResult {
        val statement = connection.createStatement()

        // Set query timeout if specified
        step.params["timeoutSeconds"]?.toIntOrNull()?.let {
            statement.queryTimeout = it
        }

        val success = try {
            statement.execute(step.sql)
            true
        } catch (e: SQLTimeoutException) {
            return DbResult.Error(
                ExecutionError(
                    category = ErrorCategory.TIMEOUT,
                    code = "DDL_TIMEOUT",
                    message = "DDL operation timed out"
                )
            )
        }

        statement.close()

        // Build output
        val output = StepOutput(
            stepId = step.stepId,
            json = buildJsonObject {
                put("mode", JsonPrimitive("DDL"))
                put("sql", JsonPrimitive(step.sql))
                put("success", JsonPrimitive(success))
            },
            artifacts = mapOf(
                "success" to success.toString()
            )
        )

        return DbResult.Success(output)
    }

    /**
     * 값을 직렬화 가능한 형태로 변환
     */
    private fun convertToSerializable(value: Any?): Any? {
        return when (value) {
            null -> null
            is String -> value
            is Number -> value
            is Boolean -> value
            is java.sql.Date -> value.toString()
            is java.sql.Time -> value.toString()
            is java.sql.Timestamp -> value.toString()
            is java.util.Date -> value.toString()
            is ByteArray -> "[BLOB:${value.size} bytes]"
            is Clob -> value.getSubString(1, minOf(value.length().toInt(), 1000))
            is Blob -> "[BLOB:${value.length()} bytes]"
            else -> value.toString()
        }
    }

    /**
     * Any를 JsonElement로 변환
     */
    private fun toJsonElement(value: Any?): JsonElement {
        return when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }

    override suspend fun cancel(executionId: String, reason: CancelReason): Boolean {
        // DB 쿼리 취소는 복잡한 상태 관리 필요
        // v1.0에서는 간단히 true 반환 (실제 취소는 타임아웃에 의존)
        return true
    }

    override fun canHandle(step: ExecutionStep): Boolean {
        return step is ExecutionStep.DbStep
    }

    /**
     * Internal result type for DB operations
     */
    private sealed class DbResult {
        data class Success(val output: StepOutput) : DbResult()
        data class Error(
            val error: ExecutionError,
            val partialOutput: StepOutput? = null
        ) : DbResult()
    }

    companion object {
        /**
         * 최대 반환 행 수 (기본값)
         */
        const val MAX_ROWS = 10000

        /**
         * Provider로 Executor 생성
         */
        fun create(connectionProvider: DbConnectionProvider): DbExecutor {
            return DbExecutor(connectionProvider)
        }
    }
}

/**
 * DB Connection Provider Interface
 *
 * 데이터베이스 연결 추상화
 */
interface DbConnectionProvider {
    /**
     * 연결 획득
     *
     * @param connectionId 연결 식별자 (null이면 기본 연결)
     * @throws SQLException 연결 실패 시
     */
    fun getConnection(connectionId: String?): Connection

    /**
     * 연결 반환
     */
    fun releaseConnection(connection: Connection)

    /**
     * 모든 연결 종료
     */
    fun close()
}

/**
 * Simple Connection Provider - 단일 연결 Provider
 *
 * 테스트 및 단순 사용을 위한 구현
 */
class SimpleConnectionProvider(
    private val url: String,
    private val username: String? = null,
    private val password: String? = null
) : DbConnectionProvider {

    override fun getConnection(connectionId: String?): Connection {
        return if (username != null && password != null) {
            DriverManager.getConnection(url, username, password)
        } else {
            DriverManager.getConnection(url)
        }
    }

    override fun releaseConnection(connection: Connection) {
        if (!connection.isClosed) {
            connection.close()
        }
    }

    override fun close() {
        // No pooled connections to close
    }

    companion object {
        /**
         * H2 인메모리 데이터베이스용 Provider
         */
        fun h2InMemory(dbName: String = "test"): SimpleConnectionProvider {
            return SimpleConnectionProvider("jdbc:h2:mem:$dbName;DB_CLOSE_DELAY=-1")
        }

        /**
         * SQLite 인메모리 데이터베이스용 Provider
         */
        fun sqliteInMemory(): SimpleConnectionProvider {
            return SimpleConnectionProvider("jdbc:sqlite::memory:")
        }

        /**
         * SQLite 파일 데이터베이스용 Provider
         */
        fun sqliteFile(path: String): SimpleConnectionProvider {
            return SimpleConnectionProvider("jdbc:sqlite:$path")
        }
    }
}

/**
 * Multi Connection Provider - 다중 연결 Provider
 *
 * 여러 데이터베이스 연결을 관리
 */
class MultiConnectionProvider(
    private val connections: Map<String, DbConnectionProvider>,
    private val defaultConnectionId: String? = null
) : DbConnectionProvider {

    override fun getConnection(connectionId: String?): Connection {
        val id = connectionId ?: defaultConnectionId
            ?: throw SQLException("No connection ID specified and no default connection")

        val provider = connections[id]
            ?: throw SQLException("Unknown connection ID: $id")

        return provider.getConnection(null)
    }

    override fun releaseConnection(connection: Connection) {
        if (!connection.isClosed) {
            connection.close()
        }
    }

    override fun close() {
        connections.values.forEach { it.close() }
    }

    companion object {
        /**
         * Builder로 생성
         */
        fun builder(): Builder = Builder()
    }

    class Builder {
        private val connections = mutableMapOf<String, DbConnectionProvider>()
        private var defaultId: String? = null

        fun addConnection(id: String, provider: DbConnectionProvider): Builder {
            connections[id] = provider
            return this
        }

        fun addConnection(id: String, url: String, username: String? = null, password: String? = null): Builder {
            connections[id] = SimpleConnectionProvider(url, username, password)
            return this
        }

        fun setDefault(id: String): Builder {
            defaultId = id
            return this
        }

        fun build(): MultiConnectionProvider {
            return MultiConnectionProvider(connections.toMap(), defaultId)
        }
    }
}

/**
 * Mock Connection Provider - 테스트용
 *
 * 실제 DB 연결 없이 테스트 가능
 */
class MockConnectionProvider(
    private val mockConnection: Connection? = null
) : DbConnectionProvider {

    private var shouldFail = false
    private var failureMessage = "Mock connection failure"

    fun setFailure(message: String) {
        shouldFail = true
        failureMessage = message
    }

    fun clearFailure() {
        shouldFail = false
    }

    override fun getConnection(connectionId: String?): Connection {
        if (shouldFail) {
            throw SQLException(failureMessage)
        }
        return mockConnection ?: throw SQLException("No mock connection provided")
    }

    override fun releaseConnection(connection: Connection) {
        // Mock - do nothing
    }

    override fun close() {
        mockConnection?.close()
    }
}
