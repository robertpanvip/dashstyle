package com.pan.dashstyle

import kotlin.math.roundToInt

/**
 * CSS 尺寸/单位换算助手 —— 纯逻辑层（不依赖 IDE SDK）。
 *
 * 在长度值旁显示换算信息：
 *  - px ↔ rem ↔ vw 互转（可配置根字号 [rootFontPx] 与视口宽度 [viewportPx]）；
 *  - `clamp(min, pref, max)` 的解析结果（在给定视口下实际取值）；
 *  - `calc()` 的简化值（支持 + - * / 与括号、px/rem/vw 混算，返回合并后的 px）。
 *
 * 所有解析对非法输入返回 null，绝不抛异常，便于 inlay 安静地只在能算时显示。
 */
object CssUnitAssistant {

    const val DEFAULT_ROOT_FONT_PX = 16.0
    const val DEFAULT_VIEWPORT_PX = 1440.0

    /** 单个数值+单位。 */
    data class Length(val value: Double, val unit: String) {
        val isPx: Boolean get() = unit == "px"
        val isRem: Boolean get() = unit == "rem"
        val isVw: Boolean get() = unit == "vw"
    }

    /** clamp() 的三段。 */
    data class Clamp(val min: Length, val preferred: Length, val max: Length)

    /** 解析 `12px` / `1.5rem` / `3vw` 等；无单位或非法返回 null。 */
    fun parseLength(s: String?): Length? {
        val t = s?.trim() ?: return null
        val m = Regex("""^([+-]?\d*\.?\d+)\s*(px|rem|vw|em|%)$""").find(t) ?: return null
        return Length(m.groupValues[1].toDouble(), m.groupValues[2].lowercase())
    }

    /** 把某单位换算成 px。 */
    fun toPx(length: Length, root: Double = DEFAULT_ROOT_FONT_PX, viewport: Double = DEFAULT_VIEWPORT_PX): Double =
        when (length.unit) {
            "px" -> length.value
            "rem", "em" -> length.value * root
            "vw" -> length.value * viewport / 100.0
            "%" -> length.value * viewport / 100.0
            else -> length.value
        }

    /** px → 目标单位。 */
    fun fromPx(px: Double, unit: String, root: Double = DEFAULT_ROOT_FONT_PX, viewport: Double = DEFAULT_VIEWPORT_PX): Double =
        when (unit) {
            "px" -> px
            "rem", "em" -> px / root
            "vw" -> px * 100.0 / viewport
            "%" -> px * 100.0 / viewport
            else -> px
        }

    /** 把一个值格式化为最简可读字符串（整数去小数点，最多保留两位小数，去尾零）。 */
    fun format(v: Double): String {
        val rounded = (v * 100.0).roundToInt() / 100.0
        if (rounded == rounded.toLong().toDouble()) return rounded.toLong().toString()
        // 去掉尾随的 0，保留必要的小数精度（如 0.75 / 0.83 / 28.8）
        val s = rounded.toString()
        return if (s.endsWith("0")) s.trimEnd('0').trimEnd('.') else s
    }

    /**
     * 生成换算提示文本。给定一个长度值，返回形如
     *  `12px ≈ 0.75rem ≈ 0.83vw` 的字符串；若无法换算返回 null。
     */
    fun convertHint(
        s: String?,
        root: Double = DEFAULT_ROOT_FONT_PX,
        viewport: Double = DEFAULT_VIEWPORT_PX
    ): String? {
        val len = parseLength(s) ?: return null
        val px = toPx(len, root, viewport)
        val parts = ArrayList<String>()
        parts.add("${format(px)}px")
        if (!len.isRem) parts.add("${format(fromPx(px, "rem", root, viewport))}rem")
        if (len.unit != "vw" && len.unit != "%") parts.add("${format(fromPx(px, "vw", root, viewport))}vw")
        return parts.joinToString(" ≈ ")
    }

    /** 解析 `clamp(16px, 2vw, 24px)`，返回三段；非法返回 null。 */
    fun parseClamp(s: String?): Clamp? {
        val t = s?.trim() ?: return null
        if (!t.startsWith("clamp(") || !t.endsWith(")")) return null
        val inner = t.substring(6, t.length - 1)
        val parts = splitTopLevel(inner)
        if (parts.size != 3) return null
        val min = parseLength(parts[0]) ?: return null
        val pref = parseLength(parts[1]) ?: return null
        val max = parseLength(parts[2]) ?: return null
        return Clamp(min, pref, max)
    }

    /**
     * clamp() 在 [viewport] 下的实际取值提示：
     *  `clamp(16px, 2vw, 24px) → 28.8px (2vw)`，夹到区间时说明来源。
     */
    fun clampHint(
        s: String?,
        root: Double = DEFAULT_ROOT_FONT_PX,
        viewport: Double = DEFAULT_VIEWPORT_PX
    ): String? {
        val c = parseClamp(s) ?: return null
        val minPx = toPx(c.min, root, viewport)
        val prefPx = toPx(c.preferred, root, viewport)
        val maxPx = toPx(c.max, root, viewport)
        val clamped = prefPx.coerceIn(minPx, maxPx)
        val source = when (clamped) {
            prefPx -> c.preferred.unit
            minPx -> "clamped to min"
            else -> "clamped to max"
        }
        return "${format(clamped)}px ($source)"
    }

    /**
     * 简化 calc() 表达式。支持 + - * / 与括号，运算数须带单位（px/rem/vw），
     * 结果统一换算为 px。返回简化后的 px 字符串；无法解析返回 null。
     */
    fun calcHint(
        s: String?,
        root: Double = DEFAULT_ROOT_FONT_PX,
        viewport: Double = DEFAULT_VIEWPORT_PX
    ): String? {
        val t = s?.trim() ?: return null
        if (!t.startsWith("calc(") || !t.endsWith(")")) return null
        val inner = t.substring(5, t.length - 1)
        val px = try {
            evalExpr(tokenizeExpr(inner), 0, root, viewport).first
        } catch (e: Exception) {
            return null
        }
        return "${format(px)}px"
    }

    // ------------------------------------------------------------------
    // calc() 求值
    // ------------------------------------------------------------------
    private fun splitTopLevel(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c == ',' && depth == 0 -> { out.add(sb.toString().trim()); sb.setLength(0) }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString().trim())
        return out
    }

    /** 词法切分为 [数字, 运算, 括号] token。 */
    private fun tokenizeExpr(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var idx = 0
        while (idx < s.length) {
            val c = s[idx]
            when {
                c.isWhitespace() -> { flushToken(sb, out); idx++ }
                c.isDigit() || c == '.' || (c == '-' && peekIsNumberStart(s, idx)) -> { sb.append(c); idx++ }
                c in "+-*/" -> { flushToken(sb, out); out.add(c.toString()); idx++ }
                c == '(' || c == ')' -> { flushToken(sb, out); out.add(c.toString()); idx++ }
                else -> { sb.append(c); idx++ }
            }
        }
        flushToken(sb, out)
        return out
    }

    private fun peekIsNumberStart(s: String, idx: Int): Boolean {
        val next = s.getOrNull(idx + 1) ?: return false
        return next.isDigit() || next == '.'
    }

    private fun flushToken(sb: StringBuilder, out: ArrayList<String>) {
        if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) }
    }

    /** 递归下降求值，返回 (值, 下一 token 下标)。tok 可能含单位后缀（如 "12px"）。 */
    private fun evalExpr(toks: List<String>, start: Int, root: Double, viewport: Double): Pair<Double, Int> {
        val first = evalTerm(toks, start, root, viewport)
        var acc = first.first
        var i = first.second
        while (i < toks.size) {
            val op = toks[i]
            if (op != "+" && op != "-") break
            val rhs = evalTerm(toks, i + 1, root, viewport)
            i = rhs.second
            acc = if (op == "+") acc + rhs.first else acc - rhs.first
        }
        return Pair(acc, i)
    }

    private fun evalTerm(toks: List<String>, start: Int, root: Double, viewport: Double): Pair<Double, Int> {
        val first = evalFactor(toks, start, root, viewport)
        var acc = first.first
        var i = first.second
        while (i < toks.size) {
            val op = toks[i]
            if (op != "*" && op != "/") break
            val rhs = evalFactor(toks, i + 1, root, viewport)
            i = rhs.second
            if (op == "*") acc *= rhs.first else acc /= rhs.first
        }
        return Pair(acc, i)
    }

    private fun evalFactor(toks: List<String>, start: Int, root: Double, viewport: Double): Pair<Double, Int> {
        val tok = toks[start]
        if (tok == "(") {
            val inner = evalExpr(toks, start + 1, root, viewport)
            // 跳过右括号
            var idx = inner.second
            if (idx < toks.size && toks[idx] == ")") idx++
            return Pair(inner.first, idx)
        }
        val len = parseLength(tok)
        if (len != null) return Pair(toPx(len, root, viewport), start + 1)
        // css calc 允许除以/乘以纯数字（如 calc(100vw / 10)）
        val bare = tok.toDoubleOrNull()
        if (bare != null) return Pair(bare, start + 1)
        throw IllegalArgumentException("not a length: $tok")
    }
}