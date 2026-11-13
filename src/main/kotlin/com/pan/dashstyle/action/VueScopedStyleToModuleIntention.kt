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
 *   1. 扫描所有 `<style scoped>` 子树里的 CssRuleset，提取类名定义集合（**仅限 scoped style**，
 *      不扫文件里其他不带 scoped 的普通 <style>，避免把全局样式的类误算进来）
 *   2. 收集模板里所有静态 `class="..."` 属性
 *   3. 额外扫描 `:class` / `v-bind:class` 里的 JS 字符串字面量
 *   4. 静态属性里：命中 CSS 定义的类 → $style.xxx；**不命中的类保持原样**（Tailwind / 组件自带 class 等），
 *      拼 JS 数组：`class="foo flex"` → `:class="[$style.foo, 'flex']"`
 *   5. 动态字面量里：只替换命中 CSS 定义的那个，剩余保持
 *   6. promote `<style scoped>` → `<style module>`
 *
 * 替换安全：按 textRange 降序从后往前做，offset 不漂移；WriteCommandAction 原子化。
 */
class VueScopedStyleToModuleIntention : IntentionAction {

    companion object {
        /** 类名空白分隔（class="foo bar" 里的多个值）。 */
        private val CLASS_SPLIT = Regex("""\s+""")
        /** 模板插值占位符（class="foo-{{ bar }}-baz" 无法安全处理，跳过整个属性）。 */
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

        val allClasses = collectCssClassNames(file, scopedTags)
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

        val styleTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) && it.getAttribute("scoped") != null }
        val allClasses = collectCssClassNames(file, styleTags)
        if (allClasses.isEmpty()) return

        // ---- 静态 class 属性 → 整体替换成 :class="..." ----
        // 关键：能转的类 → $style.xxx，**不能转的类保持为 JS 字符串**（Tailwind / 全局 class）
        //        这样 `class="mybtn flex gap-2"` 不会把 flex/gap-2 丢成空。
        val staticAttrReplacements = collectStaticClassAttrs(file).mapNotNull { (attr, value) ->
            if (TEMPLATE_INTERPOLATION.containsMatchIn(value)) return@mapNotNull null
            val parts = value.split(CLASS_SPLIT).filter { it.isNotBlank() }
            // 至少有一个 part 命中 CSS 定义——否则整属性跳过
            val hasConverted = parts.any { it in allClasses }
            if (!hasConverted) return@mapNotNull null
            // 每个 part：命中的 → $style.xxx，未命中的 → JS 字符串字面量（保持原样）
            val exprs = parts.map { name ->
                if (name in allClasses) nameAccessExpr(name)
                else "'$name'"
            }
            val newExpr = if (exprs.size == 1) exprs.first()
                           else "[" + exprs.joinToString(", ") + "]"
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
                // Step 1: promote <style scoped> → <style module>
                CssModuleFileResolver.promoteVueStyleModule(project, file)

                // Step 2: 从后往前替换所有 range（降序 → offset 不漂移）
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

    /**
     * 从指定的 scoped style tags **子树内部** 提取 CSS 类名。
     * 关键：scope 参数限定了 CssRuleset 只在 scoped style tag 子树里找——
     * 不能从 file 全局扫，否则会把不带 scoped 的普通 <style>（全局样式）里的类也误算进来，
     * 导致模板引用这些全局类的静态 class 被误转成 :class="$style.xxx"，
     * 而 promote 只升级 scoped style，全局 style 保持原样，运行时 $style.xxx 找不到定义。
     */
    private fun collectCssClassNames(file: PsiFile, scopedStyles: List<XmlTag>): Set<String> {
        val result = LinkedHashSet<String>()
        for (style in scopedStyles) {
            // 从 style tag 子树里找——不是 file 全局
            val rulesets = runCatching {
                PsiTreeUtil.findChildrenOfType(style, CssRuleset::class.java)
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
     * 收集模板里的静态 `class="..."` 属性。
     * 只匹配 attr.name == "class"（:class / v-bind:class 的 attr.name 是 ":class" / "v-bind:class"，
     * 在 name 判定这一行就被过滤掉了，不需要额外检查 value.startsWith(":")——L167 那个检查是 dead code）。
     */
    private fun collectStaticClassAttrs(file: PsiFile): List<Pair<XmlAttribute, String>> {
        val attrs = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
        val result = mutableListOf<Pair<XmlAttribute, String>>()
        for (attr in attrs) {
            if (attr.name != "class") continue
            val value = attr.value ?: continue
            if (value.isBlank()) continue
            result += attr to value.trim()
        }
        return result
    }

    /**
     * 收集模板里 `:class` / `v-bind:class` 属性值内部的所有 JS 字符串字面量。
     * 反引号模板 literal 不会被 JSLiteralExpression 命中（它是 JsTemplateLiteral 另一种类型），
     * 所以天然安全跳过。
     */
    private fun collectDynamicStringLiterals(file: PsiFile): List<JSLiteralExpression> {
        val dynamicClassRanges = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
            .filter { DYNAMIC_CLASS_ATTR.matches(it.name) }
            .map { it.textRange }

        if (dynamicClassRanges.isEmpty()) return emptyList()

        val allLiterals = runCatching {
            PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
        }.getOrDefault(emptyList())

        return allLiterals.filter { literal ->
            val r = literal.textRange
            dynamicClassRanges.any { it.contains(r) }
        }
    }

    /**
     * 类名 → $style.xxx 访问表达式。
     * kebab-case（含 "-"）走 bracket 写法，内层固定单引号——避开外层 Vue 属性值的双引号:
     *   :class="$style.nodeRight"           → kebab: $style['node-right']
     *   :class="$style['node-right']"       → 内层单引号，外层双引号，不冲突
     */
    private fun nameAccessExpr(name: String): String {
        val kebab = NamingUtil.camelToKebab(name)
        return if (kebab.contains("-")) "\$style['$kebab']" else "\$style.$name"
    }
}
