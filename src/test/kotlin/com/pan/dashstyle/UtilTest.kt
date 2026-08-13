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
}
