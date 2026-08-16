package com.pan.dashstyle

import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemDescriptor
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.psi.util.PsiTreeUtil

/**
 * CSS Module 未使用类检测：通过静态分析识别未被引用的 CSS Module 类并置灰显示。
 *
 * 检测逻辑：
 * 1. 查找所有引用该 CSS Module 的源文件（通过 ReferencesSearch）
 * 2. 扫描源文件中 `styles.xxx` / `styles['xxx']` / `className="xxx"` 等引用
 * 3. 将 CSS 文件中未出现在引用集合里的类标记为 INFORMATION 级别（配合 Annotator 置灰）
 *
 * 注册：plugin.xml 的 <localInspection> 与 <annotator> 各一份。
 * Annotator 负责实时置灰（DashStyleHighlightAnnotator），Inspection 负责批量检查与
 * 提供 Snapshot 数据给 Annotator 复用。
 */
class UnusedCssModuleClassInspection : LocalInspectionTool() {

    // ===================== Inspection 入口 =====================

    override fun checkFile(
        file: PsiFile,
        manager: InspectionManager,
        isOnTheFly: Boolean
    ): Array<ProblemDescriptor>? {
        val cssVf = file.virtualFile ?: return null
        if (!MODULE_EXTS.any { cssVf.name.endsWith(it, ignoreCase = true) }) return null
        val snap = getOrComputeFileSnapshot(file)
        if (snap.hasDynamic) return null

        val descriptors = ArrayList<ProblemDescriptor>()
        for (rs in PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)) {
            val clsNames = extractClassNamesFromRuleset(rs)
            if (clsNames.isEmpty()) continue
            val allUnused = clsNames.all { it !in snap.used }
            if (!allUnused) continue
            val selectorList = rs.selectorList ?: continue
            if (!selectorList.isPhysical) continue
            val slRange = selectorList.textRange
            if (slRange.length <= 0) continue
            descriptors.add(
                manager.createProblemDescriptor(
                    selectorList,
                    slRange,
                    "Unused CSS class${if (clsNames.size > 1) "es" else ""}: ${clsNames.joinToString(", ")}",
                    ProblemHighlightType.INFORMATION,
                    isOnTheFly,
                    null
                )
            )
        }
        return if (descriptors.isEmpty()) null else descriptors.toTypedArray()
    }

    // ===================== Snapshot 缓存 =====================

    data class Snapshot(
        val used: Set<String>,
        val hasDynamic: Boolean,
        val classesByRuleset: Map<String, List<String>>
    )

    fun snapshotFor(cssFile: PsiFile): Snapshot = getOrComputeFileSnapshot(cssFile)

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
        // className="a b-c" / :class="'a b-c'" / :class="`a b-c`"
        // 注：正则用 3 组 capture group（第 2/3/4 组分别对应 双引号 / 单引号 / 反引号）；
        //     下游读取 tokens 时从 groupValues.drop(1).first { it.isNotBlank() } 取值。
        private val STRING_CLASSNAME_RE = Regex(
            """(?:className|class)\s*[:=]\s*("([^"]+)"|'([^']+)'|`([^`]+)`)"""
        )

        fun getOrComputeFileSnapshot(cssFile: PsiFile): Snapshot {
            return CachedValuesManager.getManager(cssFile.project).getCachedValue(
                cssFile,
                CachedValueProvider { computeSnapshotWithDeps(cssFile) }
            )
        }

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
            deps += com.intellij.psi.util.PsiModificationTracker.getInstance(cssFile.project)
                .forLanguages { lang ->
                    val id = lang.id.lowercase()
                    id == "javascript" || id == "html" || id == "vue" ||
                        id.contains("typescript") || id.contains("jsx")
                }
            return CachedValueProvider.Result.create(snap, deps)
        }

        private fun findReferencingSourceFiles(cssFile: PsiFile): List<Pair<PsiFile, VirtualFile>> {
            val scope = GlobalSearchScope.projectScope(cssFile.project)
            val refs = runCatching {
                ReferencesSearch.search(cssFile, scope).findAll()
            }.getOrNull() ?: emptyList()
            return refs.mapNotNull { ref ->
                val element = ref.element
                val containingFile = element.containingFile ?: return@mapNotNull null
                val vf = containingFile.virtualFile ?: return@mapNotNull null
                containingFile to vf
            }.distinctBy { it.second }
        }

        private fun computeFileSnapshot(
            cssFile: PsiFile,
            references: List<Pair<PsiFile, VirtualFile>>
        ): Snapshot {
            val used = HashSet<String>()
            val classesByRuleset = HashMap<String, List<String>>()

            // 1. 从引用源文件中提取使用的类名
            for ((srcFile, _) in references) {
                val srcText = runCatching { srcFile.text }.getOrNull() ?: continue
                extractUsedFromSource(srcText, used)
            }

            // 2. 扫描 CSS 文件自身的 @extend / @apply
            val cssText = runCatching { cssFile.text }.getOrNull() ?: ""
            for (m in EXTEND_RE.findAll(cssText)) {
                val name = m.groupValues[1].trim()
                if (name.isNotEmpty()) used += name
            }
            for (m in APPLY_RE.findAll(cssText)) {
                for (name in m.groupValues[1].split(Regex("""\s+"""))) {
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) used += trimmed
                }
            }

            // 3. 扫描嵌套选择器引用（如 .a .b 中 .b 被 .a 引用）
            val classesInFile = MODULE_CLASS_RE.findAll(cssText).mapNotNull { m ->
                val name = m.groupValues[2]  // group 2 = class name, group 1 = prefix anchor
                name.trim().takeIf { it.isNotEmpty() }
            }.toSet()

            for (rs in runCatching { PsiTreeUtil.findChildrenOfType(cssFile, CssRuleset::class.java) }.getOrDefault(emptyList())) {
                val selText = runCatching { rs.selectorList?.text }.getOrNull().orEmpty()
                for (nested in NESTED_CLASS_RE.findAll(selText).map { it.groupValues[1] }) {
                    if (nested in classesInFile) used += nested
                }
                val clsNames = extractClassNamesFromRuleset(rs)
                if (clsNames.isNotEmpty()) classesByRuleset[System.identityHashCode(rs).toString()] = clsNames
            }

            return Snapshot(used, false, classesByRuleset)
        }

        private fun extractUsedFromSource(srcText: String, used: MutableSet<String>) {
            // styles.xxx / styles['xxx'] / styles["xxx"]
            val dotRef = Regex("""styles\.([_a-zA-Z][_a-zA-Z0-9]*)""")
            for (m in dotRef.findAll(srcText)) {
                val name = m.groupValues[1]
                used += name
                // camelCase → kebab-case
                val kebab = com.pan.dashstyle.Util.camelToKebab(name)
                if (kebab != name) used += kebab
            }
            // styles['xxx'] / styles["xxx"]
            val bracketRef = Regex("""styles\[['"]([^'"]+)['"]\]""")
            for (m in bracketRef.findAll(srcText)) {
                used += m.groupValues[1].trim()
            }
            // className="xxx" / class="xxx" / :class="..."
            for (m in STRING_CLASSNAME_RE.findAll(srcText)) {
                val tokens = m.groupValues.drop(1).first { it.isNotBlank() }
                for (tok in tokens.split(Regex("""\s+"""))) {
                    val trimmed = tok.trim()
                    if (trimmed.isNotEmpty()) used += trimmed
                }
            }
        }

        private fun extractClassNamesFromRuleset(rs: CssRuleset): List<String> {
            val raw = runCatching { rs.selectorList?.text }.getOrNull().orEmpty().trim()
            if (raw.isEmpty()) return emptyList()
            val normalized = runCatching { com.pan.dashstyle.Util.expandSelector(rs) }.getOrNull()
                ?: raw.replace('&', ' ').replace(Regex("""\s+"""), " ").trim()
            val cleaned = PSEUDO_PART_RE.replace(normalized, "")
            return MODULE_CLASS_RE.findAll(cleaned).mapNotNull { m ->
                val name = m.groupValues[2]  // group 2 = class name, group 1 = prefix anchor
                name.trim().takeIf { it.isNotEmpty() }
            }.distinct().toList()
        }
    }
}