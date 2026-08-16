package com.pan.dashstyle

import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.psi.util.PsiTreeUtil

/**
 * flex/grid 布局上下文解析 —— 供各种渲染入口（inlay / gutter）复用。
 *
 * 扫描文件里所有 `display:flex|grid` 的 ruleset，解析出：
 *  - [overall]：整个容器的总效果 [LayoutModel]（挂在 display 行）；
 *  - [perProperty]：每个布局属性单独聚焦后的 [LayoutModel]（挂在对应属性行）。
 *
 * 结果按文件缓存（CachedValue），大文件反复高亮只解析一次。
 */
object LayoutContextResolver {

    data class LayoutContext(
        val ruleset: CssRuleset,
        val display: CssDeclaration,
        val overall: LayoutModel,
        val perProperty: List<Pair<CssDeclaration, LayoutModel>>
    )

    fun contexts(file: PsiFile): List<LayoutContext> {
        return CachedValuesManager.getCachedValue(file) {
            CachedValueProvider.Result.create(
                compute(file),
                file,
                PsiModificationTracker.MODIFICATION_COUNT
            )
        }
    }

    private fun compute(file: PsiFile): List<LayoutContext> {
        val result = ArrayList<LayoutContext>()
        for (rs in PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)) {
            val block = rs.block ?: continue
            val decls = block.getDeclarations().toList()
            if (decls.isEmpty()) continue
            val display = decls.firstOrNull { it.propertyName?.trim().equals("display", true) } ?: continue
            val dv = display.value?.text?.trim()?.lowercase()
            val ctx = when (dv) {
                "flex", "inline-flex" -> buildFlex(rs, decls)
                "grid", "inline-grid" -> buildGrid(rs, decls)
                else -> null
            }
            if (ctx != null) result.add(ctx)
        }
        return result
    }

    private fun buildFlex(rs: CssRuleset, decls: List<CssDeclaration>): LayoutContext {
        val ctx = HashMap<String, String>()
        for (d in decls) {
            val p = d.propertyName?.trim()?.lowercase() ?: continue
            if (p in FLEX_PROPS) ctx[p] = d.value?.text?.trim().orEmpty()
        }
        val display = decls.first { it.propertyName?.trim().equals("display", true) }
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

    private fun buildGrid(rs: CssRuleset, decls: List<CssDeclaration>): LayoutContext {
        val ctx = HashMap<String, String>()
        for (d in decls) {
            val p = d.propertyName?.trim()?.lowercase() ?: continue
            if (p in GRID_PROPS) ctx[p] = d.value?.text?.trim().orEmpty()
        }
        val display = decls.first { it.propertyName?.trim().equals("display", true) }
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

    private val FLEX_PROPS = setOf(
        "justify-content", "align-items", "align-content", "flex-direction", "flex-wrap",
        "gap", "row-gap", "column-gap"
    )

    private val GRID_PROPS = setOf(
        "grid-template-columns", "grid-template-rows", "gap", "column-gap", "row-gap",
        "justify-items", "align-items", "justify-content", "align-content"
    )
}