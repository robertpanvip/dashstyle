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
import java.awt.image.BufferedImage
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * CSS 布局增强 —— gutter 迷你预览（flex 与 grid）。
 *
 * 在行号前的 gutter 渲染布局预览图标：
 *  - 在 `display:flex/grid` 行前渲染一个「总效果」布局预览（强调色）；
 *  - 在每个布局属性行前渲染一个「单轴指示」简图（普通色），仅显示该属性对应的轴方向。
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
            val propName = element.propertyName?.trim()?.lowercase().orEmpty()
            val icon = if (overall) {
                LayoutGutterIcon(model)
            } else {
                PerPropertyGutterIcon(propName)
            }
            val marker = LineMarkerInfo(
                leaf,
                leaf.textRange,
                icon,
                { LayoutPreviewTooltip.html(model, overall) },
                openPopupHandler(ruleset, model, propName.takeIf { !overall }),
                GutterIconRenderer.Alignment.LEFT
            )
            result.add(marker)
        }
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun isDumbAware(): Boolean = true

    private fun openPopupHandler(
        ruleset: CssRuleset,
        model: LayoutModel,
        triggerProperty: String? = null
    ): GutterIconNavigationHandler<PsiElement> {
        return object : GutterIconNavigationHandler<PsiElement> {
            override fun navigate(e: java.awt.event.MouseEvent, element: PsiElement) {
                val doc = element.containingFile?.viewProvider?.document
                val editor = doc?.let { EditorFactory.getInstance().getEditors(it).firstOrNull() }
                if (editor != null && e.component != null) {
                    val popup = LayoutPreviewPopup.create(editor, ruleset, model, triggerProperty)
                    popup.showInScreenCoordinates(e.component, e.locationOnScreen)
                }
            }
        }
    }
}

/**
 * gutter 总效果布局预览 icon：根据实际布局模型渲染子块位置。
 * 仅用于 `display:flex/grid` 行，16×16 紧凑显示不占行号空间。
 * 完整布局预览在 tooltip 中展示。
 */
private class LayoutGutterIcon(
    private val model: LayoutModel
) : Icon {

    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val pad = 2
            val innerW = getIconWidth() - pad * 2
            val innerH = getIconHeight() - pad * 2
            // 容器边框（强调色）
            g2.color = OVERALL_BORDER
            g2.stroke = java.awt.BasicStroke(0.8f)
            g2.draw(Rectangle2D.Float((x + pad).toFloat(), (y + pad).toFloat(),
                (innerW - 1).toFloat(), (innerH - 1).toFloat()))
            // 子块：根据实际布局模型摆位
            g2.color = OVERALL_CHILD
            g2.stroke = java.awt.BasicStroke(0.5f)
            for (b in model.boxes(innerW, innerH)) {
                val bw = b.w.coerceAtLeast(2)
                val bh = b.h.coerceAtLeast(2)
                if (bw <= 0 || bh <= 0) continue
                g2.fill(RoundRectangle2D.Float(
                    (x + pad + b.x).toFloat(),
                    (y + pad + b.y).toFloat(),
                    bw.toFloat(), bh.toFloat(), 1f, 1f))
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
    }
}

/**
 * 单属性布局预览 icon：根据属性名称显示对应的轴方向简图。
 * 不展示完整布局，仅用箭头/线条指示该属性影响哪个轴。
 * 尺寸 16×16，紧凑显示，不占行号空间。
 *
 * 各属性对应图形：
 *  - justify-content / justify-items / justify-self → 水平双箭头（主轴）
 *  - align-items / align-self / align-content → 垂直双箭头（交叉轴）
 *  - flex-direction → 方向箭头
 *  - flex-wrap → 换行指示
 *  - gap / row-gap / column-gap → 间距指示
 *  - grid-template-columns → 水平轨道
 *  - grid-template-rows → 垂直轨道
 */
private class PerPropertyGutterIcon(
    private val propName: String
) : Icon {

    override fun getIconWidth(): Int = 16
    override fun getIconHeight(): Int = 16

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = AXIS_COLOR
            g2.stroke = AXIS_STROKE

            val cx = x + iconWidth / 2f
            val cy = y + iconHeight / 2f

            when {
                // 水平主轴：justify-content / justify-items / justify-self
                propName.startsWith("justify") || propName.startsWith("grid-template-columns") -> {
                    drawHorizontalArrow(g2, x, y)
                }
                // 垂直交叉轴：align-items / align-self / align-content
                propName.startsWith("align") || propName.startsWith("grid-template-rows") -> {
                    drawVerticalArrow(g2, x, y)
                }
                // flex-direction：方向切换
                propName == "flex-direction" -> {
                    drawDirectionArrow(g2, cx, cy)
                }
                // flex-wrap：换行
                propName == "flex-wrap" -> {
                    drawWrapIndicator(g2, cx, cy)
                }
                // gap
                propName == "gap" || propName.endsWith("-gap") -> {
                    drawGapIndicator(g2, cx, cy)
                }
                // 默认：十字箭头
                else -> {
                    drawHorizontalArrow(g2, x, y)
                    drawVerticalArrow(g2, x, y)
                }
            }
        } finally {
            g2.dispose()
        }
    }

    private fun drawHorizontalArrow(g2: Graphics2D, x: Int, y: Int) {
        val cy = y + iconHeight / 2f
        // 左箭头
        g2.drawLine(x + 2, cy.toInt(), x + iconWidth - 4, cy.toInt())
        g2.drawLine(x + 4, cy.toInt() - 3, x + 2, cy.toInt())
        g2.drawLine(x + 4, cy.toInt() + 3, x + 2, cy.toInt())
        // 右箭头
        g2.drawLine(x + iconWidth - 4, cy.toInt() - 3, x + iconWidth - 2, cy.toInt())
        g2.drawLine(x + iconWidth - 4, cy.toInt() + 3, x + iconWidth - 2, cy.toInt())
    }

    private fun drawVerticalArrow(g2: Graphics2D, x: Int, y: Int) {
        val cx = x + iconWidth / 2f
        g2.drawLine(cx.toInt(), y + 2, cx.toInt(), y + iconHeight - 4)
        g2.drawLine(cx.toInt() - 3, y + 4, cx.toInt(), y + 2)
        g2.drawLine(cx.toInt() + 3, y + 4, cx.toInt(), y + 2)
        // 下箭头
        g2.drawLine(cx.toInt() - 3, y + iconHeight - 4, cx.toInt(), y + iconHeight - 2)
        g2.drawLine(cx.toInt() + 3, y + iconHeight - 4, cx.toInt(), y + iconHeight - 2)
    }

    private fun drawDirectionArrow(g2: Graphics2D, cx: Float, cy: Float) {
        // 画一个顺时针弧形箭头
        g2.drawArc((cx - 4).toInt(), (cy - 4).toInt(), 8, 8, 0, 270)
        // 箭头尖
        g2.drawLine((cx + 4).toInt(), (cy - 4).toInt(), (cx + 4).toInt(), (cy - 1).toInt())
        g2.drawLine((cx + 4).toInt(), (cy - 4).toInt(), (cx + 1).toInt(), (cy - 4).toInt())
    }

    private fun drawWrapIndicator(g2: Graphics2D, cx: Float, cy: Float) {
        // 两行短线表示换行
        g2.drawLine((cx - 5).toInt(), (cy - 3).toInt(), (cx + 1).toInt(), (cy - 3).toInt())
        g2.drawLine((cx - 1).toInt(), (cy + 3).toInt(), (cx + 5).toInt(), (cy + 3).toInt())
        // 换行箭头：从第一行末尾折向第二行开头
        g2.drawLine((cx + 1).toInt(), (cy - 3).toInt(), (cx + 3).toInt(), (cy - 1).toInt())
        g2.drawLine((cx + 3).toInt(), (cy - 1).toInt(), (cx - 1).toInt(), (cy - 1).toInt())
        g2.drawLine((cx - 1).toInt(), (cy - 1).toInt(), (cx - 1).toInt(), (cy + 3).toInt())
    }

    private fun drawGapIndicator(g2: Graphics2D, cx: Float, cy: Float) {
        // 两个平行小块表示间距
        g2.fill(RoundRectangle2D.Float(cx - 6f, cy - 4f, 4f, 8f, 1f, 1f))
        g2.fill(RoundRectangle2D.Float(cx + 2f, cy - 4f, 4f, 8f, 1f, 1f))
        // 中间的双箭头线表示间距
        g2.drawLine((cx - 1).toInt(), cy.toInt(), (cx + 1).toInt(), cy.toInt())
    }

    private companion object {
        val AXIS_COLOR: JBColor = JBColor(Color(0x8a8d93), Color(0x6a6d73))
        val AXIS_STROKE = java.awt.BasicStroke(1.2f)
    }
}

/**
 * gutter 图标的放大预览 tooltip。
 *
 * guter 图标空间有限（32×32），悬浮时把同一布局以更大尺寸（如 200×130）渲染成 PNG，
 * 并以 HTML `<img src="file://...">` 作为 tooltip 显示，达到与 inlay 相当的清晰度。
 * 结果按 [LayoutModel] 缓存；临时 PNG 在 JVM 退出时清理。
 */
private object LayoutPreviewTooltip {

    private val htmlCache = ConcurrentHashMap<LayoutModel, String>()
    private val createdFiles = ConcurrentLinkedQueue<File>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            for (f in createdFiles) runCatching { f.delete() }
        })
    }

    /** 返回放大预览的 HTML tooltip；同一模型复用已生成的 HTML。 */
    fun html(model: LayoutModel, overall: Boolean): String {
        htmlCache[model]?.let { return it }
        val img = render(model)
        val f = File.createTempFile("dashstyle-preview-", ".png")
        ImageIO.write(img, "png", f)
        createdFiles.add(f)
        val label = if (overall) "容器总效果（点击调整）" else "布局预览（点击调整）"
        val html = buildString {
            append("<html><body style=\"padding:6px\">")
            append("<img src=\"file://${f.absolutePath}\" border=\"0\"><br>")
            append("<div style=\"color:#7a7e85;font:11px sans-serif;margin-top:4px;text-align:center\">$label</div>")
            append("</body></html>")
        }
        htmlCache[model] = html
        return html
    }

    /** 在 200×130 画布上渲染布局预览位图。 */
    private fun render(model: LayoutModel): BufferedImage {
        val w = 200
        val h = 130
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            // 深色面板底
            g2.color = Color(0x2b2d31)
            g2.fillRoundRect(0, 0, w, h, 8, 8)
            val pad = 12
            val boxW = w - pad * 2
            val boxH = h - pad * 2
            // 容器边框
            g2.color = Color(0x8a8d93)
            g2.draw(Rectangle2D.Float(pad.toFloat(), pad.toFloat(), boxW - 1f, boxH - 1f))
            // 子块（内缩 1px + 圆角）
            g2.color = Color(0x4f9bff)
            for (b in model.boxes(boxW, boxH)) {
                val bw = b.w - 1
                val bh = b.h - 1
                if (bw <= 0 || bh <= 0) continue
                g2.fill(RoundRectangle2D.Float(
                    (pad + b.x).toFloat(), (pad + b.y).toFloat(), bw.toFloat(), bh.toFloat(), 3f, 3f
                ))
            }
        } finally {
            g2.dispose()
        }
        return img
    }
}