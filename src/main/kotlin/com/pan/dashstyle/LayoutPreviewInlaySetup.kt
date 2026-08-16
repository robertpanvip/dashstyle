package com.pan.dashstyle

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.css.CssDeclaration
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.util.concurrent.ConcurrentHashMap

/**
 * CSS 布局预览 inlay 管理器。
 *
 * 使用 ProjectManagerListener 在项目打开时注册 EditorFactoryListener，
 * 在 display:flex/grid 声明行尾添加可点击的布局预览 inlay，替代原先的 gutter 图标。
 */
class LayoutPreviewInlaySetup : ProjectManagerListener {

    private val editorInlays = ConcurrentHashMap<Editor, MutableList<Inlay<*>>>()
    private val clickHandlers = ConcurrentHashMap<Inlay<*>, () -> Unit>()
    private val mouseListeners = ConcurrentHashMap<Editor, EditorMouseListener>()

    override fun projectOpened(project: Project) {
        val listener = object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                handleEditorCreated(event.editor, project)
            }

            override fun editorReleased(event: EditorFactoryEvent) {
                handleEditorReleased(event.editor)
            }
        }
        EditorFactory.getInstance().addEditorFactoryListener(listener, project)

        for (editor in EditorFactory.getInstance().allEditors) {
            if (editor.project == project && !editor.isDisposed) {
                setupMouseListener(editor)
                refreshInlays(editor, project)
            }
        }
    }

    private fun handleEditorCreated(editor: Editor, project: Project) {
        if (project.isDisposed || editor.isDisposed) return
        val vFile = FileDocumentManager.getInstance().getFile(editor.document) ?: return
        val name = vFile.name.lowercase()
        if (!name.endsWith(".css") && !name.endsWith(".scss") && !name.endsWith(".less")) return
        setupMouseListener(editor)
        refreshInlays(editor, project)
    }

    private fun handleEditorReleased(editor: Editor) {
        editorInlays.remove(editor)?.forEach { it.dispose() }
        mouseListeners.remove(editor)?.let { editor.removeEditorMouseListener(it) }
        clickHandlers.keys.removeIf { it.editor == editor || !it.isValid }
    }

    /** 为编辑器注册鼠标监听，检测 inlay 点击。 */
    private fun setupMouseListener(editor: Editor) {
        if (mouseListeners.containsKey(editor)) return
        val listener = object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) {
                val mouseEvent = event.mouseEvent
                if (mouseEvent.button != 1) return
                val logicalPos = editor.xyToLogicalPosition(mouseEvent.point)
                val offset = editor.logicalPositionToOffset(logicalPos)
                val inlays = editor.inlayModel.getInlineElementsInRange(offset, offset)
                for (inlay in inlays) {
                    val handler = clickHandlers[inlay] ?: continue
                    handler()
                    mouseEvent.consume()
                    break
                }
            }
        }
        editor.addEditorMouseListener(listener)
        mouseListeners[editor] = listener
    }

    private fun refreshInlays(editor: Editor, project: Project) {
        editorInlays.remove(editor)?.forEach { it.dispose() }

        val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(editor.document) ?: return
        val contexts = LayoutContextResolver.contexts(psiFile)
        if (contexts.isEmpty()) return

        val newInlays = mutableListOf<Inlay<*>>()
        for (ctx in contexts) {
            addOverallInlay(editor, ctx)?.let { newInlays.add(it) }
            for ((d, model) in ctx.perProperty) {
                addPropertyInlay(editor, d, model, ctx)?.let { newInlays.add(it) }
            }
        }
        if (newInlays.isNotEmpty()) {
            editorInlays[editor] = newInlays
        }
    }

    private fun addOverallInlay(
        editor: Editor,
        ctx: LayoutContextResolver.LayoutContext
    ): Inlay<*>? {
        val model = previewModelFor(ctx.overall)
        val ruleset = ctx.ruleset
        val inlay = editor.inlayModel.addInlineElement(
            ctx.display.textRange.endOffset,
            true,
            0,
            OverallPreviewRenderer(model)
        )
        if (inlay != null) {
            clickHandlers[inlay] = {
                val popup = LayoutPreviewPopup.create(editor, ruleset, model)
                popup.showInBestPositionFor(editor)
            }
        }
        return inlay
    }

    private fun addPropertyInlay(
        editor: Editor,
        decl: CssDeclaration,
        model: LayoutModel,
        ctx: LayoutContextResolver.LayoutContext
    ): Inlay<*>? {
        val propName = decl.propertyName?.trim()?.lowercase() ?: ""
        val ruleset = ctx.ruleset
        val inlay = editor.inlayModel.addInlineElement(
            decl.textRange.endOffset,
            true,
            0,
            PropertyPreviewRenderer(propName, model)
        )
        if (inlay != null) {
            clickHandlers[inlay] = {
                val popup = LayoutPreviewPopup.create(editor, ruleset, model, propName)
                popup.showInBestPositionFor(editor)
            }
        }
        return inlay
    }

    /** 为 inlay 预览创建精简模型（2 个子项确保单行显示，避免 2 排）。 */
    private fun previewModelFor(model: LayoutModel): LayoutModel {
        return when (model) {
            is LayoutModel.Flex -> LayoutModel.Flex(model.props.copy(childCount = 2))
            else -> model
        }
    }

    // ====================================================================
    // 整体布局预览（display:flex/grid 行）
    // ====================================================================
    private class OverallPreviewRenderer(
        private val model: LayoutModel
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = 80 + GAP
        override fun calcHeightInPixels(inlay: Inlay<*>): Int = 30

        override fun paint(
            inlay: Inlay<*>,
            g: Graphics,
            targetRegion: Rectangle,
            textAttributes: TextAttributes
        ) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val pad = 2
            val drawX = targetRegion.x + GAP
            val innerW = 80 - pad * 2
            val innerH = 30 - pad * 2

            // 边框
            g2.color = OVERALL_BORDER
            g2.draw(RoundRectangle2D.Float(
                (drawX + pad).toFloat(),
                (targetRegion.y + pad).toFloat(),
                (innerW - 1).toFloat(),
                (innerH - 1).toFloat(),
                3f, 3f
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
            val offY = targetRegion.y + pad + (innerH - scaledH) / 2

            g2.color = OVERALL_CHILD
            for (b in boxes) {
                val sx = offX + ((b.x - minX) * scale).toInt()
                val sy = offY + ((b.y - minY) * scale).toInt()
                val sw = ((b.w * scale).coerceAtLeast(2.0f)).toInt()
                val sh = ((b.h * scale).coerceAtLeast(2.0f)).toInt()
                g2.fill(RoundRectangle2D.Float(
                    sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), 2f, 2f))
            }
        }

        override fun toString(): String = "OverallPreviewRenderer"
    }

    // ====================================================================
    // 属性简便图标（justify-content / align-items / gap 等）
    // ====================================================================
    private class PropertyPreviewRenderer(
        private val propName: String,
        private val model: LayoutModel
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = 50 + GAP
        override fun calcHeightInPixels(inlay: Inlay<*>): Int = 20

        override fun paint(
            inlay: Inlay<*>,
            g: Graphics,
            targetRegion: Rectangle,
            textAttributes: TextAttributes
        ) {
            val g2 = g as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

            val drawX = targetRegion.x + GAP
            val drawY = targetRegion.y
            val w = 50
            val h = 20
            val pad = 2

            // 边框
            g2.color = PROP_BORDER
            g2.draw(RoundRectangle2D.Float(
                (drawX + pad).toFloat(),
                (drawY + pad).toFloat(),
                (w - pad * 2 - 1).toFloat(),
                (h - pad * 2 - 1).toFloat(),
                3f, 3f
            ))

            val innerW = w - pad * 2 - 4
            val innerH = h - pad * 2 - 4
            val cx = drawX + pad + 2
            val cy = drawY + pad + 2

            when (propName) {
                "justify-content" -> drawJustifyIcon(g2, cx, cy, innerW, innerH, model)
                "align-items" -> drawAlignIcon(g2, cx, cy, innerW, innerH, model)
                "gap", "row-gap", "column-gap" -> drawGapIcon(g2, cx, cy, innerW, innerH, model)
                "flex-direction" -> drawDirectionIcon(g2, cx, cy, innerW, innerH, model)
                "flex-wrap" -> drawWrapIcon(g2, cx, cy, innerW, innerH, model)
                "align-content" -> drawAlignContentIcon(g2, cx, cy, innerW, innerH, model)
                else -> drawDefaultIcon(g2, cx, cy, innerW, innerH, model)
            }
        }

        private fun flexProps(model: LayoutModel): FlexLayoutResolver.Props? =
            (model as? LayoutModel.Flex)?.props

        private fun gridProps(model: LayoutModel): GridLayoutResolver.Props? =
            (model as? LayoutModel.Grid)?.props

        /**
         * justify-content 图标：水平方向，小方块按 justify/justifyContent 值摆放。
         * 支持 flex 和 grid 两种模型。
         */
        private fun drawJustifyIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            val boxW = 6
            val boxH = (h * 0.6).toInt().coerceAtLeast(4)
            val boxY = y + (h - boxH) / 2
            val positions = flexProps(model)?.let { fp ->
                when (fp.justify) {
                    FlexLayoutResolver.Justify.FLEX_START -> listOf(0)
                    FlexLayoutResolver.Justify.CENTER -> listOf((w - boxW) / 2)
                    FlexLayoutResolver.Justify.FLEX_END -> listOf(w - boxW)
                    FlexLayoutResolver.Justify.SPACE_BETWEEN -> listOf(0, w - boxW)
                    FlexLayoutResolver.Justify.SPACE_AROUND -> listOf(0, w - boxW)
                    FlexLayoutResolver.Justify.SPACE_EVENLY -> listOf(0, w - boxW)
                }
            } ?: gridProps(model)?.let { gp ->
                when (gp.justifyContent) {
                    GridLayoutResolver.GridAlign.START -> listOf(0)
                    GridLayoutResolver.GridAlign.CENTER -> listOf((w - boxW) / 2)
                    GridLayoutResolver.GridAlign.END -> listOf(w - boxW)
                    GridLayoutResolver.GridAlign.STRETCH -> listOf(0, w - boxW)
                }
            } ?: return
            g2.color = PROP_CHILD
            for (px in positions) {
                g2.fill(RoundRectangle2D.Float(
                    (x + px).toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
        }

        /**
         * align-items 图标：垂直方向，小方块按 align/alignItems 值摆放。
         * 支持 flex 和 grid 两种模型。
         */
        private fun drawAlignIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            val boxW = (w * 0.6).toInt().coerceAtLeast(4)
            val boxH = 6
            val boxX = x + (w - boxW) / 2
            val (py, bh) = flexProps(model)?.let { fp ->
                when (fp.align) {
                    FlexLayoutResolver.Align.FLEX_START -> Pair(0, boxH)
                    FlexLayoutResolver.Align.CENTER, FlexLayoutResolver.Align.BASELINE -> Pair((h - boxH) / 2, boxH)
                    FlexLayoutResolver.Align.FLEX_END -> Pair(h - boxH, boxH)
                    FlexLayoutResolver.Align.STRETCH -> Pair(0, h)
                }
            } ?: gridProps(model)?.let { gp ->
                when (gp.alignItems) {
                    GridLayoutResolver.GridAlign.START -> Pair(0, boxH)
                    GridLayoutResolver.GridAlign.CENTER -> Pair((h - boxH) / 2, boxH)
                    GridLayoutResolver.GridAlign.END -> Pair(h - boxH, boxH)
                    GridLayoutResolver.GridAlign.STRETCH -> Pair(0, h)
                }
            } ?: return
            g2.color = PROP_CHILD
            g2.fill(RoundRectangle2D.Float(
                boxX.toFloat(), (y + py).toFloat(), boxW.toFloat(), bh.toFloat(), 1.5f, 1.5f))
        }

        /**
         * gap 图标：两个小方块，中间间距表示 gap 大小。
         * 支持 flex 和 grid 两种模型。
         */
        private fun drawGapIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            val gapVal = flexProps(model)?.gap ?: gridProps(model)?.gap ?: return
            val boxW = 6
            val boxH = (h * 0.6).toInt().coerceAtLeast(4)
            val boxY = y + (h - boxH) / 2
            val gapNorm = gapVal.coerceIn(0, 20)
            val gapPx = (gapNorm.toFloat() / 20 * (w - boxW * 2)).toInt().coerceAtLeast(2)

            g2.color = PROP_CHILD
            g2.fill(RoundRectangle2D.Float(
                x.toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            g2.fill(RoundRectangle2D.Float(
                (x + boxW + gapPx).toFloat(), boxY.toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
        }

        private fun drawDirectionIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            val props = flexProps(model) ?: return
            val isRow = props.direction == FlexLayoutResolver.Direction.ROW ||
                    props.direction == FlexLayoutResolver.Direction.ROW_REVERSE
            g2.color = PROP_CHILD
            if (isRow) {
                val boxH = (h * 0.5).toInt().coerceAtLeast(4)
                val boxY = y + (h - boxH) / 2
                g2.fill(RoundRectangle2D.Float(x.toFloat(), boxY.toFloat(), (w * 0.3).toFloat(), boxH.toFloat(), 1.5f, 1.5f))
                g2.fill(RoundRectangle2D.Float((x + w * 0.4f).toFloat(), boxY.toFloat(), (w * 0.3).toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            } else {
                val boxW = (w * 0.5).toInt().coerceAtLeast(4)
                val boxX = x + (w - boxW) / 2
                g2.fill(RoundRectangle2D.Float(boxX.toFloat(), y.toFloat(), boxW.toFloat(), (h * 0.3).toFloat(), 1.5f, 1.5f))
                g2.fill(RoundRectangle2D.Float(boxX.toFloat(), (y + h * 0.4f).toFloat(), boxW.toFloat(), (h * 0.3).toFloat(), 1.5f, 1.5f))
            }
        }

        private fun drawWrapIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            val props = flexProps(model) ?: return
            val pw = (w * 0.2).toInt().coerceAtLeast(3)
            val ph = (h * 0.3).toInt().coerceAtLeast(3)
            g2.color = PROP_CHILD
            if (props.wrap) {
                g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
                g2.fill(RoundRectangle2D.Float((x + pw + 2).toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
                g2.fill(RoundRectangle2D.Float(x.toFloat(), (y + ph + 2).toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
                g2.fill(RoundRectangle2D.Float((x + pw + 2).toFloat(), (y + ph + 2).toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            } else {
                g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
                g2.fill(RoundRectangle2D.Float((x + pw + 2).toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
                g2.fill(RoundRectangle2D.Float((x + (pw + 2) * 2).toFloat(), y.toFloat(), pw.toFloat(), ph.toFloat(), 1f, 1f))
            }
        }

        private fun drawAlignContentIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            val boxW = (w * 0.35).toInt().coerceAtLeast(4)
            val boxH = (h * 0.25).toInt().coerceAtLeast(3)
            val positions = flexProps(model)?.let { fp ->
                when (fp.alignContent) {
                    FlexLayoutResolver.AlignContent.FLEX_START -> listOf(0, h / 2)
                    FlexLayoutResolver.AlignContent.CENTER -> listOf(h / 4, h * 3 / 4 - boxH)
                    FlexLayoutResolver.AlignContent.FLEX_END -> listOf(h - boxH * 2, h - boxH)
                    else -> listOf(0, h / 2)
                }
            } ?: gridProps(model)?.let { gp ->
                when (gp.alignContent) {
                    GridLayoutResolver.GridAlign.START -> listOf(0, h / 2)
                    GridLayoutResolver.GridAlign.CENTER -> listOf(h / 4, h * 3 / 4 - boxH)
                    GridLayoutResolver.GridAlign.END -> listOf(h - boxH * 2, h - boxH)
                    GridLayoutResolver.GridAlign.STRETCH -> listOf(0, h / 2)
                }
            } ?: return
            g2.color = PROP_CHILD
            for (py in positions) {
                g2.fill(RoundRectangle2D.Float(x.toFloat(), (y + py).toFloat(), boxW.toFloat(), boxH.toFloat(), 1.5f, 1.5f))
            }
        }

        private fun drawDefaultIcon(g2: Graphics2D, x: Int, y: Int, w: Int, h: Int, model: LayoutModel) {
            g2.color = PROP_CHILD
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), 4f, 4f, 1f, 1f))
            g2.fill(RoundRectangle2D.Float((x + 8).toFloat(), y.toFloat(), 4f, 4f, 1f, 1f))
            g2.fill(RoundRectangle2D.Float(x.toFloat(), (y + 8).toFloat(), 4f, 4f, 1f, 1f))
        }

        override fun toString(): String = "PropertyPreviewRenderer($propName)"
    }

    companion object {
        /** 文本与 inlay 之间的间距。 */
        private const val GAP = 6

        private const val BOUND_W = 80
        private const val BOUND_H = 30

        val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
        val PROP_BORDER: JBColor = JBColor(Color(0x9aa0aa), Color(0x5a5e65))
        val PROP_CHILD: JBColor = JBColor(Color(0x6a7a8a), Color(0x7a8a9a))
    }
}