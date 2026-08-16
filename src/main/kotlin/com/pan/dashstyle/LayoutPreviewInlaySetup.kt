package com.pan.dashstyle

import com.intellij.codeInsight.hints.InlayGroup
import com.intellij.codeInsight.hints.InlayHintsCollector
import com.intellij.codeInsight.hints.InlayHintsProvider
import com.intellij.codeInsight.hints.InlayHintsSink
import com.intellij.codeInsight.hints.SettingsKey
import com.intellij.codeInsight.hints.presentation.BasePresentation
import com.intellij.lang.Language
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D

/**
 * CSS 布局预览 inlay —— 通过 InlayHintsProvider 框架实现。
 *
 * 在 display:flex/grid 及其子属性行尾显示布局预览 inlay。
 */
class CssLayoutPreviewInlayProvider : InlayHintsProvider<CssLayoutPreviewInlayProvider.Settings> {

    class Settings {
        @JvmField var showLayoutPreview: Boolean = true
    }

    override val key: SettingsKey<Settings> = SettingsKey("dashstyle.layout.preview")
    override val name: String = "CSS 布局预览"
    override val description: String =
        "在 display:flex/grid 及其子属性（justify-content、align-items、gap 等）行尾显示布局预览 inlay。"
    override val group: InlayGroup = InlayGroup.OTHER_GROUP

    override fun createSettings(): Settings = Settings()

    override val previewText: String? = null

    override fun isLanguageSupported(language: Language): Boolean {
        val id = language.id.lowercase()
        return id.contains("css") || id == "less" || id.contains("scss")
    }

    override fun createConfigurable(settings: Settings): com.intellij.codeInsight.hints.ImmediateConfigurable {
        return object : com.intellij.codeInsight.hints.ImmediateConfigurable {
            override fun createComponent(listener: com.intellij.codeInsight.hints.ChangeListener): javax.swing.JComponent {
                val checkBox = javax.swing.JCheckBox("显示布局预览（flex/grid）", settings.showLayoutPreview)
                checkBox.addActionListener {
                    settings.showLayoutPreview = checkBox.isSelected
                    listener.settingsChanged()
                }
                val box = javax.swing.Box.createVerticalBox()
                box.add(checkBox)
                return com.intellij.util.ui.JBUI.Panels.simplePanel().addToCenter(box)
            }

            override val mainCheckboxText: String get() = "启用 CSS 布局预览"
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: Settings,
        sink: InlayHintsSink
    ): InlayHintsCollector? {
        if (!settings.showLayoutPreview) return null
        if (!isStylesheetLike(file)) return null
        return LayoutHintCollector(file, editor, sink)
    }

    private fun isStylesheetLike(file: PsiFile): Boolean {
        val rc = file.javaClass.name
        if (rc.contains("StylesheetFile", true) || rc.contains("CssFile", true) ||
            rc.contains("ScssFile", true) || rc.contains("LessFile", true) ||
            rc.contains("SassFile", true)) return true
        val ext = file.virtualFile?.extension?.lowercase()
        return ext in setOf("css", "scss", "sass", "less")
    }

    override fun toString(): String = "CssLayoutPreviewInlayProvider"
}

/**
 * 布局提示收集器：遍历 CSS 文件中的 CssRuleset，为 display:flex/grid
 * 及其子属性添加可点击的 inlay 图标。
 */
private class LayoutHintCollector(
    private val file: PsiFile,
    private val editor: Editor,
    private val sink: InlayHintsSink
) : InlayHintsCollector {

    /** 延迟注册鼠标监听（只注册一次）。 */
    private var mouseListenerRegistered = false
    /** 是否已添加 inlay，防止重复处理。 */
    private var collected = false

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (collected) return false
        collected = true
        // 注册鼠标监听（仅一次）
        if (!mouseListenerRegistered) {
            ClickHandlerRegistry.register(editor, file)
            mouseListenerRegistered = true
        }
        val contexts = LayoutContextResolver.contexts(file)
        for (ctx in contexts) {
            // overall 布局预览（display:flex/grid 行）
            val model = previewModelFor(ctx.overall)
            sink.addInlineElement(
                ctx.display.textRange.endOffset, false,
                OverallPreviewPresentation(model), false
            )
            // 属性简便图标（justify-content / align-items / gap 等）
            for ((d, m) in ctx.perProperty) {
                val propName = d.propertyName?.trim()?.lowercase() ?: ""
                sink.addInlineElement(
                    d.textRange.endOffset, false,
                    PropertyPreviewPresentation(propName, m), false
                )
            }
        }
        return false
    }

    private fun previewModelFor(model: LayoutModel): LayoutModel {
        return when (model) {
            is LayoutModel.Flex -> LayoutModel.Flex(model.props.copy(childCount = 2))
            else -> model
        }
    }
}

/**
 * 鼠标点击处理器，用于 inlay 点击弹出布局调整面板。
 */
object ClickHandlerRegistry {

    private val registeredEditors = java.util.concurrent.ConcurrentHashMap.newKeySet<Editor>()

    fun register(editor: Editor, file: PsiFile) {
        if (!registeredEditors.add(editor)) return
        val listener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val e = event.mouseEvent
                if (e.clickCount < 1) return
                val offset = editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.point))
                val contexts = LayoutContextResolver.contexts(file)
                for (ctx in contexts) {
                    // 先检查子属性（justify-content / align-items / gap 等），避免 +30 范围覆盖 display 的弹出
                    for ((d, m) in ctx.perProperty) {
                        if (offsetInRange(offset, d)) {
                            val propName = d.propertyName?.trim()?.lowercase() ?: ""
                            val popup = LayoutPreviewPopup.create(editor, ctx.ruleset, m, propName)
                            popup.showInBestPositionFor(editor)
                            e.consume()
                            return
                        }
                    }
                    if (offsetInRange(offset, ctx.display)) {
                        val popup = LayoutPreviewPopup.create(editor, ctx.ruleset, ctx.overall)
                        popup.showInBestPositionFor(editor)
                        e.consume()
                        return
                    }
                }
            }
        }
        editor.addEditorMouseListener(listener)
    }

    private fun offsetInRange(offset: Int, decl: CssDeclaration): Boolean {
        return offset >= decl.textRange.startOffset && offset <= decl.textRange.endOffset + 30
    }
}

// ====================================================================
// 整体布局预览（display:flex/grid 行）
// ====================================================================

private class OverallPreviewPresentation(
    private val model: LayoutModel
) : BasePresentation() {

    override val width: Int get() = 90
    override val height: Int get() = 30

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pad = 4
        val innerW = width - pad * 2   // 82
        val innerH = height - pad * 2  // 22

        // 边框居中
        g2d.color = OVERALL_BORDER
        g2d.draw(RoundRectangle2D.Float(
            pad.toFloat(), pad.toFloat(),
            (innerW - 1).toFloat(), (innerH - 1).toFloat(), 3f, 3f
        ))

        val boxes = model.boxes(innerW, innerH)
        if (boxes.isEmpty()) return

        val minX = boxes.minOf { it.x }
        val minY = boxes.minOf { it.y }
        val maxX = boxes.maxOf { it.x + it.w }
        val maxY = boxes.maxOf { it.y + it.h }
        val bbW = (maxX - minX).coerceAtLeast(1)
        val bbH = (maxY - minY).coerceAtLeast(1)
        val scale = minOf(innerW.toFloat() / bbW, innerH.toFloat() / bbH) * 0.85f
        val scaledW = (bbW * scale).toInt()
        val scaledH = (bbH * scale).toInt()
        val offX = pad + (innerW - scaledW) / 2
        val offY = pad + (innerH - scaledH) / 2

        g2d.color = OVERALL_CHILD
        for (b in boxes) {
            val sx = offX + ((b.x - minX) * scale).toInt()
            val sy = offY + ((b.y - minY) * scale).toInt()
            val sw = ((b.w * scale).coerceAtLeast(2.0f)).toInt()
            val sh = ((b.h * scale).coerceAtLeast(2.0f)).toInt()
            g2d.fill(RoundRectangle2D.Float(
                sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), 2f, 2f))
        }
    }

    override fun toString(): String = "OverallPreview"
}

// ====================================================================
// 属性简便图标（justify-content / align-items / gap 等）
// ====================================================================

private class PropertyPreviewPresentation(
    private val propName: String,
    private val model: LayoutModel
) : BasePresentation() {

    override val width: Int get() = 60
    override val height: Int get() = 20

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pad = 4
        val w = width
        val h = height
        val innerW = w - pad * 2   // 52
        val innerH = h - pad * 2   // 12

        // 边框居中
        g2d.color = PROP_BORDER
        g2d.draw(RoundRectangle2D.Float(
            pad.toFloat(), pad.toFloat(),
            (innerW - 1).toFloat(), (innerH - 1).toFloat(), 3f, 3f
        ))

        // 图标在边框内居中（与之前一致的绘制区域大小）
        val iconW = innerW - 10   // 42
        val iconH = innerH - 4    // 8
        val cx = pad + (innerW - iconW) / 2  // 9
        val cy = pad + (innerH - iconH) / 2  // 6

        when (propName) {
            "justify-content" -> drawJustifyIcon(g2d, cx, cy, iconW, iconH)
            "align-items" -> drawAlignIcon(g2d, cx, cy, iconW, iconH)
            "gap", "row-gap", "column-gap" -> drawGapIcon(g2d, cx, cy, iconW, iconH)
            "flex-direction" -> drawDirectionIcon(g2d, cx, cy, iconW, iconH)
            "flex-wrap" -> drawWrapIcon(g2d, cx, cy, iconW, iconH)
            "align-content" -> drawAlignContentIcon(g2d, cx, cy, iconW, iconH)
            else -> drawDefaultIcon(g2d, cx, cy, iconW, iconH)
        }
    }

    private fun flexProps(): FlexLayoutResolver.Props? =
        (model as? LayoutModel.Flex)?.props

    private fun gridProps(): GridLayoutResolver.Props? =
        (model as? LayoutModel.Grid)?.props

    private fun drawJustifyIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val boxW = 6
        val boxH = (h * 0.6).toInt().coerceAtLeast(4)
        val boxY = y + (h - boxH) / 2
        val positions = flexProps()?.let { fp ->
            when (fp.justify) {
                FlexLayoutResolver.Justify.FLEX_START -> listOf(0)
                FlexLayoutResolver.Justify.CENTER -> listOf((w - boxW) / 2)
                FlexLayoutResolver.Justify.FLEX_END -> listOf(w - boxW)
                FlexLayoutResolver.Justify.SPACE_BETWEEN -> listOf(0, w - boxW)
                FlexLayoutResolver.Justify.SPACE_AROUND -> listOf(0, w - boxW)
                FlexLayoutResolver.Justify.SPACE_EVENLY -> listOf(0, w - boxW)
            }
        } ?: gridProps()?.let { gp ->
            when (gp.justifyContent) {
                GridLayoutResolver.GridAlign.START -> listOf(0)
                GridLayoutResolver.GridAlign.CENTER -> listOf((w - boxW) / 2)
                GridLayoutResolver.GridAlign.END -> listOf(w - boxW)
                GridLayoutResolver.GridAlign.STRETCH -> listOf(0, w - boxW)
            }
        } ?: return
        g2d.color = PROP_CHILD
        for (px in positions) {
            g2d.fill(RoundRectangle2D.Float(
                (x + px).toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawAlignIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val boxW = (w * 0.6).toInt().coerceAtLeast(4)
        val boxH = 6
        val boxX = x + (w - boxW) / 2
        val pair = (flexProps()?.let { fp ->
            when (fp.align) {
                FlexLayoutResolver.Align.FLEX_START -> 0 to (h * 0.3).toInt().coerceAtLeast(2)
                FlexLayoutResolver.Align.CENTER -> (h - boxH) / 2 to boxH
                FlexLayoutResolver.Align.FLEX_END -> h - boxH to boxH
                FlexLayoutResolver.Align.STRETCH -> 0 to h
                FlexLayoutResolver.Align.BASELINE -> 0 to (h * 0.3).toInt().coerceAtLeast(2)
            }
        } ?: gridProps()?.let { gp ->
            when (gp.alignItems) {
                GridLayoutResolver.GridAlign.START -> 0 to (h * 0.3).toInt().coerceAtLeast(2)
                GridLayoutResolver.GridAlign.CENTER -> (h - boxH) / 2 to boxH
                GridLayoutResolver.GridAlign.END -> h - boxH to boxH
                GridLayoutResolver.GridAlign.STRETCH -> 0 to h
            }
        }) ?: return
        val (py, bh) = pair as Pair<Int, Int>
        g2d.color = PROP_CHILD
        g2d.fill(RoundRectangle2D.Float(
            boxX.toFloat(), (y + py).toFloat(), boxW.toFloat(), bh.toFloat(), 1.5f, 1.5f))
    }

    private fun drawGapIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2d.color = PROP_CHILD
        val gap = flexProps()?.gap?.coerceIn(1, 10) ?: 4
        val segW = ((w - gap * 2) / 3).coerceAtLeast(2)
        val segH = (h * 0.6).toInt().coerceAtLeast(4)
        val segY = y + (h - segH) / 2
        g2d.fill(RoundRectangle2D.Float(x.toFloat(), segY.toFloat(), segW.toFloat(), segH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (x + segW + gap).toFloat(), segY.toFloat(), segW.toFloat(), segH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (x + (segW + gap) * 2).toFloat(), segY.toFloat(), segW.toFloat(), segH.toFloat(), 1.5f, 1.5f))
    }

    private fun drawDirectionIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2d.color = PROP_CHILD
        val dir = flexProps()?.direction ?: FlexLayoutResolver.Direction.ROW
        val boxW = (w * 0.35).toInt().coerceAtLeast(4)
        val boxH = (h * 0.35).toInt().coerceAtLeast(4)
        if (dir == FlexLayoutResolver.Direction.ROW || dir == FlexLayoutResolver.Direction.ROW_REVERSE) {
            // 水平排列
            val y1 = y
            val y2 = h - boxH
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), y1.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(
                (x + w - boxW).toFloat(), y2.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        } else {
            // 垂直排列
            val x1 = x
            val x2 = w - boxW
            g2d.fill(RoundRectangle2D.Float(x1.toFloat(), y.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(
                x2.toFloat(), (y + h - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawWrapIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2d.color = PROP_CHILD
        val wrap = flexProps()?.wrap == true
        val boxW = (w * 0.35).toInt().coerceAtLeast(4)
        val boxH = (h * 0.35).toInt().coerceAtLeast(4)
        if (wrap) {
            // 多行
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(
                (x + w - boxW).toFloat(), (y + h - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        } else {
            // 单行
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), (y + (h - boxH) / 2).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(
                (x + w - boxW).toFloat(), (y + (h - boxH) / 2).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawAlignContentIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2d.color = PROP_CHILD
        val ac = flexProps()?.alignContent ?: FlexLayoutResolver.AlignContent.STRETCH
        val boxW = (w * 0.35).toInt().coerceAtLeast(4)
        val boxH = (h * 0.35).toInt().coerceAtLeast(4)
        val gap = 2
        when (ac) {
            FlexLayoutResolver.AlignContent.FLEX_START -> {
                g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), y.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.CENTER -> {
                val cy = y + (h - boxH * 2 - gap) / 2
                g2d.fill(RoundRectangle2D.Float(x.toFloat(), cy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), (cy + boxH + gap).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.FLEX_END -> {
                g2d.fill(RoundRectangle2D.Float(
                    x.toFloat(), (y + h - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), (y + h - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.STRETCH -> {
                val sh = (h - gap) / 2
                g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), boxW.toFloat(), sh.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), (y + sh + gap).toFloat(), boxW.toFloat(), sh.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.SPACE_BETWEEN -> {
                g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), (y + h - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.SPACE_AROUND -> {
                val cy = y + (h - boxH * 2 - gap) / 2
                g2d.fill(RoundRectangle2D.Float(x.toFloat(), cy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), (cy + boxH + gap).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.SPACE_EVENLY -> {
                val cy = y + (h - boxH * 2 - gap) / 2
                g2d.fill(RoundRectangle2D.Float(x.toFloat(), cy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (x + w - boxW).toFloat(), (cy + boxH + gap).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
        }
    }

    private fun drawDefaultIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2d.color = PROP_CHILD
        val boxW = (w * 0.35).toInt().coerceAtLeast(4)
        val boxH = (h * 0.35).toInt().coerceAtLeast(4)
        g2d.fill(RoundRectangle2D.Float(x.toFloat(), (y + (h - boxH) / 2).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (x + w - boxW).toFloat(), (y + (h - boxH) / 2).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
    }

    override fun toString(): String = "PropertyPreview($propName)"
}

// ====================================================================
// 通用颜色常量
// ====================================================================

private val OVERALL_BORDER = JBColor(Color(0x808080), Color(0x808080))
private val OVERALL_CHILD = JBColor(Color(0x4a90d9), Color(0x5a9fe6))
private val PROP_BORDER = JBColor(Color(0x999999), Color(0x999999))
private val PROP_CHILD = JBColor(Color(0x4a90d9), Color(0x5a9fe6))