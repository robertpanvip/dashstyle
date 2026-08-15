package com.pan.dashstyle

/**
 * 布局预览的共享模型。
 *
 * [Box]：布局项的坐标/尺寸，供 flex / grid 解析器共同使用。
 * [LayoutModel]：统一封装「容器类型 + 具体属性 + 摆位结果」，渲染、位图缓存、点击弹窗
 * 都只依赖它，不关心底层是 flex 还是 grid。
 */
data class Box(val x: Int, val y: Int, val w: Int, val h: Int)

sealed class LayoutModel {
    abstract fun boxes(W: Int, H: Int): List<Box>

    class Flex(val props: FlexLayoutResolver.Props) : LayoutModel() {
        override fun boxes(W: Int, H: Int): List<Box> = FlexLayoutResolver.place(props, W, H)
    }

    class Grid(val props: GridLayoutResolver.Props) : LayoutModel() {
        override fun boxes(W: Int, H: Int): List<Box> = GridLayoutResolver.place(props, W, H)
    }
}