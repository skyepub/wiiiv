package io.wiiiv.integration

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Mock API Server for Phase 4 E2E Testing
 *
 * Embedded Ktor Netty server with in-memory data store.
 * ApiExecutor uses java.net.http.HttpClient, so we need a real HTTP server.
 *
 * ## Data Model
 * - Users: john (id=1), jane (id=2), bob (id=3)
 * - Orders: order-101 (userId=1), order-102 (userId=1), order-103 (userId=2)
 *
 * ## Endpoints
 * - GET /api/users?name={name} - Search users by name
 * - GET /api/users/{id} - Get user by ID
 * - GET /api/orders?userId={userId} - Get orders by userId
 * - PUT /api/orders/{id} - Update order status
 */
class MockApiServer {

    private val json = Json { prettyPrint = true }
    private var server: ApplicationEngine? = null
    var port: Int = 0
        private set

    val dataStore = MockDataStore()

    /**
     * Start the server on a random available port
     */
    fun start() {
        // Find a free port
        val freePort = java.net.ServerSocket(0).use { it.localPort }
        this.port = freePort

        server = embeddedServer(Netty, port = freePort) {
            routing {
                // GET /api/users?name=
                get("/api/users") {
                    val nameQuery = call.request.queryParameters["name"]
                    val users = if (nameQuery != null) {
                        dataStore.findUsersByName(nameQuery)
                    } else {
                        dataStore.getAllUsers()
                    }
                    call.respondText(json.encodeToString(users), ContentType.Application.Json)
                }

                // GET /api/users/{id}
                get("/api/users/{id}") {
                    val id = call.parameters["id"]?.toIntOrNull()
                    if (id == null) {
                        call.respondText(
                            """{"error":"Invalid user ID"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@get
                    }
                    val user = dataStore.getUserById(id)
                    if (user != null) {
                        call.respondText(json.encodeToString(user), ContentType.Application.Json)
                    } else {
                        call.respondText(
                            """{"error":"User not found","id":$id}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound
                        )
                    }
                }

                // GET /api/orders?userId=
                get("/api/orders") {
                    val userId = call.request.queryParameters["userId"]?.toIntOrNull()
                    val orders = if (userId != null) {
                        dataStore.getOrdersByUserId(userId)
                    } else {
                        dataStore.getAllOrders()
                    }
                    call.respondText(json.encodeToString(orders), ContentType.Application.Json)
                }

                // PUT /api/orders/{id}
                put("/api/orders/{id}") {
                    val orderId = call.parameters["id"]
                    if (orderId == null) {
                        call.respondText(
                            """{"error":"Missing order ID"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@put
                    }

                    val bodyText = call.receiveText()
                    val update = try {
                        json.decodeFromString<OrderUpdate>(bodyText)
                    } catch (e: Exception) {
                        call.respondText(
                            """{"error":"Invalid request body: ${e.message}"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.BadRequest
                        )
                        return@put
                    }

                    val updated = dataStore.updateOrder(orderId, update.status)
                    if (updated != null) {
                        call.respondText(json.encodeToString(updated), ContentType.Application.Json)
                    } else {
                        call.respondText(
                            """{"error":"Order not found","id":"$orderId"}""",
                            ContentType.Application.Json,
                            HttpStatusCode.NotFound
                        )
                    }
                }

                // Health check
                get("/api/health") {
                    call.respondText("""{"status":"ok"}""", ContentType.Application.Json)
                }
            }
        }.start(wait = false)
    }

    /**
     * Stop the server
     */
    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    /**
     * Base URL of the running server
     */
    fun baseUrl(): String = "http://localhost:$port"
}

/**
 * In-memory data store with predefined test data
 */
class MockDataStore {

    private val users = ConcurrentHashMap<Int, MockUser>()
    private val orders = ConcurrentHashMap<String, MockOrder>()

    init {
        // Seed users
        users[1] = MockUser(1, "john", "john@example.com")
        users[2] = MockUser(2, "jane", "jane@example.com")
        users[3] = MockUser(3, "bob", "bob@example.com")

        // Seed orders
        orders["order-101"] = MockOrder("order-101", 1, "pending", "Laptop")
        orders["order-102"] = MockOrder("order-102", 1, "pending", "Mouse")
        orders["order-103"] = MockOrder("order-103", 2, "shipped", "Keyboard")
    }

    fun getAllUsers(): List<MockUser> = users.values.toList()

    fun findUsersByName(name: String): List<MockUser> {
        return users.values.filter { it.name.contains(name, ignoreCase = true) }
    }

    fun getUserById(id: Int): MockUser? = users[id]

    fun getAllOrders(): List<MockOrder> = orders.values.toList()

    fun getOrdersByUserId(userId: Int): List<MockOrder> {
        return orders.values.filter { it.userId == userId }
    }

    fun updateOrder(orderId: String, newStatus: String): MockOrder? {
        val order = orders[orderId] ?: return null
        val updated = order.copy(status = newStatus)
        orders[orderId] = updated
        return updated
    }

    fun reset() {
        users.clear()
        orders.clear()
        // Re-seed
        users[1] = MockUser(1, "john", "john@example.com")
        users[2] = MockUser(2, "jane", "jane@example.com")
        users[3] = MockUser(3, "bob", "bob@example.com")
        orders["order-101"] = MockOrder("order-101", 1, "pending", "Laptop")
        orders["order-102"] = MockOrder("order-102", 1, "pending", "Mouse")
        orders["order-103"] = MockOrder("order-103", 2, "shipped", "Keyboard")
    }
}

@Serializable
data class MockUser(
    val id: Int,
    val name: String,
    val email: String
)

@Serializable
data class MockOrder(
    val id: String,
    val userId: Int,
    val status: String,
    val item: String
)

@Serializable
data class OrderUpdate(
    val status: String
)
