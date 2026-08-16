package com.pan.dashstyle

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.css.CssBlock
import com.intellij.psi.css.CssRuleset
import com.intellij.ui.JBColor
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.BorderFactory
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSlider
import kotlin.math.abs

/**
 * CSS 布局预览的交互弹窗（flex 与 grid 通用）。
 *
 * 点击行尾的迷你布局图后弹出，包含：
 *  - 实时画布：随控件调整即时重绘布局（复用 LayoutModel 的摆位逻辑）
 *  - 按容器类型的属性控件（flex：主轴/交叉轴；grid：轨道/对齐）
 *  - 「应用到样式」按钮：把当前值写回 CSS ruleset（已存在改值，缺失则新增）
 *
 * @param triggerProperty 触发该弹窗的属性名（如 "align-items"，为空表示从 display 行触发）
 */
object LayoutPreviewPopup {

    fun create(editor: Editor, rs: CssRuleset, model: LayoutModel, triggerProperty: String? = null): JBPopup {
        val content: PopupEditor = when (model) {
            is LayoutModel.Flex -> FlexEditor(editor, rs, model.props, triggerProperty)
            is LayoutModel.Grid -> GridEditor(editor, rs, model.props, triggerProperty)
        }
        return content.build()
    }

    /** flex / grid 编辑器的公共抽象，统一 [build] 入口。 */
    private interface PopupEditor {
        fun build(): JBPopup
    }

    /** 数字输入滑块：JSlider + 实时数值标签，右侧显示当前值。 */
    private class SliderControl(min: Int, max: Int, initial: Int) {
        private val slider = JSlider(min, max, initial.coerceIn(min, max))
        private val valueLabel = JLabel(initial.coerceIn(min, max).toString())
        private val panel = JPanel(BorderLayout(6, 0)).apply {
            add(slider, BorderLayout.CENTER)
            add(valueLabel, BorderLayout.EAST)
            preferredSize = Dimension(160, 24)
        }
        init {
            slider.addChangeListener {
                valueLabel.text = slider.value.toString()
            }
        }
        val view: JComponent get() = panel
        val value: Int get() = slider.value
        fun addChangeListener(l: () -> Unit) {
            slider.addChangeListener { l() }
        }
    }

    // ====================================================================
    // flex 编辑器
    // ====================================================================
    private class FlexEditor(
        private val editor: Editor,
        private val rs: CssRuleset,
        initial: FlexLayoutResolver.Props,
        private val triggerProperty: String? = null  // 触发属性名，为空表示从 display 行触发
    ) : PopupEditor {
        private val state = arrayOf(initial)

        private val justify = JComboBox(arrayOf("flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"))
        private val align = JComboBox(arrayOf("stretch", "flex-start", "flex-end", "center", "baseline"))
        private val alignContent = JComboBox(arrayOf("flex-start", "flex-end", "center", "stretch", "space-between", "space-around", "space-evenly"))
        private val direction = JComboBox(arrayOf("row", "row-reverse", "column", "column-reverse"))
        private val wrap = JComboBox(arrayOf("nowrap", "wrap"))
        private val gap = SliderControl(0, 40, initial.gap)
        private val preview = previewPanel({ current() }, dragSettings())

        init {
            justify.selectedItem = initial.justify.cssValue()
            align.selectedItem = initial.align.cssValue()
            alignContent.selectedItem = initial.alignContent.cssValue()
            direction.selectedItem = initial.direction.cssValue()
            wrap.selectedItem = if (initial.wrap) "wrap" else "nowrap"

            justify.addActionListener { refresh() }
            align.addActionListener { refresh() }
            alignContent.addActionListener { refresh() }
            direction.addActionListener { refresh() }
            wrap.addActionListener { refresh() }
            gap.addChangeListener { refresh() }
        }

        private fun refresh() {
            state[0] = FlexLayoutResolver.Props(
                direction = FlexLayoutResolver.parseDirection(direction.selectedItem?.toString(), state[0].direction),
                justify = FlexLayoutResolver.parseJustify(justify.selectedItem?.toString(), state[0].justify),
                align = FlexLayoutResolver.parseAlign(align.selectedItem?.toString(), state[0].align),
                alignContent = FlexLayoutResolver.parseAlignContent(alignContent.selectedItem?.toString(), state[0].alignContent),
                gap = gap.value.coerceIn(0, 40),
                wrap = (wrap.selectedItem?.toString()) == "wrap",
                childCount = state[0].childCount,
                alignSelfs = state[0].alignSelfs
            )
            apply()
            javax.swing.SwingUtilities.invokeLater {
                preview.revalidate()
                preview.repaint()
            }
        }

        /** 拖动反向推断：拖空白整组（X 改 justify / Y 改 align）；拖单个子项改该子项 align-self。 */
        private fun dragSettings(): DragSettings {
            val pad = 12
            return DragSettings(
                pad = pad,
                hitTest = { p ->
                    val s = state[0]
                    val boxW = preview.width - pad * 2
                    val boxH = preview.height - pad * 2
                    val boxes = FlexLayoutResolver.place(s, boxW, boxH)
                    val hit = boxes.indexOfFirst { p.x in it.x..(it.x + it.w) && p.y in it.y..(it.y + it.h) }
                    if (hit >= 0) DragTarget.Item(hit) else DragTarget.Group
                },
                onDrag = { target, e ->
                    val s = state[0]
                    when (target) {
                        is DragTarget.Item -> {
                            if (e.axis == DragAxis.X) {
                                dragJustify(s, e)
                            } else {
                                dragChildAlignSelf(s, target.index, e)
                            }
                        }
                        else -> {
                            when (e.axis) {
                                DragAxis.X -> dragJustify(s, e)
                                DragAxis.Y -> dragAlign(s, e)
                            }
                        }
                    }
                }
            )
        }

        private fun dragJustify(s: FlexLayoutResolver.Props, e: DragEvent) {
            val cands = listOf(
                FlexLayoutResolver.Justify.FLEX_START,
                FlexLayoutResolver.Justify.CENTER,
                FlexLayoutResolver.Justify.FLEX_END,
                FlexLayoutResolver.Justify.SPACE_BETWEEN,
                FlexLayoutResolver.Justify.SPACE_AROUND,
                FlexLayoutResolver.Justify.SPACE_EVENLY
            )
            val best = cands.minByOrNull { j ->
                val b = FlexLayoutResolver.place(s.copy(justify = j), e.boxW, e.boxH)[0]
                val cx = b.x + b.w / 2.0
                abs(cx - e.point.x)
            } ?: s.justify
            if (best != s.justify) applyNewProps(s.copy(justify = best))
        }

        private fun dragAlign(s: FlexLayoutResolver.Props, e: DragEvent) {
            val cands = listOf(
                FlexLayoutResolver.Align.STRETCH,
                FlexLayoutResolver.Align.FLEX_START,
                FlexLayoutResolver.Align.CENTER,
                FlexLayoutResolver.Align.FLEX_END
            )
            val best = cands.minByOrNull { a ->
                val b = FlexLayoutResolver.place(s.copy(align = a), e.boxW, e.boxH)[0]
                val cy = b.y + b.h / 2.0
                abs(cy - e.point.y)
            } ?: s.align
            if (best != s.align) applyNewProps(s.copy(align = best))
        }

        /** 通过拖动第 [index] 个子项在交叉轴上的位置，推断该子项的 align-self（覆盖容器 align-items）。 */
        private fun dragChildAlignSelf(s: FlexLayoutResolver.Props, index: Int, e: DragEvent) {
            val cands = listOf(
                FlexLayoutResolver.Align.STRETCH,
                FlexLayoutResolver.Align.FLEX_START,
                FlexLayoutResolver.Align.CENTER,
                FlexLayoutResolver.Align.FLEX_END
            )
            val best = cands.minByOrNull { a ->
                val rows = s.alignSelfs.toMutableList()
                while (rows.size <= index) rows.add(null)
                rows[index] = a
                val b = FlexLayoutResolver.place(s.copy(alignSelfs = rows), e.boxW, e.boxH)[index]
                val cy = b.y + b.h / 2.0
                abs(cy - e.point.y)
            } ?: s.alignSelfs.getOrNull(index) ?: s.align
            val rows = s.alignSelfs.toMutableList()
            while (rows.size <= index) rows.add(null)
            if (rows[index] == best) return
            rows[index] = best
            applyNewProps(s.copy(alignSelfs = rows))
        }

        /** 应用新 props：更新 state、同步下拉控件、重绘画布并实时写回 CSS。 */
        private fun applyNewProps(newProps: FlexLayoutResolver.Props) {
            if (newProps == state[0]) return
            state[0] = newProps
            justify.selectedItem = newProps.justify.cssValue()
            align.selectedItem = newProps.align.cssValue()
            alignContent.selectedItem = newProps.alignContent.cssValue()
            direction.selectedItem = newProps.direction.cssValue()
            wrap.selectedItem = if (newProps.wrap) "wrap" else "nowrap"
            preview.repaint()
            apply()
        }

        private fun current(): LayoutModel.Flex = LayoutModel.Flex(state[0])

        private fun apply() {
        val project = editor.project ?: return
        val block = rs.block ?: return
        WriteCommandAction.runWriteCommandAction(project) {
            applyFlex(block, state[0], triggerProperty)
            val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(rs.containingFile)
            if (doc != null) {
                com.intellij.psi.PsiDocumentManager.getInstance(project).doPostponedOperationsAndUnblockDocument(doc)
            }
        }
    }

        override fun build(): JBPopup {
            val form = JPanel(GridBagLayout())
            var row = 0
            fun addRow(label: String, comp: JComponent) {
                val c = GridBagConstraints()
                c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = Insets(2, 0, 2, 8)
                form.add(JLabel(label), c)
                c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0
                form.add(comp, c); row++
            }
            // 根据触发属性显示对应控件，null（display 行触发）显示全部
            val showAll = triggerProperty == null
            if (showAll || triggerProperty == "justify-content") addRow("justify-content", justify)
            if (showAll || triggerProperty == "align-items") addRow("align-items", align)
            if (showAll || triggerProperty == "align-content") addRow("align-content", alignContent)
            if (showAll || triggerProperty == "flex-direction") addRow("flex-direction", direction)
            if (showAll || triggerProperty == "flex-wrap") addRow("flex-wrap", wrap)
            if (showAll || triggerProperty == "gap" || triggerProperty == "row-gap") addRow("gap", gap.view)

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            panel.add(preview, BorderLayout.NORTH)
            panel.add(form, BorderLayout.CENTER)

            val title = if (showAll) "Flex 布局预览" else "Flex 布局预览 — $triggerProperty"
            return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, justify)
                .setTitle(title)
                .setMovable(true).setResizable(false).setCancelKeyEnabled(true)
                .createPopup()
        }
    }

    // ====================================================================
    // grid 编辑器
    // ====================================================================
    private class GridEditor(
        private val editor: Editor,
        private val rs: CssRuleset,
        initial: GridLayoutResolver.Props,
        private val triggerProperty: String? = null
    ) : PopupEditor {
        private val state = arrayOf(initial)

        private val columns = SliderControl(1, 6, count(initial.columns))
        private val rows = SliderControl(1, 4, count(initial.rows).coerceAtLeast(1))
        private val gap = SliderControl(0, 40, initial.gap)
        private val justifyItems = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val alignItems = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val justifyContent = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val alignContent = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val preview = previewPanel({ current() }, dragSettings())

        init {
            justifyItems.selectedItem = initial.justifyItems.cssValue()
            alignItems.selectedItem = initial.alignItems.cssValue()
            justifyContent.selectedItem = initial.justifyContent.cssValue()
            alignContent.selectedItem = initial.alignContent.cssValue()

            columns.addChangeListener { refresh() }
            rows.addChangeListener { refresh() }
            gap.addChangeListener { refresh() }
            justifyItems.addActionListener { refresh() }
            alignItems.addActionListener { refresh() }
            justifyContent.addActionListener { refresh() }
            alignContent.addActionListener { refresh() }
        }

        private fun refresh() {
            val nCols = columns.value.coerceIn(1, 6)
            val nRows = rows.value.coerceIn(1, 4)
            state[0] = GridLayoutResolver.Props(
                columns = List(nCols) { GridLayoutResolver.Track.Flex(1) },
                rows = List(nRows) { GridLayoutResolver.Track.Flex(1) },
                gap = gap.value.coerceIn(0, 40),
                justifyItems = GridLayoutResolver.parseAlign(justifyItems.selectedItem?.toString(), state[0].justifyItems),
                alignItems = GridLayoutResolver.parseAlign(alignItems.selectedItem?.toString(), state[0].alignItems),
                justifyContent = GridLayoutResolver.parseAlign(justifyContent.selectedItem?.toString(), state[0].justifyContent),
                alignContent = GridLayoutResolver.parseAlign(alignContent.selectedItem?.toString(), state[0].alignContent),
                childCount = state[0].childCount
            )
            apply()
            javax.swing.SwingUtilities.invokeLater {
                preview.revalidate()
                preview.repaint()
            }
        }

        /** 拖动反向推断：拖分隔线改轨道尺寸；拖空白整组改 justify-content / align-content。 */
        private fun dragSettings(): DragSettings {
            val pad = 12
            return DragSettings(
                pad = pad,
                hitTest = { p ->
                    val s = state[0]
                    val boxW = preview.width - pad * 2
                    val boxH = preview.height - pad * 2
                    val colBounds = GridLayoutResolver.trackBounds(s.columns, boxW, s.gap)
                    val rowBounds = GridLayoutResolver.trackBounds(s.rows, boxH, s.gap)
                    val tol = 4
                    for (i in 1 until colBounds.size - 1) {
                        if (abs(p.x - colBounds[i]) <= tol) return@DragSettings DragTarget.Track(i, horizontal = false)
                    }
                    for (i in 1 until rowBounds.size - 1) {
                        if (abs(p.y - rowBounds[i]) <= tol) return@DragSettings DragTarget.Track(i, horizontal = true)
                    }
                    val boxes = GridLayoutResolver.place(s, boxW, boxH)
                    if (boxes.any { p.x in it.x..(it.x + it.w) && p.y in it.y..(it.y + it.h) }) DragTarget.Group
                    else DragTarget.Group
                },
                onDrag = { target, e ->
                    val s = state[0]
                    when (target) {
                        is DragTarget.Track -> {
                            if (target.horizontal) {
                                if (s.rows.size > 1) {
                                    var index = target.index
                                    if (index >= s.rows.size) index = s.rows.size - 1
                                    val newRows = GridLayoutResolver.resizeAdjacentTracks(s.rows, index - 1, e.dy / 8)
                                    if (newRows != s.rows) applyNewProps(s.copy(rows = newRows))
                                }
                            } else {
                                if (s.columns.size > 1) {
                                    var index = target.index
                                    if (index >= s.columns.size) index = s.columns.size - 1
                                    val newCols = GridLayoutResolver.resizeAdjacentTracks(s.columns, index - 1, e.dx / 8)
                                    if (newCols != s.columns) applyNewProps(s.copy(columns = newCols))
                                }
                            }
                        }
                        else -> {
                            when (e.axis) {
                                DragAxis.X -> dragJustifyContent(s, e)
                                DragAxis.Y -> dragAlignContent(s, e)
                            }
                        }
                    }
                }
            )
        }

        private fun dragJustifyContent(s: GridLayoutResolver.Props, e: DragEvent) {
            val cands = listOf(
                GridLayoutResolver.GridAlign.START,
                GridLayoutResolver.GridAlign.CENTER,
                GridLayoutResolver.GridAlign.END
            )
            val best = cands.minByOrNull { a ->
                val b = GridLayoutResolver.place(s.copy(justifyContent = a), e.boxW, e.boxH)[0]
                val cx = b.x + b.w / 2.0
                abs(cx - e.point.x)
            } ?: s.justifyContent
            if (best != s.justifyContent) applyNewProps(s.copy(justifyContent = best))
        }

        private fun dragAlignContent(s: GridLayoutResolver.Props, e: DragEvent) {
            val cands = listOf(
                GridLayoutResolver.GridAlign.START,
                GridLayoutResolver.GridAlign.CENTER,
                GridLayoutResolver.GridAlign.END
            )
            val best = cands.minByOrNull { a ->
                val b = GridLayoutResolver.place(s.copy(alignContent = a), e.boxW, e.boxH)[0]
                val cy = b.y + b.h / 2.0
                abs(cy - e.point.y)
            } ?: s.alignContent
            if (best != s.alignContent) applyNewProps(s.copy(alignContent = best))
        }

        /** 应用新 props：更新 state、同步下拉控件、重绘画布并实时写回 CSS。 */
        private fun applyNewProps(newProps: GridLayoutResolver.Props) {
            if (newProps == state[0]) return
            state[0] = newProps
            justifyItems.selectedItem = newProps.justifyItems.cssValue()
            alignItems.selectedItem = newProps.alignItems.cssValue()
            justifyContent.selectedItem = newProps.justifyContent.cssValue()
            alignContent.selectedItem = newProps.alignContent.cssValue()
            preview.repaint()
            apply()
        }

        private fun apply() {
            val project = editor.project ?: return
            val block = rs.block ?: return
            WriteCommandAction.runWriteCommandAction(project) {
                applyGrid(block, state[0], triggerProperty)
            }
        }

        private fun current(): LayoutModel.Grid = LayoutModel.Grid(state[0])

        override fun build(): JBPopup {
            val form = JPanel(GridBagLayout())
            var row = 0
            fun addRow(label: String, comp: JComponent) {
                val c = GridBagConstraints()
                c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = Insets(2, 0, 2, 8)
                form.add(JLabel(label), c)
                c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0
                form.add(comp, c); row++
            }
            val showAll = triggerProperty == null
            if (showAll || triggerProperty == "grid-template-columns") addRow("columns(1fr)", columns.view)
            if (showAll || triggerProperty == "grid-template-rows") addRow("rows(1fr)", rows.view)
            if (showAll || triggerProperty == "gap" || triggerProperty == "column-gap") addRow("gap", gap.view)
            if (showAll || triggerProperty == "justify-items") addRow("justify-items", justifyItems)
            if (showAll || triggerProperty == "align-items") addRow("align-items", alignItems)
            if (showAll || triggerProperty == "justify-content") addRow("justify-content", justifyContent)
            if (showAll || triggerProperty == "align-content") addRow("align-content", alignContent)

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            panel.add(preview, BorderLayout.NORTH)
            panel.add(form, BorderLayout.CENTER)

            val title = if (showAll) "Grid 布局预览" else "Grid 布局预览 — $triggerProperty"
            return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, columns.view)
                .setTitle(title)
                .setMovable(true).setResizable(false).setCancelKeyEnabled(true)
                .createPopup()
        }
    }

    // ====================================================================
    // 公共：实时画布
    // ====================================================================

    /** 拖动轴：按位移主方向判定。 */
    private enum class DragAxis { X, Y }

    /** 拖动目标：整组 / 单个子项 / 轨道分隔线。 */
    private sealed class DragTarget {
        object Group : DragTarget()
        data class Item(val index: Int) : DragTarget()
        data class Track(val index: Int, val horizontal: Boolean) : DragTarget()
    }

    /** 一次拖动事件：包含当前指向、主轴方向、位移增量与画布局内可用尺寸。 */
    private data class DragEvent(
        val point: CanvasPoint,
        val axis: DragAxis,
        val dx: Int,
        val dy: Int,
        val boxW: Int,
        val boxH: Int
    )

    /**
     * 画布拖动配置。hitTest 判定命中的 [DragTarget]（null 表示该点不可拖），
     * onDrag 根据目标语义做「反向推断」并写回 CSS。
     *  - flex：拖空白整组（X 改 justify / Y 改 align），拖单个子项 Y 方向调该子项 align-self；
     *  - grid：拖分隔线改轨道尺寸，拖空白整组改 justify-content / align-content。
     */
    private data class DragSettings(
        val pad: Int,
        val hitTest: (CanvasPoint) -> DragTarget?,
        val onDrag: (DragTarget, DragEvent) -> Unit
    )

    private data class CanvasPoint(val x: Int, val y: Int)

    private fun previewPanel(modelProvider: () -> LayoutModel, drag: DragSettings? = null): JPanel {
        return object : JPanel() {
            private var dragging = false
            private var startX = 0
            private var startY = 0
            private var axis: DragAxis? = null
            private var target: DragTarget? = null

            init {
                preferredSize = Dimension(200, 120)
                background = JBColor(Color(0xf7f7f8), Color(0x2b2d30))
                border = BorderFactory.createLineBorder(JBColor(Color(0xc9cdd4), Color(0x4a4d52)))
                if (drag != null) {
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    addMouseListener(object : MouseAdapter() {
                        override fun mousePressed(e: MouseEvent) {
                            val p = toCanvas(e, drag)
                            val t = drag.hitTest(p) ?: return
                            dragging = true
                            target = t
                            startX = p.x; startY = p.y
                            axis = null
                        }
                        override fun mouseReleased(e: MouseEvent) {
                            dragging = false
                            axis = null
                            target = null
                        }
                    })
                    addMouseMotionListener(object : MouseMotionAdapter() {
                        override fun mouseDragged(e: MouseEvent) {
                            if (!dragging) return
                            val p = toCanvas(e, drag)
                            val dx = p.x - startX
                            val dy = p.y - startY
                            if (axis == null) {
                                if (abs(dx) < 6 && abs(dy) < 6) return
                                axis = if (abs(dx) >= abs(dy)) DragAxis.X else DragAxis.Y
                            }
                            val boxW = width - drag.pad * 2
                            val boxH = height - drag.pad * 2
                            val t = target ?: return
                            drag.onDrag(t, DragEvent(p, axis!!, dx, dy, boxW, boxH))
                        }
                    })
                }
            }

            private fun toCanvas(e: MouseEvent, d: DragSettings): CanvasPoint =
                CanvasPoint(e.x - d.pad, e.y - d.pad)

            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                val pad = drag?.pad ?: 12
                val boxW = width - pad * 2
                val boxH = height - pad * 2
                val boxes = modelProvider().boxes(boxW, boxH)
                g2.color = JBColor(Color(0x9aa0aa), Color(0x62666d))
                g2.drawRect(pad, pad, boxW - 1, boxH - 1)
                g2.color = JBColor(Color(0x4f8cff), Color(0x6aa0ff))
                for (b in boxes) {
                    g2.fillRect(pad + b.x, pad + b.y, b.w, b.h)
                }
                // grid：叠画轨道分隔线，提示可拖拽调尺寸
                val model = modelProvider()
                if (model is LayoutModel.Grid) {
                    val gp = model.props
                    val colBounds = GridLayoutResolver.trackBounds(gp.columns, boxW, gp.gap)
                    val rowBounds = GridLayoutResolver.trackBounds(gp.rows, boxH, gp.gap)
                    g2.color = JBColor(Color(0x8a8d93), Color(0x5a5d63))
                    for (i in 1 until colBounds.size - 1) {
                        g2.drawLine(pad + colBounds[i], pad, pad + colBounds[i], pad + boxH - 1)
                    }
                    for (i in 1 until rowBounds.size - 1) {
                        g2.drawLine(pad, pad + rowBounds[i], pad + boxW - 1, pad + rowBounds[i])
                    }
                }
            }
        }
    }

    // ====================================================================
    // 写回 CSS
    // ====================================================================
    fun applyToBlock(block: CssBlock, model: LayoutModel) {
        when (model) {
            is LayoutModel.Flex -> applyFlex(block, model.props)
            is LayoutModel.Grid -> applyGrid(block, model.props)
        }
    }

    private fun applyFlex(block: CssBlock, props: FlexLayoutResolver.Props, triggerProperty: String? = null) {
        val showAll = triggerProperty == null
        if (showAll || triggerProperty == "justify-content") setOrAdd(block, "justify-content", props.justify.cssValue())
        if (showAll || triggerProperty == "align-items") setOrAdd(block, "align-items", props.align.cssValue())
        if (showAll || triggerProperty == "align-content") setOrAdd(block, "align-content", props.alignContent.cssValue())
        if (showAll || triggerProperty == "flex-direction") setOrAdd(block, "flex-direction", props.direction.cssValue())
        if (showAll || triggerProperty == "flex-wrap") setOrAdd(block, "flex-wrap", if (props.wrap) "wrap" else "nowrap")
        if (showAll || triggerProperty == "gap" || triggerProperty == "row-gap") setOrAdd(block, "gap", "${props.gap}px")
    }

    private fun applyGrid(block: CssBlock, props: GridLayoutResolver.Props, triggerProperty: String? = null) {
        val showAll = triggerProperty == null
        if (showAll || triggerProperty == "grid-template-columns") setOrAdd(block, "grid-template-columns", tracksString(props.columns))
        if (showAll || triggerProperty == "grid-template-rows") setOrAdd(block, "grid-template-rows", tracksString(props.rows))
        if (showAll || triggerProperty == "gap" || triggerProperty == "column-gap") setOrAdd(block, "gap", "${props.gap}px")
        if (showAll || triggerProperty == "justify-items") setOrAdd(block, "justify-items", props.justifyItems.cssValue())
        if (showAll || triggerProperty == "align-items") setOrAdd(block, "align-items", props.alignItems.cssValue())
        if (showAll || triggerProperty == "justify-content") setOrAdd(block, "justify-content", props.justifyContent.cssValue())
        if (showAll || triggerProperty == "align-content") setOrAdd(block, "align-content", props.alignContent.cssValue())
    }

    @Suppress("DEPRECATION")
    private fun setOrAdd(block: CssBlock, name: String, value: String) {
        val existing = block.findDeclaration(name)
        if (existing != null) {
            // 优先用 document.replaceString 替换 value 文本
            val valueNode = existing.value
            val project = block.project
            val doc = com.intellij.psi.PsiDocumentManager.getInstance(project).getDocument(block.containingFile)
            if (valueNode != null && doc != null) {
                val range = valueNode.textRange
                val oldText = valueNode.text
                val newText = if (oldText.startsWith("\"")) "\"$value\"" else value
                if (oldText != newText) {
                    doc.replaceString(range.startOffset, range.endOffset, newText)
                }
            } else {
                // 回退到 setValue API
                existing.setValue(value)
            }
        } else {
            block.addDeclaration(name, value, existing)
        }
    }

    // ====================================================================
    // 小工具
    // ====================================================================
    private fun tracksString(tracks: List<GridLayoutResolver.Track>): String =
        if (tracks.isEmpty()) "1fr" else tracks.joinToString(" ") {
            when (it) {
                is GridLayoutResolver.Track.Fixed -> "${it.px}px"
                is GridLayoutResolver.Track.Flex -> "${it.weight}fr"
                GridLayoutResolver.Track.Auto -> "auto"
            }
        }

    private fun count(tracks: List<GridLayoutResolver.Track>): Int = tracks.size.coerceAtLeast(1)

    private fun FlexLayoutResolver.Justify.cssValue(): String = when (this) {
        FlexLayoutResolver.Justify.FLEX_START -> "flex-start"
        FlexLayoutResolver.Justify.FLEX_END -> "flex-end"
        FlexLayoutResolver.Justify.CENTER -> "center"
        FlexLayoutResolver.Justify.SPACE_BETWEEN -> "space-between"
        FlexLayoutResolver.Justify.SPACE_AROUND -> "space-around"
        FlexLayoutResolver.Justify.SPACE_EVENLY -> "space-evenly"
    }

    private fun FlexLayoutResolver.Align.cssValue(): String = when (this) {
        FlexLayoutResolver.Align.FLEX_START -> "flex-start"
        FlexLayoutResolver.Align.FLEX_END -> "flex-end"
        FlexLayoutResolver.Align.CENTER -> "center"
        FlexLayoutResolver.Align.BASELINE -> "baseline"
        FlexLayoutResolver.Align.STRETCH -> "stretch"
    }

    private fun FlexLayoutResolver.AlignContent.cssValue(): String = when (this) {
        FlexLayoutResolver.AlignContent.FLEX_START -> "flex-start"
        FlexLayoutResolver.AlignContent.FLEX_END -> "flex-end"
        FlexLayoutResolver.AlignContent.CENTER -> "center"
        FlexLayoutResolver.AlignContent.STRETCH -> "stretch"
        FlexLayoutResolver.AlignContent.SPACE_BETWEEN -> "space-between"
        FlexLayoutResolver.AlignContent.SPACE_AROUND -> "space-around"
        FlexLayoutResolver.AlignContent.SPACE_EVENLY -> "space-evenly"
    }

    private fun FlexLayoutResolver.Direction.cssValue(): String = when (this) {
        FlexLayoutResolver.Direction.ROW -> "row"
        FlexLayoutResolver.Direction.ROW_REVERSE -> "row-reverse"
        FlexLayoutResolver.Direction.COLUMN -> "column"
        FlexLayoutResolver.Direction.COLUMN_REVERSE -> "column-reverse"
    }

    private fun GridLayoutResolver.GridAlign.cssValue(): String = when (this) {
        GridLayoutResolver.GridAlign.START -> "start"
        GridLayoutResolver.GridAlign.CENTER -> "center"
        GridLayoutResolver.GridAlign.END -> "end"
        GridLayoutResolver.GridAlign.STRETCH -> "stretch"
    }
}