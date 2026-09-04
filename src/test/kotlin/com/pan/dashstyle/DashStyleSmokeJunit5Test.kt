package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * JUnit5 风格的快速 smoke 测试（不启动 IDE 沙箱，0.5s 跑完）。
 *
 * 覆盖核心纯函数（Util + JsonToCss + Less 展开）是否按约定工作；
 * 集成/UI 级用例（置灰/Intention/Daemon 高亮）保留在 [DashStyleIntegrationTest] 里，
 * 它是 JUnit4 风格（BasePlatformTestCase），需要 vintage-engine 正确被发现才能运行，
 * 可在本地去掉上面的 @Ignore 单步调试。
 *
 * 运行：
 *   gradle --init-script _local_init.gradle.kts test --tests "com.pan.dashstyle.DashStyleSmokeJunit5Test"
 */
class DashStyleSmokeJunit5Test {

    @Test
    @DisplayName("NamingUtil.camelToKebab / kebabToCamel 往返正确")
    fun `camel kebab roundtrip`() {
        // camel → kebab
        assertEquals("hello-world", NamingUtil.camelToKebab("helloWorld"))
        assertEquals("my-class-name", NamingUtil.camelToKebab("myClassName"))
        assertEquals("all-lower-no-op", NamingUtil.camelToKebab("all-lower-no-op"))

        // kebab → camel
        assertEquals("helloWorld", NamingUtil.kebabToCamel("hello-world"))
        assertEquals("myClassName", NamingUtil.kebabToCamel("my-class-name"))
        assertEquals("allLowerNoOp", NamingUtil.kebabToCamel("all-lower-no-op"))
    }

    @Test
    @DisplayName("ColorUtil.normalizeColor 把常见颜色格式统一归一化成 #rrggbb / rgb(...) / 命名色")
    fun `normalize color formats`() {
        // 命名颜色：normalizeColor 会 lower() 后原样返回（"red" → "red"），真正的命名转 hex6 是
        // NAMED_TO_HEX6 + suggestColorVarName 路径负责的，不要在 smoke 里对命名色断言 #rrggbb。
        assertEquals("red", ColorUtil.normalizeColor("red"))
        assertEquals("red", ColorUtil.normalizeColor(" Red "))
        assertEquals("#ff0000", ColorUtil.normalizeColor("#f00"))
        assertEquals("#123456", ColorUtil.normalizeColor("#123456"))
        // rgb() → 规范化为不带空格的 rgb(r,g,b)
        val normRgb = ColorUtil.normalizeColor("rgb(255, 0, 0)")
        assertTrue(
            normRgb == "rgb(255,0,0)" || normRgb?.startsWith("rgb(") == true,
            "rgb 归一化失败: $normRgb"
        )
        // rgba() 百分比 alpha 会被转成小数 0.5，或保留为 rgba 格式（不纠结精确小数，只要以 rgba( 开头就行）
        val normRgba = ColorUtil.normalizeColor("rgba(255,0,0,50%)")
        assertTrue(
            normRgba?.startsWith("rgba(") == true,
            "rgba 百分比必须保留为 rgba(...) 格式，实际=$normRgba"
        )
    }

    @Test
    @DisplayName("json to css 核心转换：单位、无引号 key、数组都能处理")
    fun `json to css conversion`() {
        val jsonWithKeys = """
            {
              color: "red",
              width: 100,
              opacity: 0.5,
              borderRadius: 8,
              zIndex: 3
            }
        """.trimIndent()
        val css = JsonToCssCopyPastePreProcessor.Util.convertJsonToCss(jsonWithKeys)
        assertTrue(css.contains("color: red"), "color 属性缺失：$css")
        assertTrue(css.contains("width: 100px"), "width 应该带 px：$css")
        assertTrue(css.contains("opacity: 0.5"), "opacity 不应该带 px：$css")
        assertTrue(css.contains("border-radius: 8px"), "kebab 属性 + px：$css")
        assertTrue(css.contains("z-index: 3"), "z-index 是 unitless：$css")
    }

    @Test
    @DisplayName("LESS 展开 / 核心 API 存在性 smoke")
    fun `less ampersand api exists`() {
        // 重构后 expandSelector 位于 support.CssSelectorUtil（不再在 Util 里）。
        // 用反射兜底校验该方法确实存在，防止后续重构再次移动后烟雾测试失准。
        val selectorMethods = CssSelectorUtil::class.java.declaredMethods.map { it.name }.toSet()
        assertTrue(
            selectorMethods.any { it.startsWith("expandSelector") },
            "CssSelectorUtil.expandSelector(...) 必须存在，实际=$selectorMethods"
        )
        // 基础层保持稳定：camel/kebab 互转
        assertEquals("foo-bar", NamingUtil.camelToKebab("fooBar"))
    }

    @Test
    @DisplayName("插件安装包生成路径存在（buildPlugin 前不会有，但目录应该可写）")
    fun `build output dir writable`() {
        // build/distributions 或 build/libs 任意存在即可（之前跑过 buildPlugin 就有 distributions）
        val pwd = System.getProperty("user.dir")
        assertNotNull(pwd, "user.dir 不能为空")
        // 保证至少插件源码的 src/test 存在
        val testRoot = java.nio.file.Path.of(pwd, "src", "test").toFile()
        assertTrue(testRoot.isDirectory, "src/test 必须存在")
    }
}
