package io.wiiiv.plugin

import java.io.Closeable

/**
 * Plugin Registry — 로드된 플러그인 관리
 *
 * Closeable: 서버 종료 시 모든 플러그인의 ClassLoader를 정리
 */
class PluginRegistry(
    private val plugins: List<LoadedPlugin>
) : Closeable {

    /** pluginId로 조회 */
    fun get(pluginId: String): LoadedPlugin? =
        plugins.find { it.plugin.pluginId == pluginId }

    /** 모든 로드된 플러그인 */
    fun all(): List<LoadedPlugin> = plugins

    /** 로드된 플러그인 수 */
    val size: Int get() = plugins.size

    /** 모든 플러그인의 ClassLoader 정리 */
    override fun close() {
        plugins.forEach { it.close() }
    }
}
