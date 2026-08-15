package com.pan.dashstyle

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.LineMarkerProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import javax.imageio.ImageIO
import javax.swing.Icon

/**
 * CSS 阴影预览 —— gutter 迷你预览（box-shadow / text-shadow）。
 *
 * 在 `box-shadow` / `text-shadow` 声明行前渲染一个圆角矩形 + 真实阴影的图标，
 * 形如 WebStorm 对颜色的 gutter 预览。悬浮时把同一阴影以更大尺寸渲染成 PNG 作为 tooltip。
 *
 * 解析复用 [ShadowResolver]（纯逻辑，可单测）。
 */
class ShadowGutterMarkerProvider : LineMarkerProvider {

    override fun collectSlowLineMarkers(
        elements: List<PsiElement>,
        result: MutableCollection<in LineMarkerInfo<*>>
    ) {
        for (element in elements) {
            if (element !is CssDeclaration) continue
            val prop = element.propertyName?.lowercase() ?: continue
            if (prop != "box-shadow" && prop != "text-shadow") continue
            val layers = ShadowResolver.parse(element.value?.text)
            if (layers.isEmpty()) continue
            val leaf = PsiTreeUtil.getDeepestFirst(element) ?: continue
            result.add(
                LineMarkerInfo(
                    leaf,
                    leaf.textRange,
                    ShadowGutterIcon(layers),
                    { ShadowTooltip.html(prop, layers) },
                    null,
                    com.intellij.openapi.editor.markup.GutterIconRenderer.Alignment.LEFT
                )
            )
        }
    }

    override fun getLineMarkerInfo(element: PsiElement): LineMarkerInfo<*>? = null

    override fun isDumbAware(): Boolean = true
}

/** gutter 阴影 icon：深色底 + 一个圆角矩形 + 按解析图层渲染阴影。 */
private class ShadowGutterIcon(private val layers: List<ShadowResolver.Layer>) : Icon {

    override fun getIconWidth(): Int = 32
    override fun getIconHeight(): Int = 32

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        try {
            val img = ShadowRender.render(32, 32, layers)
            g2.drawImage(img, x, y, null)
        } finally {
            g2.dispose()
        }
    }
}

/** 阴影放大预览 tooltip：把阴影渲染成 PNG，以 HTML `<img src="file://...">` 显示。 */
private object ShadowTooltip {

    private val htmlCache = ConcurrentHashMap<Pair<String, List<ShadowResolver.Layer>>, String>()
    private val createdFiles = ConcurrentLinkedQueue<File>()

    init {
        Runtime.getRuntime().addShutdownHook(Thread {
            for (f in createdFiles) runCatching { f.delete() }
        })
    }

    fun html(prop: String, layers: List<ShadowResolver.Layer>): String {
        val key = prop to layers
        htmlCache[key]?.let { return it }
        val img = ShadowRender.render(200, 130, layers)
        val f = File.createTempFile("dashstyle-shadow-", ".png")
        ImageIO.write(img, "png", f)
        createdFiles.add(f)
        val colorStops = ShadowRender.colorStops(layers)
        val html = buildString {
            append("<html><body style=\"padding:6px\">")
            append("<img src=\"file://${f.absolutePath}\" border=\"0\"><br>")
            append("<div style=\"color:#7a7e85;font:11px sans-serif;margin-top:4px;text-align:center\">")
            append("$prop · ${layers.size} 层阴影 · $colorStops")
            append("</div></body></html>")
        }
        htmlCache[key] = html
        return html
    }
}

/** 阴影位图渲染（icon 与放大 tooltip 共用）。 */
internal object ShadowRender {

    private val PANEL_BG = Color(0x2b2d31)
    private val ELEMENT_BG = Color(0x4a4e55)
    private val ELEMENT_BORDER = Color(0xaab0b8)

    /** 渲染阴影预览位图。 */
    fun render(w: Int, h: Int, layers: List<ShadowResolver.Layer>): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g2 = img.createGraphics() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = PANEL_BG
            g2.fillRoundRect(0, 0, w, h, 0, 0)

            val elW = (w * 0.62).toInt()
            val elH = (h * 0.42).toInt()
            val elX = (w - elW) / 2
            val elY = (h - elH) / 2
            val corner = (minOf(w, h) * 0.08).toInt().coerceAtLeast(2)
            val scale = 0.8

            for (layer in layers) {
                val color = layer.color ?: Color(0, 0, 0, 150)
                if (layer.inset) {
                    drawInset(g2, elX, elY, elW, elH, layer, color, scale)
                } else {
                    drawOuterShadow(img, elX, elY, elW, elH, layer, color, scale)
                }
            }

            // 元素本体
            g2.color = ELEMENT_BG
            g2.fill(RoundRectangle2D.Float(elX.toFloat(), elY.toFloat(), elW.toFloat(), elH.toFloat(), corner.toFloat(), corner.toFloat()))
            g2.color = ELEMENT_BORDER
            g2.draw(RoundRectangle2D.Float(elX.toFloat(), elY.toFloat(), elW.toFloat(), elH.toFloat(), corner.toFloat(), corner.toFloat()))
        } finally {
            g2.dispose()
        }
        return img
    }

    private fun drawOuterShadow(
        img: BufferedImage,
        elX: Int, elY: Int, elW: Int, elH: Int,
        layer: ShadowResolver.Layer, color: Color, scale: Double
    ) {
        val w = img.width; val h = img.height
        val sb = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val sg = sb.createGraphics()
        try {
            sg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val sx = (elX + layer.offsetX * scale).toInt()
            val sy = (elY + layer.offsetY * scale).toInt()
            val sw = (elW + layer.spread * 2 * scale).toInt()
            val sh = (elH + layer.spread * 2 * scale).toInt()
            val corner = (minOf(sw, sh) * 0.08).toInt().coerceAtLeast(2)
            sg.color = Color(0, 0, 0, 255)
            sg.fill(RoundRectangle2D.Float(sx.toFloat(), sy.toFloat(), sw.toFloat(), sh.toFloat(), corner.toFloat(), corner.toFloat()))
        } finally {
            sg.dispose()
        }
        val blurred = boxBlur(sb, (layer.blur * scale).toInt().coerceIn(0, 20))
        tintAndComposite(img, blurred, color)
    }

    private fun drawInset(
        g2: Graphics2D,
        elX: Int, elY: Int, elW: Int, elH: Int,
        layer: ShadowResolver.Layer, color: Color, scale: Double
    ) {
        // 内阴影：沿元素内缘画一条半透明粗边框
        g2.color = Color(color.red, color.green, color.blue, (color.alpha * 0.8).toInt())
        val inset = 2
        val strokeW = (2 + layer.blur * scale).toInt().coerceAtLeast(2)
        g2.stroke = BasicStroke(strokeW.toFloat())
        g2.draw(RoundRectangle2D.Float(
            (elX + inset).toFloat(), (elY + inset).toFloat(),
            (elW - inset * 2).toFloat(), (elH - inset * 2).toFloat(),
            (minOf(elW, elH) * 0.08).toFloat().coerceAtLeast(2f), (minOf(elW, elH) * 0.08).toFloat().coerceAtLeast(2f)
        ))
        g2.stroke = BasicStroke(1f)
    }

    private fun boxBlur(src: BufferedImage, radius: Int): BufferedImage {
        if (radius <= 0) return src
        val r = radius.coerceIn(1, 20)
        val w = src.width; val h = src.height
        val srcI = (src.raster.dataBuffer as DataBufferInt).data
        val tmp = IntArray(w * h)
        val out = IntArray(w * h)
        // horizontal pass（alpha 滑动窗口）
        for (y in 0 until h) {
            var sum = 0
            var cnt = 0
            for (kx in 0 until r) { sum += alphaAt(srcI, kx, y, w); cnt++ }
            for (x in 0 until w) {
                if (x + r < w) { sum += alphaAt(srcI, x + r, y, w); cnt++ }
                if (x - r - 1 >= 0) { sum -= alphaAt(srcI, x - r - 1, y, w); cnt-- }
                tmp[y * w + x] = if (cnt > 0) sum / cnt else 0
            }
        }
        // vertical pass
        for (x in 0 until w) {
            var sum = 0
            var cnt = 0
            for (ky in 0 until r) { sum += tmp[ky * w + x]; cnt++ }
            for (y in 0 until h) {
                if (y + r < h) { sum += tmp[(y + r) * w + x]; cnt++ }
                if (y - r - 1 >= 0) { sum -= tmp[(y - r - 1) * w + x]; cnt-- }
                out[y * w + x] = if (cnt > 0) sum / cnt else 0
            }
        }
        val outImg = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val dstI = (outImg.raster.dataBuffer as DataBufferInt).data
        for (i in 0 until w * h) {
            val a = out[i]
            dstI[i] = (a shl 24) or (srcI[i] and 0x00ffffff)
        }
        return outImg
    }

    private fun alphaAt(data: IntArray, x: Int, y: Int, w: Int): Int = (data[y * w + x] ushr 24) and 0xff

    /** 把 [shadow] 的 alpha 形状以 [color] 叠加到 [dst]（source-over）。 */
    private fun tintAndComposite(dst: BufferedImage, shadow: BufferedImage, color: Color) {
        val w = dst.width; val h = dst.height
        val dstI = (dst.raster.dataBuffer as DataBufferInt).data
        val shI = (shadow.raster.dataBuffer as DataBufferInt).data
        val cr = color.red; val cg = color.green; val cb = color.blue; val ca = color.alpha
        for (i in 0 until w * h) {
            val sa = (shI[i] ushr 24) and 0xff
            if (sa == 0) continue
            val a = (ca * sa / 255.0).toInt()
            if (a == 0) continue
            val dstA = (dstI[i] ushr 24) and 0xff
            val outA = a + dstA * (255 - a) / 255
            val srcFrac = a.toDouble() / outA
            val dstFrac = 1.0 - srcFrac
            val dr = (dstI[i] ushr 16) and 0xff
            val dg = (dstI[i] ushr 8) and 0xff
            val db = dstI[i] and 0xff
            val r = (cr * srcFrac + dr * dstFrac).toInt()
            val g = (cg * srcFrac + dg * dstFrac).toInt()
            val b = (cb * srcFrac + db * dstFrac).toInt()
            dstI[i] = (outA shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    /** tooltip 副标题：各层颜色的简短概要。 */
    fun colorStops(layers: List<ShadowResolver.Layer>): String {
        if (layers.isEmpty()) return ""
        return layers.joinToString(" + ") { l ->
            val c = l.color
            val desc = if (c == null) "默认色" else "#%02x%02x%02x".format(c.red, c.green, c.blue)
            (if (l.inset) "inset " else "") + desc
        }
    }
}