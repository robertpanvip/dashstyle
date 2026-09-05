package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * JSON→CSS 转换边缘用例补充（无 IDE 沙箱）：
 * shorthand 数组、transform 数组形态、style={{...}} 完整属性、
 * unsupported object 占位、font-family 引号、undefined、unitless 前缀特判。
 */
class JsonToCssEdgeCaseTest {

    private fun convert(raw: String): String? = JsonToCssCopyPastePreProcessor.Util.convertOrNull(raw)

    // ===================== shorthand 数组 ======================

    @Test
    fun `shorthand 数组展开为空格连接`() {
        assertEquals("  padding: 1px 2px 3px;\n", convert("""{"padding":[1,2,3]}"""))
    }

    @Test
    fun `camelCase shorthand 同样展开`() {
        assertEquals("  border-radius: 4px 8px;\n", convert("""{"borderRadius":[4,8]}"""))
    }

    // ===================== transform 数组 ======================

    @Test
    fun `transform 数组形态生成函数序列`() {
        assertEquals("  transform: scale(2) translateX(10px);\n", convert("""{"transform":[{"scale":2},{"translateX":10}]}"""))
    }

    @Test
    fun `transform 角度函数自动补 deg`() {
        assertEquals("  transform: rotate(45deg);\n", convert("""{"transform":[{"rotate":45}]}"""))
    }

    @Test
    fun `transform 字符串形态保持原样`() {
        assertEquals("  transform: translateX(10px) scale(1.5);\n", convert("""{"transform":"translateX(10px) scale(1.5)"}"""))
    }

    // ===================== style={{...}} 完整形态 ======================

    @Test
    fun `React style 双花括号完整属性可转换`() {
        assertEquals("  color: red;\n", convert("style={{ color: 'red' }}"))
    }

    @Test
    fun `JS 对象字面量单引号与尾随逗号可转换`() {
        assertEquals("  color: red;\n  margin: 4px;\n", convert("{ color: 'red', margin: 4, }"))
    }

    // ===================== 特殊值处理 ======================

    @Test
    fun `对象值生成 unsupported 占位注释`() {
        val css = convert("""{"typography":{"fontSize":14}}""")
        assertNotNull(css)
        assertTrue(css!!.contains("typography: /* unsupported object value"), css)
    }

    @Test
    fun `font-family 含空格自动加双引号`() {
        assertEquals("  font-family: \"Helvetica Neue\";\n", convert("""{"fontFamily":"Helvetica Neue"}"""))
    }

    @Test
    fun `undefined 值被替换为 null 并跳过该行`() {
        assertEquals("  margin: 4px;\n", convert("{ color: undefined, margin: 4 }"))
    }

    @Test
    fun `unitless 前缀特判不加 px`() {
        assertEquals("  animation-iteration-count: 3;\n", convert("""{"animationIterationCount":3}"""))
        assertEquals("  border-image-outset: 2;\n", convert("""{"borderImageOutset":2}"""))
    }

    @Test
    fun `负数补 px`() {
        assertEquals("  left: -5px;\n", convert("""{"left":-5}"""))
    }

    @Test
    fun `非 shorthand 数组空格连接各元素`() {
        assertEquals("  transition: opacity 0.2s ease;\n", convert("""{"transition":["opacity 0.2s","ease"]}"""))
    }

    // ===================== 归一化边界 ======================

    @Test
    fun `纯文本不是样式对象返回 null`() {
        assertNull(convert("hello world"))
        assertNull(convert(""))
        assertNull(convert("{ broken json"))
    }
}
