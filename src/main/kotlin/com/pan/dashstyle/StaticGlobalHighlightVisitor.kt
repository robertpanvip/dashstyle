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
        // 注意：plugin.xml 里这个 visitor 目前已经被注释掉（2026-08-14 修复「普通 TS 高亮全没了」）。
        // 如果以后重新启用，suitableForFile 必须严格限制在「有真正内嵌 CssRuleset 注入」的文件后缀里，
        // 绝不可以包含 .tsx / .jsx / .html —— 否则 WS-2025.3 的 GeneralHighlightingPass 只要在
        // 该文件的 HighlightVisitor 链里被任何异常打断就会「整片高亮消失」。
        val name = file.name?.lowercase().orEmpty()
// 只对"可能内嵌 <style>"的宿主文件返回 true，避免在每个 JSX/TSX 大文件上按元素走 visit()。
        // Vue / Svelte / Astro / Html 内嵌 CSS 的 annotator 语言过滤可能命中不了，HighlightVisitor 才需要兜底；
        // 纯 .css/.scss/.less（有 annotator）以及 .tsx/.jsx（样式来自 .module.*，无内嵌 <style>）不在此列。
        return name.endsWith(".vue") || name.endsWith(".svelte") || name.endsWith(".astro") ||
                name.endsWith(".html") || name.endsWith(".htm") ||
                name.endsWith(".vue.ts") // 一些插件会生成虚拟文件名（只接受 vue 派生）
    }

    override fun visit(element: PsiElement) {
        val rs = runCatching { element as? CssRuleset }.getOrNull() ?: return
        val containingFile = runCatching { rs.containingFile }.getOrNull() ?: return
        val fileName = containingFile.name?.lowercase().orEmpty()
        // 只处理真正有内嵌 <style module> 的文件类型（Vue / Svelte / Astro）
        val isEmbedded = fileName.endsWith(".vue") || fileName.endsWith(".svelte") || fileName.endsWith(".astro") ||
                fileName.endsWith(".vue.ts")
        if (!isEmbedded) return

        val markKey = try {
            System.identityHashCode(rs) * 31 + (containingFile.virtualFile?.path?.hashCode() ?: 0)
        } catch (_: Throwable) {
            System.identityHashCode(rs)
        }
        val already = runCatching { alreadyDrawnThreadLocal.get() }.getOrNull() ?: return
        if (!already.add(markKey)) return

        val project = runCatching { rs.project }.getOrNull() ?: return
        runCatching { unusedInspection.inspectRulesetAndRegisterProblems(rs, project) }
        runCatching { duplicateInspection.inspectRulesetAndRegisterProblems(rs, project) }
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
        // 强防御：任何 Throwable 都不能 propagate，否则会把默认高亮 pass 干掉，
        // 造成「TS 文件所有高亮全没了」的严重症状。
        try {
            val set = runCatching { alreadyDrawnThreadLocal.get() }.getOrNull()
            set?.clear()
            try {
                action.run()
            } catch (t: Throwable) {
                // 外部传入的 action 内部出错（比如后续 visitor 炸了），我们也兜住抛回给上层之前
                // 先清 ThreadLocal，避免内存泄漏 + 下次 daemon pass 脏缓存
                runCatching { alreadyDrawnThreadLocal.remove() }
                throw t
            }
        } catch (_: Throwable) {
            // 自己内部的错误，绝对不能把异常带出 analyze()
        } finally {
            runCatching { alreadyDrawnThreadLocal.remove() }
        }
        // 返回 false：让默认实现继续走树遍历 / 后续 visitor 继续处理
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
            val globals = snap.globalClassNames
            val stripped = Util.stripGlobalBlocks(expanded)
            val classes = DashStyleHighlightAnnotator.CLASS_NAME_RE
                .findAll(stripped).mapNotNull { it.groupValues[2].trim().takeIf { s -> s.isNotEmpty() } }
                .distinct().filter { it !in globals }.toList()
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
