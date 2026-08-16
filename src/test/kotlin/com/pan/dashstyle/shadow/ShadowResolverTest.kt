package com.pan.dashstyle.shadow

import com.pan.dashstyle.ShadowResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.Color

/**
 * ShadowResolver 专门单测 —— box-shadow / text-shadow 解析关键函数。
 *
 * 覆盖：单/多层阴影、inset、offset/blur/spread、单位换算（px/rem/无单位）、
 * 颜色提取、以及无法解析的动态值（var/calc）与非法输入。
 */
class ShadowResolverTest {

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
        // rgba(0,0,0,0.5): 0.5 * 255 = 127.5 → Math.round = 128
        assertEquals(Color(0, 0, 0, 128), l.color)
    }

    @Test
    fun `parse inset shadow`() {
        val l = ShadowResolver.parse("inset 2px 2px 4px #000").first()
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
        // rgba(0,0,255,.3): 0.3 * 255 = 76.5 → Math.round = 77
        assertEquals(Color(0, 0, 255, 77), layers[1].color)
    }

    @Test
    fun `parse unitless offsets treat as px`() {
        val l = ShadowResolver.parse("0 1px 2px gray").first()
        assertEquals(0.0, l.offsetX)
        assertEquals(1.0, l.offsetY)
    }

    @Test
    fun `parse rem offsets convert to px`() {
        val l = ShadowResolver.parse("1rem 2rem 0 0 #000").first()
        assertEquals(16.0, l.offsetX)
        assertEquals(32.0, l.offsetY)
    }

    @Test
    fun `uncolored layer uses null color`() {
        val l = ShadowResolver.parse("2px 2px 4px").first()
        assertNotNull(l)
        assertTrue(l.color == null)
    }

    @Test
    fun `text-shadow supports spread-less layer`() {
        val l = ShadowResolver.parse("1px 2px 3px rgba(0,0,0,0.5)").first()
        assertEquals(0.0, l.spread)
        assertNotNull(l.color)
    }

    // ---------- 非法 / 边界 ----------

    @Test
    fun `none and empty return empty list`() {
        assertTrue(ShadowResolver.parse("none").isEmpty())
        assertTrue(ShadowResolver.parse("None").isEmpty())
        assertTrue(ShadowResolver.parse("").isEmpty())
        assertTrue(ShadowResolver.parse(null).isEmpty())
        assertTrue(ShadowResolver.parse("   ").isEmpty())
    }

    @Test
    fun `unparseable layer with var is dropped`() {
        assertTrue(ShadowResolver.parse("var(--x) 2px 3px #000").isEmpty())
    }

    @Test
    fun `missing offsets returns empty`() {
        assertTrue(ShadowResolver.parse("red").isEmpty())
    }

    @Test
    fun `whitespace around commas tolerated`() {
        val layers = ShadowResolver.parse("0 0 2px #000 , 1px 1px 1px red")
        assertEquals(2, layers.size)
    }
}