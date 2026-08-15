package com.pan.dashstyle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * GridLayoutResolver 纯逻辑单测（不依赖 IDE 沙箱，直接跑在 Gradle JVM 上）。
 *
 * 覆盖：默认自动放置、轨道解析（fixed/fr/auto/repeat/minmax）、gap、
 * justify/align-items 格内对齐、justify/align-content 整块网格对齐。
 */
class GridLayoutResolverTest {

    // ---------- 轨道解析 ----------

    @Test
    fun `parse fixed px track`() {
        assertEquals(GridLayoutResolver.Track.Fixed(100), GridLayoutResolver.parseTrack("100px"))
    }

    @Test
    fun `parse fr track`() {
        assertEquals(GridLayoutResolver.Track.Flex(2), GridLayoutResolver.parseTrack("2fr"))
    }

    @Test
    fun `parse auto track`() {
        assertEquals(GridLayoutResolver.Track.Auto, GridLayoutResolver.parseTrack("auto"))
    }

    @Test
    fun `parse repeat expands to n tracks`() {
        val tracks = GridLayoutResolver.parseTrackList("repeat(3, 1fr)")
        assertEquals(3, tracks.size)
        assertTrue(tracks.all { it == GridLayoutResolver.Track.Flex(1) })
    }

    @Test
    fun `parse minmax uses min as fixed`() {
        val track = GridLayoutResolver.parseTrack("minmax(50px, 1fr)")
        assertEquals(GridLayoutResolver.Track.Fixed(50), track)
    }

    @Test
    fun `parse track list mixes units`() {
        val tracks = GridLayoutResolver.parseTrackList("100px 1fr auto")
        assertEquals(3, tracks.size)
        assertEquals(GridLayoutResolver.Track.Fixed(100), tracks[0])
        assertEquals(GridLayoutResolver.Track.Flex(1), tracks[1])
        assertEquals(GridLayoutResolver.Track.Auto, tracks[2])
    }

    // ---------- 自动放置 / 摆位 ----------

    @Test
    fun `default grid auto places children into cells row by row`() {
        // 无列定义 → 默认 3 列；4 个子项 → 2 行
        val boxes = GridLayoutResolver.place(GridLayoutResolver.Props(childCount = 4), W = 200, H = 100)
        assertEquals(4, boxes.size)
        // 第一行：x 递增
        assertTrue(boxes[0].x < boxes[1].x && boxes[1].x < boxes[2].x)
        // 自动换行：第 4 个回到第一列，落到第二行
        assertEquals(boxes[0].x, boxes[3].x)
        assertTrue(boxes[3].y > boxes[0].y)
        // 默认 stretch 填满格子
        assertEquals(64, boxes[0].w)
        assertEquals(46, boxes[0].h)
    }

    @Test
    fun `explicit columns place items across fixed tracks`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px(), 100.px(), 100.px()),
                childCount = 3
            ),
            W = 320, H = 100
        )
        assertEquals(3, boxes.size)
        // 三列原点分别为 4 / 104 / 204
        assertEquals(4, boxes[0].x)
        assertEquals(104, boxes[1].x)
        assertEquals(204, boxes[2].x)
    }

    @Test
    fun `gap adds spacing between cells`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(50.px(), 50.px()),
                gap = 10,
                childCount = 2
            ),
            W = 200, H = 100
        )
        // box0.x=4 宽 50 占 [4,54)；box1.x=4+50+10=64
        assertEquals(4, boxes[0].x)
        assertEquals(64, boxes[1].x)
        assertEquals(64 - 54, boxes[1].x - (boxes[0].x + boxes[0].w)) // 间隙 = 10
    }

    // ---------- 格内对齐 ----------

    @Test
    fun `justify-items center centers item horizontally in cell`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px()),
                justifyItems = GridLayoutResolver.GridAlign.CENTER,
                childCount = 1
            ),
            W = 200, H = 100
        )
        // 100px 列，item 宽 60，居中 → x = 4 + (100-60)/2 = 24
        assertEquals(60, boxes[0].w)
        assertEquals(24, boxes[0].x)
    }

    @Test
    fun `justify-items end aligns item to right edge of cell`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px()),
                justifyItems = GridLayoutResolver.GridAlign.END,
                childCount = 1
            ),
            W = 200, H = 100
        )
        // item 宽 60，靠右 → x = 4 + 100 - 60 = 44
        assertEquals(44, boxes[0].x)
    }

    @Test
    fun `align-items center centers item vertically in cell`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px()),
                alignContent = GridLayoutResolver.GridAlign.START,
                alignItems = GridLayoutResolver.GridAlign.CENTER,
                childCount = 1
            ),
            W = 200, H = 100
        )
        // 行高 92，item 高 55，垂直居中 → y = 4 + (92-55)/2 = 22
        assertEquals(55, boxes[0].h)
        assertEquals(22, boxes[0].y)
    }

    // ---------- 整块网格对齐 ----------

    @Test
    fun `justify-content center centers the whole grid horizontally`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px()),
                justifyContent = GridLayoutResolver.GridAlign.CENTER,
                childCount = 2
            ),
            W = 200, H = 100
        )
        // 网格宽 100，居中 → 起点 x = 4 + (200-8-100)/2 = 50
        assertEquals(50, boxes[0].x)
        assertEquals(50, boxes[1].x)
    }

    @Test
    fun `justify-content start keeps grid at padding edge`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px()),
                justifyContent = GridLayoutResolver.GridAlign.START,
                childCount = 2
            ),
            W = 200, H = 100
        )
        assertEquals(4, boxes[0].x)
    }

    @Test
    fun `align-content center centers the whole grid vertically`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(
                columns = listOf(100.px()),
                rows = listOf(50.px()),
                alignContent = GridLayoutResolver.GridAlign.CENTER,
                childCount = 1
            ),
            W = 200, H = 100
        )
        // 网格高 50，居中 → 起点 y = 4 + (100-8-50)/2 = 25
        assertEquals(25, boxes[0].y)
    }

    @Test
    fun `all children stay inside container bounds`() {
        val boxes = GridLayoutResolver.place(
            GridLayoutResolver.Props(columns = listOf(40.px(), 40.px(), 40.px()), childCount = 6),
            W = 140, H = 120
        )
        assertTrue(boxes.all { it.x >= 0 && it.y >= 0 && it.x + it.w <= 140 && it.y + it.h <= 120 })
    }

    // 便捷构造：px 轨道
    private fun Int.px() = GridLayoutResolver.Track.Fixed(this)
}