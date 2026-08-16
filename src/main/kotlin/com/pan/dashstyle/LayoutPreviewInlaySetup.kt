package com.pan.dashstyle

import com.intellij.codeInsight.hints.ChangeListener
import com.intellij.codeInsight.hints.ImmediateConfigurable
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
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * CSS 布局预览 inlay —— 使用 InlayHintsProvider 框架在 display:flex/grid
 * 及其子属性行尾渲染可点击的布局预览图标。
 */
class CssLayoutPreviewInlayProvider : InlayHintsProvider<CssLayoutPreviewInlayProvider.Settings> {

    class Settings {
        @JvmField var showLayoutPreview: Boolean = true
    }

    override val key: SettingsKey<Settings> = SettingsKey("dashstyle.layout.preview")
    override val name: String = "CSS 布局预览"
    override val description: String = "在 display:flex/grid 及其子属性行尾显示布局预览 inlay。"
    override val group: InlayGroup = InlayGroup.OTHER_GROUP

    override fun createSettings(): Settings = Settings()
    override val previewText: String? = null

    override fun isLanguageSupported(language: Language): Boolean {
        val id = language.id.lowercase()
        return id.contains("css") || id == "less" || id.contains("scss")
    }

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                val cb = JCheckBox("显示布局预览", settings.showLayoutPreview)
                cb.addActionListener {
                    settings.showLayoutPreview = cb.isSelected
                    listener.settingsChanged()
                }
                return JBUI.Panels.simplePanel().addToCenter(cb)
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

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        // 不检查 element !== file，直接处理所有元素
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
                ctx.display.textRange.endOffset, true,
                OverallPreviewPresentation(model), false
            )
            // 属性简便图标（justify-content / align-items / gap 等）
            for ((d, m) in ctx.perProperty) {
                val propName = d.propertyName?.trim()?.lowercase() ?: ""
                sink.addInlineElement(
                    d.textRange.endOffset, true,
                    PropertyPreviewPresentation(propName, m), false
                )
            }
        }
        return true
    }

    private fun previewModelFor(model: LayoutModel): LayoutModel {
        return when (model) {
            is LayoutModel.Flex -> LayoutModel.Flex(model.props.copy(childCount = 2))
            else -> model
        }
    }
}

// ====================================================================
// 点击处理（全局注册一次，双击复用）
// ====================================================================

/** 全局点击处理器注册表。 */
private object ClickHandlerRegistry {
    private val registeredEditors = ConcurrentHashMap<Editor, Boolean>()

    fun register(editor: Editor, file: PsiFile) {
        if (registeredEditors.containsKey(editor)) return
        registeredEditors[editor] = true
        val listener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val mouseEvent = event.mouseEvent
                if (mouseEvent.button != 1) return
                if (editor.isDisposed) return
                val logicalPos = editor.xyToLogicalPosition(mouseEvent.point)
                val offset = editor.logicalPositionToOffset(logicalPos)
                val inlays = editor.inlayModel.getInlineElementsInRange(offset, offset)
                if (inlays.isEmpty()) return
                val project = editor.project ?: return
                val psiFile = file
                val contexts = LayoutContextResolver.contexts(psiFile)
                for (ctx in contexts) {
                    if (offsetInRange(offset, ctx.display)) {
                        val popup = LayoutPreviewPopup.create(editor, ctx.ruleset, ctx.overall)
                        popup.showInBestPositionFor(editor)
                        mouseEvent.consume()
                        return
                    }
                    for ((d, m) in ctx.perProperty) {
                        if (offsetInRange(offset, d)) {
                            val propName = d.propertyName?.trim()?.lowercase() ?: ""
                            val popup = LayoutPreviewPopup.create(editor, ctx.ruleset, m, propName)
                            popup.showInBestPositionFor(editor)
                            mouseEvent.consume()
                            return
                        }
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

    override val width: Int get() = 80 + GAP
    override val height: Int get() = 30

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pad = 2
        val drawX = GAP
        val innerW = 80 - pad * 2
        val innerH = 30 - pad * 2

        g2d.color = OVERALL_BORDER
        g2d.draw(RoundRectangle2D.Float(
            (drawX + pad).toFloat(), pad.toFloat(),
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
        val offX = drawX + pad + (innerW - scaledW) / 2
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

    override val width: Int get() = 50 + GAP
    override val height: Int get() = 20

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val drawX = GAP
        val w = 50
        val h = 20
        val pad = 2

        // 边框
        g2d.color = PROP_BORDER
        g2d.draw(RoundRectangle2D.Float(
            (drawX + pad).toFloat(), pad.toFloat(),
            (w - pad * 2 - 1).toFloat(), (h - pad * 2 - 1).toFloat(), 3f, 3f
        ))

        val innerW = w - pad * 2 - 4
        val innerH = h - pad * 2 - 4
        val cx = drawX + pad + 2
        val cy = pad + 2

        when (propName) {
            "justify-content" -> drawJustifyIcon(g2d, cx, cy, innerW, innerH)
            "align-items" -> drawAlignIcon(g2d, cx, cy, innerW, innerH)
            "gap", "row-gap", "column-gap" -> drawGapIcon(g2d, cx, cy, innerW, innerH)
            "flex-direction" -> drawDirectionIcon(g2d, cx, cy, innerW, innerH)
            "flex-wrap" -> drawWrapIcon(g2d, cx, cy, innerW, innerH)
            "align-content" -> drawAlignContentIcon(g2d, cx, cy, innerW, innerH)
            else -> drawDefaultIcon(g2d, cx, cy, innerW, innerH)
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
        val (py, bh) = flexProps()?.let { fp ->
            when (fp.align) {
                FlexLayoutResolver.Align.FLEX_START -> Pair(0, boxH)
                FlexLayoutResolver.Align.CENTER, FlexLayoutResolver.Align.BASELINE -> Pair((h - boxH) / 2, boxH)
                FlexLayoutResolver.Align.FLEX_END -> Pair(h - boxH, boxH)
                FlexLayoutResolver.Align.STRETCH -> Pair(0, h)
            }
        } ?: gridProps()?.let { gp ->
            when (gp.alignItems) {
                GridLayoutResolver.GridAlign.START -> Pair(0, boxH)
                GridLayoutResolver.GridAlign.CENTER -> Pair((h - boxH) / 2, boxH)
                GridLayoutResolver.GridAlign.END -> Pair(h - boxH, boxH)
                GridLayoutResolver.GridAlign.STRETCH -> Pair(0, h)
            }
        } ?: return
        g2d.color = PROP_CHILD
        g2d.fill(RoundRectangle2D.Float(
            boxX.toFloat(), (y + py).toFloat(), boxW.toFloat(), bh.toFloat(), 1.5f, 1.5f))
    }

    private fun drawGapIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val gapVal = flexProps()?.gap ?: gridProps()?.gap ?: return
        val boxW = 6
        val boxH = (h * 0.6).toInt().coerceAtLeast(4)
        val boxY = y + (h - boxH) / 2
        val gapNorm = gapVal.coerceIn(0, 20)
        val gapPx = (gapNorm.toFloat() / 20 * (w - boxW * 2)).toInt().coerceAtLeast(2)

        g2d.color = PROP_CHILD
        g2d.fill(RoundRectangle2D.Float(x.toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (x + boxW + gapPx).toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
    }

    private fun drawDirectionIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val props = flexProps() ?: return
        val isRow = props.direction == FlexLayoutResolver.Direction.ROW ||
                props.direction == FlexLayoutResolver.Direction.ROW_REVERSE
        g2d.color = PROP_CHILD
        if (isRow) {
            val boxH = (h * 0.5).toInt().coerceAtLeast(4)
            val boxY = y + (h - boxH) / 2
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), boxY.toFloat(), (w * 0.3).toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float((x + w * 0.4f).toFloat(), boxY.toFloat(), (w * 0.3).toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        } else {
            val boxW = (w * 0.5).toInt().coerceAtLeast(4)
            val boxX = x + (w - boxW) / 2
            g2d.fill(RoundRectangle2D.Float(boxX.toFloat(), y.toFloat(), boxW.toFloat(), (h * 0.3).toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(boxX.toFloat(), (y + h * 0.4f).toFloat(), boxW.toFloat(), (h * 0.3).toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawWrapIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val props = flexProps() ?: return
        val pw = (w * 0.2).toInt().coerceAtLeast(3)
        val ph = (h * 0.3).toInt().coerceAtLeast(3)
        g2d.color = PROP_CHILD
        if (props.wrap) {
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            g2d.fill(RoundRectangle2D.Float((x + pw + 2).toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), (y + ph + 2).toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            g2d.fill(RoundRectangle2D.Float((x + pw + 2).toFloat(), (y + ph + 2).toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
        } else {
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            g2d.fill(RoundRectangle2D.Float((x + pw + 2).toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            g2d.fill(RoundRectangle2D.Float((x + (pw + 2) * 2).toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
        }
    }

    private fun drawAlignContentIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        val boxW = (w * 0.35).toInt().coerceAtLeast(4)
        val boxH = (h * 0.25).toInt().coerceAtLeast(3)
        val positions = flexProps()?.let { fp ->
            when (fp.alignContent) {
                FlexLayoutResolver.AlignContent.FLEX_START -> listOf(0, h / 2)
                FlexLayoutResolver.AlignContent.CENTER -> listOf(h / 4, h * 3 / 4 - boxH)
                FlexLayoutResolver.AlignContent.FLEX_END -> listOf(h - boxH * 2, h - boxH)
                else -> listOf(0, h / 2)
            }
        } ?: gridProps()?.let { gp ->
            when (gp.alignContent) {
                GridLayoutResolver.GridAlign.START -> listOf(0, h / 2)
                GridLayoutResolver.GridAlign.CENTER -> listOf(h / 4, h * 3 / 4 - boxH)
                GridLayoutResolver.GridAlign.END -> listOf(h - boxH * 2, h - boxH)
                GridLayoutResolver.GridAlign.STRETCH -> listOf(0, h / 2)
            }
        } ?: return
        g2d.color = PROP_CHILD
        for (py in positions) {
            g2d.fill(RoundRectangle2D.Float(x.toFloat(), (y + py).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawDefaultIcon(g2d: Graphics2D, x: Int, y: Int, w: Int, h: Int) {
        g2d.color = PROP_CHILD
        g2d.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), 4f, 4f, 1f, 1f))
        g2d.fill(RoundRectangle2D.Float((x + 8).toFloat(), y.toFloat(), 4f, 4f, 1f, 1f))
        g2d.fill(RoundRectangle2D.Float(x.toFloat(), (y + 8).toFloat(), 4f, 4f, 1f, 1f))
    }

    override fun toString(): String = "PropertyPreview($propName)"
}

// ====================================================================
// 颜色常量
// ====================================================================

private const val GAP = 6

private val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
private val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
private val PROP_BORDER: JBColor = JBColor(Color(0x9aa0aa), Color(0x5a5e65))
private val PROP_CHILD: JBColor = JBColor(Color(0x6a7a8a), Color(0x7a8a9a))