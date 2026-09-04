package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

/**
 * 纯 Kotlin 单元测试 - 不需要 IntelliJ Platform 环境
 * 测试 Util.Companion 中的纯函数（无 PSI 依赖的部分）
 */
class UtilTest {

    // ===================== kebabToCamel 测试 =====================

    @Test
    fun `kebabToCamel - 普通 kebab-case`() {
        assertEquals("fooBar", NamingUtil.kebabToCamel("foo-bar"))
    }

    @Test
    fun `kebabToCamel - 多段 kebab-case`() {
        assertEquals("fooBarBaz", NamingUtil.kebabToCamel("foo-bar-baz"))
    }

    @Test
    fun `kebabToCamel - 单段无连字符`() {
        assertEquals("foo", NamingUtil.kebabToCamel("foo"))
    }

    @Test
    fun `kebabToCamel - 空字符串`() {
        assertEquals("", NamingUtil.kebabToCamel(""))
    }

    @Test
    fun `kebabToCamel - 开头连字符`() {
        assertEquals("fooBar", NamingUtil.kebabToCamel("-foo-bar"))
    }

    @Test
    fun `kebabToCamel - 单字符段`() {
        assertEquals("aBC", NamingUtil.kebabToCamel("a-b-c"))
    }

    @Test
    fun `kebabToCamel - 含数字`() {
        assertEquals("col12Row", NamingUtil.kebabToCamel("col12-row"))
    }

    @Test
    fun `kebabToCamel - 已经是 camelCase 原样返回`() {
        // 注意：当前实现如果输入没有 - 会原样返回
        assertEquals("fooBar", NamingUtil.kebabToCamel("fooBar"))
    }

    // ===================== camelToKebab 测试 =====================

    @Test
    fun `camelToKebab - 普通 camelCase`() {
        assertEquals("foo-bar", NamingUtil.camelToKebab("fooBar"))
    }

    @Test
    fun `camelToKebab - 多段大写`() {
        assertEquals("foo-bar-baz", NamingUtil.camelToKebab("fooBarBaz"))
    }

    @Test
    fun `camelToKebab - 全小写无大写`() {
        assertEquals("foobar", NamingUtil.camelToKebab("foobar"))
    }

    @Test
    fun `camelToKebab - 首字母大写`() {
        // 首字母大写时会有前导 - 但被 removePrefix 去掉了
        assertEquals("foo-bar", NamingUtil.camelToKebab("FooBar"))
    }

    @Test
    fun `camelToKebab - 空字符串`() {
        assertEquals("", NamingUtil.camelToKebab(""))
    }

    @Test
    fun `camelToKebab - 单字符`() {
        assertEquals("a", NamingUtil.camelToKebab("A"))
    }

    @Test
    fun `camelToKebab - 连续大写`() {
        // ABC → -A-B-C → 去前缀 → A-B-C
        assertEquals("A-B-C", NamingUtil.camelToKebab("ABC"))
    }

    @Test
    fun `camelToKebab - 含数字`() {
        assertEquals("col12-row", NamingUtil.camelToKebab("col12Row"))
    }

    @Test
    fun `camelToKebab - 已经是 kebab-case`() {
        // 如果输入本身是 kebab-case，里面没有大写字母，就原样输出
        assertEquals("foo-bar", NamingUtil.camelToKebab("foo-bar"))
    }

    // ===================== kebabToCamel ↔ camelToKebab 互逆测试 =====================

    @Test
    fun `roundtrip - kebab to camel and back`() {
        val original = "foo-bar-baz-qux"
        val camel = NamingUtil.kebabToCamel(original)
        val kebabBack = NamingUtil.camelToKebab(camel)
        assertEquals(original, kebabBack)
    }

    @Test
    fun `roundtrip - camel to kebab and back (lowercase start)`() {
        val original = "fooBarBazQux"
        val kebab = NamingUtil.camelToKebab(original)
        val camelBack = NamingUtil.kebabToCamel(kebab)
        assertEquals(original, camelBack)
    }

    // ===================== normalizeColor 测试 =====================

    @Test
    fun `normalizeColor - HEX6 原样`() {
        assertEquals("#1a2b3c", ColorUtil.normalizeColor("#1a2b3c"))
        assertEquals("#1a2b3c", ColorUtil.normalizeColor("#1A2B3C"))
    }

    @Test
    fun `normalizeColor - HEX3 展开成 HEX6`() {
        assertEquals("#ffffff", ColorUtil.normalizeColor("#fff"))
        assertEquals("#aabbcc", ColorUtil.normalizeColor("#AbC"))
    }

    @Test
    fun `normalizeColor - HEX8 保留 alpha`() {
        assertEquals("#112233aa", ColorUtil.normalizeColor("#112233aa"))
        assertEquals("#112233cc", ColorUtil.normalizeColor("#112233CC"))
    }

    @Test
    fun `normalizeColor - HEX8 alpha FF 降为 HEX6`() {
        assertEquals("#112233", ColorUtil.normalizeColor("#112233ff"))
    }

    @Test
    fun `normalizeColor - rgb 三参数`() {
        assertEquals("rgb(255,0,0)", ColorUtil.normalizeColor("rgb(255,0,0)"))
        assertEquals("rgb(255,0,0)", ColorUtil.normalizeColor("rgb( 255 , 0 , 0 )"))
        assertEquals("rgb(255,0,0)", ColorUtil.normalizeColor("RGB(255 0 0)"))
    }

    @Test
    fun `normalizeColor - rgba alpha 为 1 降为 rgb`() {
        assertEquals("rgb(10,20,30)", ColorUtil.normalizeColor("rgba(10,20,30,1)"))
        assertEquals("rgb(10,20,30)", ColorUtil.normalizeColor("rgba(10, 20, 30, 1.000)"))
    }

    @Test
    fun `normalizeColor - rgba alpha 百分比`() {
        assertEquals("rgba(10,20,30,0.5)", ColorUtil.normalizeColor("rgba(10,20,30,50%)"))
    }

    @Test
    fun `normalizeColor - hsl 和 hsla`() {
        assertEquals("hsl(120,50%,50%)", ColorUtil.normalizeColor("hsl(120, 50%, 50%)"))
        assertEquals("hsla(120,50%,50%,0.25)", ColorUtil.normalizeColor("hsla(120, 50%, 50%, 0.25)"))
    }

    @Test
    fun `normalizeColor - 命名颜色不区分大小写`() {
        assertEquals("red", ColorUtil.normalizeColor("red"))
        assertEquals("white", ColorUtil.normalizeColor("WHITE"))
        assertEquals("cornflowerblue", ColorUtil.normalizeColor("CornFlowerBlue"))
    }

    @Test
    fun `normalizeColor - 非法输入返回 null`() {
        assertEquals(null, ColorUtil.normalizeColor(""))
        assertEquals(null, ColorUtil.normalizeColor("#ggg"))
        assertEquals(null, ColorUtil.normalizeColor("foobarcolor"))
        assertEquals(null, ColorUtil.normalizeColor("rgba(1,2,3)")) // 少参数
    }

    // ===================== suggestColorVarName 测试 =====================

    @Test
    fun `suggestColorVarName - 蓝色生成 primary`() {
        val name = ColorUtil.suggestColorVarName("#2563eb", emptySet(), 0)
        assertEquals("--color-primary", name)
    }

    @Test
    fun `suggestColorVarName - 红色生成 danger`() {
        assertEquals("--color-danger", ColorUtil.suggestColorVarName("#dc2626", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 绿色生成 success`() {
        assertEquals("--color-success", ColorUtil.suggestColorVarName("#16a34a", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 黄色生成 warning`() {
        assertEquals("--color-warning", ColorUtil.suggestColorVarName("#eab308", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 灰度生成 neutral 族`() {
        assertEquals("--color-dark", ColorUtil.suggestColorVarName("#111111", emptySet(), 0))
        assertEquals("--color-neutral", ColorUtil.suggestColorVarName("#808080", emptySet(), 0))
        assertEquals("--color-bg-light", ColorUtil.suggestColorVarName("#f5f5f5", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 冲突自动加数字`() {
        val existing = setOf("--color-primary")
        assertEquals("--color-primary-2", ColorUtil.suggestColorVarName("#2563eb", existing, 0))
    }

    @Test
    fun `suggestColorVarName - 无可匹配语义时走 index`() {
        assertEquals("--color-1", ColorUtil.suggestColorVarName("hsl(123,50%,50%)", emptySet(), 0))
        assertEquals("--color-3", ColorUtil.suggestColorVarName("hsla(123,50%,50%,0.5)", emptySet(), 2))
    }

    // ===================== scanColorsInText 测试 =====================

    @Test
    fun `scanColorsInText - 多种格式混合并按 offset 排序`() {
        val css = """
            .a { color: #fff; border: 1px solid red; background: rgb(255, 0, 0); }
            .b { color: hsl(0, 0%, 50%); }
        """.trimIndent()
        val out = ColorUtil.scanColorsInText(css)
        val normalized = out.map { it.second }
        assert("#ffffff" in normalized) { "HEX3 没展开" }
        assert("red" in normalized) { "命名颜色 red 没识别" }
        assert("rgb(255,0,0)" in normalized) { "rgb() 没识别" }
        assert("hsl(0,0%,50%)" in normalized) { "hsl() 没识别" }
        // 必须按 offset 递增
        val offsets = out.map { it.third.first }
        assertEquals(offsets, offsets.sorted())
    }

    @Test
    fun `scanColorsInText - 不重复重叠消耗 HEX3 和 HEX6 冲突避免`() {
        val out = ColorUtil.scanColorsInText(".x { color: #abcdef; }")
        assertEquals(1, out.size, "只应该识别出一个 HEX6")
        assertEquals("#abcdef", out[0].second)
    }

    @Test
    fun `scanColorsInText - 命名颜色边界检查，前缀带连字符的单词不匹配`() {
        // non-color 包含 color 单词片段但不是命名颜色，要排除
        val out = ColorUtil.scanColorsInText(".a { x-blah-foo: 1; color: white-space: nowrap; }")
        val colors = out.filter { it.second == "white" }
        // 上面字符串是 "white-space"，white 后面跟 '-'，按边界规则应排除
        assertEquals(0, colors.size)
    }

    @Test
    fun `scanColorsInText - 空文本返回空`() {
        assertEquals(emptyList(), ColorUtil.scanColorsInText(""))
        assertEquals(emptyList(), ColorUtil.scanColorsInText(".no-color-here { font-size: 14px; }"))
    }

    @Test
    fun `scanColorsInText - 返回的 range 能正确还原原始字符串`() {
        val src = "x: rgba(1,2,3,0.5); y: #12345678;"
        val out = ColorUtil.scanColorsInText(src)
        for ((orig, _, r) in out) {
            assertEquals(orig, src.substring(r.first, r.last + 1))
        }
    }
}
