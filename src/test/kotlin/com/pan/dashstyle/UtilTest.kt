package com.pan.dashstyle

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
        assertEquals("fooBar", Util.kebabToCamel("foo-bar"))
    }

    @Test
    fun `kebabToCamel - 多段 kebab-case`() {
        assertEquals("fooBarBaz", Util.kebabToCamel("foo-bar-baz"))
    }

    @Test
    fun `kebabToCamel - 单段无连字符`() {
        assertEquals("foo", Util.kebabToCamel("foo"))
    }

    @Test
    fun `kebabToCamel - 空字符串`() {
        assertEquals("", Util.kebabToCamel(""))
    }

    @Test
    fun `kebabToCamel - 开头连字符`() {
        assertEquals("fooBar", Util.kebabToCamel("-foo-bar"))
    }

    @Test
    fun `kebabToCamel - 单字符段`() {
        assertEquals("aBC", Util.kebabToCamel("a-b-c"))
    }

    @Test
    fun `kebabToCamel - 含数字`() {
        assertEquals("col12Row", Util.kebabToCamel("col12-row"))
    }

    @Test
    fun `kebabToCamel - 已经是 camelCase 原样返回`() {
        // 注意：当前实现如果输入没有 - 会原样返回
        assertEquals("fooBar", Util.kebabToCamel("fooBar"))
    }

    // ===================== camelToKebab 测试 =====================

    @Test
    fun `camelToKebab - 普通 camelCase`() {
        assertEquals("foo-bar", Util.camelToKebab("fooBar"))
    }

    @Test
    fun `camelToKebab - 多段大写`() {
        assertEquals("foo-bar-baz", Util.camelToKebab("fooBarBaz"))
    }

    @Test
    fun `camelToKebab - 全小写无大写`() {
        assertEquals("foobar", Util.camelToKebab("foobar"))
    }

    @Test
    fun `camelToKebab - 首字母大写`() {
        // 首字母大写时会有前导 - 但被 removePrefix 去掉了
        assertEquals("foo-bar", Util.camelToKebab("FooBar"))
    }

    @Test
    fun `camelToKebab - 空字符串`() {
        assertEquals("", Util.camelToKebab(""))
    }

    @Test
    fun `camelToKebab - 单字符`() {
        assertEquals("a", Util.camelToKebab("A"))
    }

    @Test
    fun `camelToKebab - 连续大写`() {
        // ABC → -A-B-C → 去前缀 → A-B-C
        assertEquals("A-B-C", Util.camelToKebab("ABC"))
    }

    @Test
    fun `camelToKebab - 含数字`() {
        assertEquals("col12-row", Util.camelToKebab("col12Row"))
    }

    @Test
    fun `camelToKebab - 已经是 kebab-case`() {
        // 如果输入本身是 kebab-case，里面没有大写字母，就原样输出
        assertEquals("foo-bar", Util.camelToKebab("foo-bar"))
    }

    // ===================== kebabToCamel ↔ camelToKebab 互逆测试 =====================

    @Test
    fun `roundtrip - kebab to camel and back`() {
        val original = "foo-bar-baz-qux"
        val camel = Util.kebabToCamel(original)
        val kebabBack = Util.camelToKebab(camel)
        assertEquals(original, kebabBack)
    }

    @Test
    fun `roundtrip - camel to kebab and back (lowercase start)`() {
        val original = "fooBarBazQux"
        val kebab = Util.camelToKebab(original)
        val camelBack = Util.kebabToCamel(kebab)
        assertEquals(original, camelBack)
    }

    // ===================== normalizeColor 测试 =====================

    @Test
    fun `normalizeColor - HEX6 原样`() {
        assertEquals("#1a2b3c", Util.normalizeColor("#1a2b3c"))
        assertEquals("#1a2b3c", Util.normalizeColor("#1A2B3C"))
    }

    @Test
    fun `normalizeColor - HEX3 展开成 HEX6`() {
        assertEquals("#ffffff", Util.normalizeColor("#fff"))
        assertEquals("#aabbcc", Util.normalizeColor("#AbC"))
    }

    @Test
    fun `normalizeColor - HEX8 保留 alpha`() {
        assertEquals("#112233aa", Util.normalizeColor("#112233aa"))
        assertEquals("#112233cc", Util.normalizeColor("#112233CC"))
    }

    @Test
    fun `normalizeColor - HEX8 alpha FF 降为 HEX6`() {
        assertEquals("#112233", Util.normalizeColor("#112233ff"))
    }

    @Test
    fun `normalizeColor - rgb 三参数`() {
        assertEquals("rgb(255,0,0)", Util.normalizeColor("rgb(255,0,0)"))
        assertEquals("rgb(255,0,0)", Util.normalizeColor("rgb( 255 , 0 , 0 )"))
        assertEquals("rgb(255,0,0)", Util.normalizeColor("RGB(255 0 0)"))
    }

    @Test
    fun `normalizeColor - rgba alpha 为 1 降为 rgb`() {
        assertEquals("rgb(10,20,30)", Util.normalizeColor("rgba(10,20,30,1)"))
        assertEquals("rgb(10,20,30)", Util.normalizeColor("rgba(10, 20, 30, 1.000)"))
    }

    @Test
    fun `normalizeColor - rgba alpha 百分比`() {
        assertEquals("rgba(10,20,30,0.5)", Util.normalizeColor("rgba(10,20,30,50%)"))
    }

    @Test
    fun `normalizeColor - hsl 和 hsla`() {
        assertEquals("hsl(120,50%,50%)", Util.normalizeColor("hsl(120, 50%, 50%)"))
        assertEquals("hsla(120,50%,50%,0.25)", Util.normalizeColor("hsla(120, 50%, 50%, 0.25)"))
    }

    @Test
    fun `normalizeColor - 命名颜色不区分大小写`() {
        assertEquals("red", Util.normalizeColor("red"))
        assertEquals("white", Util.normalizeColor("WHITE"))
        assertEquals("cornflowerblue", Util.normalizeColor("CornFlowerBlue"))
    }

    @Test
    fun `normalizeColor - 非法输入返回 null`() {
        assertEquals(null, Util.normalizeColor(""))
        assertEquals(null, Util.normalizeColor("#ggg"))
        assertEquals(null, Util.normalizeColor("foobarcolor"))
        assertEquals(null, Util.normalizeColor("rgba(1,2,3)")) // 少参数
    }

    // ===================== suggestColorVarName 测试 =====================

    @Test
    fun `suggestColorVarName - 蓝色生成 primary`() {
        val name = Util.suggestColorVarName("#2563eb", emptySet(), 0)
        assertEquals("--color-primary", name)
    }

    @Test
    fun `suggestColorVarName - 红色生成 danger`() {
        assertEquals("--color-danger", Util.suggestColorVarName("#dc2626", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 绿色生成 success`() {
        assertEquals("--color-success", Util.suggestColorVarName("#16a34a", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 黄色生成 warning`() {
        assertEquals("--color-warning", Util.suggestColorVarName("#eab308", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 灰度生成 neutral 族`() {
        assertEquals("--color-dark", Util.suggestColorVarName("#111111", emptySet(), 0))
        assertEquals("--color-neutral", Util.suggestColorVarName("#808080", emptySet(), 0))
        assertEquals("--color-bg-light", Util.suggestColorVarName("#f5f5f5", emptySet(), 0))
    }

    @Test
    fun `suggestColorVarName - 冲突自动加数字`() {
        val existing = setOf("--color-primary")
        assertEquals("--color-primary-2", Util.suggestColorVarName("#2563eb", existing, 0))
    }

    @Test
    fun `suggestColorVarName - 无可匹配语义时走 index`() {
        assertEquals("--color-1", Util.suggestColorVarName("hsl(123,50%,50%)", emptySet(), 0))
        assertEquals("--color-3", Util.suggestColorVarName("hsla(123,50%,50%,0.5)", emptySet(), 2))
    }

    // ===================== scanColorsInText 测试 =====================

    @Test
    fun `scanColorsInText - 多种格式混合并按 offset 排序`() {
        val css = """
            .a { color: #fff; border: 1px solid red; background: rgb(255, 0, 0); }
            .b { color: hsl(0, 0%, 50%); }
        """.trimIndent()
        val out = Util.scanColorsInText(css)
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
        val out = Util.scanColorsInText(".x { color: #abcdef; }")
        assertEquals(1, out.size, "只应该识别出一个 HEX6")
        assertEquals("#abcdef", out[0].second)
    }

    @Test
    fun `scanColorsInText - 命名颜色边界检查，前缀带连字符的单词不匹配`() {
        // non-color 包含 color 单词片段但不是命名颜色，要排除
        val out = Util.scanColorsInText(".a { x-blah-foo: 1; color: white-space: nowrap; }")
        val colors = out.filter { it.second == "white" }
        // 上面字符串是 "white-space"，white 后面跟 '-'，按边界规则应排除
        assertEquals(0, colors.size)
    }

    @Test
    fun `scanColorsInText - 空文本返回空`() {
        assertEquals(emptyList(), Util.scanColorsInText(""))
        assertEquals(emptyList(), Util.scanColorsInText(".no-color-here { font-size: 14px; }"))
    }

    @Test
    fun `scanColorsInText - 返回的 range 能正确还原原始字符串`() {
        val src = "x: rgba(1,2,3,0.5); y: #12345678;"
        val out = Util.scanColorsInText(src)
        for ((orig, _, r) in out) {
            assertEquals(orig, src.substring(r.first, r.last + 1))
        }
    }
}
