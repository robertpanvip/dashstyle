package com.pan.dashstyle

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.action.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * CssUnitAssistant 纯逻辑单测。
 *
 * 覆盖：px↔rem↔vw 互转、clamp() 解析与取值、calc() 简化、非法输入返回 null。
 */
class CssUnitAssistantTest {

    // ---------- 换算提示 ----------

    @Test
    fun `parse length with unit`() {
        assertEquals(CssUnitAssistant.Length(12.0, "px"), CssUnitAssistant.parseLength("12px"))
        assertEquals(CssUnitAssistant.Length(1.5, "rem"), CssUnitAssistant.parseLength("1.5rem"))
        assertEquals(CssUnitAssistant.Length(3.0, "vw"), CssUnitAssistant.parseLength(" 3vw "))
    }

    @Test
    fun `parse length rejects invalid input`() {
        assertNull(CssUnitAssistant.parseLength("auto"))
        assertNull(CssUnitAssistant.parseLength("12"))
        assertNull(CssUnitAssistant.parseLength("px"))
        assertNull(CssUnitAssistant.parseLength(null))
    }

    @Test
    fun `convert px to rem and vw`() {
        // 12px：rem = 12/16 = 0.75，vw = 12*100/1440 ≈ 0.83
        assertEquals("12px ≈ 0.75rem ≈ 0.83vw", CssUnitAssistant.convertHint("12px"))
    }

    @Test
    fun `convert rem to px and vw`() {
        // 1rem = 16px，vw = 16*100/1440 ≈ 1.11
        assertEquals("16px ≈ 1.11vw", CssUnitAssistant.convertHint("1rem"))
    }

    @Test
    fun `convert vw to px and rem`() {
        // 10vw = 144px，rem = 144/16 = 9
        assertEquals("144px ≈ 9rem", CssUnitAssistant.convertHint("10vw"))
    }

    // ---------- clamp() ----------

    @Test
    fun `clamp resolves preferred when within range`() {
        // 2vw = 28.8px，在 [16, 32] 内
        assertEquals("28.8px (vw)", CssUnitAssistant.clampHint("clamp(16px, 2vw, 32px)"))
    }

    @Test
    fun `clamp clamps to max when preferred exceeds range`() {
        // 2vw = 28.8px > 24px → 夹到 max
        assertEquals("24px (clamped to max)", CssUnitAssistant.clampHint("clamp(16px, 2vw, 24px)"))
    }

    @Test
    fun `clamp clamps to min when preferred below range`() {
        // 0.5vw = 7.2px < 16px → 夹到 min
        assertEquals("16px (clamped to min)", CssUnitAssistant.clampHint("clamp(16px, 0.5vw, 24px)"))
    }

    @Test
    fun `clamp rejects malformed input`() {
        assertNull(CssUnitAssistant.clampHint("clamp(16px, 2vw)"))
        assertNull(CssUnitAssistant.clampHint("clamp(auto, 2vw, 24px)"))
        assertNull(CssUnitAssistant.clampHint("not-clamp(16px, 2vw, 24px)"))
    }

    // ---------- calc() ----------

    @Test
    fun `calc adds px and rem`() {
        // 10px + 2rem = 10 + 32 = 42px
        assertEquals("42px", CssUnitAssistant.calcHint("calc(10px + 2rem)"))
    }

    @Test
    fun `calc divides vw`() {
        // 100vw / 10 = 1440 / 10 = 144px
        assertEquals("144px", CssUnitAssistant.calcHint("calc(100vw / 10)"))
    }

    @Test
    fun `calc multiplies and respects precedence`() {
        // 2 * (10px + 5px) = 30px
        assertEquals("30px", CssUnitAssistant.calcHint("calc(2 * (10px + 5px))"))
    }

    @Test
    fun `calc rejects invalid expression`() {
        assertNull(CssUnitAssistant.calcHint("calc(10px + auto)"))
        assertNull(CssUnitAssistant.calcHint("calc(10px +)"))
    }
}