package com.pan.dashstyle

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

/**
 * DashStyle 插件的多语言 resource bundle 入口。
 *
 * - 默认文案（英文）：messages/DashStyleBundle.properties
 * - 简体中文：messages/DashStyleBundle_zh_CN.properties
 *
 * 使用 DynamicBundle（而非传统 ResourceBundle）：跟随 IDE「外观与行为 → 系统设置 → 语言与地区」
 * 的语言设置动态解析，切换语言无需重启 IDE。
 * 默认（英文）bundle 同时供 plugin.xml 的 %key% 引用（见 <resource-bundle> 声明）。
 */
object DashStyleBundle {

    private const val BUNDLE = "messages.DashStyleBundle"

    private val INSTANCE: DynamicBundle = DynamicBundle(DashStyleBundle::class.java, BUNDLE)

    @JvmStatic
    fun message(@PropertyKey(resourceBundle = BUNDLE) key: String, vararg params: Any): String =
        INSTANCE.getMessage(key, *params)
}
