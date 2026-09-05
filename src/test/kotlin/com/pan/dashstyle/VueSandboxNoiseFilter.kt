package com.pan.dashstyle

import com.intellij.openapi.application.AccessToken
import com.intellij.testFramework.LoggedErrorProcessor

/**
 * 测试沙箱里 Vue 插件语言服务噪音过滤器。
 *
 * 背景：Gradle 下载的 WebStorm 发行版里，语言服务资源位于 plugins/javascript-plugin/
 * jsLanguageServicesImpl，而 JSPluginPathManager 按 "plugins/JavaScriptLanguage/resources/"
 * 布局查找（安装器版布局）→ VueLspServerPackageDescriptor 构造抛 ExceptionInInitializerError，
 * 错误经后台线程/EDT 异步落到任意正在运行的测试头上（TestLoggerAssertionError）。
 * LSP 服务对 headless 测试无意义（启动外部 vue-tsc 进程），这里精准吞掉相关错误。
 *
 * 注意：EDT rethrow 路径（addFileToProject 把 .ts/.vue 写入真实 VFS 触发
 * TypeScriptCompilerServiceVfsListener → VueLsp 初始化）会绕过本过滤器，
 * 测试 fixture 请一律用 configureByText（内存 LightVirtualFile，不触发该监听器）。
 */
object VueSandboxNoiseFilter {

    fun install(): AccessToken = LoggedErrorProcessor.executeWith(object : LoggedErrorProcessor() {
        override fun processError(
            message: String,
            detailMessage: String,
            details: Array<out String>,
            t: Throwable?
        ): Set<LoggedErrorProcessor.Action> {
            val text = "$message $detailMessage " +
                (t?.let { "${it.javaClass.name}: ${it.message}" } ?: "") + " " +
                (t?.cause?.let { "${it.javaClass.name}: ${it.message}" } ?: "")
            // 结构性判断：异常链任一层的调用栈经过 org.jetbrains.vuejs /
            // JSPluginPathManager，即视为 Vue 插件语言服务在下载版布局下的已知噪音
            val fromVueServices = generateSequence(t) { it.cause }.any { th ->
                th.stackTrace.any {
                    it.className.startsWith("org.jetbrains.vuejs") ||
                        it.className == "com.intellij.lang.javascript.psi.util.JSPluginPathManager"
                }
            }
            return if (fromVueServices ||
                text.contains("VueLsp") ||
                text.contains("vuejs.lang.typescript.service") ||
                text.contains("should be lib directory") ||
                text.contains("jsLanguageServicesImpl")
            ) {
                emptySet()
            } else {
                super.processError(message, detailMessage, details, t)
            }
        }
    })
}
