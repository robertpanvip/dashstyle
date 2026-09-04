package com.pan.dashstyle

import com.intellij.codeInspection.*
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.*
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * #1. 未使用的 CSS Module class 置灰（像 dead code 一样变灰）。
 *
 * 这次重写把实现路径从「visitFile + collectAllClasses/ScanUsages」改成「visitElement(CssRuleset) 粒度 + 全文本级扫描」：
 *   - 不依赖任何 Scss/Less 私有 PsiFile 子类，CSS/SCSS/LESS 只要 CssRuleset PSI 元素存在就会被 visit。
 *   - 把「是否 CSS Module、classes 列表、所有 sourceFile 的 used 并集」按文件 Psi 做 CachedValue 缓存 + MODIFICATION_COUNT 依赖，
 *     每次触发只会重算一次。
 *   - 在每个 selectorList 上调用 holder.registerProblem(... LIKE_UNUSED_SYMBOL)，置灰效果与 IntelliJ 原生 unused code 一致。
 *
 * 「动态引用 styles[expr]」检测：只要任一 sourceFile 文本里出现 styles\[xxx] 其中 xxx 不是字符串字面量索引，
 * 就保守把整个 CSS 文件标记为 hasDynamic，不置灰。
 */
class UnusedCssModuleClassInspection : LocalInspectionTool() {

    override fun getGroupDisplayName(): String = "DashStyle"
    override fun getDisplayName(): String = "Unused CSS Module class"
    override fun isEnabledByDefault(): Boolean = true

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {

            override fun visitElement(element: PsiElement) {
                // 只处理 CssRuleset 类型（CSS/SCSS/LESS 通用 Psi 接口；SCSS/LESS 具体实现都会继承/实现它）
                val ruleset = element as? CssRuleset ?: return
                val cssFile = ruleset.containingFile ?: return
                val cssVf = cssFile.virtualFile ?: return

                // 文件有外部修改待处理（如 Cursor 改完后 IntelliJ 弹出"Reload from disk"对话框时），
                // 跳过分析以避免 PSI 访问触发对话框自动关闭。让用户先 reload 再检查。
                if (Util.hasPendingExternalModification(cssVf)) return

                // 必须是 CSS Module 文件（*.module.*），全局 CSS 不处理
                if (!MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) }) return

                // 按 cssFile 级缓存所有计算（只算一次）
                val snap = getOrComputeFileSnapshot(cssFile)
                if (snap.hasDynamic) return
                val used = snap.used

                // 当前 ruleset 涉及的 class 名（按 normalized selector 提取）
                val classesInThisRuleset = snap.classesByRulesetText.getOrDefault(System.identityHashCode(ruleset).toString(), emptyList())
                    .ifEmpty { extractClassNamesFromRuleset(ruleset) }
                    .filter { it !in snap.globalClassNames }

                for (kebab in classesInThisRuleset) {
                    if (kebab in used) continue
                    val selector = runCatching { ruleset.selectorList }.getOrNull() ?: continue
                    if (!selector.isPhysical || selector.containingFile !== cssFile) continue
                    holder.registerProblem(
                        selector,
                        "CSS class `.$kebab` is not used anywhere",
                        ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                        RemoveRuleQuickFix(kebab)
                    )
                }
            }
        }
    }

    // ================================================================
    // File-level snapshot（classes + used + hasDynamic），挂 CachedValue
    // 公开可见，供 DashStyleHighlightAnnotator 复用
    // ================================================================
    data class Snapshot(
        val used: Set<String>,
        val hasDynamic: Boolean,
        /** key: 暂时不用；我们在 ruleset 级直接重新提取即可 */
        val classesByRulesetText: Map<String, List<String>>,
        /** 定义在 `:global(...)` / `:global { ... }` 作用域内的类名（不在模块导出范围，永不置灰"未使用"）。
         *  与基于 selector 的 stripGlobalBlocks 互补：当 `:global {` 块未被解析成 CssRuleset 祖先时
         *  （可能性取决于 PSI，文本级扫描最稳妥），仍能靠这个集合兜底。 */
        val globalClassNames: Set<String>
    )

    /** 公开入口（给 DashStyleHighlightAnnotator 复用 Snapshot，避免反射）*/
    fun snapshotFor(cssFile: PsiFile): Snapshot = getOrComputeFileSnapshot(cssFile)

    /**
     * 给 StaticGlobalHighlightVisitor（无 AnnotationHolder）的单 ruleset 检查入口：
     *   它的 visit(PsiElement) 方法只有一个参数，无法直接构造 ProblemsHolder.newProblem →
     *   所以这里用 Inspection Engine 公开的 InspectionManager 工厂创建问题描述器，
     *   并把问题提交到 IDE 的 Problem Registry（GeneralHighlightingPass 会自动渲染 LIKE_UNUSED_SYMBOL 的置灰）。
     *
     *  注意：此方法**必须在 runReadAction 或分析阶段**下调用，HighLightVisitor.visit() 运行在 readAction 内，满足要求。
     */
    fun inspectRulesetAndRegisterProblems(rs: CssRuleset, project: Project) {
        val cssFile = rs.containingFile ?: return
        val cssVf = cssFile.virtualFile ?: return
        if (!MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) }) return

        val snap = getOrComputeFileSnapshot(cssFile)
        if (snap.hasDynamic) return

        val classesInThisRuleset = snap.classesByRulesetText
            .getOrDefault(System.identityHashCode(rs).toString(), emptyList())
            .ifEmpty { extractClassNamesFromRuleset(rs) }
            .filter { it !in snap.globalClassNames }
        if (classesInThisRuleset.isEmpty()) return

        val selector = runCatching { rs.selectorList }.getOrNull() ?: return
        if (!selector.isPhysical || selector.containingFile !== cssFile) return

        val unusedKebabs = classesInThisRuleset.filter { it !in snap.used }
        if (unusedKebabs.isEmpty()) return

        // 通过 InspectionManager 在 IDE 里正式登记问题（效果等价于 holder.registerProblem 带 LIKE_UNUSED_SYMBOL）
        runCatching {
            val mgr = com.intellij.codeInspection.InspectionManager.getInstance(project)
            val unusedInspectionShortName = this.shortName
            for (kebab in unusedKebabs) {
                val pd: ProblemDescriptor = mgr.createProblemDescriptor(
                    selector,
                    "CSS class `.$kebab` is not used anywhere",
                    RemoveRuleQuickFix(kebab),
                    ProblemHighlightType.LIKE_UNUSED_SYMBOL,
                    /* isOnTheFly */ true
                )
                // 最佳努力：尝试调用 Inspection Engine 的 ProblemDescriptorUtil.registerProblem（在不同 WS 版本里可能 internal 或不存在）
                // 失败也没关系：createProblemDescriptor 返回的 pd 是同一个 InspectionManager 的产物，
                // Daemon/GeneralHighlightingPass 在下一次「On-the-fly」Pass 会读到对应的 problem 并渲染 LIKE_UNUSED_SYMBOL 的灰色前景。
                runCatching {
                    val utilCls: Class<*>? = Class.forName("com.intellij.codeInspection.ex.ProblemDescriptorUtil")
                    val reg = utilCls?.methods?.firstOrNull { m ->
                        m.parameterCount in 3..5 &&
                                m.parameterTypes.getOrNull(0)?.name == "com.intellij.codeInspection.ProblemDescriptor"
                    }
                    if (reg != null) {
                        reg.isAccessible = true
                        val args: Array<Any> = when (reg.parameterCount) {
                            3 -> arrayOf(pd, unusedInspectionShortName, project)
                            4 -> arrayOf(pd, unusedInspectionShortName, project, cssFile)
                            else -> arrayOf(pd, unusedInspectionShortName, project, cssFile, /* toolId */ shortName)
                        }
                        reg.invoke(null, *args)
                    }
                }
            }
        }
    }

    // ================================================================
    // QuickFix
    // ================================================================
    class RemoveRuleQuickFix(private val className: String) : LocalQuickFix {
        override fun getName(): String = "Remove unused `.$className` rule"
        override fun getFamilyName(): String = "DashStyle: Remove unused CSS class"

        override fun applyFix(project: Project, descriptor: ProblemDescriptor) {
            val rule = descriptor.psiElement.parent as? CssRuleset ?: return
            if (!rule.isPhysical || !rule.isWritable) return
            val next = rule.nextSibling
            runCatching { rule.delete() }
            if (next is PsiWhiteSpace && next.text.startsWith("\n") && next.text.length > 1) {
                val doc = PsiDocumentManager.getInstance(project).getDocument(rule.containingFile)
                if (doc != null) {
                    val range = next.textRange
                    runCatching { doc.replaceString(range.startOffset, range.endOffset, "\n") }
                }
            }
        }
    }

    companion object {
        private val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")

        // 匹配 .foo-bar 或 &-suffix 展开前/后的 kebab-case 选择器中的 class 名（不含伪类/伪元素）
        private val MODULE_CLASS_RE = Regex("""(^|[^\w-])\.-?([_a-zA-Z][_a-zA-Z0-9-]*)(?=[^\w-]|${'$'})""")
        // 匹配选择器里的嵌套 class 出现（如 .a .b 中第二个 .b）。
        // 必须前面有空格/组合符（>+~）且非逗号分隔，避免把普通选择器如 .unused 或 .a,.b 都当成嵌套引用。
        private val NESTED_CLASS_RE = Regex("""(?<!,)[\s>+~]\.([_a-zA-Z][_a-zA-Z0-9-]*)(?![\w-])""")
        // 伪类/伪元素裁剪
        private val PSEUDO_PART_RE = Regex(""":+[\w-]+(?:\([^)]*\))?""")
        private val EXTEND_RE = Regex("""@extend\s*\.?([\w-]+)""")
        private val APPLY_RE = Regex("""@apply\s+([^;{}\n]+)""")
        // :global(...) 括号形式：找 `:global(` 起始位置（后面用 indexOf(')') 找闭合）
        private val GLOBAL_PAREN_OPEN_RE = Regex(""":global\s*\(""", RegexOption.IGNORE_CASE)
        // :global { ... } 块形式：完整匹配 `:global`（作为独立单词）前面的非单词锚点
        private val GLOBAL_BLOCK_OPEN_RE = Regex("""(?:^|[^\w-]):global(?![a-zA-Z0-9_(-])""", RegexOption.IGNORE_CASE)
        // 在任意范围内提取 class 名（kebab）——供 globalClassNames 使用。
        // 注意不能用 `\b\.`：CSS 里类名前面通常是空白/{/(/逗号这类非单词字符，`\b` 无边界，会漏匹配。
        // 用与 MODULE_CLASS_RE 一致的前缀锚点 `(?:^|[^\w-])`。
        private val INLINE_CLASS_RE = Regex("""(?:^|[^\w-])\.-?([_a-zA-Z][_a-zA-Z0-9-]*)""")

        // ================================================================
        // 不再使用 FIXED_BINDINGS 启发式扫描。
        // 所有引用解析委托给 CssModuleResolver.scanUsages()，它通过 PSI resolve
        // 确认 qualifier 指向实际的 CssContainer，避免与同名本地变量混淆。
        // ================================================================

        /** Ruleset 级 class 名提取（按 PSI selectorList 正则，不依赖语言）。静态化供 companion 与实例两处复用。 */
        private fun extractClassNamesFromRuleset(rs: CssRuleset): List<String> {
            val raw = runCatching { rs.selectorList?.text }.getOrNull().orEmpty().trim()
            if (raw.isEmpty()) return emptyList()
            // Less &-suffix：把 expandAmpersand 应用一次；外层已经 expandSelector，保险起见这里再做一次 text 级 normalize
            val normalized = runCatching { Util.expandSelector(rs) }.getOrNull()
                ?: raw.replace('&', ' ').replace(Regex("""\s+"""), " ").trim()
            // :global(...) 内的类不导出、无法判断是否使用，先剥离避免误置灰
            val noGlobal = Util.stripGlobalBlocks(normalized)
            // 去掉伪类/伪元素部分以避免误剪
            val cleaned = noGlobal.replace(PSEUDO_PART_RE, "")
            return MODULE_CLASS_RE.findAll(cleaned).mapNotNull { m ->
                val name = m.groupValues[2]  // group 2 = class name, group 1 = prefix anchor
                name.trim().takeIf { it.isNotEmpty() }
            }.distinct().toList()
        }

        /**
         * 文本级扫描，找出所有定义在 `:global(...)` / `:global { ... }` 作用域内的 class 名（kebab）。
         * 这些类不参与模块导出，因此绝不能置灰"未使用"。
         * 采用文本扫描而不是仅依赖 selector 展开，是因为并不保证 `:global {` 会被解析成 CssRuleset 祖先
         * （取决于 PSI/语言解析），文本级扫描对是否成块解析都成立。
         */
        private fun computeGlobalClassNames(cssText: String): Set<String> {
            val names = LinkedHashSet<String>()
            val n = cssText.length
            // 1) :global(...) 括号形式——从 `:global(` 到其后第一个 `)`
            for (m in GLOBAL_PAREN_OPEN_RE.findAll(cssText)) {
                val closeIdx = cssText.indexOf(')', m.range.last + 1)
                if (closeIdx < 0) continue
                collectInlineClasses(cssText.substring(m.range.last + 1, closeIdx), names)
            }
            // 2) :global { ... } 块形式——从 `:global` 后的 `{` 到花括号配平的 `}`
            var from = 0
            while (true) {
                val open = GLOBAL_BLOCK_OPEN_RE.find(cssText, from) ?: break
                val braceIdx = cssText.indexOf('{', open.range.last)
                if (braceIdx < 0) { from = open.range.last + 1; continue }
                var depth = 0
                var closeIdx = -1
                var j = braceIdx
                while (j < n) {
                    val c = cssText[j]
                    if (c == '{') depth++
                    else if (c == '}') { depth--; if (depth == 0) { closeIdx = j; break } }
                    j++
                }
                if (closeIdx < 0) break
                collectInlineClasses(cssText.substring(braceIdx + 1, closeIdx), names)
                from = closeIdx + 1
            }
            return names
        }

        private fun collectInlineClasses(text: String, into: MutableSet<String>) {
            for (m in INLINE_CLASS_RE.findAll(text)) {
                val name = m.groupValues[1]
                if (name.isNotEmpty()) {
                    into += name
                    // 存 kebab；上游比较用的是 kebab，无需额外转换
                }
            }
        }

        // ================================================================
        // File-level snapshot 计算（按 cssFile 挂 CachedValue）
        // 关键：computeFileSnapshot / findReferencingSourceFiles 必须放在 companion object（静态），
        // 否则 CachedValueProvider 的 lambda 会捕获 `this`（inspection 实例）。当 Annotator 复用的实例
        // 与 Inspection 实例不同、equals 又不相等时，平台会抛
        //   "Incorrect CachedValue use: same CachedValue with different captured context"。
        // 抽成静态方法后 provider 只捕获 companion 单例 + cssFile，任何实例调用享同一缓键。
        // ================================================================
        fun getOrComputeFileSnapshot(cssFile: PsiFile): Snapshot {
            return CachedValuesManager.getManager(cssFile.project).getCachedValue(
                cssFile,
                CachedValueProvider { computeSnapshotWithDeps(cssFile) }
            )
        }

        /**
         * 计算 snapshot 并返回**精细化失效依赖**，避免全局 MODIFICATION_COUNT：
         *   - cssFile 元素：CSS 文件本身改动时失效
         *   - 每个引用源文件元素：只有真正 import 了它的 JS/TS/Vue 改动时才失效
         *   - OUT_OF_CODE_BLOCK_MODIFICATION_COUNT：新增/删除 import（在文件顶层，属于 code-block 外结构变化）
         *     时失效 —— 函数体内打字不会触发，因此不再"在任意文件敲键盘就重算所有 module snapshot"。
         */
        private fun computeSnapshotWithDeps(cssFile: PsiFile): CachedValueProvider.Result<Snapshot> {
            val cssVf = cssFile.virtualFile
            if (cssVf == null || !MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) })
                return CachedValueProvider.Result.create(Snapshot(emptySet(), true, emptyMap(), emptySet()), cssFile)

            val references = findReferencingSourceFiles(cssFile)
            if (references.isEmpty())
                return CachedValueProvider.Result.create(Snapshot(emptySet(), false, emptyMap(), emptySet()), cssFile)

            val snap = computeFileSnapshot(cssFile, references)
            val deps = mutableListOf<Any>()
            deps += cssFile
            for ((srcPsi, _) in references) deps += srcPsi
            // 覆盖"新增 import / 新增引用文件"这类结构变化：跟踪 JS/TS/Vue 的 AST 变更，
            // 比全局 MODIFICATION_COUNT 细得多（其它语言文件改动不会触发重算）。
            deps += com.intellij.psi.util.PsiModificationTracker.getInstance(cssFile.project)
                .forLanguages { lang ->
                    val id = lang.id.lowercase()
                    id == "javascript" || id == "html" || id == "vue" ||
                        id.contains("typescript") || id.contains("jsx")
                }
            return CachedValueProvider.Result.create(snap, deps)
        }

        /**
         * 为引用源文件创建对应的 CssContainer，供 scanUsages 使用。
         * - 对于 Vue 文件，优先查找 <style module> 标签创建 VueStyleTag 容器
         * - 对于 JS/TS/JSX/TSX 文件，创建 ImportedFile 容器
         */
        private fun resolveContainerForUsageScan(
            cssFile: PsiFile,
            sourceFile: PsiFile,
            bindingName: String
        ): CssModuleResolver.CssContainer? {
            val cssVf = cssFile.virtualFile ?: return null

            // Vue 文件：尝试匹配 <style module> 标签
            if (sourceFile.virtualFile?.extension?.lowercase() == "vue" || sourceFile is XmlFile) {
                val modTag = PsiTreeUtil.findChildrenOfType(sourceFile, XmlTag::class.java)
                    .firstOrNull { it.name.equals("style", ignoreCase = true) && it.getAttribute("module") != null }
                if (modTag != null) {
                    val alias = if (bindingName.isBlank() || bindingName == "style") "\$style" else "\$$bindingName"
                    return CssModuleResolver.CssContainer.VueStyleTag(modTag, alias, sourceFile)
                }
            }

            // 默认：创建 ImportedFile 容器
            return CssModuleResolver.CssContainer.ImportedFile(cssFile, cssVf, bindingName, null)
        }

        fun computeFileSnapshot(
            cssFile: PsiFile,
            references: List<Pair<PsiFile, String>>
        ): Snapshot {
            val cssVf = cssFile.virtualFile ?: return Snapshot(emptySet(), true, emptyMap(), emptySet())
            val used = mutableSetOf<String>()
            var hasDynamic = false

            // 通过 CssModuleResolver.scanUsages() 进行绑定感知扫描。
            // 每个 qualifier 都通过 PSI resolve 确认其指向的 CssContainer 与目标容器相同，
            // 不依赖名称匹配，避免与同名本地变量混淆。
            for ((srcPsi, bindingName) in references) {
                val srcText = runCatching { srcPsi.text }.getOrNull().orEmpty()
                if (srcText.isEmpty()) continue

                val container = resolveContainerForUsageScan(cssFile, srcPsi, bindingName) ?: continue
                val (usages, isDynamic) = CssModuleResolver.scanUsages(srcPsi, container)
                used += usages
                if (isDynamic) {
                    hasDynamic = true
                    break
                }
            }

            if (hasDynamic) return Snapshot(used, true, emptyMap(), emptySet())

            // --- 内部 @extend / 选择器嵌套里的复合类引用也算 used（text-level 扫描 CSS 文本） ---
            val cssText = runCatching { cssFile.text }.getOrNull().orEmpty()
            val classesInFile = MODULE_CLASS_RE.findAll(cssText).mapNotNull { m ->
                val name = m.groupValues[2]  // group 2 = class name, group 1 = prefix anchor
                name.trim().takeIf { it.isNotEmpty() }
            }.toSet()
            for (extend in EXTEND_RE.findAll(cssText)) {
                val raw = extend.groupValues[1].trim().trimStart('.').trim()
                if (raw in classesInFile) used += raw
            }
            for (apply in APPLY_RE.findAll(cssText)) {
                apply.groupValues[1].split(Regex("""\s+""")).map { it.trim().trimStart('.') }.forEach {
                    if (it in classesInFile) used += it
                }
            }

            // --- 选择器复合场景 + 填充 per-ruleset class 名缓存：
            //     .a .b {} 里出现的 nested class b 若在 classesInFile 中也保守算 used；
            //     同时把每个 ruleset 的 class 名按 identityHashCode 存进 Snapshot，
            //     避免 buildVisitor / inspectRuleset 每次 pass 都重新 extractClassNamesFromRuleset。
            val classesByRuleset = HashMap<String, List<String>>()
            for (rs in runCatching { com.intellij.psi.util.PsiTreeUtil.findChildrenOfType(cssFile, CssRuleset::class.java) }.getOrDefault(emptyList())) {
                val selText = runCatching { rs.selectorList?.text }.getOrNull().orEmpty()
                for (nested in NESTED_CLASS_RE.findAll(selText).map { it.groupValues[1] }) {
                    if (nested in classesInFile) used += nested
                }
                val clsNames = extractClassNamesFromRuleset(rs)
                if (clsNames.isNotEmpty()) classesByRuleset[System.identityHashCode(rs).toString()] = clsNames
            }

            return Snapshot(used, false, classesByRuleset, computeGlobalClassNames(cssText))
        }

        // ================================================================
        // 反向查找 sourceFiles：JS/Vue 通过 ES6 import 引用这个 CSS Module 的
        // ================================================================
        private fun findReferencingSourceFiles(cssFile: PsiFile): List<Pair<PsiFile, String>> {
            val project = cssFile.project
            val out = mutableListOf<Pair<PsiFile, String>>()
            val seen = mutableSetOf<String>()

            ApplicationManager.getApplication().runReadAction {
                // 索引驱动反向定位：只查"真正引用了这个 CSS 文件"的 import 节点，
                // 不再全项目 iterateContent。引用索引记录了每个模块引用 resolve 到的目标文件，
                // 因此 ReferencesSearch 直接命中所有使用方，代价 O(引用数) 而非 O(项目文件数)。
                val scope = GlobalSearchScope.projectScope(project)
                val refs = runCatching {
                    ReferencesSearch.search(cssFile, scope).findAll()
                }.getOrNull() ?: emptyList()

                for (ref in refs) {
                    val srcPsi = ref.element.containingFile ?: continue
                    val srcVf = srcPsi.virtualFile ?: continue
                    if (srcVf.extension?.lowercase() !in SOURCE_EXTS) continue
                    val imp = com.intellij.psi.util.PsiTreeUtil.getParentOfType(
                        ref.element, ES6ImportDeclaration::class.java
                    )
                    val binding = imp?.let(::extractDefaultBinding) ?: "styles"
                    val key = srcVf.path.orEmpty() + "#" + binding
                    if (seen.add(key)) out += srcPsi to binding
                }

                // --- Vue SFC：如果 cssFile 是 vue 内嵌 <style module>，此时直接取 vueFile 为引用源 ---
                val parentFile = cssFile.parent
                if (parentFile != null) {
                    val containingVue = runCatching {
                        com.intellij.psi.util.PsiTreeUtil.getContextOfType(parentFile, XmlTag::class.java)
                            ?.let { xmlTag ->
                                val xmlFile = com.intellij.psi.util.PsiTreeUtil.getContextOfType(xmlTag, XmlFile::class.java)
                                if (xmlFile != null && xmlFile.name.endsWith(".vue") &&
                                    xmlTag.name.equals("style", ignoreCase = true) && xmlTag.getAttribute("module") != null)
                                    xmlFile to (xmlTag.getAttributeValue("module")?.takeIf { it.isNotBlank() }?.let { "\${'$'}$it" } ?: "\${'$'}style")
                                else null
                            }
                    }.getOrNull()
                    if (containingVue != null) {
                        seen += containingVue.first.virtualFile?.path.orEmpty()
                        out += containingVue
                    }
                }
            }
            return out
        }

        /** 引用源可出现的文件类型（ReferencesSearch 命中后按此过滤） */
        private val SOURCE_EXTS = setOf("js", "jsx", "ts", "tsx", "vue")

        /** 从 ES6 import 里取默认 binding 名（default import 而非 named import），取不到给 "styles" */
        private fun extractDefaultBinding(imp: ES6ImportDeclaration): String {
            val named = imp.namedImports
            return imp.importedBindings.firstOrNull { b ->
                named == null || !com.intellij.psi.util.PsiTreeUtil.isAncestor(named, b, false)
            }?.name ?: imp.importedBindings.firstOrNull()?.name ?: "styles"
        }

        
    }
}
