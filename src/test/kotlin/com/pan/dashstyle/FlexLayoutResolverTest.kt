package com.pan.dashstyle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FlexLayoutResolver 纯逻辑单测（不依赖 IDE 沙箱，直接跑在 Gradle JVM 上）。
 *
 * 覆盖：默认单行摆位、flex-wrap 换行、flex-direction: column、align-content 多行分布。
 */
class FlexLayoutResolverTest {

    @Test
    fun `default row places children left to right with same y`() {
        val boxes = FlexLayoutResolver.place(FlexLayoutResolver.Props(), W = 200, H = 60)
        assertTrue("应摆 4 个子项", boxes.size == 4)
        assertTrue("行布局下 x 递增", boxes[0].x < boxes[1].x && boxes[1].x < boxes[2].x && boxes[2].x < boxes[3].x)
        assertEquals("行布局默认 align-items:stretch 时 y 相同", boxes[0].y, boxes[1].y)
        assertEquals("行布局拉伸时高度一致", boxes[0].h, boxes[1].h)
    }

    @Test
    fun `wrap splits children into multiple lines`() {
        // 一行示意最多放 3 个（capacity=3），4 个子项 wrap → 3 + 1 = 2 行
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(wrap = true, childCount = 4),
            W = 60, H = 200
        )
        assertEquals("同属第一行，y 相同", boxes[0].y, boxes[1].y)
        assertTrue("第二行与第一行 y 不同", boxes[3].y != boxes[0].y)
        assertTrue("第二行在第一行下方", boxes[3].y > boxes[0].y)
        assertTrue("第二行 x 应回到行首", boxes[3].x == boxes[0].x)
    }

    @Test
    fun `wrap false keeps single line regardless of width`() {
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(wrap = false, childCount = 3),
            W = 40, H = 200
        )
        // 单行：所有子项 y 相同
        assertEquals("nowrap 时所有子项 y 相同", boxes.map { it.y }.distinct().size, 1)
    }

    @Test
    fun `column direction stacks children vertically with same x`() {
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(direction = FlexLayoutResolver.Direction.COLUMN),
            W = 200, H = 60
        )
        assertTrue("column 布局下 y 递增", boxes[0].y < boxes[1].y && boxes[1].y < boxes[2].y)
        assertEquals("column 布局默认 align-items:stretch 时 x 相同", boxes[0].x, boxes[1].x)
    }

    @Test
    fun `align-content stretches or aligns wrapped lines`() {
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(
                wrap = true,
                alignContent = FlexLayoutResolver.AlignContent.FLEX_START,
                childCount = 4
            ),
            W = 60, H = 200
        )
        // 两行，flex-start：第一行贴顶，第二行在第一行下方，且都落在容器内
        assertTrue("所有子项都在容器内", boxes.all { it.y >= 0 && it.y + it.h <= 200 })
        assertTrue("多行时 y 至少有两种取值", boxes.map { it.y }.distinct().size >= 2)
    }

    @Test
    fun `reverse row mirrors main axis`() {
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(direction = FlexLayoutResolver.Direction.ROW_REVERSE),
            W = 200, H = 60
        )
        // 反行：第一个子项在最右侧
        assertTrue("row-reverse 时第一个子项更靠右", boxes[0].x > boxes[2].x)
    }

    @Test
    fun `wrap applies justify-content per line independently`() {
        // 一行示意放 3 个（capacity=3），4 个子项 wrap → 第一行 3 个、第二行 1 个
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(
                wrap = true,
                justify = FlexLayoutResolver.Justify.SPACE_BETWEEN,
                childCount = 4
            ),
            W = 60, H = 200
        )
        // 第一行（index 0,1,2）spread：x 递增（space-between 拉开间距）
        assertTrue("第一行 space-between 下 x 递增", boxes[1].x > boxes[0].x)
        assertTrue("第一行三个子项 y 相同", boxes[0].y == boxes[1].y && boxes[0].y == boxes[2].y)
        // 第二行只有一个子项（index 3）：单行 space-between 无效果，回到行首
        assertEquals("第二行单子项落在行首", 4, boxes[3].x)
        // 两行 y 不同，证明确实换行
        assertTrue("两行 y 不同", boxes[3].y != boxes[0].y)
    }

    // ---------- 子项级微调：align-self 覆盖容器 align-items ----------

    @Test
    fun `child align-self flex-end moves that child down below others`() {
        // 容器 align:flex-start，第 0 个子项 align-self:flex-end → 该子项 y 更大
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(
                align = FlexLayoutResolver.Align.FLEX_START,
                alignSelfs = listOf(FlexLayoutResolver.Align.FLEX_END, null, null)
            ),
            W = 200, H = 200
        )
        assertTrue("align-self flex-end 子项应更靠下", boxes[0].y > boxes[1].y)
        assertEquals("其余子项沿用容器 flex-start，y 相同", boxes[1].y, boxes[2].y)
    }

    @Test
    fun `child align-self center centers that child vertically`() {
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(
                align = FlexLayoutResolver.Align.FLEX_START,
                alignSelfs = listOf(FlexLayoutResolver.Align.CENTER)
            ),
            W = 200, H = 200
        )
        // flex-start 基准 y=4；center 应把子项居中到容器，y 明显大于 4 且小于容器底
        assertTrue("center 子项 y 大于 flex-start 基准", boxes[0].y > boxes[1].y)
        assertTrue("center 子项仍在容器内", boxes[0].y + boxes[0].h <= 200)
    }

    @Test
    fun `child align-self stretch stretches only that child`() {
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(
                align = FlexLayoutResolver.Align.FLEX_START,
                alignSelfs = listOf(FlexLayoutResolver.Align.STRETCH, null, null)
            ),
            W = 200, H = 200
        )
        // 单行时 stretch 填满行带（容高-2P），比非 stretch 的 20 更高
        assertTrue("stretch 子项高度应大于普通子项", boxes[0].h > boxes[1].h)
        assertEquals("其余子项高度不变", boxes[1].h, boxes[2].h)
    }
}