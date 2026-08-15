package com.pan.dashstyle

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon

/**
 * 尺寸/单位换算助手的「gutter 色块预览」。
 *
 * 类似 WebStorm 对 CSS 颜色在行号前的预览：对每个值可换算的 CSS 声明
 * （px/rem/vw/clamp/calc），在第行号前的 gutter 渲染一个按类型着色的小圆角色块，
 * 鼠标悬浮显示完整的换算文本（复用 [CssUnitInlayProvider.hintFor]）。
 *
 * 与行尾文本 inlay（CssUnitInlayProvider）互补：gutter 承担「一眼识别+悬浮详情」，
 * 原 inlay 保留行位实时文本。
 */
class CssUnitGutterMarkerProvider : LineMarkerProvider {

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        for (element in elements) {
            if (element !is CssDeclaration) continue
            val text = element.value?.text?.trim() ?: continue
            val hint = CssUnitInlayProvider.hintFor(text) ?: continue
            // LineMarkerInfo 要求挂在叶子元素上（否则抛 "registered for leaf elements only"），
            // gutter 图标按该叶子的行定位。取声明内最深的第一个叶子（属性名 token）。
            val leaf = PsiTreeUtil.getDeepestFirst(element) ?: continue
            val marker = LineMarkerInfo(
                leaf,
                leaf.textRange,
                UnitSwatchIcon(swatchColor(text)),
                { hint },
                null,
                GutterIconRenderer.Alignment.LEFT
            )
            result.add(marker)
        }
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun isDumbAware(): Boolean = true

    /** 按换算类型给色块着色，便于一眼区分。 */
    private fun swatchColor(value: String): Color {
        val v = value.trim().lowercase()
        return when {
            v.startsWith("clamp(") -> Color(0xf0, 0x9f, 0x3b)                 // clamp → 橙
            v.startsWith("calc(") -> Color(0x2a, 0xa1, 0x98)                  // calc → 青
            v.endsWith("px") -> Color(0x4a, 0x90, 0xd9)                       // px → 蓝
            v.endsWith("rem") || v.endsWith("em") -> Color(0x7a, 0xb8, 0x4a)  // rem/em → 绿
            v.endsWith("vw") || v.endsWith("%") -> Color(0x9b, 0x6f, 0xd6)    // vw/% → 紫
            else -> Color(0x9a, 0xa0, 0xaa)                                   // 兜底灰
        }
    }
}

/** 小圆角色块 icon，模拟 WebStorm 颜色预览外观。 */
private class UnitSwatchIcon(private val color: Color) : Icon {
    override fun getIconWidth(): Int = 11
    override fun getIconHeight(): Int = 11

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = color
            g2.fill(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), 11f, 11f, 3f, 3f))
            // 细描边，提升浅色/深色背景下的辨识度
            g2.color = JBColor(Color(0, 0, 0, 60), Color(255, 255, 255, 70))
            g2.draw(RoundRectangle2D.Float(x.toFloat(), y.toFloat(), 11f, 11f, 3f, 3f))
        } finally {
            g2.dispose()
        }
    }
}