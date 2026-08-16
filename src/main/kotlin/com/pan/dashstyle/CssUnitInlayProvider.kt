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
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.font.FontRenderContext
import java.awt.geom.Rectangle2D
import javax.swing.Box
import javax.swing.JCheckBox
import javax.swing.JComponent

/**
 * 尺寸/单位换算助手 —— inlay。
 *
 * 在 CSS 长度值（px/rem/vw）行尾、`clamp(...)` / `calc(...)` 值行尾渲染换算提示：
 *  - `12px` → `≈ 0.75rem ≈ 0.83vw`
 *  - `clamp(16px, 2vw, 24px)` → `→ 28.8px (2vw)`
 *  - `calc(100% - 20px)` → `= 1420px`
 * 淡灰色小字，随输入实时刷新（InlayHintsProvider 由高亮通道驱动）。
 */
class CssUnitInlayProvider : InlayHintsProvider<CssUnitInlayProvider.Settings> {

    class Settings {
        @JvmField var showConversions: Boolean = true
    }

    override val key: SettingsKey<Settings> = SettingsKey("dashstyle.css.unit.assistant")
    override val name: String = "CSS 尺寸/单位换算助手"
    override val description: String =
        "在 px/rem/vw 长度、clamp()、calc() 值行尾显示换算结果（px↔rem↔vw、clamp 实际取值、calc 简化值）。"
    override val group: InlayGroup = InlayGroup.OTHER_GROUP

    override fun createSettings(): Settings = Settings()

    override val previewText: String =
        "  width: 12px;\n  font-size: clamp(16px, 2vw, 24px);\n  margin: calc(100% - 20px);"

    override fun isLanguageSupported(language: Language): Boolean {
        val id = language.id.lowercase()
        return id.contains("css") || id == "less" || id.contains("scss")
    }

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                val conversions = JCheckBox("显示长度换算（px↔rem↔vw）及 clamp/calc 结果", settings.showConversions)
                conversions.addActionListener {
                    settings.showConversions = conversions.isSelected
                    listener.settingsChanged()
                }
                val box = Box.createVerticalBox()
                box.add(conversions)
                return JBUI.Panels.simplePanel().addToCenter(box)
            }

            override val mainCheckboxText: String get() = "启用 CSS 尺寸/单位换算助手"
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
                if (element !== file || !isStylesheetLike(file)) return false
                if (!settings.showConversions) return false
                for (d in PsiTreeUtil.findChildrenOfType(file, CssDeclaration::class.java)) {
                    val text = d.value?.text?.trim() ?: continue
                    val hint = hintFor(text) ?: continue
                    sink.addInlineElement(d.textRange.endOffset, false, UnitHintPresentation(hint), false)
                }
                return false
            }
        }
    }

    private fun isStylesheetLike(file: PsiFile): Boolean {
        val rc = file.javaClass.name
        if (rc.contains("StylesheetFile", true) || rc.contains("CssFile", true) ||
            rc.contains("ScssFile", true) || rc.contains("LessFile", true) ||
            rc.contains("SassFile", true)) return true
        val ext = file.virtualFile?.extension?.lowercase()
        return ext in setOf("css", "scss", "sass", "less")
    }

    companion object {
        /** 计算某个声明值的换算提示；不可换算返回 null。 */
        fun hintFor(value: String): String? {
            val v = value.trim()
            return when {
                v.startsWith("clamp(") -> CssUnitAssistant.clampHint(v)?.let { "→ $it" }
                v.startsWith("calc(") -> CssUnitAssistant.calcHint(v)?.let { "= $it" }
                CssUnitAssistant.parseLength(v) != null -> CssUnitAssistant.convertHint(v)?.let { "≈ $it" }
                else -> null
            }
        }
    }
}

/** 淡灰色换算提示的小字 inlay。 */
private class UnitHintPresentation(private val text: String) : BasePresentation() {
    private val font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
    private val fg: JBColor = JBColor(Color(0x9aa0aa), Color(0x7a7e85))

    override val width: Int
        get() = fontMetricsWidth(text)

    override val height: Int get() = 14

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        val old = g2d.font
        g2d.font = font
        g2d.color = fg
        g2d.drawString(text, 0, fontMetricsAscent())
        g2d.font = old
    }

    private fun fontMetricsBounds(s: String): Rectangle2D {
        val frc = FontRenderContext(null, true, true)
        return font.getStringBounds(s, frc)
    }

    private fun fontMetricsWidth(s: String): Int = fontMetricsBounds(s).width.toInt().coerceAtLeast(1)

    private fun fontMetricsAscent(): Int = font.getLineMetrics(text, FontRenderContext(null, true, true)).ascent.toInt()

    override fun toString(): String = "UnitHint($text)"
}