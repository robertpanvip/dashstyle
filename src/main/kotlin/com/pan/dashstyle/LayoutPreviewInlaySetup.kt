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
                if (mouseEvent.button != 1) return // 左键
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
            LayoutPreviewRenderer(model, false)
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
            LayoutPreviewRenderer(model, true)
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

    private class LayoutPreviewRenderer(
        private val model: LayoutModel,
        private val isFocused: Boolean
    ) : EditorCustomElementRenderer {

        override fun calcWidthInPixels(inlay: Inlay<*>): Int = 80
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
            val innerW = BOUND_W - pad * 2
            val innerH = BOUND_H - pad * 2

            g2.color = if (isFocused) FOCUS_BORDER else OVERALL_BORDER
            g2.draw(RoundRectangle2D.Float(
                (targetRegion.x + pad).toFloat(),
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
            val offX = targetRegion.x + pad + (innerW - scaledW) / 2
            val offY = targetRegion.y + pad + (innerH - scaledH) / 2

            g2.color = if (isFocused) FOCUS_CHILD else OVERALL_CHILD
            for (b in boxes) {
                val sx = offX + ((b.x - minX) * scale).toInt()
                val sy = offY + ((b.y - minY) * scale).toInt()
                val sw = ((b.w * scale).coerceAtLeast(2.0f)).toInt()
                val sh = ((b.h * scale).coerceAtLeast(2.0f)).toInt()
                g2.fill(RoundRectangle2D.Float(
                    sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), 2f, 2f))
            }
        }

        override fun toString(): String = "LayoutPreviewRenderer"
    }

    companion object {
        private const val BOUND_W = 80
        private const val BOUND_H = 30

        val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
        val FOCUS_BORDER: JBColor = JBColor(Color(0x7a7e85), Color(0x5a5e65))
        val FOCUS_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
    }
}