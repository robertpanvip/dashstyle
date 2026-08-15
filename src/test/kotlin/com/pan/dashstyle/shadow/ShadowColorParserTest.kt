package com.pan.dashstyle.shadow

import com.pan.dashstyle.CssColorParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * CssColorParser 专门单测 —— 阴影预览依赖的颜色解析关键函数。
 *
 * 覆盖：hex（3/4/6/8 位）、rgb/rgba（数值与百分比）、hsl/hsla、
 * 命名色、透明色，以及各种非法输入返回 null。
 */
class ShadowColorParserTest {

    // ---------- hex ----------

    @Test
    fun `parse 3 and 4 digit hex`() {
        assertEquals(Color(255, 0, 0), CssColorParser.parse("#f00"))
        assertEquals(Color(255, 255, 255, 128), CssColorParser.parse("#ffff80"))
    }

    @Test
    fun `parse 6 digit hex`() {
        assertEquals(Color(255, 0, 0), CssColorParser.parse("#ff0000"))
        assertEquals(Color(0, 128, 255), CssColorParser.parse("#0080ff"))
    }

    @Test
    fun `parse 8 digit hex keeps alpha`() {
        assertEquals(Color(255, 0, 0, 128), CssColorParser.parse("#ff000080"))
        assertEquals(Color(0, 0, 0, 0), CssColorParser.parse("#00000000"))
    }

    // ---------- rgb / rgba ----------

    @Test
    fun `parse rgb with numeric channels`() {
        assertEquals(Color(10, 20, 30), CssColorParser.parse("rgb(10,20,30)"))
        assertEquals(Color(255, 255, 255), CssColorParser.parse("rgb(255,255,255)"))
    }

    @Test
    fun `parse rgba with decimal alpha`() {
        assertEquals(Color(10, 20, 30, 127), CssColorParser.parse("rgba(10,20,30,0.5)"))
        assertEquals(Color(10, 20, 30, 255), CssColorParser.parse("rgba(10,20,30,1)"))
        assertEquals(Color(10, 20, 30, 0), CssColorParser.parse("rgba(10,20,30,0)"))
    }

    @Test
    fun `parse rgb with percentage channels`() {
        assertEquals(Color(255, 0, 0), CssColorParser.parse("rgb(100%, 0%, 0%)"))
        assertEquals(Color(128, 128, 128), CssColorParser.parse("rgb(50%, 50%, 50%)"))
    }

    // ---------- hsl / hsla ----------

    @Test
    fun `parse hsl`() {
        assertEquals(Color(255, 0, 0), CssColorParser.parse("hsl(0, 100%, 50%)"))
        assertEquals(Color(0, 0, 0), CssColorParser.parse("hsl(0, 0%, 0%)"))
    }

    @Test
    fun `parse hsla keeps alpha`() {
        assertEquals(Color(255, 0, 0, 153), CssColorParser.parse("hsla(0, 100%, 50%, 0.6)"))
    }

    // ---------- 命名色 / 透明 / 非法 ----------

    @Test
    fun `parse named colors`() {
        assertEquals(Color.RED, CssColorParser.parse("red"))
        assertEquals(Color.WHITE, CssColorParser.parse("White"))
        assertEquals(Color.BLACK, CssColorParser.parse("black"))
    }

    @Test
    fun `parse transparent`() {
        assertEquals(Color(0, 0, 0, 0), CssColorParser.parse("transparent"))
    }

    @Test
    fun `parse invalid or unsupported returns null`() {
        assertNull(CssColorParser.parse("rebeccapurple")) // AWT 无此命名色
        assertNull(CssColorParser.parse("not-a-color"))
        assertNull(CssColorParser.parse(""))
        assertNull(CssColorParser.parse(null))
        assertNull(CssColorParser.parse("#ff00")) // 非法 hex 长度
        assertNull(CssColorParser.parse("rgb(1,2)")) // 缺通道
    }
}