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
    enum class AlignContent { FLEX_START, FLEX_END, CENTER, STRETCH, SPACE_BETWEEN, SPACE_AROUND, SPACE_EVENLY }

    data class Props(
        val direction: Direction = Direction.ROW,
        val justify: Justify = Justify.FLEX_START,
        val align: Align = Align.STRETCH,
        val alignContent: AlignContent = AlignContent.FLEX_START,
        val gap: Int = 0,
        val wrap: Boolean = false,
        val childCount: Int = 3,
        /**
         * 逐子项的交叉轴对齐覆盖（align-self）。下标 i 对应第 i 个子项；
         * null 表示沿用容器级 [align]。长度不足的部分视为沿用容器对齐。
         */
        val alignSelfs: List<Align?> = emptyList()
    )

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

    fun parseAlignContent(s: String?, fallback: AlignContent = AlignContent.STRETCH): AlignContent = when (s?.trim()?.lowercase()) {
        "flex-start" -> AlignContent.FLEX_START
        "flex-end" -> AlignContent.FLEX_END
        "center" -> AlignContent.CENTER
        "space-between" -> AlignContent.SPACE_BETWEEN
        "space-around" -> AlignContent.SPACE_AROUND
        "space-evenly" -> AlignContent.SPACE_EVENLY
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
    // (Props, W, H) → 摆放结果的有界 LRU 缓存：place 是确定性纯函数，结果可复用
    private data class PlaceKey(val props: Props, val w: Int, val h: Int)

    private val placeCache = object : LinkedHashMap<PlaceKey, List<Box>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<PlaceKey, List<Box>>?): Boolean = size > 512
    }

    fun place(props: Props, W: Int, H: Int): List<Box> {
        val key = PlaceKey(props, W, H)
        synchronized(placeCache) {
            placeCache[key]?.let { return it }
        }
        val boxes = computePlace(props, W, H)
        synchronized(placeCache) {
            placeCache[key] = boxes
        }
        return boxes
    }

    private fun computePlace(props: Props, W: Int, H: Int): List<Box> {
        val n = props.childCount.coerceAtLeast(1)
        val row = props.direction == Direction.ROW || props.direction == Direction.ROW_REVERSE
        val reverse = props.direction == Direction.ROW_REVERSE || props.direction == Direction.COLUMN_REVERSE
        val P = 4
        val cw = if (row) 26 else 20
        val ch = if (row) 20 else 26
        val mainAvail = if (row) W else H
        val crossAvail = if (row) H else W
        val itemMain = if (row) cw else ch
        val itemCross = if (row) ch else cw
        val gap = props.gap.coerceIn(0, 40)
        fun effAlign(i: Int): Align = props.alignSelfs.getOrNull(i) ?: props.align

        // ---- 换行行数：wrap 时按主轴能容纳的个数拆多行，否则单行 ----
        val maxPerLine = if (props.wrap) {
            ((mainAvail - 2 * P + gap) / (itemMain + gap)).coerceAtLeast(1)
        } else n
        val lines = ((n + maxPerLine - 1) / maxPerLine).coerceAtLeast(1)

        // ---- align-content 决定各行在交叉轴上的分布（仅多行时有意义）----
        val lineBase = ArrayList<Int>(lines)
        if (lines > 1) {
            val totalCross = lines * itemCross + (lines - 1) * gap
            var gapCross = gap
            var start = P
            when (props.alignContent) {
                AlignContent.CENTER -> start = P + (crossAvail - totalCross) / 2
                AlignContent.FLEX_END -> start = crossAvail - P - totalCross
                AlignContent.SPACE_BETWEEN -> if (lines > 1) gapCross = (crossAvail - 2 * P - lines * itemCross) / (lines - 1)
                AlignContent.SPACE_AROUND -> { gapCross = (crossAvail - 2 * P - lines * itemCross) / lines; start = P + gapCross / 2 }
                AlignContent.SPACE_EVENLY -> { gapCross = (crossAvail - 2 * P - lines * itemCross) / (lines + 1); start = P + gapCross }
                AlignContent.FLEX_START, AlignContent.STRETCH -> {}
            }
            for (li in 0 until lines) lineBase.add(start + li * (itemCross + gapCross))
        }

        // 单行时：交叉轴上直接用 align-items 摆子项（不引入行带）。
        // 多行时：每行拥有一个「行带」[lineBase[li], lineBase[li]+itemCross]，子项在带内按自身对齐摆放。
        fun lineBand(li: Int): Pair<Int, Int> {
            if (lines == 1) return Pair(P, (crossAvail - 2 * P).coerceAtLeast(1))
            return Pair(lineBase[li], itemCross)
        }

        // 子项在行带 [base, base+band] 内的交叉轴偏移；stretch 返回 null 表示填满整带。
        fun crossPos(align: Align, base: Int, band: Int): Pair<Int, Int?> {
            return when (align) {
                Align.FLEX_START -> Pair(base, itemCross)
                Align.CENTER, Align.BASELINE -> Pair(base + (band - itemCross) / 2, itemCross)
                Align.FLEX_END -> Pair(base + band - itemCross, itemCross)
                Align.STRETCH -> Pair(base, null)
            }
        }

        val boxes = ArrayList<Box>(n)
        for (i in 0 until n) {
            val li = i / maxPerLine
            val posInLine = i % maxPerLine
            val countInLine = minOf(maxPerLine, n - li * maxPerLine)

            // 主轴：行内按 justify 摆放
            var gapMain = gap
            var start = P
            val totalMain = countInLine * itemMain + (countInLine - 1) * gap
            when (props.justify) {
                Justify.CENTER -> start = P + (mainAvail - totalMain) / 2
                Justify.FLEX_END -> start = mainAvail - P - totalMain
                Justify.SPACE_BETWEEN -> if (countInLine > 1) gapMain = (mainAvail - 2 * P - countInLine * itemMain) / (countInLine - 1)
                Justify.SPACE_AROUND -> { gapMain = (mainAvail - 2 * P - countInLine * itemMain) / countInLine; start = P + gapMain / 2 }
                Justify.SPACE_EVENLY -> { gapMain = (mainAvail - 2 * P - countInLine * itemMain) / (countInLine + 1); start = P + gapMain }
                Justify.FLEX_START -> {}
            }
            var mainPos = start + posInLine * (itemMain + gapMain)

            // 交叉轴：行带内按该子项的对齐摆放（优先 align-self，缺省用容器 align-items）
            val (base, band) = lineBand(li)
            val (crossBase, crossSize) = crossPos(effAlign(i), base, band)

            var x: Int
            var y: Int
            var w = cw
            var h = ch
            if (row) {
                x = mainPos
                y = crossBase
                if (crossSize == null) h = band.coerceAtLeast(1)
            } else {
                y = mainPos
                x = crossBase
                if (crossSize == null) w = band.coerceAtLeast(1)
            }
            if (reverse) {
                if (row) x = mainAvail - x - w else y = mainAvail - y - h
            }
            boxes.add(Box(x, y, w, h))
        }
        return boxes
    }
}