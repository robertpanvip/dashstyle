package com.pan.dashstyle

import com.intellij.codeInsight.daemon.GutterIconNavigationHandler
import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * CSS 布局增强 —— gutter 迷你预览（flex 与 grid）。
 *
 * 把原先的行尾 inlay 预览改到行号前的 gutter，形如 WebStorm 对颜色的 gutter 预览：
 *  - 在 `display:flex/grid` 行前渲染一个「总效果」色块图标（强调色）；
 *  - 在每个布局属性行前渲染一个「聚焦该属性」的迷你布局图。
 *
 * 点击图标弹出交互面板（[LayoutPreviewPopup]），可调整属性并写回 CSS。
 * 布局解析复用 [LayoutContextResolver]。
 */
class LayoutPreviewGutterMarkerProvider : LineMarkerProvider {

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        val file = elements.firstOrNull()?.containingFile ?: return
        val contexts = LayoutContextResolver.contexts(file)
        if (contexts.isEmpty()) return

        // 声明 → (模型, 是否总效果图标)
        val declToModel = HashMap<CssDeclaration, Pair<LayoutModel, Boolean>>()
        for (ctx in contexts) {
            declToModel[ctx.display] = ctx.overall to true
            for ((d, model) in ctx.perProperty) declToModel[d] = model to false
        }

        for (element in elements) {
            if (element !is CssDeclaration) continue
            val (model, overall) = declToModel[element] ?: continue
            val leaf = PsiTreeUtil.getDeepestFirst(element) ?: continue
            val ruleset = PsiTreeUtil.getParentOfType(element, CssRuleset::class.java) ?: continue
            val marker = LineMarkerInfo(
                leaf,
                leaf.textRange,
                LayoutGutterIcon(model, overall),
                { if (overall) "容器总效果预览（点击调整）" else "布局预览（点击调整）" },
                openPopupHandler(ruleset, model),
                GutterIconRenderer.Alignment.LEFT
            )
            result.add(marker)
        }
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun isDumbAware(): Boolean = true

    private fun openPopupHandler(
        ruleset: CssRuleset,
        model: LayoutModel
    ): GutterIconNavigationHandler<PsiElement> {
        return object : GutterIconNavigationHandler<PsiElement> {
            override fun navigate(e: java.awt.event.MouseEvent, element: PsiElement) {
                val doc = element.containingFile?.viewProvider?.document
                val editor = doc?.let { EditorFactory.getInstance().getEditors(it).firstOrNull() }
                if (editor != null && e.component != null) {
                    val popup = LayoutPreviewPopup.create(editor, ruleset, model)
                    popup.showInScreenCoordinates(e.component, e.locationOnScreen)
                }
            }
        }
    }
}

/**
 * gutter 布局预览 icon：小容器 + 内部子块布局，模拟 WebStorm 颜色预览的 gutter 色块。
 * 总效果用强调色，单属性用普通色。
 *
 * 尺寸取 32×32 以在 gutter 内尽可能清晰地展示 flex 排列方向 / grid 网格；
 * 子块之间留 1px 间隙，避免相邻块糊在一起，提升辨识度。
 */
private class LayoutGutterIcon(
    private val model: LayoutModel,
    private val overall: Boolean
) : Icon {

    override fun getIconWidth(): Int = 32
    override fun getIconHeight(): Int = 32

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val pad = 2
            val boxW = iconWidth - pad * 2
            val boxH = iconHeight - pad * 2
            val border = if (overall) OVERALL_BORDER else BORDER
            val fill = if (overall) OVERALL_CHILD else CHILD
            // 容器边框
            g2.color = border
            g2.draw(Rectangle2D.Float(x + pad.toFloat(), y + pad.toFloat(), boxW - 1f, boxH - 1f))
            // 子块（每块内缩 1px，块间留出间隙，更清晰）
            val boxes = model.boxes(boxW, boxH)
            g2.color = fill
            for (b in boxes) {
                val bx = x + pad + b.x
                val by = y + pad + b.y
                val bw = b.w - 1
                val bh = b.h - 1
                if (bw <= 0 || bh <= 0) continue
                g2.fill(RoundRectangle2D.Float(bx.toFloat(), by.toFloat(), bw.toFloat(), bh.toFloat(), 1.5f, 1.5f))
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val BORDER: JBColor = JBColor(Color(0x8a8d93), Color(0x5a5d63))
        val CHILD: JBColor = JBColor(Color(0x4f8cff), Color(0x6aa0ff))
        val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
    }
}