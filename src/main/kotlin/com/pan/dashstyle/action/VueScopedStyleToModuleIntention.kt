package com.pan.dashstyle.action

import com.pan.dashstyle.DashStyleBundle.message
import com.pan.dashstyle.support.CssModuleFileResolver
import com.pan.dashstyle.support.CssSelectorUtil
import com.pan.dashstyle.support.NamingUtil

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * Vue SFC 一键全量迁移 `<style scoped>` → `<style module>`：
 *   1. 扫描 `<style scoped>` 里所有 CSS 类名（用于判断哪些模板 class 该转、哪些保持原样）
 *   2. 收集模板里所有静态 `class="..."` 属性（跳过已有 `:class` 动态绑定、模板插值 `{{ }}`）
 *   3. 额外扫描 `:class` / `v-bind:class` 里的 JS 字符串字面量（只替换命中 CSS 定义的那个，
 *      剩余的 computedVar / ternary / template literal 保持原样）
 *   4. 从后往前统一替换所有 text range（不管是静态 attr 整体替换，还是动态绑定里的字面量内联替换）
 *   5. promote `<style scoped>` → `<style module>`（移除 scoped，自动注入 module）
 *
 * 全流程原子化：一个 WriteCommandAction 里完成。替换按 textRange 降序排列，
 * 保证先改后面的再改前面的，offset 不漂移。
 */
class VueScopedStyleToModuleIntention : IntentionAction {

    companion object {
        /** 类名空白分隔（class="foo bar" 里的多个值）。 */
        private val CLASS_SPLIT = Regex("""\s+""")
        /** 模板插值占位符（class="foo-{{ bar }}-baz" 无法安全处理，跳过）。 */
        private val TEMPLATE_INTERPOLATION = Regex("""\{\{.*?\}\}""")
        /** 识别 :class / v-bind:class 属性名。 */
        private val DYNAMIC_CLASS_ATTR = Regex("""^(?::class|v-bind:class)$""")
    }

    override fun getText(): String = message("intention.convert.vue.scoped.to.module.text")
    override fun getFamilyName(): String = message("intention.convert.vue.scoped.to.module.family")

    override fun startInWriteAction(): Boolean = false // 我们自己用 WriteCommandAction 原子化

    // ================================================================
    // isAvailable：精确判定当前场景是否可迁移
    // ================================================================
    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file !is XmlFile) return false
        if (file.virtualFile?.extension?.lowercase() != "vue") return false

        val styleTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) }
        val hasModule = styleTags.any { it.getAttribute("module") != null }
        val scopedTags = styleTags.filter { it.getAttribute("scoped") != null }
        if (hasModule || scopedTags.isEmpty()) return false

        val allClasses = collectCssClassNames(file)
        if (allClasses.isEmpty()) return false

        // 条件 A：存在静态 class="..." 且类名命中 CSS 定义集合
        if (collectStaticClassAttrs(file).any { entry ->
                entry.second.split(CLASS_SPLIT).any { it in allClasses }
            }) return true

        // 条件 B：存在 :class / v-bind:class 里的 JS 字符串字面量值命中 CSS 定义集合
        if (collectDynamicStringLiterals(file).any { literal ->
                literal.stringValue?.let { it in allClasses } == true
            }) return true

        return false
    }

    // ================================================================
    // invoke：主流程（原子化）
    // ================================================================
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is XmlFile) return

        val allClasses = collectCssClassNames(file)
        if (allClasses.isEmpty()) return

        // ---- 静态 class 属性 → 整体替换成 :class="..." ----
        val staticAttrReplacements = collectStaticClassAttrs(file).mapNotNull { (attr, value) ->
            if (TEMPLATE_INTERPOLATION.containsMatchIn(value)) return@mapNotNull null
            val parts = value.split(CLASS_SPLIT).filter { it.isNotBlank() }
            val converted = parts.filter { it in allClasses }
            if (converted.isEmpty()) return@mapNotNull null
            val accesses = converted.map { nameAccessExpr(it) }
            val newExpr = if (accesses.size == 1) accesses.first()
                           else "[" + accesses.joinToString(", ") + "]"
            attr.textRange to ":class=\"$newExpr\""
        }

        // ---- :class / v-bind:class 里的 JS 字符串字面量 → 只替换命中的那个 ----
        val dynamicLiteralReplacements = collectDynamicStringLiterals(file)
            .mapNotNull { literal ->
                val strVal = literal.stringValue ?: return@mapNotNull null
                if (strVal !in allClasses) return@mapNotNull null
                val newExpr = nameAccessExpr(strVal)
                literal.textRange to newExpr
            }

        // ---- 合并所有替换，按 range 降序 ----
        val allReplacements = (staticAttrReplacements + dynamicLiteralReplacements)
            .sortedByDescending { (range, _) -> range.startOffset }

        if (allReplacements.isEmpty()) return

        WriteCommandAction.writeCommandAction(project, file)
            .withName(message("command.convert.vue.scoped.to.module"))
            .run<Nothing> {
                // Step 1: promote <style scoped> → <style module>（先改 style 再改 template，
                // 保证 $style 绑定在模板引用它之前就被 Vue SFC 注入）
                CssModuleFileResolver.promoteVueStyleModule(project, file)

                // Step 2: 从后往前替换所有 range（range 降序 → offset 不漂移）
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run
                for ((range, newText) in allReplacements) {
                    runCatching {
                        document.replaceString(range.startOffset, range.endOffset, newText)
                    }
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
    }

    // ================================================================
    // 工具方法
    // ================================================================

    /** 从 `<style scoped>` 提取所有 CSS 类名。 */
    private fun collectCssClassNames(file: PsiFile): Set<String> {
        val result = LinkedHashSet<String>()
        val styleTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) && it.getAttribute("scoped") != null }
        for (style in styleTags) {
            val rulesets = runCatching {
                PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)
            }.getOrDefault(emptyList())
            for (rs in rulesets) {
                val expanded = runCatching { CssSelectorUtil.expandSelector(rs) }.getOrDefault("")
                if (expanded.isBlank()) continue
                for (name in CssSelectorUtil.extractClassNames(expanded)) {
                    result += name
                }
            }
        }
        return result
    }

    /**
     * 收集模板里的静态 `class="..."` 属性（跳过 `:class` / `v-bind:class` 动态绑定）。
     * 返回 List<Pair<XmlAttribute, trimmedValue>>。
     */
    private fun collectStaticClassAttrs(file: PsiFile): List<Pair<XmlAttribute, String>> {
        val attrs = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
        val result = mutableListOf<Pair<XmlAttribute, String>>()
        for (attr in attrs) {
            if (attr.name != "class") continue
            val value = attr.value ?: continue
            if (value.startsWith(":") || value.startsWith("v-bind")) continue
            if (value.isBlank()) continue
            result += attr to value.trim()
        }
        return result
    }

    /**
     * 收集模板里 `:class` / `v-bind:class` 属性值内部的所有 JS 字符串字面量
     * （单引号 / 双引号）。模板反引号 literal 不会被 JSLiteralExpression 命中，
     * 所以天然安全跳过。
     */
    private fun collectDynamicStringLiterals(file: PsiFile): List<JSLiteralExpression> {
        // 先找所有 :class / v-bind:class 属性的 textRange，作为后续过滤的边界
        val dynamicClassRanges = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
            .filter { DYNAMIC_CLASS_ATTR.matches(it.name) }
            .map { it.textRange }

        if (dynamicClassRanges.isEmpty()) return emptyList()

        // 再扫所有 JSLiteralExpression，看哪个落在这些 range 里
        val allLiterals = runCatching {
            PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
        }.getOrDefault(emptyList())

        return allLiterals.filter { literal ->
            val r = literal.textRange
            dynamicClassRanges.any { it.contains(r) }
        }
    }

    /** 类名 → `$style.xxx`（kebab 自动走 bracket 写法）。 */
    private fun nameAccessExpr(name: String): String {
        val kebab = NamingUtil.camelToKebab(name)
        return if (kebab.contains("-")) "\$style['$kebab']" else "\$style.$name"
    }
}
