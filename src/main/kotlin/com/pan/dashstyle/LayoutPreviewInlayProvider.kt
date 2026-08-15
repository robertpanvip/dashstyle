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
import java.awt.image.BufferedImage
import java.util.LinkedHashMap
import javax.swing.Box
import javax.swing.JComponent
import javax.swing.JCheckBox
import javax.swing.SwingUtilities

/**
 * CSS 布局增强 —— 行尾迷你预览（flex 与 grid）。
 *
 * 输入时自动更新（InlayHintsProvider 由高亮通道驱动，随输入实时刷新）：
 *  - 在 display:flex / display:grid 所在 ruleset 的每个布局属性行尾，渲染一个迷你布局图，
 *    聚焦展示当前那条属性；
 *  - 在 display:flex / display:grid 行尾额外渲染一个「总效果」徽标，汇总整个容器布局。
 *
 * 点击预览会弹出交互面板（见 [LayoutPreviewPopup]），可调整属性并写回 CSS。
 */
class LayoutPreviewInlayProvider : InlayHintsProvider<LayoutPreviewInlayProvider.Settings> {

    class Settings {
        @JvmField var showPerProperty: Boolean = true
        @JvmField var showOverallBadge: Boolean = true
    }

    override val key: SettingsKey<Settings> = SettingsKey("dashstyle.css.layout.preview")
    override val name: String = "CSS 布局预览"
    override val description: String =
        "在 display:flex/grid 及其布局属性行尾显示迷你布局预览；display 行额外显示总效果徽标，点击可交互调整并写回 CSS。"
    override val group: InlayGroup = InlayGroup.OTHER_GROUP

    override fun createSettings(): Settings = Settings()

    override val previewText: String =
        ".toolbar {\n  display: grid;\n  grid-template-columns: repeat(3, 1fr);\n  gap: 8px;\n  align-items: center;\n}"

    override fun isLanguageSupported(language: Language): Boolean {
        val id = language.id.lowercase()
        return id.contains("css") || id == "less" || id.contains("scss")
    }

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent {
                val perProp = JCheckBox("每个布局属性行尾显示迷你预览", settings.showPerProperty)
                val overall = JCheckBox("在 display:flex/grid 行显示总效果徽标", settings.showOverallBadge)
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

            override val mainCheckboxText: String get() = "启用 CSS 布局预览"
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
                emitInlays(layoutContexts(file), editor, settings, sink)
                return false
            }
        }
    }

    // ================================================================
    // 布局上下文解析 + CachedValue 缓存（大文件反复高亮只解析一次）
    // ================================================================
    private data class LayoutContext(
        val ruleset: CssRuleset,
        val display: CssDeclaration,
        val overall: LayoutModel,
        val perProperty: List<Pair<CssDeclaration, LayoutModel>>
    )

    private fun layoutContexts(file: PsiFile): List<LayoutContext> {
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(computeLayoutContexts(file), file)
        }
    }

    private fun computeLayoutContexts(file: PsiFile): List<LayoutContext> {
        val result = ArrayList<LayoutContext>()
        for (rs in PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)) {
            val block = rs.block ?: continue
            val decls = block.getDeclarations().toList()
            if (decls.isEmpty()) continue
            val display = decls.firstOrNull { it.propertyName?.trim().equals("display", true) } ?: continue
            val dv = display.value?.text?.trim()?.lowercase()
            val ctx = when (dv) {
                "flex", "inline-flex" -> buildFlexContext(rs, decls, display)
                "grid", "inline-grid" -> buildGridContext(rs, decls, display)
                else -> null
            }
            if (ctx != null) result.add(ctx)
        }
        return result
    }

    private fun buildFlexContext(
        rs: CssRuleset,
        decls: List<CssDeclaration>,
        display: CssDeclaration
    ): LayoutContext {
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
        val per = ArrayList<Pair<CssDeclaration, LayoutModel>>()
        for (d in decls) {
            val p = d.propertyName?.trim()?.lowercase() ?: continue
            val props = when (p) {
                "justify-content" -> base.copy(justify = FlexLayoutResolver.parseJustify(d.value?.text, base.justify))
                "align-items" -> base.copy(align = FlexLayoutResolver.parseAlign(d.value?.text, base.align))
                "align-content" -> base.copy(alignContent = FlexLayoutResolver.parseAlignContent(d.value?.text, base.alignContent))
                "flex-direction" -> base.copy(direction = FlexLayoutResolver.parseDirection(d.value?.text, base.direction))
                "gap", "row-gap" -> base.copy(gap = FlexLayoutResolver.parseGap(d.value?.text))
                "flex-wrap" -> base.copy(wrap = d.value?.text?.trim()?.equals("wrap", true) == true)
                else -> continue
            }
            per.add(d to LayoutModel.Flex(props))
        }
        return LayoutContext(rs, display, LayoutModel.Flex(base), per)
    }

    private fun buildGridContext(
        rs: CssRuleset,
        decls: List<CssDeclaration>,
        display: CssDeclaration
    ): LayoutContext {
        val ctx = HashMap<String, String>()
        for (d in decls) {
            val p = d.propertyName?.trim()?.lowercase() ?: continue
            if (p in GRID_PROPS) ctx[p] = d.value?.text?.trim().orEmpty()
        }
        val base = GridLayoutResolver.Props(
            columns = GridLayoutResolver.parseTrackList(ctx["grid-template-columns"]),
            rows = GridLayoutResolver.parseTrackList(ctx["grid-template-rows"]),
            gap = FlexLayoutResolver.parseGap(ctx["gap"] ?: ctx["column-gap"]),
            justifyItems = GridLayoutResolver.parseAlign(ctx["justify-items"]),
            alignItems = GridLayoutResolver.parseAlign(ctx["align-items"]),
            justifyContent = GridLayoutResolver.parseAlign(ctx["justify-content"]),
            alignContent = GridLayoutResolver.parseAlign(ctx["align-content"])
        )
        val per = ArrayList<Pair<CssDeclaration, LayoutModel>>()
        for (d in decls) {
            val p = d.propertyName?.trim()?.lowercase() ?: continue
            val props = when (p) {
                "grid-template-columns" -> base.copy(columns = GridLayoutResolver.parseTrackList(d.value?.text))
                "grid-template-rows" -> base.copy(rows = GridLayoutResolver.parseTrackList(d.value?.text))
                "gap", "column-gap" -> base.copy(gap = FlexLayoutResolver.parseGap(d.value?.text))
                "justify-items" -> base.copy(justifyItems = GridLayoutResolver.parseAlign(d.value?.text, base.justifyItems))
                "align-items" -> base.copy(alignItems = GridLayoutResolver.parseAlign(d.value?.text, base.alignItems))
                "justify-content" -> base.copy(justifyContent = GridLayoutResolver.parseAlign(d.value?.text, base.justifyContent))
                "align-content" -> base.copy(alignContent = GridLayoutResolver.parseAlign(d.value?.text, base.alignContent))
                else -> continue
            }
            per.add(d to LayoutModel.Grid(props))
        }
        return LayoutContext(rs, display, LayoutModel.Grid(base), per)
    }

    // ================================================================
    // 渲染成 inlay
    // ================================================================
    private fun emitInlays(
        contexts: List<LayoutContext>,
        editor: Editor,
        settings: Settings,
        sink: InlayHintsSink
    ) {
        for (ctx in contexts) {
            if (settings.showOverallBadge) {
                sink.addInlineElement(
                    ctx.display.textRange.endOffset, false,
                    LayoutPreviewPresentation(ctx.overall, editor, ctx.ruleset, isOverall = true), false
                )
            }
            if (settings.showPerProperty) {
                for ((d, model) in ctx.perProperty) {
                    sink.addInlineElement(d.textRange.endOffset, false, LayoutPreviewPresentation(model, editor, ctx.ruleset), false)
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

        private val GRID_PROPS = setOf(
            "grid-template-columns", "grid-template-rows", "gap", "column-gap", "row-gap",
            "justify-items", "align-items", "justify-content", "align-content"
        )
    }
}

/**
 * 自定义绘制的迷你布局图。底层可以是 flex 或 grid（通过 [LayoutModel] 抽象）。
 * 已按「模型 + 尺寸」预渲染成位图缓存，paint 直接整图 blit。
 */
class LayoutPreviewPresentation(
    private val model: LayoutModel,
    private val editor: Editor,
    private val ruleset: CssRuleset,
    private val isOverall: Boolean = false
) : BasePresentation() {

    override val width: Int get() = if (isOverall) 46 else 40
    override val height: Int get() = if (isOverall) 26 else 22

    override fun mouseClicked(event: MouseEvent, point: Point) {
        if (SwingUtilities.isLeftMouseButton(event) && event.clickCount == 1) {
            val popup = LayoutPreviewPopup.create(editor, ruleset, model)
            if (event.component != null) {
                popup.showInScreenCoordinates(event.component, event.locationOnScreen)
            }
        }
    }

    override fun paint(g2d: Graphics2D, textAttributes: TextAttributes) {
        g2d.drawImage(image(model, width, height, isOverall), 0, 0, width, height, null)
    }

    override fun toString(): String = "LayoutPreview(${model.javaClass.simpleName})"

    private companion object {
        val BORDER: JBColor = JBColor(Color(0x8a8d93), Color(0x5a5d63))
        val CHILD: JBColor = JBColor(Color(0x4f8cff), Color(0x6aa0ff))
        val OVERALL_BORDER: JBColor = JBColor(Color(0x2d7ff9), Color(0x4f9bff))
        val OVERALL_CHILD: JBColor = JBColor(Color(0x1b5fd0), Color(0x5f9bff))

        private data class ImageKey(
            val props: Any,
            val w: Int,
            val h: Int,
            val overall: Boolean
        )

        private const val MAX_IMAGES = 256
        private val imageCache = object : LinkedHashMap<ImageKey, BufferedImage>(MAX_IMAGES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ImageKey, BufferedImage>?): Boolean =
                size > MAX_IMAGES
        }

        private fun modelProps(model: LayoutModel): Any = when (model) {
            is LayoutModel.Flex -> model.props
            is LayoutModel.Grid -> model.props
        }

        private fun image(model: LayoutModel, w: Int, h: Int, overall: Boolean): BufferedImage {
            val key = ImageKey(modelProps(model), w, h, overall)
            synchronized(imageCache) {
                imageCache[key]?.let { return it }
            }
            val img = BufferedImage(w.coerceAtLeast(1), h.coerceAtLeast(1), BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            val pad = if (overall) 3 else 2
            val boxW = w - pad * 2
            val boxH = h - pad * 2
            val boxes = model.boxes(boxW, boxH)
            g.color = if (overall) OVERALL_BORDER else BORDER
            g.drawRect(pad, pad, boxW - 1, boxH - 1)
            val fill = if (overall) OVERALL_CHILD else CHILD
            for (b in boxes) {
                g.color = fill
                g.fillRect(pad + b.x, pad + b.y, b.w, b.h)
            }
            g.dispose()
            synchronized(imageCache) {
                imageCache[key] = img
            }
            return img
        }
    }
}