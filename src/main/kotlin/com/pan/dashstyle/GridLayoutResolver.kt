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
}