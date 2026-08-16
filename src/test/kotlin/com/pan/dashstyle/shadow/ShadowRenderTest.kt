package com.pan.dashstyle.shadow

import com.pan.dashstyle.ShadowRender
import com.pan.dashstyle.ShadowResolver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage

/**
 * ShadowRender 专门单测 —— 阴影位图渲染关键函数（gutter 图标与放大 tooltip 共用）。
 *
 * 纯 AWT 渲染，不依赖 IDE 沙箱：验证输出图像的类型/尺寸/透明度分布，
 * 以及 tooltip 副标题 colorStops 的格式。
 */
class ShadowRenderTest {

    private fun layer(
        offsetX: Double = 0.0,
        offsetY: Double = 0.0,
        blur: Double = 4.0,
        spread: Double = 0.0,
        inset: Boolean = false,
        color: java.awt.Color? = java.awt.Color(0, 0, 0, 150)
    ) = ShadowResolver.Layer(inset, offsetX, offsetY, blur, spread, color)

    // ---------- render ----------

    @Test
    fun `render produces argb image of requested size`() {
        val img = ShadowRender.render(32, 32, listOf(layer()))
        assertNotNull(img)
        assertEquals(32, img.width)
        assertEquals(32, img.height)
        assertEquals(BufferedImage.TYPE_INT_ARGB, img.type)
    }

    @Test
    fun `render image is not fully transparent`() {
        val img = ShadowRender.render(32, 32, listOf(layer()))
        var opaque = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y).ushr(24) and 0xff) > 0) opaque++
            }
        }
        // 渲染垫了 PANEL_BG 底，因此几乎全部像素都应可见
        assertTrue(opaque > img.width * img.height / 2)
    }

    @Test
    fun `render with blurred outer shadow does not throw`() {
        val img = ShadowRender.render(64, 48, listOf(layer(blur = 12.0, spread = 3.0)))
        assertNotNull(img)
        assertEquals(64, img.width)
    }

    @Test
    fun `render with inset shadow does not throw`() {
        val img = ShadowRender.render(32, 32, listOf(layer(inset = true, blur = 6.0)))
        assertNotNull(img)
        assertEquals(32, img.height)
    }

    @Test
    fun `render with multiple layers does not throw`() {
        val layers = listOf(
            layer(offsetX = 2.0, offsetY = 2.0, blur = 8.0),
            layer(inset = true, blur = 2.0, color = java.awt.Color(255, 0, 0, 120))
        )
        val img = ShadowRender.render(48, 48, layers)
        assertNotNull(img)
        assertEquals(48, img.width)
    }

    @Test
    fun `render with uncolored layer uses fallback`() {
        val img = ShadowRender.render(32, 32, listOf(layer(color = null)))
        assertNotNull(img)
    }

    @Test
    fun `render with empty layers still produces panel background`() {
        val img = ShadowRender.render(32, 32, emptyList())
        assertNotNull(img)
        var opaque = 0
        for (y in 0 until img.height) {
            for (x in 0 until img.width) {
                if ((img.getRGB(x, y).ushr(24) and 0xff) > 0) opaque++
            }
        }
        assertTrue(opaque > 0)
    }

    // ---------- colorStops（tooltip 副标题） ----------

    @Test
    fun `colorStops empty layers returns empty string`() {
        assertEquals("", ShadowRender.colorStops(emptyList()))
    }

    @Test
    fun `colorStops formats hex for colored layer`() {
        val s = ShadowRender.colorStops(listOf(layer(color = java.awt.Color(255, 0, 0))))
        assertTrue(s.contains("#ff0000"), "got: $s")
    }

    @Test
    fun `colorStops marks inset layers`() {
        val s = ShadowRender.colorStops(listOf(layer(inset = true, color = java.awt.Color(0, 0, 0))))
        assertTrue(s.contains("inset"), "got: $s")
        assertTrue(s.contains("#000000"), "got: $s")
    }

    @Test
    fun `colorStops joins multiple layers`() {
        val s = ShadowRender.colorStops(listOf(
            layer(color = java.awt.Color(255, 0, 0)),
            layer(color = java.awt.Color(0, 0, 255))
        ))
        assertTrue(s.contains("#ff0000") && s.contains("#0000ff"), "got: $s")
    }
}