package com.pan.dashstyle.action

import com.pan.dashstyle.DashStyleBundle.message
import com.pan.dashstyle.support.CssModuleFileResolver
import com.pan.dashstyle.support.CssSelectorUtil
import com.pan.dashstyle.support.NamingUtil

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import com.intellij.psi.PsiDocumentManager

/**
 * Vue SFC 一键全量迁移 `<style scoped>` → `<style module>`：
 *   1. 扫描 `<style scoped>` 里所有 CSS 类名（用于判断哪些模板 class 该转、哪些保持原样）
 *   2. 收集模板里所有静态 `class="..."` 属性（跳过 `:class` 动态绑定、模板插值 `{{ }}`）
 *   3. 只转换那些"在 CSS 文件里确实定义过"的 class（跳过 Tailwind / 组件自带 class 等）
 *   4. 从后往前逐个替换：
 *        class="foo"          →  :class="$style.foo"
 *        class="foo bar"      →  :class="[$style.foo, $style.bar]"
 *        class="foo tailwind" →  :class="[$style.foo]"   （tailwind 不在 CSS 定义集合里，跳过）
 *   5. promote `<style scoped>` → `<style module>`（移除 scoped 属性，自动注入 module 属性）
 *
 * 全流程原子化：一个 WriteCommandAction 里完成。若替换过程中任何一步 PSI 异常，
 * 整个事务回滚，用户看到的代码不会半改。
 */
class VueScopedStyleToModuleIntention : IntentionAction {

    companion object {
        /** 类名空白分隔（class="foo bar" 里的多个值）。 */
        private val CLASS_SPLIT = Regex("""\s+""")
        /** 模板插值占位符（class="foo-{{ bar }}-baz" 无法安全处理，跳过）。 */
        private val TEMPLATE_INTERPOLATION = Regex("""\{\{.*?\}\}""")
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

        // 1. 必须有 <style scoped>（且没有 <style module>）
        val styleTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) }
        val hasModule = styleTags.any { it.getAttribute("module") != null }
        val scopedTags = styleTags.filter { it.getAttribute("scoped") != null }
        if (hasModule || scopedTags.isEmpty()) return false

        // 2. CSS 里必须有至少一个类名定义
        val allClasses = collectCssClassNames(file)
        if (allClasses.isEmpty()) return false

        // 3. 模板里必须有至少一个静态 class="..." 属性（且类名出现在 CSS 里）
        return collectStaticClassAttrs(file).any { entry ->
            entry.second.split(CLASS_SPLIT).any { it in allClasses }
        }
    }

    // ================================================================
    // invoke：主流程（原子化）
    // ================================================================
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file !is XmlFile) return

        val allClasses = collectCssClassNames(file)
        val attrEntries = collectStaticClassAttrs(file)

        if (allClasses.isEmpty() || attrEntries.isEmpty()) return

        // 先算好所有替换内容（纯 PSI 读取，不写），避免 WriteCommandAction 里读 PSI 被并发改写
        // 替换数据结构：(XmlAttribute → 新的属性文本)
        // 空字符串表示该属性应该被删除（所有类都不在 CSS 定义集合里）
        val attrReplacements = attrEntries.mapNotNull { (attr, value) ->
            if (TEMPLATE_INTERPOLATION.containsMatchIn(value)) return@mapNotNull null
            val parts = value.split(CLASS_SPLIT).filter { it.isNotBlank() }
            val converted = parts.filter { it in allClasses }
            if (converted.isEmpty()) return@mapNotNull null // 全是 Tailwind 等外部类，跳过
            // kebab 类名走 $style["foo-bar"]
            val accesses = converted.map { nameAccessExpr(it) }
            val newExpr = if (accesses.size == 1) accesses.first()
                           else "[" + accesses.joinToString(", ") + "]"
            attr to ":class=\"$newExpr\""
        }

        if (attrReplacements.isEmpty()) return

        WriteCommandAction.writeCommandAction(project, file)
            .withName(message("command.convert.vue.scoped.to.module"))
            .run<Nothing> {
                // ---- Step 1: promote <style scoped> → <style module> ----
                //     必须先改 style，再替换模板 class。否则 class 替换后 style 还在 scoped，
                //     会报"$style 未定义"语义错误（临时态），且 promote 依赖 XmlTag PSI，
                //     先改 template 可能让 document offset 漂移干扰 promote。
                CssModuleFileResolver.promoteVueStyleModule(project, file)

                // ---- Step 2: 从后往前替换 class 属性 ----
                //     先按 XmlAttribute.textRange 降序排列，保证 offset 不漂移。
                val sorted = attrReplacements.sortedByDescending { (attr, _) -> attr.textRange.startOffset }
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run

                for ((attr, newText) in sorted) {
                    runCatching {
                        val range = attr.textRange
                        document.replaceString(range.startOffset, range.endOffset, newText)
                    }
                }

                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
    }

    // ================================================================
    // 工具方法（MVP 专属，不需要抽象到共享层）
    // ================================================================

    /** 从 `<style scoped>` 所有 `<style>` tag 里提取 CSS 类名集合。 */
    private fun collectCssClassNames(file: PsiFile): Set<String> {
        val result = LinkedHashSet<String>()
        val styleTags = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) && it.getAttribute("scoped") != null }
        for (style in styleTags) {
            // 直接从文本里用 CssRuleset PSI 展开 selector → 类名
            val rulesets = runCatching {
                PsiTreeUtil.findChildrenOfType(file, CssRuleset::class.java)
            }.getOrDefault(emptyList())
            for (rs in rulesets) {
                // 先展开 selector（:global() / :local() / ::v-deep 等伪类剥离），再提取 .foo 类选择器
                val expanded = runCatching { CssSelectorUtil.expandSelector(rs) }.getOrDefault("")
                if (expanded.isBlank()) continue
                for (name in CssSelectorUtil.extractClassNames(expanded)) {
                    result += name
                }
            }
        }
        return result
    }

    /** 收集模板里所有"静态 class 属性"（name=class, 不以 : 开头的 value）。 */
    private fun collectStaticClassAttrs(file: PsiFile): List<Pair<XmlAttribute, String>> {
        val attrs = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
        val result = mutableListOf<Pair<XmlAttribute, String>>()
        for (attr in attrs) {
            if (attr.name != "class") continue
            val value = attr.value ?: continue
            if (value.startsWith(":")) continue        // 跳过 :class 动态绑定
            if (value.startsWith("v-bind")) continue  // 跳过 v-bind:class
            if (value.isBlank()) continue
            result += attr to value.trim()
        }
        return result
    }

    /** 类名 → `$style.xxx` 访问表达式（kebab 类名自动走 bracket 写法）。 */
    private fun nameAccessExpr(name: String): String {
        val kebab = NamingUtil.camelToKebab(name)
        return if (kebab.contains("-")) "\$style[\"$kebab\"]" else "\$style.$name"
    }
}
