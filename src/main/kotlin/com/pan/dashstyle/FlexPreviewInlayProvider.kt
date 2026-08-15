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
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Graphics2D
import java.awt.Point
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.SwingUtilities

/**
 * CSS Flex 布局增强 —— 行尾迷你预览。
 *
 * 输入时自动更新（InlayHintsProvider 由高亮通道驱动，随输入实时刷新）：
 *  - 在 `display:flex` 所在 ruleset 的每个 flex 属性行尾，渲染一个迷你布局图，
 *    聚焦展示当前那条属性（如 `justify-content: center` → 子项水平居中）。
 *  - 在 `display:flex` 行尾额外渲染一个「总效果」徽标，汇总整个容器所有 flex 属性。
 *
 * 交互（点击弹窗）属于后续阶段：本类只负责静态渲染，交互由 JBPopup 面板实现。
 */
class FlexPreviewInlayProvider : InlayHintsProvider<FlexPreviewInlayProvider.Settings> {

    class Settings {
        @JvmField var showPerProperty: Boolean = true
        @JvmField var showOverallBadge: Boolean = true
    }

    override val key: SettingsKey<Settings> = SettingsKey("dashstyle.css.flex.preview")
    override val name: String = "CSS Flex 布局预览"
    override val description: String =
        "在 display:flex 及其 flex 属性行尾显示迷你布局预览，直观反映当前属性值；flex 行额外显示总效果徽标。"
    override val group: InlayGroup = InlayGroup.OTHER_GROUP

    override fun createSettings(): Settings = Settings()

    override val previewText: String =
        ".toolbar {\n  display: flex;\n  justify-content: center;\n  align-items: center;\n  gap: 12px;\n  flex-wrap: nowrap;\n}"

    override fun isLanguageSupported(language: Language): Boolean {
        val id = language.id.lowercase()
        return id.contains("css") || id == "less" || id.contains("scss")
    }

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                val perProp = JCheckBox("每个 flex 属性行尾显示迷你预览", settings.showPerProperty)
                val overall = JCheckBox("在 display:flex 行显示总效果徽标", settings.showOverallBadge)
                perProp.addActionListener {
                    settings.showPerProperty = perProp.isSelected
                    listener.settingsChanged()
                }
                overall.addActionListener {
                    settings.showOverallBadge = overall.isSelected
                    listener.settingsChanged()
                }
                val box = Box.createVerticalBox()
                box.add(perProp)
                box.add(overall)
                return JBUI.Panels.simplePanel().addToCenter(box)
            }

            override val mainCheckboxText: String get() = "启用 CSS Flex 布局预览"
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
                if (!settings.showPerProperty && !settings.showOverallBadge) return false
                emitInlays(flexContexts(file), editor, settings, sink)
                return false
            }
        }
    }

    // ================================================================
    // flex 上下文解析（结果用 CachedValue 缓存：大文件反复高亮时只解析一次，
    // 文档 PSI 变化后自动失效重建，避免每次全量扫描所有 ruleset）
    // ================================================================
    private data class FlexContext(
        val ruleset: CssRuleset,
        val display: CssDeclaration,
        val decls: List<CssDeclaration>,
        val base: FlexLayoutResolver.Props
    )

    private fun flexContexts(file: PsiFile): List<FlexContext> {
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(computeFlexContexts(file), file)
        }
    }

    private fun computeFlexContexts(file: PsiFile): List<FlexContext> {
        val result = ArrayList<FlexContext>()
        for (rs in PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)) {
            val block = rs.block ?: continue
            val decls = PsiTreeUtil.findChildrenOfType(block, CssDeclaration::class.java).toList()
            if (decls.isEmpty()) continue

            val display = decls.firstOrNull { it.propertyName?.trim().equals("display", true) }
                ?: continue
            val displayVal = display.value?.text?.trim()?.lowercase()
            if (displayVal != "flex" && displayVal != "inline-flex") continue

            // 收集当前 ruleset 里所有 flex 相关属性，作为上下文（总效果 + 每条预览的底座）
            val ctx = HashMap<String, String>()
            for (d in decls) {
                val p = d.propertyName?.trim()?.lowercase() ?: continue
                if (p in FLEX_PROPS) ctx[p] = d.value?.text?.trim().orEmpty()
            }
            val base = FlexLayoutResolver.Props(
                direction = FlexLayoutResolver.parseDirection(ctx["flex-direction"]),
                justify = FlexLayoutResolver.parseJustify(ctx["justify-content"]),
                align = FlexLayoutResolver.parseAlign(ctx["align-items"]),
                alignContent = FlexLayoutResolver.parseAlignContent(ctx["align-content"]),
                gap = FlexLayoutResolver.parseGap(ctx["gap"] ?: ctx["row-gap"]),
                wrap = ctx["flex-wrap"]?.equals("wrap", true) == true
            )
            result.add(FlexContext(rs, display, decls, base))
        }
        return result
    }

    // ================================================================
    // 把缓存的上下文渲染成 inlay
    // ================================================================
    private fun emitInlays(
        contexts: List<FlexContext>,
        editor: Editor,
        settings: Settings,
        sink: InlayHintsSink
    ) {
        for (fc in contexts) {
            // display:flex 行 → 总效果徽标
            if (settings.showOverallBadge) {
                sink.addInlineElement(
                    fc.display.textRange.endOffset, false,
                    FlexPreviewPresentation(fc.base, editor, fc.ruleset, isOverall = true), false
                )
            }
            // 每个 flex 属性行尾 → 以 base 为底座、仅替换该条属性值，聚焦展示当前那条
            if (settings.showPerProperty) {
                for (d in fc.decls) {
                    val p = d.propertyName?.trim()?.lowercase() ?: continue
                    val props = when (p) {
                        "justify-content" -> fc.base.copy(justify = FlexLayoutResolver.parseJustify(d.value?.text, fc.base.justify))
                        "align-items" -> fc.base.copy(align = FlexLayoutResolver.parseAlign(d.value?.text, fc.base.align))
                        "align-content" -> fc.base.copy(alignContent = FlexLayoutResolver.parseAlignContent(d.value?.text, fc.base.alignContent))
                        "flex-direction" -> fc.base.copy(direction = FlexLayoutResolver.parseDirection(d.value?.text, fc.base.direction))
                        "gap", "row-gap" -> fc.base.copy(gap = FlexLayoutResolver.parseGap(d.value?.text))
                        "flex-wrap" -> fc.base.copy(wrap = d.value?.text?.trim()?.equals("wrap", true) == true)
                        else -> continue
                    }
                    sink.addInlineElement(d.textRange.endOffset, false, FlexPreviewPresentation(props, editor, fc.ruleset), false)
                }
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
        private val FLEX_PROPS = setOf(
            "justify-content", "align-items", "align-content", "flex-direction", "flex-wrap",
            "gap", "row-gap", "column-gap"
        )
    }
}

/**
 * 自定义绘制的迷你 Flex 布局图。基于 FlexLayoutResolver 计算子项位置后，
 * 在 [getWidth]×[getHeight] 区域内：画一个容器边框 + 若干子项填充矩形。
 * [isOverall] = true 时用更醒目配色并放大，作为「总效果」徽标。
 */
class FlexPreviewPresentation(
    private val props: FlexLayoutResolver.Props,
    private val editor: Editor,
    private val ruleset: CssRuleset,
    private val isOverall: Boolean = false
) : BasePresentation() {

    override val width: Int get() = if (isOverall) 46 else 40
    override val height: Int get() = if (isOverall) 26 else 22

    override fun mouseClicked(event: MouseEvent, point: Point) {
        if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) {
            val popup = FlexPreviewPopup.create(editor, ruleset, props)
            // 锚定在 inlay 附近弹出，用户可拖动
            if (event.component != null) {
                popup.showInScreenCoordinates(event.component, event.locationOnScreen)
            }
        }
    }

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        val pad = if (isOverall) 3 else 2
        val boxW = width - pad * 2
        val boxH = height - pad * 2
        val boxes = FlexLayoutResolver.place(props, boxW, boxH)

        g2d.color = if (isOverall) OVERALL_BORDER else BORDER
        g2d.drawRect(pad, pad, boxW - 1, boxH - 1)
        val fill = if (isOverall) OVERALL_CHILD else CHILD
        for (b in boxes) {
            g2d.color = fill
            g2d.fillRect(pad + b.x, pad + b.y, b.w, b.h)
        }
    }

    override fun toString(): String = "FlexPreview(${props.direction},${props.justify},${props.align},gap=${props.gap})"

    private companion object {
        val BORDER: JBColor = JBColor(Color(0x8a8d93), Color(0x5a5d63))
        val CHILD: JBColor = JBColor(Color(0x4f8cff), Color(0x6aa0ff))
        val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))
    }
}