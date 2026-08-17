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
        return LayoutHintCollector(file, editor)
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
    private val editor: Editor
) : InlayHintsCollector {

    private val contexts by lazy {
        ClickHandlerRegistry.register(editor, file)
        LayoutContextResolver.contexts(file)
    }

    override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
        if (element !is CssDeclaration) return true

        for (ctx in contexts) {
            // display:flex/grid 行 → 整体预览
            if (element === ctx.display) {
                val model = previewModelFor(ctx.overall)
                sink.addInlineElement(element.textRange.endOffset, false,
                    OverallPreviewPresentation(model), false)
                return true
            }
            // 子属性行 → 属性图标
            for ((d, m) in ctx.perProperty) {
                if (element === d) {
                    val propName = d.propertyName?.trim()?.lowercase() ?: ""
                    sink.addInlineElement(d.textRange.endOffset, false,
                        PropertyPreviewPresentation(propName, m), false)
                    return true
                }
            }
        }
        return true
    }

    private fun previewModelFor(model: LayoutModel): LayoutModel =
        if (model is LayoutModel.Flex) LayoutModel.Flex(model.props.copy(childCount = 4)) else model
}

/**
 * 鼠标点击处理器，用于 inlay 点击弹出布局调整面板。
 *
 * 通过 [EditorMouseEvent.inlay] 精确定位被点击的 inlay，
 * 再按 inlay offset 精确匹配对应的 CssDeclaration，避免模糊范围错位。
 */
object ClickHandlerRegistry {

    private val registeredEditors = java.util.concurrent.ConcurrentHashMap.newKeySet<Editor>()

    fun register(editor: Editor, file: PsiFile) {
        if (!registeredEditors.add(editor)) return
        val listener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val e = event.mouseEvent
                if (e.clickCount < 1) return

                // 优先用 event.inlay 精确匹配，为 null 时回退到 offset 匹配
                val inlay = event.inlay
                val clickOffset = inlay?.offset
                    ?: editor.logicalPositionToOffset(editor.xyToLogicalPosition(e.point))

                val contexts = LayoutContextResolver.contexts(file)
                for (ctx in contexts) {
                    // 先检查子属性（justify-content / align-items / gap 等）
                    for ((d, m) in ctx.perProperty) {
                        if (clickOffset == d.textRange.endOffset) {
                            val propName = d.propertyName?.trim()?.lowercase() ?: ""
                            val popup = LayoutPreviewPopup.create(editor, ctx.ruleset, m, propName)
                            popup.showInBestPositionFor(editor)
                            e.consume()
                            return
                        }
                    }
                    // 再检查 display
                    if (clickOffset == ctx.display.textRange.endOffset) {
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
}

// ====================================================================
// 整体布局预览（display:flex/grid 行）
// ====================================================================

private class OverallPreviewPresentation(
    private val model: LayoutModel
) : BasePresentation() {

    override val width: Int get() = 110
    override val height: Int get() = 36

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val w = width
        val h = height

        // 左边距 12px（与前面文字拉开间距），垂直居中
        val bx = 12
        val by = 4
        val bw = w - bx - 3   // 95
        val bh = h - by * 2   // 28

        g2d.color = OVERALL_BORDER
        g2d.draw(RoundRectangle2D.Float(bx.toFloat(), by.toFloat(), bw.toFloat(), bh.toFloat(), 3f, 3f))

        // 子元素在边框内区域计算
        val innerW = bw - 2
        val innerH = bh - 2
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
        val ox = bx + 1 + (innerW - scaledW) / 2
        val oy = by + 1 + (innerH - scaledH) / 2

        val oldStroke = g2d.stroke
        g2d.stroke = java.awt.BasicStroke(1f)
        g2d.color = OVERALL_CHILD
        for (b in boxes) {
            val sx = ox + ((b.x - minX) * scale).toInt()
            val sy = oy + ((b.y - minY) * scale).toInt()
            val sw = ((b.w * scale).coerceAtLeast(2.0f)).toInt()
            val sh = ((b.h * scale).coerceAtLeast(2.0f)).toInt()
            val r = RoundRectangle2D.Float(sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), 2f, 2f)
            g2d.fill(r)
            g2d.color = OVERALL_CHILD_BORDER
            g2d.draw(r)
            g2d.color = OVERALL_CHILD
        }
        g2d.stroke = oldStroke
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

    override val width: Int get() = 80
    override val height: Int get() = 32

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val w = width
        val h = height

        // 左边距 12px（与前面文字拉开间距），垂直居中
        g2d.color = PROP_BORDER
        g2d.draw(RoundRectangle2D.Float(12f, 3f, (w - 14).toFloat(), (h - 6).toFloat(), 3f, 3f))

        // 图标在边框内居中绘制，drawXxx 自行处理内部居中
        when (propName) {
            "justify-content" -> drawJustifyIcon(g2d, w, h)
            "align-items" -> drawAlignIcon(g2d, w, h)
            "gap", "row-gap", "column-gap" -> drawGapIcon(g2d, w, h)
            "flex-direction" -> drawDirectionIcon(g2d, w, h)
            "flex-wrap" -> drawWrapIcon(g2d, w, h)
            "align-content" -> drawAlignContentIcon(g2d, w, h)
            else -> drawDefaultIcon(g2d, w, h)
        }
    }

    private fun flexProps(): FlexLayoutResolver.Props? =
        (model as? LayoutModel.Flex)?.props

    private fun gridProps(): GridLayoutResolver.Props? =
        (model as? LayoutModel.Grid)?.props

    private fun drawJustifyIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        val boxW = 6
        val boxH = (ih * 0.6).toInt().coerceAtLeast(4)
        val boxY = iy + (ih - boxH) / 2
        val positions = flexProps()?.let { fp ->
            when (fp.justify) {
                FlexLayoutResolver.Justify.FLEX_START -> listOf(0)
                FlexLayoutResolver.Justify.CENTER -> listOf((iw - boxW) / 2)
                FlexLayoutResolver.Justify.FLEX_END -> listOf(iw - boxW)
                FlexLayoutResolver.Justify.SPACE_BETWEEN -> listOf(0, iw - boxW)
                FlexLayoutResolver.Justify.SPACE_AROUND -> listOf(0, iw - boxW)
                FlexLayoutResolver.Justify.SPACE_EVENLY -> listOf(0, iw - boxW)
            }
        } ?: gridProps()?.let { gp ->
            when (gp.justifyContent) {
                GridLayoutResolver.GridAlign.START -> listOf(0)
                GridLayoutResolver.GridAlign.CENTER -> listOf((iw - boxW) / 2)
                GridLayoutResolver.GridAlign.END -> listOf(iw - boxW)
                GridLayoutResolver.GridAlign.STRETCH -> listOf(0, iw - boxW)
            }
        } ?: return
        g2d.color = PROP_CHILD
        for (px in positions) {
            g2d.fill(RoundRectangle2D.Float(
                (ix + px).toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawAlignIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        val boxW = (iw * 0.6).toInt().coerceAtLeast(4)
        val boxH = 6
        val boxX = ix + (iw - boxW) / 2
        val pair = (flexProps()?.let { fp ->
            when (fp.align) {
                FlexLayoutResolver.Align.FLEX_START -> 0 to (ih * 0.3).toInt().coerceAtLeast(2)
                FlexLayoutResolver.Align.CENTER -> (ih - boxH) / 2 to boxH
                FlexLayoutResolver.Align.FLEX_END -> ih - boxH to boxH
                FlexLayoutResolver.Align.STRETCH -> 0 to ih
                FlexLayoutResolver.Align.BASELINE -> 0 to (ih * 0.3).toInt().coerceAtLeast(2)
            }
        } ?: gridProps()?.let { gp ->
            when (gp.alignItems) {
                GridLayoutResolver.GridAlign.START -> 0 to (ih * 0.3).toInt().coerceAtLeast(2)
                GridLayoutResolver.GridAlign.CENTER -> (ih - boxH) / 2 to boxH
                GridLayoutResolver.GridAlign.END -> ih - boxH to boxH
                GridLayoutResolver.GridAlign.STRETCH -> 0 to ih
            }
        }) ?: return
        val (py, bh) = pair as Pair<Int, Int>
        g2d.color = PROP_CHILD
        g2d.fill(RoundRectangle2D.Float(
            boxX.toFloat(), (iy + py).toFloat(), boxW.toFloat(), bh.toFloat(), 1.5f, 1.5f))
    }

    private fun drawGapIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        g2d.color = PROP_CHILD
        val gap = flexProps()?.gap?.coerceIn(1, 10) ?: 4
        val segW = ((iw - gap * 2) / 3).coerceAtLeast(2)
        val segH = (ih * 0.6).toInt().coerceAtLeast(4)
        val segY = iy + (ih - segH) / 2
        g2d.fill(RoundRectangle2D.Float(ix.toFloat(), segY.toFloat(), segW.toFloat(), segH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (ix + segW + gap).toFloat(), segY.toFloat(), segW.toFloat(), segH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (ix + (segW + gap) * 2).toFloat(), segY.toFloat(), segW.toFloat(), segH.toFloat(), 1.5f, 1.5f))
    }

    private fun drawDirectionIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        g2d.color = PROP_CHILD
        val dir = flexProps()?.direction ?: FlexLayoutResolver.Direction.ROW
        val boxW = (iw * 0.35).toInt().coerceAtLeast(4)
        val boxH = (ih * 0.35).toInt().coerceAtLeast(4)
        g2d.fill(RoundRectangle2D.Float(ix.toFloat(), iy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (ix + iw - boxW).toFloat(), (iy + ih - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
    }

    private fun drawWrapIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        g2d.color = PROP_CHILD
        val wrap = flexProps()?.wrap == true
        val boxW = (iw * 0.35).toInt().coerceAtLeast(4)
        val boxH = (ih * 0.35).toInt().coerceAtLeast(4)
        if (wrap) {
            g2d.fill(RoundRectangle2D.Float(ix.toFloat(), iy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(
                (ix + iw - boxW).toFloat(), (iy + ih - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        } else {
            val midY = iy + (ih - boxH) / 2
            g2d.fill(RoundRectangle2D.Float(ix.toFloat(), midY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2d.fill(RoundRectangle2D.Float(
                (ix + iw - boxW).toFloat(), midY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }
    }

    private fun drawAlignContentIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        g2d.color = PROP_CHILD
        val ac = flexProps()?.alignContent ?: FlexLayoutResolver.AlignContent.STRETCH
        val boxW = (iw * 0.35).toInt().coerceAtLeast(4)
        val boxH = (ih * 0.35).toInt().coerceAtLeast(4)
        val gap = 2
        when (ac) {
            FlexLayoutResolver.AlignContent.FLEX_START -> {
                g2d.fill(RoundRectangle2D.Float(ix.toFloat(), iy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), iy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.CENTER -> {
                val cy = iy + (ih - boxH * 2 - gap) / 2
                g2d.fill(RoundRectangle2D.Float(ix.toFloat(), cy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), (cy + boxH + gap).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.FLEX_END -> {
                g2d.fill(RoundRectangle2D.Float(
                    ix.toFloat(), (iy + ih - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), (iy + ih - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.STRETCH -> {
                val sh = (ih - gap) / 2
                g2d.fill(RoundRectangle2D.Float(ix.toFloat(), iy.toFloat(), boxW.toFloat(), sh.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), (iy + sh + gap).toFloat(), boxW.toFloat(), sh.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.SPACE_BETWEEN -> {
                g2d.fill(RoundRectangle2D.Float(ix.toFloat(), iy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), (iy + ih - boxH).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.SPACE_AROUND -> {
                val cy = iy + (ih - boxH * 2 - gap) / 2
                g2d.fill(RoundRectangle2D.Float(ix.toFloat(), cy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), (cy + boxH + gap).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
            FlexLayoutResolver.AlignContent.SPACE_EVENLY -> {
                val cy = iy + (ih - boxH * 2 - gap) / 2
                g2d.fill(RoundRectangle2D.Float(ix.toFloat(), cy.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2d.fill(RoundRectangle2D.Float(
                    (ix + iw - boxW).toFloat(), (cy + boxH + gap).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
        }
    }

    private fun drawDefaultIcon(g2d: Graphics2D, cw: Int, ch: Int) {
        val ix = 13; val iy = 4
        val iw = cw - ix - 3; val ih = ch - iy - 3
        g2d.color = PROP_CHILD
        val boxW = (iw * 0.35).toInt().coerceAtLeast(4)
        val boxH = (ih * 0.35).toInt().coerceAtLeast(4)
        val midY = iy + (ih - boxH) / 2
        g2d.fill(RoundRectangle2D.Float(ix.toFloat(), midY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        g2d.fill(RoundRectangle2D.Float(
            (ix + iw - boxW).toFloat(), midY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
    }

    override fun toString(): String = "PropertyPreview($propName)"
}

// ====================================================================
// 通用颜色常量
// ====================================================================

private val OVERALL_BORDER = JBColor(Color(0x808080), Color(0x808080))
private val OVERALL_CHILD = JBColor(Color(0x4a90d9), Color(0x5a9fe6))
private val OVERALL_CHILD_BORDER = JBColor(Color(0x2f6bb0), Color(0x9cc4ef))
private val PROP_BORDER = JBColor(Color(0x999999), Color(0x999999))
private val PROP_CHILD = JBColor(Color(0x4a90d9), Color(0x5a9fe6))