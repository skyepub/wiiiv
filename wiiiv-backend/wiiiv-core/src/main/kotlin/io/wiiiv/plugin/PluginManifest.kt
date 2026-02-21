package io.wiiiv.plugin

/**
 * Plugin Manifest — plugin.yaml의 표시용 메타 정보
 *
 * 코드가 진실(single source of truth): actions, risk, capability는 WiiivPlugin 코드에서 제공.
 * YAML은 displayName, description, vendor, configSchema 같은 문서화 정보만 담는다.
 */
data class PluginManifest(
    val pluginId: String,
    val displayName: String = "",
    val description: String = "",
    val vendor: String = "",
    val docsUrl: String = "",
    val configSchema: Map<String, ConfigSchemaEntry> = emptyMap()
) {
    data class ConfigSchemaEntry(
        val description: String = "",
        val defaultValue: String = "",
        val type: String = "string"
    )

    companion object {
        /**
         * 간단한 key=value 기반 YAML 파싱 (SnakeYAML 의존성 없이)
         *
         * plugin.yaml 예시:
         * ```
         * plugin.id: webhook
         * plugin.displayName: Webhook Executor
         * plugin.description: HTTP Webhook 전송
         * plugin.vendor: wiiiv-team
         * ```
         */
        fun parseSimpleYaml(content: String): PluginManifest {
            val props = mutableMapOf<String, String>()
            content.lines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    val idx = trimmed.indexOf(':')
                    if (idx > 0) {
                        val key = trimmed.substring(0, idx).trim()
                        val value = trimmed.substring(idx + 1).trim().removeSurrounding("\"")
                        props[key] = value
                    }
                }
            }
            return PluginManifest(
                pluginId = props["plugin.id"] ?: "",
                displayName = props["plugin.displayName"] ?: "",
                description = props["plugin.description"] ?: "",
                vendor = props["plugin.vendor"] ?: "",
                docsUrl = props["plugin.docsUrl"] ?: ""
            )
        }
    }
}
