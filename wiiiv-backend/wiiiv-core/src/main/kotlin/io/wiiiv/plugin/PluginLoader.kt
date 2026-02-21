package io.wiiiv.plugin

import java.io.Closeable
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * 로드된 플러그인 — Executor + 메타 + ClassLoader 보유
 *
 * Closeable: 서버 종료 시 URLClassLoader.close() → JAR 잠금 해제
 */
data class LoadedPlugin(
    val plugin: WiiivPlugin,
    val executor: io.wiiiv.execution.Executor,
    val meta: io.wiiiv.execution.ExecutorMeta,
    val actions: List<PluginAction>,
    val manifest: PluginManifest?,
    val jarPath: String,
    val classLoader: URLClassLoader
) : Closeable {
    override fun close() {
        runCatching { classLoader.close() }
    }
}

/**
 * Plugin Loader — ~/.wiiiv/plugins 디렉토리의 JAR을 스캔하여 SPI로 로드
 *
 * @property pluginsDir 플러그인 JAR 디렉토리 (기본: ~/.wiiiv/plugins)
 */
class PluginLoader(
    private val pluginsDir: String = defaultPluginsDir()
) {
    /**
     * plugins 디렉토리의 모든 JAR을 로드
     *
     * @return 로드된 플러그인 목록 (실패한 JAR은 로그 출력 후 스킵)
     */
    fun loadAll(): List<LoadedPlugin> {
        val dir = File(pluginsDir)
        if (!dir.isDirectory) {
            println("[PLUGIN] Plugins directory not found: $pluginsDir (OK — no plugins)")
            return emptyList()
        }

        val jars = dir.listFiles { f -> f.extension == "jar" } ?: emptyArray()
        if (jars.isEmpty()) {
            println("[PLUGIN] No plugin JARs in: $pluginsDir")
            return emptyList()
        }

        return jars.mapNotNull { jarFile ->
            try {
                loadPlugin(jarFile)
            } catch (e: Exception) {
                println("[PLUGIN] Failed to load ${jarFile.name}: ${e.message}")
                null
            }
        }
    }

    private fun loadPlugin(jarFile: File): LoadedPlugin? {
        val classLoader = URLClassLoader(
            arrayOf(jarFile.toURI().toURL()),
            javaClass.classLoader
        )

        val plugins = ServiceLoader.load(WiiivPlugin::class.java, classLoader).toList()
        if (plugins.isEmpty()) {
            println("[PLUGIN] No WiiivPlugin SPI in: ${jarFile.name}")
            classLoader.close()
            return null
        }

        val plugin = plugins.first()

        // plugin.yaml 로드 (표시용 메타 — 없어도 동작)
        val manifest = loadManifest(classLoader)

        // PluginConfig 구성: System.getenv → defaults
        val config = buildConfig(plugin.pluginId)
        val executor = plugin.createExecutor(config)

        return LoadedPlugin(
            plugin = plugin,
            executor = executor,
            meta = plugin.executorMeta(),
            actions = plugin.actions(),
            manifest = manifest,
            jarPath = jarFile.absolutePath,
            classLoader = classLoader
        )
    }

    private fun loadManifest(classLoader: URLClassLoader): PluginManifest? {
        return try {
            val stream = classLoader.getResourceAsStream("META-INF/wiiiv/plugin.yaml")
                ?: return null
            val content = stream.bufferedReader().use { it.readText() }
            PluginManifest.parseSimpleYaml(content)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * PluginConfig 구성: System.getenv에서 플러그인 관련 환경변수 수집
     *
     * 규칙: WIIIV_PLUGIN_{PLUGIN_ID}_{KEY} → env["KEY"]
     * 예: WIIIV_PLUGIN_WEBHOOK_DEFAULT_TIMEOUT_MS → env["DEFAULT_TIMEOUT_MS"]
     */
    private fun buildConfig(pluginId: String): PluginConfig {
        val prefix = "WIIIV_PLUGIN_${pluginId.uppercase()}_"
        val env = System.getenv()
            .filter { (k, _) -> k.startsWith(prefix) }
            .map { (k, v) -> k.removePrefix(prefix) to v }
            .toMap()
        return PluginConfig(env = env)
    }

    companion object {
        fun defaultPluginsDir(): String {
            val home = System.getProperty("user.home") ?: "."
            return "$home/.wiiiv/plugins"
        }
    }
}
