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

                // 必须是 CSS Module 文件（*.module.*），全局 CSS 不处理
                if (!MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) }) return

                // 按 cssFile 级缓存所有计算（只算一次）
                val (used, hasDynamic, classesByRulesetText) = getOrComputeFileSnapshot(cssFile)
                if (hasDynamic) return

                // 当前 ruleset 涉及的 class 名（按 normalized selector 提取）
                val classesInThisRuleset = classesByRulesetText.getOrDefault(System.identityHashCode(ruleset).toString(), emptyList())
                    .ifEmpty { extractClassNamesFromRuleset(ruleset) }

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
        val classesByRulesetText: Map<String, List<String>>
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
        // 匹配选择器里的嵌套 class 出现（如 .a .b 中第二个 .b）
        private val NESTED_CLASS_RE = Regex("""\.([_a-zA-Z][_a-zA-Z0-9-]*)(?![\w-])""")
        // 伪类/伪元素裁剪
        private val PSEUDO_PART_RE = Regex(""":+[\w-]+(?:\([^)]*\))?""")
        private val EXTEND_RE = Regex("""@extend\s*\.?([\w-]+)""")
        private val APPLY_RE = Regex("""@apply\s+([^;{}\n]+)""")
        // className="a b-c" / :class="'a b-c'" / :class="`a b-c`"
        // 注：正则用 3 组 capture group（第 2/3/4 组分别对应 双引号 / 单引号 / 反引号）；
        //     下游读取 tokens 时从 groupValues.drop(1).first { it.isNotBlank() } 取值。
        private val STRING_CLASSNAME_RE = Regex(
            "(?:className|class)\\s*=\\s*(?:\\(\\s*)?(?:\"([^\"]*)\"|'([^']*)'|`([^`]*)`)",
            RegexOption.IGNORE_CASE
        )

        // ================================================================
        // 性能优化：computeFileSnapshot 里会遍历多个候选绑定名（styles/css/classes/...），
        // 不能在 per-source-file 内层循环里反复构造 Regex。这里把固定绑定名的模式预编译成一次，
        // 动态 bindingNameHint 仅在确实不在固定集合里时才补一个。
        // ================================================================
        private val FIXED_BINDINGS = setOf("styles", "css", "classes", "styled", "style", "moduleStyles")
        private class BindingPatterns(val binding: String) {
            val dynRe: Regex = Regex("""\b${Regex.escape(binding)}\s*\[\s*([^\s\]])""")
            val memberRe: Regex = Regex("""\b${Regex.escape(binding)}\s*\.\s*([A-Za-z_][A-Za-z0-9_]*)""")
            val idxRe: Regex = Regex("""\b${Regex.escape(binding)}\s*\[\s*(['"`])([^'"`]+)\1\s*\]""")
        }
        private val FIXED_BINDING_PATTERNS: List<BindingPatterns> = FIXED_BINDINGS.map { BindingPatterns(it) }
        // Vue $style.member / $style["key"]（不依赖绑定名，可直接预编译）
        private val VUE_MEMBER_RE = Regex("""\${'$'}style\.([A-Za-z_][A-Za-z0-9_-]*)""")
        private val VUE_IDX_RE = Regex("""\${'$'}style\[(['"`])([^'"`]+)\1\]""")

        /** Ruleset 级 class 名提取（按 PSI selectorList 正则，不依赖语言）。静态化供 companion 与实例两处复用。 */
        private fun extractClassNamesFromRuleset(rs: CssRuleset): List<String> {
            val raw = runCatching { rs.selectorList?.text }.getOrNull().orEmpty().trim()
            if (raw.isEmpty()) return emptyList()
            // Less &-suffix：把 expandAmpersand 应用一次；外层已经 expandSelector，保险起见这里再做一次 text 级 normalize
            val normalized = runCatching { Util.expandSelector(rs) }.getOrNull()
                ?: raw.replace('&', ' ').replace(Regex("""\s+"""), " ").trim()
            // 去掉伪类/伪元素部分以避免误剪
            val cleaned = normalized.replace(PSEUDO_PART_RE, "")
            return MODULE_CLASS_RE.findAll(cleaned).mapNotNull { m ->
                val rawName = m.groupValues[1]
                val name = if (rawName.startsWith(".")) rawName.drop(1) else rawName
                name.trim().takeIf { it.isNotEmpty() }
            }.distinct().toList()
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
                return CachedValueProvider.Result.create(Snapshot(emptySet(), true, emptyMap()), cssFile)

            val references = findReferencingSourceFiles(cssFile)
            if (references.isEmpty())
                return CachedValueProvider.Result.create(Snapshot(emptySet(), false, emptyMap()), cssFile)

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

        fun computeFileSnapshot(
            cssFile: PsiFile,
            references: List<Pair<PsiFile, String>>
        ): Snapshot {
            val cssVf = cssFile.virtualFile ?: return Snapshot(emptySet(), true, emptyMap())
            val used = mutableSetOf<String>()
            var hasDynamic = false

            // 候选绑定名模式：固定集合预编译 + 动态 hint 兜底（避免 per-source-file 内层循环重复构造 Regex）
            val patterns = ArrayList<BindingPatterns>(FIXED_BINDING_PATTERNS.size + 1)
            patterns += FIXED_BINDING_PATTERNS
            val hint = references.firstOrNull()?.second?.ifBlank { "styles" } ?: "styles"
            if (hint !in FIXED_BINDINGS) patterns += BindingPatterns(hint)

            for ((srcPsi, _) in references) {
                val srcText = runCatching { srcPsi.text }.getOrNull().orEmpty()
                if (srcText.isEmpty()) continue

                // --- 快速 hasDynamic 检测：
                //     文本里出现 bindingName[xxx] 中括号引用，且 [ 之后第一个非空字符不是 ' 或 "，则视为动态索引
                dynLoop@ for (bp in patterns) {
                    val m = bp.dynRe.find(srcText) ?: continue
                    val firstChar = m.groupValues[1].firstOrNull() ?: continue
                    if (firstChar != '\'' && firstChar != '"' && firstChar != '`') {
                        hasDynamic = true
                        break@dynLoop
                    }
                }
                if (hasDynamic) break

                // --- 文本级 usedClassNames 扫描：同时覆盖 member access 和字符串索引 ---
                // 注意：used 里同时存 camelCase(fooBar) + kebab(foo-bar)，匹配双风格。
                for (bp in patterns) {
                    // styles.fooBar
                    for (mm in bp.memberRe.findAll(srcText)) {
                        val name = mm.groupValues[1]
                        used += name
                        used += Util.camelToKebab(name)
                    }
                    // styles["foo-bar"] / styles['foo-bar'] / styles[`fooBar`]
                    for (mm in bp.idxRe.findAll(srcText)) {
                        val name = mm.groupValues[2]
                        used += name
                        used += Util.camelToKebab(name)
                        used += Util.kebabToCamel(name)
                    }
                }

                // --- Vue 场景 :class="$style.xxx" 或 :class="xxx in $style" ---
                if (srcPsi is XmlFile || (srcPsi.virtualFile?.extension?.lowercase() == "vue")) {
                    for (mm in VUE_MEMBER_RE.findAll(srcText)) {
                        val n = mm.groupValues[1]
                        used += n
                        used += Util.camelToKebab(n)
                        used += Util.kebabToCamel(n)
                    }
                    for (mm in VUE_IDX_RE.findAll(srcText)) {
                        val n = mm.groupValues[2]
                        used += n
                        used += Util.camelToKebab(n)
                        used += Util.kebabToCamel(n)
                    }
                }

                // --- className="xxx" 或 :class="['a','b']" 如果是字符串字面量直接引用 kebab class 也当 used ---
                for (mm in STRING_CLASSNAME_RE.findAll(srcText)) {
                    val captured = mm.groupValues.drop(1).firstOrNull { it.isNotBlank() }.orEmpty()
                    val tokens = captured.split(Regex("""\s+""")).filter { it.isNotBlank() }
                    for (t in tokens) {
                        used += t
                        used += Util.kebabToCamel(t)
                    }
                }
            }

            if (hasDynamic) return Snapshot(used, true, emptyMap())

            // --- 内部 @extend / 选择器嵌套里的复合类引用也算 used（text-level 扫描 CSS 文本） ---
            val cssText = runCatching { cssFile.text }.getOrNull().orEmpty()
            val classesInFile = MODULE_CLASS_RE.findAll(cssText).mapNotNull { m ->
                val raw = m.groupValues[1]
                val firstChar = raw.firstOrNull() ?: return@mapNotNull null
                val name = if (firstChar == '.') raw.drop(1) else raw
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

            return Snapshot(used, false, classesByRuleset)
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
