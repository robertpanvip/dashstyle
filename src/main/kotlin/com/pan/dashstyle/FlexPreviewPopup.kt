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
 * CSS Flex 预览的交互弹窗。
 *
 * 点击行尾的迷你布局图后弹出，包含：
 *  - 一块实时画布：随控件调整即时重绘布局（复用 [FlexLayoutResolver] 的摆位逻辑）
 *  - 各 flex 属性控件：justify-content / align-items / flex-direction / flex-wrap / gap
 *  - 「应用到样式」按钮：把当前选中的值写回 CSS ruleset（已存在则改值，缺失则新增）
 */
object FlexPreviewPopup {

    fun create(
        editor: Editor,
        rs: CssRuleset,
        initial: FlexLayoutResolver.Props
    ): JBPopup {
        val p = initial
        val state = object {
            var props: FlexLayoutResolver.Props = p
        }

        // ---- 实时预览画布 -------------------------------------------------
        val preview = object : JPanel() {
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
                val boxes = FlexLayoutResolver.place(state.props.copy(childCount = 3), boxW, boxH)
                g2.color = JBColor(Color(0x9aa0aa), Color(0x62666d))
                g2.drawRect(pad, pad, boxW - 1, boxH - 1)
                g2.color = JBColor(Color(0x4f8cff), Color(0x6aa0ff))
                for (b in boxes) g2.fillRect(pad + b.x, pad + b.y, b.w, b.h)
            }
        }

        // ---- 控件 ---------------------------------------------------------
        val justify = JComboBox(arrayOf("flex-start", "flex-end", "center", "space-between", "space-around", "space-evenly"))
        val align = JComboBox(arrayOf("stretch", "flex-start", "flex-end", "center", "baseline"))
        val direction = JComboBox(arrayOf("row", "row-reverse", "column", "column-reverse"))
        val wrap = JComboBox(arrayOf("nowrap", "wrap"))
        val gap = JSpinner(SpinnerNumberModel(0, 0, 40, 1))

        justify.selectedItem = p.justify.cssValue()
        align.selectedItem = p.align.cssValue()
        direction.selectedItem = p.direction.cssValue()
        wrap.selectedItem = if (p.wrap) "wrap" else "nowrap"
        gap.value = p.gap

        fun refreshPreview() {
            state.props = FlexLayoutResolver.Props(
                direction = FlexLayoutResolver.parseDirection(direction.selectedItem as? String, state.props.direction),
                justify = FlexLayoutResolver.parseJustify(justify.selectedItem as? String, state.props.justify),
                align = FlexLayoutResolver.parseAlign(align.selectedItem as? String, state.props.align),
                gap = (gap.value as Int).coerceIn(0, 40),
                wrap = (wrap.selectedItem as? String) == "wrap",
                childCount = state.props.childCount
            )
            preview.repaint()
        }

        justify.addActionListener { refreshPreview() }
        align.addActionListener { refreshPreview() }
        direction.addActionListener { refreshPreview() }
        wrap.addActionListener { refreshPreview() }
        gap.addChangeListener { refreshPreview() }

        // ---- 应用回 CSS ---------------------------------------------------
        fun apply() {
            val project = editor.project ?: return
            val block = rs.block ?: return
            WriteCommandAction.runWriteCommandAction(project) {
                applyToBlock(block, state.props)
            }
        }

        // ---- 组装面板 -----------------------------------------------------
        val form = JPanel(GridBagLayout())
        var row = 0
        fun addRow(label: String, comp: JComponent) {
            val c = GridBagConstraints()
            c.gridx = 0; c.gridy = row
            c.anchor = GridBagConstraints.WEST
            c.insets = Insets(2, 0, 2, 8)
            form.add(JLabel(label), c)
            c.gridx = 1
            c.fill = GridBagConstraints.HORIZONTAL
            c.weightx = 1.0
            form.add(comp, c)
            row++
        }
        addRow("justify-content", justify)
        addRow("align-items", align)
        addRow("flex-direction", direction)
        addRow("flex-wrap", wrap)
        addRow("gap", gap)

        val applyBtn = JButton("应用到样式")
        applyBtn.addActionListener {
            apply() // 写回 CSS 后行内 inlay 会随高亮通道自动刷新
        }

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
            .setMovable(true)
            .setResizable(false)
            .setCancelKeyEnabled(true)
            .createPopup()
    }

    /**
     * 把 [props] 的 flex 值写回 [block]：已存在的声明改值，缺失的声明新增。
     * 独立成公共函数便于测试。
     */
    fun applyToBlock(block: CssBlock, props: FlexLayoutResolver.Props) {
        setOrAdd(block, "justify-content", props.justify.cssValue())
        setOrAdd(block, "align-items", props.align.cssValue())
        setOrAdd(block, "flex-direction", props.direction.cssValue())
        setOrAdd(block, "flex-wrap", if (props.wrap) "wrap" else "nowrap")
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

    private fun FlexLayoutResolver.Direction.cssValue(): String = when (this) {
        FlexLayoutResolver.Direction.ROW -> "row"
        FlexLayoutResolver.Direction.ROW_REVERSE -> "row-reverse"
        FlexLayoutResolver.Direction.COLUMN -> "column"
        FlexLayoutResolver.Direction.COLUMN_REVERSE -> "column-reverse"
    }
}