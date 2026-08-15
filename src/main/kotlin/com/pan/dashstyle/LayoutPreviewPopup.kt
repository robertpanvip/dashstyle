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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * CSS 布局预览的交互弹窗（flex 与 grid 通用）。
 *
 * 点击行尾的迷你布局图后弹出，包含：
 *  - 实时画布：随控件调整即时重绘布局（复用 LayoutModel 的摆位逻辑）
 *  - 按容器类型的属性控件（flex：主轴/交叉轴；grid：轨道/对齐）
 *  - 「应用到样式」按钮：把当前值写回 CSS ruleset（已存在改值，缺失则新增）
 */
object LayoutPreviewPopup {

    fun create(editor: Editor, rs: CssRuleset, model: LayoutModel): JBPopup {
        val content: PopupEditor = when (model) {
            is LayoutModel.Flex -> FlexEditor(editor, rs, model.props)
            is LayoutModel.Grid -> GridEditor(editor, rs, model.props)
        }
        return content.build()
    }

    /** flex / grid 编辑器的公共抽象，统一 [build] 入口。 */
    private interface PopupEditor {
        fun build(): JBPopup
    }

    // ====================================================================
    // flex 编辑器
    // ====================================================================
    private class FlexEditor(
        private val editor: Editor,
        private val rs: CssRuleset,
        initial: FlexLayoutResolver.Props
    ) : PopupEditor {
        private val state = arrayOf(initial)

        private val justify = JComboBox(arrayOf("flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"))
        private val align = JComboBox(arrayOf("stretch", "flex-start", "flex-end", "center", "baseline"))
        private val alignContent = JComboBox(arrayOf("flex-start", "flex-end", "center", "stretch", "space-between", "space-around", "space-evenly"))
        private val direction = JComboBox(arrayOf("row", "row-reverse", "column", "column-reverse"))
        private val wrap = JComboBox(arrayOf("nowrap", "wrap"))
        private val gap = JSpinner(SpinnerNumberModel(0, 0, 40, 1))

        init {
            justify.selectedItem = initial.justify.cssValue()
            align.selectedItem = initial.align.cssValue()
            alignContent.selectedItem = initial.alignContent.cssValue()
            direction.selectedItem = initial.direction.cssValue()
            wrap.selectedItem = if (initial.wrap) "wrap" else "nowrap"
            gap.value = initial.gap

            justify.addActionListener { refresh() }
            align.addActionListener { refresh() }
            alignContent.addActionListener { refresh() }
            direction.addActionListener { refresh() }
            wrap.addActionListener { refresh() }
            gap.addChangeListener { refresh() }
        }

        private fun refresh() {
            state[0] = FlexLayoutResolver.Props(
                direction = FlexLayoutResolver.parseDirection(direction.selectedItem as? String, state[0].direction),
                justify = FlexLayoutResolver.parseJustify(justify.selectedItem as? String, state[0].justify),
                align = FlexLayoutResolver.parseAlign(align.selectedItem as? String, state[0].align),
                alignContent = FlexLayoutResolver.parseAlignContent(alignContent.selectedItem as? String, state[0].alignContent),
                gap = (gap.value as Int).coerceIn(0, 40),
                wrap = (wrap.selectedItem as? String) == "wrap",
                childCount = state[0].childCount
            )
        }

        private fun current(): LayoutModel.Flex = LayoutModel.Flex(state[0])

        private fun apply() {
            val project = editor.project ?: return
            val block = rs.block ?: return
            WriteCommandAction.runWriteCommandAction(project) {
                applyFlex(block, state[0])
            }
        }

        override fun build(): JBPopup {
            val preview = previewPanel(current())
            val form = JPanel(GridBagLayout())
            var row = 0
            fun addRow(label: String, comp: JComponent) {
                val c = GridBagConstraints()
                c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = Insets(2, 0, 2, 8)
                form.add(JLabel(label), c)
                c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0
                form.add(comp, c); row++
            }
            addRow("justify-content", justify)
            addRow("align-items", align)
            addRow("align-content", alignContent)
            addRow("flex-direction", direction)
            addRow("flex-wrap", wrap)
            addRow("gap", gap)

            val applyBtn = JButton("应用到样式")
            applyBtn.addActionListener { apply() }
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            buttons.add(applyBtn)

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            panel.add(preview, BorderLayout.NORTH)
            panel.add(form, BorderLayout.CENTER)
            panel.add(buttons, BorderLayout.SOUTH)

            return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, justify)
                .setTitle("Flex 布局预览")
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
        initial: GridLayoutResolver.Props
    ) : PopupEditor {
        private val state = arrayOf(initial)

        private val columns = JSpinner(SpinnerNumberModel(count(initial.columns), 1, 6, 1))
        private val rows = JSpinner(SpinnerNumberModel(count(initial.rows).coerceAtLeast(1), 1, 4, 1))
        private val gap = JSpinner(SpinnerNumberModel(initial.gap, 0, 40, 1))
        private val justifyItems = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val alignItems = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val justifyContent = JComboBox(arrayOf("stretch", "start", "center", "end"))
        private val alignContent = JComboBox(arrayOf("stretch", "start", "center", "end"))

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
            val nCols = (columns.value as Int).coerceIn(1, 6)
            val nRows = (rows.value as Int).coerceIn(1, 4)
            state[0] = GridLayoutResolver.Props(
                columns = List(nCols) { GridLayoutResolver.Track.Flex(1) },
                rows = List(nRows) { GridLayoutResolver.Track.Flex(1) },
                gap = (gap.value as Int).coerceIn(0, 40),
                justifyItems = GridLayoutResolver.parseAlign(justifyItems.selectedItem as? String, state[0].justifyItems),
                alignItems = GridLayoutResolver.parseAlign(alignItems.selectedItem as? String, state[0].alignItems),
                justifyContent = GridLayoutResolver.parseAlign(justifyContent.selectedItem as? String, state[0].justifyContent),
                alignContent = GridLayoutResolver.parseAlign(alignContent.selectedItem as? String, state[0].alignContent),
                childCount = state[0].childCount
            )
        }

        private fun current(): LayoutModel.Grid = LayoutModel.Grid(state[0])

        private fun apply() {
            val project = editor.project ?: return
            val block = rs.block ?: return
            WriteCommandAction.runWriteCommandAction(project) {
                applyGrid(block, state[0])
            }
        }

        override fun build(): JBPopup {
            val preview = previewPanel(current())
            val form = JPanel(GridBagLayout())
            var row = 0
            fun addRow(label: String, comp: JComponent) {
                val c = GridBagConstraints()
                c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = Insets(2, 0, 2, 8)
                form.add(JLabel(label), c)
                c.gridx = 1; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1.0
                form.add(comp, c); row++
            }
            addRow("columns(1fr)", columns)
            addRow("rows(1fr)", rows)
            addRow("gap", gap)
            addRow("justify-items", justifyItems)
            addRow("align-items", alignItems)
            addRow("justify-content", justifyContent)
            addRow("align-content", alignContent)

            val applyBtn = JButton("应用到样式")
            applyBtn.addActionListener { apply() }
            val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0))
            buttons.add(applyBtn)

            val panel = JPanel(BorderLayout(8, 8))
            panel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
            panel.add(preview, BorderLayout.NORTH)
            panel.add(form, BorderLayout.CENTER)
            panel.add(buttons, BorderLayout.SOUTH)

            return JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, columns)
                .setTitle("Grid 布局预览")
                .setMovable(true).setResizable(false).setCancelKeyEnabled(true)
                .createPopup()
        }
    }

    // ====================================================================
    // 公共：实时画布
    // ====================================================================
    private fun previewPanel(model: LayoutModel): JPanel {
        return object : JPanel() {
            private var current = model
            init {
                preferredSize = Dimension(200, 120)
                background = JBColor(Color(0xf7f7f8), Color(0x2b2d30))
                border = BorderFactory.createLineBorder(JBColor(Color(0xc9cdd4), Color(0x4a4d52)))
            }
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g as Graphics2D
                val pad = 12
                val boxW = width - pad * 2
                val boxH = height - pad * 2
                val boxes = current.boxes(boxW, boxH)
                g2.color = JBColor(Color(0x9aa0aa), Color(0x62666d))
                g2.drawRect(pad, pad, boxW - 1, boxH - 1)
                g2.color = JBColor(Color(0x4f8cff), Color(0x6aa0ff))
                for (b in boxes) {
                    g2.fillRect(pad + b.x, pad + b.y, b.w, b.h)
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

    private fun applyFlex(block: CssBlock, props: FlexLayoutResolver.Props) {
        setOrAdd(block, "justify-content", props.justify.cssValue())
        setOrAdd(block, "align-items", props.align.cssValue())
        setOrAdd(block, "align-content", props.alignContent.cssValue())
        setOrAdd(block, "flex-direction", props.direction.cssValue())
        setOrAdd(block, "flex-wrap", if (props.wrap) "wrap" else "nowrap")
        setOrAdd(block, "gap", "${props.gap}px")
    }

    private fun applyGrid(block: CssBlock, props: GridLayoutResolver.Props) {
        setOrAdd(block, "grid-template-columns", tracksString(props.columns))
        setOrAdd(block, "grid-template-rows", tracksString(props.rows))
        setOrAdd(block, "justify-items", props.justifyItems.cssValue())
        setOrAdd(block, "align-items", props.alignItems.cssValue())
        setOrAdd(block, "justify-content", props.justifyContent.cssValue())
        setOrAdd(block, "align-content", props.alignContent.cssValue())
        setOrAdd(block, "gap", "${props.gap}px")
    }

    @Suppress("DEPRECATION")
    private fun setOrAdd(block: CssBlock, name: String, value: String) {
        val existing = block.findDeclaration(name)
        if (existing != null) {
            existing.setValue(value)
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