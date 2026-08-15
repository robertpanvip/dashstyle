package com.pan.dashstyle

/**
 * CSS Grid 布局预览 —— 纯逻辑层（镜像 FlexLayoutResolver，不依赖 IDE SDK）。
 *
 * 解析 grid-template-columns/rows 的轨道定义，计算轨道尺寸与交点坐标，
 * 再按 grid-auto-flow(row) 自动放置子项，最后根据 justify-items/align-items
 * 在格子内对齐、justify-content/align-content 对齐整块网格。
 *
 * 支持的轨道语法：固定 `px`、`fr` 权重、`auto`、`repeat(n, ...)`、`minmax(a,b)`。
 * （预览为示意，不做浏览器级精确排版。）
 */
object GridLayoutResolver {

    enum class GridAlign { START, CENTER, END, STRETCH }

    sealed class Track {
        data class Fixed(val px: Int) : Track()
        data class Flex(val weight: Int) : Track()
        object Auto : Track()
    }

    data class Props(
        val columns: List<Track> = emptyList(),
        val rows: List<Track> = emptyList(),
        val gap: Int = 0,
        val justifyItems: GridAlign = GridAlign.STRETCH,
        val alignItems: GridAlign = GridAlign.STRETCH,
        val justifyContent: GridAlign = GridAlign.STRETCH,
        val alignContent: GridAlign = GridAlign.STRETCH,
        val childCount: Int = 4
    )

    /** 以 [BoxProvider] 形式暴露 grid 摆位，供统一渲染/弹窗层调用 */
    fun parseAlign(s: String?, fallback: GridAlign = GridAlign.STRETCH): GridAlign = when (s?.trim()?.lowercase()) {
        "start" -> GridAlign.START
        "center" -> GridAlign.CENTER
        "end" -> GridAlign.END
        else -> fallback
    }

    fun parseTrack(s: String?): Track? {
        val t = s?.trim()?.lowercase() ?: return null
        if (t.startsWith("minmax(")) {
            val inner = t.substring("minmax(".length, t.length - 1)
            return parseTrack(inner.substringBefore(",").trim()) ?: Track.Flex(1)
        }
        if (t == "auto") return Track.Auto
        if (t.endsWith("px")) return Track.Fixed(t.removeSuffix("px").trim().toIntOrNull() ?: 0)
        if (t.endsWith("fr")) return Track.Flex(t.removeSuffix("fr").trim().toIntOrNull() ?: 1)
        t.toIntOrNull()?.let { return Track.Flex(it) }
        return Track.Auto
    }

    fun parseTrackList(s: String?): List<Track> {
        if (s.isNullOrBlank()) return emptyList()
        val out = ArrayList<Track>()
        for (tok in tokenize(s)) {
            if (tok.startsWith("repeat(")) {
                val inner = tok.substring("repeat(".length, tok.length - 1)
                val parts = inner.split(",", limit = 2)
                val n = parts.getOrNull(0)?.trim()?.toIntOrNull() ?: 1
                val tracks = parseTrackList(parts.getOrNull(1))
                repeat(n.coerceIn(1, 12)) { out.addAll(tracks) }
            } else {
                parseTrack(tok)?.let { out.add(it) }
            }
        }
        return out
    }

    private fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var depth = 0
        for (c in s) {
            when {
                c == '(' -> { depth++; sb.append(c) }
                c == ')' -> { depth--; sb.append(c) }
                c.isWhitespace() && depth == 0 -> { if (sb.isNotEmpty()) { out.add(sb.toString()); sb.setLength(0) } }
                else -> sb.append(c)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    fun place(props: Props, W: Int, H: Int): List<Box> {
        val n = props.childCount.coerceAtLeast(1)
        val P = 4

        val colCount = if (props.columns.isEmpty()) 3 else props.columns.size
        val rowCount = if (props.rows.isEmpty()) ((n + colCount - 1) / colCount).coerceAtLeast(1) else props.rows.size
        val colTracks = if (props.columns.isEmpty()) List(colCount) { Track.Flex(1) } else props.columns
        val rowTracks = if (props.rows.isEmpty()) List(rowCount) { Track.Flex(1) } else props.rows

        val colWidths = layoutTracks(colTracks, (W - 2 * P).coerceAtLeast(0))
        val rowHeights = layoutTracks(rowTracks, (H - 2 * P).coerceAtLeast(0))

        val gridW = colWidths.sum() + props.gap * (colCount - 1)
        val gridH = rowHeights.sum() + props.gap * (rowCount - 1)

        val ox = when (props.justifyContent) {
            GridAlign.CENTER -> P + (W - 2 * P - gridW).coerceAtLeast(0) / 2
            GridAlign.END -> (W - P - gridW).coerceAtLeast(P)
            GridAlign.START, GridAlign.STRETCH -> P
        }
        val oy = when (props.alignContent) {
            GridAlign.CENTER -> P + (H - 2 * P - gridH).coerceAtLeast(0) / 2
            GridAlign.END -> (H - P - gridH).coerceAtLeast(P)
            GridAlign.START, GridAlign.STRETCH -> P
        }

        val prefX = IntArray(colCount + 1)
        for (i in 0 until colCount) prefX[i + 1] = prefX[i] + colWidths[i] + props.gap
        val prefY = IntArray(rowCount + 1)
        for (i in 0 until rowCount) prefY[i + 1] = prefY[i] + rowHeights[i] + props.gap

        val boxes = ArrayList<Box>(n)
        for (i in 0 until n) {
            val c = i % colCount
            val r = i / colCount
            if (r >= rowCount) break
            val cellX = ox + prefX[c]
            val cellY = oy + prefY[r]
            val cw = colWidths[c]
            val ch = rowHeights[r]

            var x = cellX
            var y = cellY
            var w = cw
            var h = ch
            when (props.justifyItems) {
                GridAlign.START -> { w = (cw * 0.6f).toInt(); x = cellX }
                GridAlign.CENTER -> { w = (cw * 0.6f).toInt(); x = cellX + (cw - w) / 2 }
                GridAlign.END -> { w = (cw * 0.6f).toInt(); x = cellX + cw - w }
                GridAlign.STRETCH -> {}
            }
            when (props.alignItems) {
                GridAlign.START -> { h = (ch * 0.6f).toInt(); y = cellY }
                GridAlign.CENTER -> { h = (ch * 0.6f).toInt(); y = cellY + (ch - h) / 2 }
                GridAlign.END -> { h = (ch * 0.6f).toInt(); y = cellY + ch - h }
                GridAlign.STRETCH -> {}
            }
            boxes.add(Box(x, y, w.coerceAtLeast(1), h.coerceAtLeast(1)))
        }
        if (boxes.isEmpty()) boxes.add(Box(P, P, 1, 1))
        return boxes
    }

    private fun layoutTracks(tracks: List<Track>, avail: Int): List<Int> {
        val fixed = tracks.filterIsInstance<Track.Fixed>().sumOf { it.px }
        val flexTotal = tracks.filter { it !is Track.Fixed }
            .sumOf { (it as? Track.Flex)?.weight ?: 1 }
        val remaining = (avail - fixed).coerceAtLeast(0)
        return tracks.map {
            when (it) {
                is Track.Fixed -> it.px.coerceAtLeast(0)
                is Track.Flex -> if (flexTotal == 0) 0 else (remaining * it.weight / flexTotal).coerceAtLeast(0)
                Track.Auto -> if (flexTotal == 0) 0 else (remaining / flexTotal).coerceAtLeast(0)
            }
        }
    }

    /**
     * 计算轨道在给定可用尺寸下的像素宽度。同 [layoutTracks]，对外暴露供拖拽/绘制复用。
     */
    fun trackWidths(tracks: List<Track>, avail: Int): List<Int> = layoutTracks(tracks, avail)

    /**
     * 相邻轨道分隔线的像素位置（含最右侧边框），供轨道拖拽命中和绘制分隔线使用。
     * 返回 n+1 个边界点（tracks 为空时返回 [0]）。
     */
    fun trackBounds(tracks: List<Track>, avail: Int, gap: Int = 0): List<Int> {
        val widths = layoutTracks(tracks, avail)
        val bounds = ArrayList<Int>(widths.size + 1)
        var acc = 0
        bounds.add(0)
        for ((idx, w) in widths.withIndex()) {
            acc += w
            bounds.add(acc)
            // 分隔线位于轨道边界处；gap 在记录边界之后叠加
            if (idx < widths.size - 1) acc += gap
        }
        return bounds
    }

    /**
     * 轨道拖拽：调整 [leftIndex] 与 [leftIndex+1] 两条相邻轨道之间的相对尺寸。
     * 拖动分隔线往右（[delta] > 0）时，左侧轨道变宽、右侧变窄。
     *
     * 规则：
     *  - 两条都是 fr → 在两者间加减权重，保持各自 ≥ [minWeight]；
     *  - 只有一条是 fr → 改变该条权重；
     *  - 都没有 fr（px/auto）→ 直接给左侧 px 加 [delta]、右侧 px 减 [delta]（钳制到 ≥0）。
     * 返回新轨道列表；越界索引或无可调整项时返回原列表。
     */
    fun resizeAdjacentTracks(
        tracks: List<Track>,
        leftIndex: Int,
        delta: Int,
        minWeight: Int = 1
    ): List<Track> {
        if (leftIndex < 0 || leftIndex + 1 >= tracks.size || delta == 0) return tracks
        val left = tracks[leftIndex]
        val right = tracks[leftIndex + 1]

        val leftW = (left as? Track.Flex)?.weight
        val rightW = (right as? Track.Flex)?.weight
        val out = tracks.toMutableList()

        if (leftW != null && rightW != null) {
            // 守恒移动：左侧实际变化量 = 拖拽量，但被 minWeight 钳制后，差值由右侧吸收
            val newLeft = (leftW + delta).coerceAtLeast(minWeight)
            val leftChange = newLeft - leftW
            out[leftIndex] = Track.Flex(newLeft)
            out[leftIndex + 1] = Track.Flex((rightW - leftChange).coerceAtLeast(minWeight))
            return out
        }
        if (leftW != null) {
            out[leftIndex] = Track.Flex((leftW + delta).coerceAtLeast(minWeight))
            return out
        }
        if (rightW != null) {
            out[leftIndex + 1] = if (delta > 0) {
                Track.Flex(rightW.coerceAtLeast(minWeight))
            } else {
                Track.Flex((rightW + delta).coerceAtLeast(minWeight))
            }
            return out
        }
        // 都没有 fr：px 平移
        val lp = (left as? Track.Fixed)?.px
        val rp = (right as? Track.Fixed)?.px
        if (lp != null && rp != null) {
            out[leftIndex] = Track.Fixed((lp + delta).coerceAtLeast(0))
            out[leftIndex + 1] = Track.Fixed((rp - delta).coerceAtLeast(0))
            return out
        }
        return tracks
    }
}