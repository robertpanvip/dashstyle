package com.pan.dashstyle

import com.intellij.codeInsight.daemon.impl.HighlightVisitor
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.PossiblyDumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssRuleset

/**
 * 按 WS-2025.3 SDK 的真实签名（ReflectorSnifferTest 嗅探确认）实现的「全局 HighlightVisitor」。
 *
 * 真实签名（com.intellij.codeInsight.daemon.impl.HighlightVisitor）：
 *   - boolean suitableForFile(@NotNull PsiFile file)
 *   - void visit(@NotNull PsiElement element)         ← 无第二个 AnnotationHolder 参数！
 *   - @NotNull HighlightVisitor clone()
 *   - int order()                         // 0，IDE 会根据 plugin.xml order="first" 再排序
 *   - boolean supersedesDefaultHighlighter()          // false
 *   - boolean analyze(PsiFile, boolean, HighlightInfoHolder, Runnable)   // 返回 false 交给默认实现走树遍历
 *   - isDumbAware() (从 PossiblyDumbAware 继承)        // true（无 index 依赖）
 *
 * plugin.xml 静态注册：
 *   <highlightVisitor implementation="com.pan.dashstyle.StaticGlobalHighlightVisitor" order="first"/>
 *
 * 为什么单独拆类（不和 DashStyleHighlightAnnotator 放一起）：
 *   1. 接口签名冲突：Annotator.annotate(PsiElement, AnnotationHolder) 有 2 个参数，
 *      HighlightVisitor.visit(PsiElement) 只有 1 个参数。放同一个类会让 IDE 版本升级时
 *      方法签名一变就混淆（"我到底在 override 哪个接口的方法？"）。
 *   2. "只画 1 次" 幂等：Annotator 已经在 CSS/SCSS/LESS 三个语言的 annotator 扩展点画过一遍，
 *      HighlightVisitor 再画一遍会得到"两层叠加一样的灰"（不明显但有微小颜色叠加/性能浪费）。
 *      所以 HighlightVisitor 只负责兜底：Vue <style module> 里的内嵌 CSS 等 annotator 语言过滤覆盖不到的情况。
 *      用 alreadyDrawnThreadLocal 保证"同一 ruleset 单次 daemon pass 只画 1 次"。
 *   3. 独立类方便 plugin.xml 单独开关，不影响 Annotator。
 */
@Suppress("UnstableApiUsage", "DEPRECATION")
class StaticGlobalHighlightVisitor : HighlightVisitor, PossiblyDumbAware {

    private val unusedInspection by lazy(LazyThreadSafetyMode.PUBLICATION) { UnusedCssModuleClassInspection() }
    // 注意：DuplicateCssDeclarationsInspection.inspectRulesetAndRegisterProblems 是 companion @JvmStatic，
    // 但为了和 unusedInspection 保持一致（懒加载），这里还是用实例化 + 显式调用 companion 桥接方法。
    private val duplicateInspection by lazy(LazyThreadSafetyMode.PUBLICATION) { DuplicateCssDelegate() }

    // 小辅助类：把 Companion 的静态方法包装成实例方法，方便上面的 visit() 里一行调用
    private class DuplicateCssDelegate {
        fun inspectRulesetAndRegisterProblems(rs: CssRuleset, project: Project) {
            DuplicateCssDeclarationsInspection.inspectRulesetAndRegisterProblems(rs, project)
        }
    }

    // 同一 ruleset 在一次 daemon pass 里的"已经画过"标记，避免 Annotator 和 HighlightVisitor 双画叠加。
    // 使用规则：ruleset.identityHashCode() + holder 对象地址哈希（粗略够用，下一次 daemon pass AnnotationHolder 是新对象，天然失效）
    private val alreadyDrawnThreadLocal = ThreadLocal.withInitial<MutableSet<Int>> { HashSet(64) }

    // ========================= HighlightVisitor 真实签名（WS-2025.3） =========================
    override fun suitableForFile(file: PsiFile): Boolean {
        val name = file.name?.lowercase().orEmpty()
        // Vue / Svelte / Astro 等"带内嵌 <style>"的文件，annotator 扩展点基于语言过滤不会命中内嵌节点，
        // HighlightVisitor 才需要兜底；纯 .css/.scss/.less 已经有 annotator 在画了，直接返回 false 避免双画。
        return name.endsWith(".vue") || name.endsWith(".svelte") || name.endsWith(".astro") ||
                name.endsWith(".html") || name.endsWith(".htm") || name.endsWith(".tsx") ||
                name.endsWith(".jsx") || name.endsWith(".vue.ts") // 一些插件会生成虚拟文件名
    }

    override fun visit(element: PsiElement) {
        // 真实签名无 AnnotationHolder 第二个参数 → 需要 IDE 的 AnnotationHolder/Session 绑定到当前 AnnotationSession
        // 直接复用 DashStyleHighlightAnnotator 的逻辑，但通过"从 PsiElement.project 获取 annotation service"的思路：
        // 最简单且跨版本稳的办法：通过 com.intellij.lang.annotation.AnnotationSessionKt.getHolder(element) 或
        //   holderProvider 在 element 上找。但高版本 IDE 中 AnnotationHolder 已经被拆成 builder 模式，
        //   所以这里**保守做法**：只对真正内嵌在非 CSS 文件的 CssRuleset 才画，
        //   复用 UnusedCssModuleClassInspection 的 registerProblem(LIKE_UNUSED_SYMBOL) 路径，
        //   它不依赖 holder——inspection 的 QuickFix 会自然触发 problem highlight。
        val rs = element as? CssRuleset ?: return
        val containingFile = rs.containingFile ?: return
        val fileName = containingFile.name?.lowercase().orEmpty()
        val isEmbedded = fileName.endsWith(".vue") || fileName.endsWith(".svelte") || fileName.endsWith(".astro")
        if (!isEmbedded) return // 纯 CSS 文件走 annotator 就够了

        val markKey = System.identityHashCode(rs) * 31 + (containingFile.virtualFile?.path?.hashCode() ?: 0)
        val already = alreadyDrawnThreadLocal.get()
        if (!already.add(markKey)) return

        // 走 Inspection 的 registerProblem，问题展示由 IDE 的 GeneralHighlightingPass 统一渲染（不需要我们拿 holder）
        val project = rs.project
        runCatching {
            unusedInspection.inspectRulesetAndRegisterProblems(rs, project)
        }
        runCatching {
            duplicateInspection.inspectRulesetAndRegisterProblems(rs, project)
        }
    }

    override fun clone(): HighlightVisitor = StaticGlobalHighlightVisitor()

    override fun order(): Int = 0

    override fun supersedesDefaultHighlighter(): Boolean = false

    override fun analyze(
        file: PsiFile,
        updateWholeFile: Boolean,
        holder: com.intellij.codeInsight.daemon.impl.analysis.HighlightInfoHolder,
        action: Runnable
    ): Boolean {
        // 返回 false：使用默认实现（即 suitableForFile → 遍历整棵 PsiTree，对每个 PsiElement 调 visit(element)）
        // 如果我们要完全自定义访问顺序才会返回 true 并自己遍历
        try {
            // 每次 analyze 开始前清空"已画缓存"（因为 holder 是新的一次 daemon pass）
            alreadyDrawnThreadLocal.get().clear()
            action.run()
        } finally {
            alreadyDrawnThreadLocal.remove()
        }
        return false
    }

    override fun isDumbAware(): Boolean = true

    // ========================= 便捷方法（复用 annotator 逻辑，给内嵌场景兜底用） =========================
    // 实际上直接走 inspection.registerProblem 即可；但我们保留这段静态方法方便未来调试。
    companion object {
        @JvmStatic
        fun tryAnnotateViaHolder(rs: CssRuleset, holderProvider: (PsiElement) -> AnnotationHolder?) {
            val holder = holderProvider(rs) ?: return
            val file = rs.containingFile ?: return
            val vf = file.virtualFile?.name?.lowercase().orEmpty()
            // module 类型文件才需要 unused / duplicate（内嵌 .module.less 在 vue 里也需要）
            val moduleExt = listOf("module.css", "module.less", "module.scss", "module.sass")
            val isModule = moduleExt.any { vf.endsWith(it) } ||
                    file.name?.matches(Regex(""".*\.module\.(css|less|scss|sass)$""", RegexOption.IGNORE_CASE)) == true
            if (!isModule && !vf.endsWith(".vue")) return
            runCatching {
                annotateUnusedForGlobalVisitor(rs, holder)
            }
            runCatching {
                annotateDuplicateForGlobalVisitor(rs, holder)
            }
        }

        /** 静态版 annotateUnused（和 DashStyleHighlightAnnotator.annotateUnused 一样的语义，
         *  方便未来在能拿到 AnnotationHolder 的路径下调用）。 */
        private fun annotateUnusedForGlobalVisitor(rs: CssRuleset, holder: AnnotationHolder) {
            val cssFile = rs.containingFile ?: return
            val snap = runCatching { UnusedCssModuleClassInspection().snapshotFor(cssFile) }.getOrNull() ?: return
            if (snap.hasDynamic) return

            val expanded = runCatching { Util.expandSelector(rs) }.getOrNull().orEmpty()
            val classes = DashStyleHighlightAnnotator.CLASS_NAME_RE
                .findAll(expanded).mapNotNull { it.groupValues[2].trim().takeIf { s -> s.isNotEmpty() } }
                .distinct().toList()
            if (classes.isEmpty()) return
            if (classes.any { cls -> cls in snap.used }) return // 任一组合被用 → 不灰

            val selectorList = runCatching { rs.selectorList }.getOrNull() ?: return
            if (!selectorList.isPhysical) return
            val r = selectorList.textRange
            if (r.length <= 0) return
            val fileLen = runCatching { cssFile.textLength }.getOrNull() ?: Int.MAX_VALUE
            val start = r.startOffset.coerceAtLeast(0)
            val end = r.endOffset.coerceAtMost(start + 1).coerceAtMost(fileLen)
            if (end <= start) return
            runCatching {
                holder.newSilentAnnotation(HighlightSeverity.INFORMATION)
                    .range(TextRange(start, end))
                    .textAttributes(DashStyleHighlightAnnotator.UNUSED_CSS_CLASS_KEY)
                    .create()
            }
        }

        private fun annotateDuplicateForGlobalVisitor(rs: CssRuleset, holder: AnnotationHolder) {
            DuplicateCssDeclarationsInspection.attachDuplicateWave(rs, holder)
        }
    }
}
