package io.wiiiv.execution.impl

import io.wiiiv.execution.MessageQueueAction
import java.time.Duration
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap

/**
 * Kafka Provider - Apache Kafka 연동
 *
 * ## 사용 방법
 *
 * 1. build.gradle.kts에 Kafka 의존성 추가:
 *    implementation("org.apache.kafka:kafka-clients:3.6.1")
 *
 * 2. Provider 생성:
 *    val provider = KafkaProvider.create(
 *        bootstrapServers = "localhost:9092",
 *        groupId = "my-consumer-group"
 *    )
 *
 * 3. MessageQueueExecutor에 등록:
 *    registry.registerProvider(provider)
 *
 * ## 환경 변수
 *
 * - KAFKA_BOOTSTRAP_SERVERS: 브로커 주소 (기본: localhost:9092)
 * - KAFKA_GROUP_ID: 컨슈머 그룹 ID (기본: wiiiv-consumer)
 *
 * ## 지원 기능
 *
 * - PUBLISH: 메시지 발행 (KafkaProducer)
 * - CONSUME: 메시지 소비 (KafkaConsumer, 단일 메시지)
 * - REQUEST_REPLY: 요청-응답 패턴 (reply topic 사용)
 *
 * ## 주의사항
 *
 * - Kafka 클라이언트 라이브러리가 classpath에 있어야 동작
 * - 없을 경우 초기화 시 예외 발생
 */
class KafkaProvider private constructor(
    private val bootstrapServers: String,
    private val groupId: String,
    private val additionalConfig: Map<String, Any>
) : MessageQueueProvider {

    override val id: String = "kafka"

    // Lazy initialization to avoid class loading issues when Kafka not present
    private val producer: Any by lazy { createProducer() }
    private val consumer: Any by lazy { createConsumer() }

    override fun publish(topic: String, message: String, timeoutMs: Long): PublishResult {
        return try {
            val producerRecord = createProducerRecord(topic, message)
            val future = sendRecord(producerRecord)
            val metadata = waitForResult(future, timeoutMs)

            PublishResult(
                success = true,
                messageId = "${getPartition(metadata)}-${getOffset(metadata)}"
            )
        } catch (e: Exception) {
            throw MessageQueueException(
                code = "KAFKA_PUBLISH_ERROR",
                message = "Failed to publish to Kafka: ${e.message}"
            )
        }
    }

    override fun consume(topic: String, timeoutMs: Long): ConsumeResult {
        return try {
            subscribe(consumer, listOf(topic))
            val records = poll(consumer, timeoutMs)

            if (isEmpty(records)) {
                ConsumeResult(message = null, messageId = null)
            } else {
                val record = firstRecord(records)
                ConsumeResult(
                    message = getValue(record),
                    messageId = "${getRecordPartition(record)}-${getRecordOffset(record)}"
                )
            }
        } catch (e: Exception) {
            throw MessageQueueException(
                code = "KAFKA_CONSUME_ERROR",
                message = "Failed to consume from Kafka: ${e.message}"
            )
        }
    }

    override fun requestReply(topic: String, request: String, timeoutMs: Long): RequestReplyResult {
        val replyTopic = "$topic.reply"
        val correlationId = java.util.UUID.randomUUID().toString()

        // Publish request with correlation ID in headers
        try {
            val headers = mapOf("correlationId" to correlationId)
            publishWithHeaders(topic, request, headers, timeoutMs)

            // Wait for reply on reply topic
            subscribe(consumer, listOf(replyTopic))
            val startTime = System.currentTimeMillis()

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val records = poll(consumer, 100)
                for (record in iterateRecords(records)) {
                    val recordCorrelationId = getHeader(record, "correlationId")
                    if (recordCorrelationId == correlationId) {
                        return RequestReplyResult(reply = getValue(record))
                    }
                }
            }

            return RequestReplyResult(reply = null)
        } catch (e: Exception) {
            throw MessageQueueException(
                code = "KAFKA_REQUEST_REPLY_ERROR",
                message = "Failed request-reply: ${e.message}"
            )
        }
    }

    fun close() {
        try {
            closeProducer(producer)
            closeConsumer(consumer)
        } catch (e: Exception) {
            // Ignore close errors
        }
    }

    // Reflection-based Kafka operations to avoid compile-time dependency
    private fun createProducer(): Any {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
            put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer")
            additionalConfig.forEach { (k, v) -> put(k, v) }
        }

        val producerClass = Class.forName("org.apache.kafka.clients.producer.KafkaProducer")
        return producerClass.getConstructor(Properties::class.java).newInstance(props)
    }

    private fun createConsumer(): Any {
        val props = Properties().apply {
            put("bootstrap.servers", bootstrapServers)
            put("group.id", groupId)
            put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer")
            put("auto.offset.reset", "earliest")
            put("enable.auto.commit", "true")
            additionalConfig.forEach { (k, v) -> put(k, v) }
        }

        val consumerClass = Class.forName("org.apache.kafka.clients.consumer.KafkaConsumer")
        return consumerClass.getConstructor(Properties::class.java).newInstance(props)
    }

    private fun createProducerRecord(topic: String, message: String): Any {
        val recordClass = Class.forName("org.apache.kafka.clients.producer.ProducerRecord")
        return recordClass.getConstructor(String::class.java, Any::class.java)
            .newInstance(topic, message)
    }

    private fun sendRecord(record: Any): Any {
        val sendMethod = producer.javaClass.getMethod("send", record.javaClass.interfaces[0])
        return sendMethod.invoke(producer, record)
    }

    private fun waitForResult(future: Any, timeoutMs: Long): Any {
        val getMethod = future.javaClass.getMethod("get", Long::class.java, java.util.concurrent.TimeUnit::class.java)
        return getMethod.invoke(future, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    private fun getPartition(metadata: Any): Int {
        return metadata.javaClass.getMethod("partition").invoke(metadata) as Int
    }

    private fun getOffset(metadata: Any): Long {
        return metadata.javaClass.getMethod("offset").invoke(metadata) as Long
    }

    private fun subscribe(consumer: Any, topics: List<String>) {
        val subscribeMethod = consumer.javaClass.getMethod("subscribe", Collection::class.java)
        subscribeMethod.invoke(consumer, topics)
    }

    private fun poll(consumer: Any, timeoutMs: Long): Any {
        val pollMethod = consumer.javaClass.getMethod("poll", Duration::class.java)
        return pollMethod.invoke(consumer, Duration.ofMillis(timeoutMs))
    }

    private fun isEmpty(records: Any): Boolean {
        return records.javaClass.getMethod("isEmpty").invoke(records) as Boolean
    }

    private fun firstRecord(records: Any): Any {
        val iterator = records.javaClass.getMethod("iterator").invoke(records) as Iterator<*>
        return iterator.next()!!
    }

    private fun iterateRecords(records: Any): Iterable<Any> {
        @Suppress("UNCHECKED_CAST")
        return records as Iterable<Any>
    }

    private fun getValue(record: Any): String {
        return record.javaClass.getMethod("value").invoke(record) as String
    }

    private fun getRecordPartition(record: Any): Int {
        return record.javaClass.getMethod("partition").invoke(record) as Int
    }

    private fun getRecordOffset(record: Any): Long {
        return record.javaClass.getMethod("offset").invoke(record) as Long
    }

    private fun getHeader(record: Any, key: String): String? {
        val headers = record.javaClass.getMethod("headers").invoke(record)
        val lastHeader = headers.javaClass.getMethod("lastHeader", String::class.java)
            .invoke(headers, key) ?: return null
        val value = lastHeader.javaClass.getMethod("value").invoke(lastHeader) as ByteArray?
        return value?.let { String(it) }
    }

    private fun publishWithHeaders(topic: String, message: String, headers: Map<String, String>, timeoutMs: Long) {
        val recordClass = Class.forName("org.apache.kafka.clients.producer.ProducerRecord")
        val record = recordClass.getConstructor(String::class.java, Any::class.java)
            .newInstance(topic, message)

        val recordHeaders = record.javaClass.getMethod("headers").invoke(record)
        val addMethod = recordHeaders.javaClass.getMethod("add", String::class.java, ByteArray::class.java)
        headers.forEach { (k, v) -> addMethod.invoke(recordHeaders, k, v.toByteArray()) }

        val future = sendRecord(record)
        waitForResult(future, timeoutMs)
    }

    private fun closeProducer(producer: Any) {
        producer.javaClass.getMethod("close").invoke(producer)
    }

    private fun closeConsumer(consumer: Any) {
        consumer.javaClass.getMethod("close").invoke(consumer)
    }

    companion object {
        private val instances = ConcurrentHashMap<String, KafkaProvider>()

        /**
         * Kafka 클라이언트 라이브러리 사용 가능 여부 확인
         */
        fun isAvailable(): Boolean {
            return try {
                Class.forName("org.apache.kafka.clients.producer.KafkaProducer")
                true
            } catch (e: ClassNotFoundException) {
                false
            }
        }

        /**
         * Provider 생성
         *
         * @throws IllegalStateException Kafka 클라이언트가 classpath에 없을 경우
         */
        fun create(
            bootstrapServers: String = System.getenv("KAFKA_BOOTSTRAP_SERVERS") ?: "localhost:9092",
            groupId: String = System.getenv("KAFKA_GROUP_ID") ?: "wiiiv-consumer",
            additionalConfig: Map<String, Any> = emptyMap()
        ): KafkaProvider {
            if (!isAvailable()) {
                throw IllegalStateException(
                    "Kafka client library not found. Add dependency: " +
                    "implementation(\"org.apache.kafka:kafka-clients:3.6.1\")"
                )
            }

            val key = "$bootstrapServers:$groupId"
            return instances.getOrPut(key) {
                KafkaProvider(bootstrapServers, groupId, additionalConfig)
            }
        }

        /**
         * 환경 변수에서 Provider 생성
         */
        fun fromEnv(): KafkaProvider = create()
    }
}
