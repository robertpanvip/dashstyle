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
        assertTrue("应摆 3 个子项", boxes.size == 3)
        assertTrue("行布局下 x 递增", boxes[0].x < boxes[1].x && boxes[1].x < boxes[2].x)
        assertEquals("行布局默认 align-items:stretch 时 y 相同", boxes[0].y, boxes[1].y)
        assertEquals("行布局拉伸时高度一致", boxes[0].h, boxes[1].h)
    }

    @Test
    fun `wrap splits children into multiple lines`() {
        // W=60：每行最多放 2 个子项（item+gap=26），3 个子项 → 2 行
        val boxes = FlexLayoutResolver.place(
            FlexLayoutResolver.Props(wrap = true, childCount = 3),
            W = 60, H = 200
        )
        assertEquals("同属第一行，y 相同", boxes[0].y, boxes[1].y)
        assertTrue("第二行与第一行 y 不同", boxes[2].y != boxes[0].y)
        assertTrue("第二行在第一行下方", boxes[2].y > boxes[0].y)
        assertTrue("第二行 x 应回到行首", boxes[2].x == boxes[0].x)
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
                childCount = 3
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
}