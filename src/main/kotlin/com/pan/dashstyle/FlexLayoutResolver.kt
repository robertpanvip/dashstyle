package com.pan.dashstyle

import kotlin.math.roundToInt

/**
 * CSS Flex 布局预览 —— 纯逻辑层。
 *
 * 把 CSS 的 flex 相关属性值解析成结构化的枚举，并计算在给定容器 (W×H) 内
 * 摆放 childCount 个子项的 (x, y, w, h)。这部分不依赖任何 IntelliJ SDK，
 * 便于用纯 JVM 单测覆盖（与 verify.sh 里的独立验证器思路一致）。
 */
object FlexLayoutResolver {

    enum class Direction { ROW, ROW_REVERSE, COLUMN, COLUMN_REVERSE }
    enum class Justify { FLEX_START, FLEX_END, CENTER, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }
    enum class Align { FLEX_START, FLEX_END, CENTER, STRETCH, BASELINE }

    data class Props(
        val direction: Direction = Direction.ROW,
        val justify: Justify = Justify.FLEX_START,
        val align: Align = Align.STRETCH,
        val gap: Int = 0,
        val wrap: Boolean = false,
        val childCount: Int = 3
    )

    data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

    fun parseDirection(s: String?, fallback: Direction = Direction.ROW): Direction = when (s?.trim()?.lowercase()) {
        "row" -> Direction.ROW
        "row-reverse" -> Direction.ROW_REVERSE
        "column" -> Direction.COLUMN
        "column-reverse" -> Direction.COLUMN_REVERSE
        else -> fallback
    }

    fun parseJustify(s: String?, fallback: Justify = Justify.FLEX_START): Justify = when (s?.trim()?.lowercase()) {
        "flex-end" -> Justify.FLEX_END
        "center" -> Justify.CENTER
        "space-between" -> Justify.SPACE_BETWEEN
        "space-around" -> Justify.SPACE_AROUND
        "space-evenly" -> Justify.SPACE_EVENLY
        else -> fallback
    }

    fun parseAlign(s: String?, fallback: Align = Align.STRETCH): Align = when (s?.trim()?.lowercase()) {
        "flex-start" -> Align.FLEX_START
        "flex-end" -> Align.FLEX_END
        "center" -> Align.CENTER
        "baseline" -> Align.BASELINE
        else -> fallback
    }

    /** 从 gap / row-gap 值里提取第一个数字（px），并钳制到 [0, 40]。 */
    fun parseGap(s: String?): Int {
        val v = s?.trim()?.lowercase() ?: return 0
        val digits = Regex("""(\d+(?:\.\d+)?)""").find(v)?.groupValues?.get(1) ?: return 0
        return digits.toFloat().roundToInt().coerceIn(0, 40)
    }

    /**
     * 在 W×H 的容器内摆放 childCount 个子项。返回每个子项的 (x, y, w, h)。
     * 算法与交互预览面板里的摆位逻辑保持一致（justify 沿主轴、align 沿交叉轴）。
     */
    fun place(props: Props, W: Int, H: Int): List<Box> {
        val n = props.childCount.coerceAtLeast(1)
        val row = props.direction == Direction.ROW || props.direction == Direction.ROW_REVERSE
        val P = 4
        val cw = if (row) 26 else 20
        val ch = if (row) 20 else 26
        val avail = if (row) W else H
        var gap = props.gap.coerceIn(0, 40)
        val item = if (row) cw else ch
        var start = P
        val total = n * item + (n - 1) * gap
        when (props.justify) {
            Justify.CENTER -> start = P + (avail - total) / 2
            Justify.FLEX_END -> start = avail - P - total
            Justify.SPACE_BETWEEN -> if (n > 1) gap = (avail - 2 * P - n * item) / (n - 1).coerceAtLeast(1)
            Justify.SPACE_AROUND -> {
                gap = (avail - 2 * P - n * item) / n
                start = P + gap / 2
            }
            Justify.SPACE_EVENLY -> {
                gap = (avail - 2 * P - n * item) / (n + 1)
                start = P + gap
            }
            Justify.FLEX_START -> {}
        }
        val boxes = ArrayList<Box>(n)
        for (i in 0 until n) {
            val pos = start + i * (item + gap)
            var x: Int
            var y: Int
            var w = cw
            var h = ch
            if (row) {
                x = pos
                when (props.align) {
                    Align.CENTER, Align.BASELINE -> y = (H - ch) / 2
                    Align.FLEX_END -> y = H - P - ch
                    Align.STRETCH -> { y = P; h = (H - 2 * P).coerceAtLeast(1) }
                    Align.FLEX_START -> y = P
                }
            } else {
                y = pos
                when (props.align) {
                    Align.CENTER, Align.BASELINE -> x = (W - cw) / 2
                    Align.FLEX_END -> x = W - P - cw
                    Align.STRETCH -> { x = P; w = (W - 2 * P).coerceAtLeast(1) }
                    Align.FLEX_START -> x = P
                }
            }
            boxes.add(Box(x, y, w, h))
        }
        return boxes
    }
}