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
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * 布局预览 inlay —— 在 display:flex/grid 声明行尾渲染实时布局预览。
 *
 * 相比 gutter 图标，inlay 有更大的空间展示子项布局，且随输入实时刷新。
 */
class LayoutPreviewInlayProvider : InlayHintsProvider<LayoutPreviewInlayProvider.Settings> {

    class Settings {
        @JvmField var showLayoutPreview: Boolean = true
    }

    override val key: SettingsKey<Settings> = SettingsKey("dashstyle.layout.preview")
    override val name: String = "CSS Flex/Grid 布局预览"
    override val description: String =
        "在 display:flex/grid 声明行尾渲染布局预览，实时反映 justify-content、align-items 等属性的变化。"
    override val group: InlayGroup = InlayGroup.OTHER_GROUP

    override fun createSettings(): Settings = Settings()

    override val previewText: String = "  display: flex;\n  justify-content: center;\n  align-items: center;\n  gap: 8px;"

    override fun isLanguageSupported(language: Language): Boolean {
        val id = language.id.lowercase()
        return id.contains("css") || id == "less" || id.contains("scss")
    }

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                val cb = JCheckBox("显示 Flex/Grid 布局预览", settings.showLayoutPreview)
                cb.addActionListener {
                    settings.showLayoutPreview = cb.isSelected
                    listener.settingsChanged()
                }
                val box = Box.createVerticalBox()
                box.add(cb)
                return box
            }

            override val mainCheckboxText: String get() = "启用 CSS Flex/Grid 布局预览"
        }
    }

    override fun getCollectorFor(
        file: PsiFile,
        editor: Editor,
        settings: Settings,
        sink: InlayHintsSink
    ): InlayHintsCollector {
        return object : InlayHintsCollector {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                if (element !== file) return false
                if (!settings.showLayoutPreview) return false
                val contexts = LayoutContextResolver.contexts(file)
                for (ctx in contexts) {
                    // 在 display:flex/grid 行尾渲染总效果预览
                    sink.addInlineElement(
                        ctx.display.textRange.endOffset,
                        true,
                        LayoutPreviewPresentation(ctx.overall)
                    )
                    // 在各属性行尾渲染聚焦预览
                    for ((d, model) in ctx.perProperty) {
                        sink.addInlineElement(
                            d.textRange.endOffset,
                            true,
                            FocusedPreviewPresentation(d, model)
                        )
                    }
                }
                return false
            }
        }
    }
}

/** 总效果布局预览的画布 inlay。 */
private class LayoutPreviewPresentation(
    private val model: LayoutModel
) : BasePresentation() {

    // 画布尺寸 80×30
    override val width: Int get() = 80
    override val height: Int get() = 30

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pad = 2
        val innerW = width - pad * 2
        val innerH = height - pad * 2

        // 容器边框
        g2d.color = BORDER
        g2d.draw(RoundRectangle2D.Float(pad.toFloat(), pad.toFloat(),
            (innerW - 1).toFloat(), (innerH - 1).toFloat(), 3f, 3f))

        // 子项：获取布局并缩放到内区
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

        g2d.color = CHILD
        for (b in boxes) {
            val sx = offX + ((b.x - minX) * scale).toInt()
            val sy = offY + ((b.y - minY) * scale).toInt()
            val sw = ((b.w * scale).coerceAtLeast(2.0f)).toInt()
            val sh = ((b.h * scale).coerceAtLeast(2.0f)).toInt()
            g2d.fill(RoundRectangle2D.Float(
                sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), 2f, 2f))
        }
    }

    private companion object {
        val BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
    }

    override fun toString(): String = "LayoutPreview"
}

/** 单属性聚焦预览 inlay（justify-content / align-items / gap 等属性行尾）。 */
private class FocusedPreviewPresentation(
    private val decl: CssDeclaration,
    private val model: LayoutModel
) : BasePresentation() {

    override val width: Int get() = 80
    override val height: Int get() = 30

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2d.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)

        val pad = 2
        val innerW = width - pad * 2
        val innerH = height - pad * 2

        // 用浅色边框标识聚焦属性
        g2d.color = FOCUS_BORDER
        g2d.draw(RoundRectangle2D.Float(pad.toFloat(), pad.toFloat(),
            (innerW - 1).toFloat(), (innerH - 1).toFloat(), 3f, 3f))

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

        // 高亮受该属性影响的子项位置
        g2d.color = FOCUS_CHILD
        for (b in boxes) {
            val sx = offX + ((b.x - minX) * scale).toInt()
            val sy = offY + ((b.y - minY) * scale).toInt()
            val sw = ((b.w * scale).coerceAtLeast(2.0f)).toInt()
            val sh = ((b.h * scale).coerceAtLeast(2.0f)).toInt()
            g2d.fill(RoundRectangle2D.Float(
                sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), 2f, 2f))
        }
    }

    private companion object {
        val FOCUS_BORDER: JBColor = JBColor(Color(0x7a7e85), Color(0x5a5e65))
        val FOCUS_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
    }

    override fun toString(): String = "FocusedPreview(${decl.propertyName ?: "?"})"
}