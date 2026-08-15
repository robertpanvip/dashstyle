package com.pan.dashstyle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * ShadowResolver / CssColorParser 纯逻辑单测（不依赖 IDE 沙箱）。
 */
class ShadowResolverTest {

    // ---------- 颜色解析 ----------

    @Test
    fun `parse hex colors`() {
        assertEquals(Color(255, 0, 0), CssColorParser.parse("#f00"))
        assertEquals(Color(255, 0, 0), CssColorParser.parse("#ff0000"))
        assertEquals(Color(255, 0, 0, 128), CssColorParser.parse("#ff000080"))
        assertEquals(Color(0, 0, 0, 0), CssColorParser.parse("transparent"))
    }

    @Test
    fun `parse rgb and rgba colors`() {
        assertEquals(Color(10, 20, 30), CssColorParser.parse("rgb(10,20,30)"))
        assertEquals(Color(10, 20, 30, 127), CssColorParser.parse("rgba(10,20,30,0.5)"))
        assertEquals(Color(255, 0, 0), CssColorParser.parse("rgb(100%, 0%, 0%)"))
    }

    @Test
    fun `parse named colors`() {
        assertEquals(Color.RED, CssColorParser.parse("red"))
        assertEquals(Color.WHITE, CssColorParser.parse("White"))
        assertNull(CssColorParser.parse("rebeccapurple")) // AWT 无此命名
        assertNull(CssColorParser.parse("not-a-color"))
    }

    // ---------- 阴影解析 ----------

    @Test
    fun `parse single outer shadow with color`() {
        val layers = ShadowResolver.parse("2px 4px 6px 8px rgba(0,0,0,0.5)")
        assertEquals(1, layers.size)
        val l = layers[0]
        assertFalse(l.inset)
        assertEquals(2.0, l.offsetX)
        assertEquals(4.0, l.offsetY)
        assertEquals(6.0, l.blur)
        assertEquals(8.0, l.spread)
        assertEquals(Color(0, 0, 0, 127), l.color)
    }

    @Test
    fun `parse inset shadow`() {
        val layers = ShadowResolver.parse("inset 2px 2px 4px #000")
        val l = layers[0]
        assertTrue(l.inset)
        assertEquals(2.0, l.offsetX)
        assertEquals(4.0, l.blur)
    }

    @Test
    fun `parse multiple comma separated layers`() {
        val layers = ShadowResolver.parse("0 1px 3px #000, 0 0 2px 1px rgba(0,0,255,.3)")
        assertEquals(2, layers.size)
        assertEquals(3.0, layers[0].blur)
        assertEquals(1.0, layers[1].spread)
    }

    @Test
    fun `parse unitless offsets treat as px`() {
        val l = ShadowResolver.parse("0 1px 2px gray")[0]
        assertEquals(0.0, l.offsetX)
        assertEquals(1.0, l.offsetY)
    }

    @Test
    fun `parse rem offsets convert to px`() {
        val l = ShadowResolver.parse("1rem 2rem 0 0 #000")[0]
        assertEquals(16.0, l.offsetX)
        assertEquals(32.0, l.offsetY)
    }

    @Test
    fun `none and empty return empty list`() {
        assertTrue(ShadowResolver.parse("none").isEmpty())
        assertTrue(ShadowResolver.parse("").isEmpty())
        assertTrue(ShadowResolver.parse(null).isEmpty())
    }

    @Test
    fun `unparseable layer with var returns null`() {
        val layers = ShadowResolver.parse("var(--x) 2px 3px #000")
        assertTrue(layers.isEmpty())
    }

    @Test
    fun `missing offsets returns empty`() {
        assertTrue(ShadowResolver.parse("red").isEmpty())
    }

    @Test
    fun `text-shadow supports spread-less layer`() {
        val l = ShadowResolver.parse("1px 2px 3px rgba(0,0,0,0.5)")[0]
        assertEquals(0.0, l.spread)
        assertNotNull(l.color)
    }
}