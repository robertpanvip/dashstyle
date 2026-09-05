package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 纯函数边缘用例补充（无 IDE 沙箱）：
 * CssUnitAssistant 的 em/%/带符号/自定义参数、calc 减法与嵌套、clamp 边界；
 * TailwindClassResolver.find 的精确匹配语义；
 * NamingUtil 连字符边界；ColorUtil 通道越界与词边界。
 */
class EdgeCaseUnitTest {

    // ===================== CssUnitAssistant：em / % 单位 ======================

    @Test
    fun `convertHint em 输入按 root 换算`() {
        // 2em = 32px；32/16 = 2rem；32*100/1440 ≈ 2.22vw
        assertEquals("32px ≈ 2rem ≈ 2.22vw", CssUnitAssistant.convertHint("2em"))
    }

    @Test
    fun `convertHint 百分号输入按 viewport 换算且不追加 vw 段`() {
        // 50% = 720px（viewport 1440）；720/16 = 45rem；% 输入不重复给 vw 段
        assertEquals("720px ≈ 45rem", CssUnitAssistant.convertHint("50%"))
    }

    @Test
    fun `toPx-fromPx em 与百分号单位`() {
        assertEquals(500.0, CssUnitAssistant.toPx(CssUnitAssistant.Length(50.0, "%"), viewport = 1000.0), 1e-9)
        assertEquals(50.0, CssUnitAssistant.fromPx(500.0, "vw", viewport = 1000.0), 1e-9)
        assertEquals(2.0, CssUnitAssistant.fromPx(32.0, "em", root = 16.0), 1e-9)
    }

    // ===================== CssUnitAssistant：带符号数值 ======================

    @Test
    fun `parseLength 接受带正负号的数值`() {
        assertEquals(CssUnitAssistant.Length(12.0, "px"), CssUnitAssistant.parseLength("+12px"))
        assertEquals(CssUnitAssistant.Length(-3.0, "vw"), CssUnitAssistant.parseLength("-3vw"))
    }

    @Test
    fun `convertHint 负值换算保持符号`() {
        // -3vw = -43.2px；-43.2/16 = -2.7rem；vw 输入不追加 vw 段
        assertEquals("-43.2px ≈ -2.7rem", CssUnitAssistant.convertHint("-3vw"))
    }

    @Test
    fun `parseLength 单位大小写敏感`() {
        assertNull(CssUnitAssistant.parseLength("12PX"))
        assertNull(CssUnitAssistant.parseLength("12Rem"))
    }

    // ===================== CssUnitAssistant：自定义参数 ======================

    @Test
    fun `自定义 root 字号参与换算`() {
        // root=20：1rem = 20px；rem 输入不追加 rem 段；20*100/1440 ≈ 1.39vw
        assertEquals("20px ≈ 1.39vw", CssUnitAssistant.convertHint("1rem", root = 20.0))
    }

    @Test
    fun `自定义 viewport 参与换算`() {
        // viewport=1000：10vw = 100px；100/16 = 6.25rem；vw 输入不追加 vw 段
        assertEquals("100px ≈ 6.25rem", CssUnitAssistant.convertHint("10vw", viewport = 1000.0))
    }

    // ===================== CssUnitAssistant：calc 边缘 ======================

    @Test
    fun `calc 支持减法`() {
        assertEquals("80px", CssUnitAssistant.calcHint("calc(100px - 20px)"))
    }

    @Test
    fun `calc 支持括号嵌套与乘法`() {
        assertEquals("30px", CssUnitAssistant.calcHint("calc((10px + 5px) * 2)"))
    }

    @Test
    fun `calc 除以纯数字`() {
        // 100vw / 10 = 1440/10
        assertEquals("144px", CssUnitAssistant.calcHint("calc(100vw / 10)"))
    }

    @Test
    fun `calc rem 乘法按 root 换算`() {
        assertEquals("96px", CssUnitAssistant.calcHint("calc(2rem * 3)"))
    }

    @Test
    fun `calc 空内容返回 null`() {
        assertNull(CssUnitAssistant.calcHint("calc()"))
        assertNull(CssUnitAssistant.calcHint("not-calc(10px)"))
    }

    // ===================== CssUnitAssistant：clamp 边界 ======================

    @Test
    fun `clamp preferred 恰好等于 min 时显示原始单位`() {
        // viewport=1600：1vw = 16px == min
        assertEquals("16px (vw)", CssUnitAssistant.clampHint("clamp(16px, 1vw, 24px)", viewport = 1600.0))
    }

    @Test
    fun `clamp preferred 超出上限时夹到 max`() {
        // viewport=1600：2vw = 32px > 24px
        assertEquals("24px (clamped to max)", CssUnitAssistant.clampHint("clamp(16px, 2vw, 24px)", viewport = 1600.0))
    }

    @Test
    fun `clamp 区间内取 preferred 并显示单位`() {
        // viewport=1600：2vw = 32px ∈ [16,48]
        assertEquals("32px (vw)", CssUnitAssistant.clampHint("clamp(1rem, 2vw, 48px)", viewport = 1600.0))
    }

    @Test
    fun `clamp min 大于 max 时抛出已知异常`() {
        // Kotlin coerceIn 要求 min<=max；该行为目前会向上传播（调用方 inlay 层需自行兜底）
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException::class.java) {
            CssUnitAssistant.clampHint("clamp(24px, 2vw, 16px)")
        }
    }

    // ===================== CssUnitAssistant：format ======================

    @Test
    fun `format 整数化与两位小数`() {
        assertEquals("2", CssUnitAssistant.format(2.0))
        assertEquals("0.08", CssUnitAssistant.format(0.075))
        assertEquals("28.8", CssUnitAssistant.format(28.8))
        assertEquals("-0.5", CssUnitAssistant.format(-0.5))
    }

    // ===================== TailwindClassResolver.find 精确语义 ======================

    @Test
    fun `find 空串与空白串返回 null`() {
        assertNull(TailwindClassResolver.find(""))
        assertNull(TailwindClassResolver.find("   "))
    }

    @Test
    fun `find 是精确匹配而非前缀`() {
        assertNull(TailwindClassResolver.find("flex-"), "前缀式误用应返回 null（语义为精确相等）")
    }

    @Test
    fun `search 结果严格前缀匹配`() {
        assertTrue(TailwindClassResolver.search("-mt-4").all { it.name.startsWith("-mt-4") })
        val spaceY = TailwindClassResolver.search("space-y")
        assertTrue(spaceY.isNotEmpty(), "space-y 前缀应有候选")
        assertTrue(spaceY.all { it.name.startsWith("space-y") })
    }

    // ===================== NamingUtil 连字符边界 ======================

    @Test
    fun `camelToKebab 尾部连字符保留`() {
        assertEquals("foo-", NamingUtil.camelToKebab("foo-"))
    }

    @Test
    fun `kebabToCamel 连续连字符只大写一次`() {
        assertEquals("fooBar", NamingUtil.kebabToCamel("foo--bar"))
    }

    @Test
    fun `kebabToCamel 前导连字符丢弃`() {
        assertEquals("lead", NamingUtil.kebabToCamel("-lead"))
    }

    // ===================== ColorUtil 越界与词边界 ======================

    @Test
    fun `normalizeColor rgb 通道越界返回 null`() {
        assertNull(ColorUtil.normalizeColor("rgb(256,0,0)"))
        assertNull(ColorUtil.normalizeColor("rgb(-1,0,0)"))
    }

    @Test
    fun `normalizeColor 非法十六进制长度返回 null`() {
        assertNull(ColorUtil.normalizeColor("#ffff")) // HEX4 不支持
        assertNull(ColorUtil.normalizeColor("#ff00"))
    }

    @Test
    fun `normalizeColor 命名颜色大小写不敏感`() {
        assertEquals("red", ColorUtil.normalizeColor("Red"))
        assertEquals("aliceblue", ColorUtil.normalizeColor("AliceBlue"))
    }

    @Test
    fun `normalizeColor hsl 饱和度越界返回 null 但 hue 不设界`() {
        assertNull(ColorUtil.normalizeColor("hsl(0,150%,50%)"))
        assertEquals("hsl(360,100%,50%)", ColorUtil.normalizeColor("hsl(360,100%,50%)"))
    }

    @Test
    fun `scanColorsInText 连字符前缀词不算颜色`() {
        // border-red 是一个完整 token，不会误识别出 red
        assertTrue(ColorUtil.scanColorsInText("border-red").isEmpty())
    }

    @Test
    fun `scanColorsInText 大写命名颜色命中并归一`() {
        val hits = ColorUtil.scanColorsInText("color: Red;")
        assertEquals(1, hits.size)
        assertEquals("red", hits[0].second)
    }
}
