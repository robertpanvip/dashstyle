package com.pan.dashstyle.action

import com.pan.dashstyle.DashStyleBundle.message
import com.pan.dashstyle.support.CssModuleFileResolver
import com.pan.dashstyle.support.CssSelectorUtil
import com.pan.dashstyle.support.NamingUtil

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.lang.javascript.psi.JSProperty
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
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
 *   3. 额外扫描 `:class` / `v-bind:class` 里的 JS 字符串字面量，并按上下文分类：
 *      - 数组元素 / 三元分支值 / 对象 value → 直接替换 `'foo'` → `$style.foo`
 *      - 对象 key（`{ 'foo': cond }`）→ 计算属性语法 `'foo'` → `[$style.foo]`
 *        （对象 key 不能是表达式，不加 `[...]` 会生成非法 JS）
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

        // 条件 B：存在 :class 里的 JS 字符串字面量（数组元素 / 三元值 / 对象 value）命中
        if (findDirectReplaceLiteralRanges(file, allClasses).isNotEmpty()) return true

        // 条件 C：存在 :class 对象字面量的字符串 key 命中（JSProperty.name 里嵌的 key 不是独立 PSI 节点）
        if (findObjectKeyReplacements(file, allClasses).isNotEmpty()) return true

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
        val staticAttrReplacements = collectStaticClassAttrs(file).mapNotNull { (attr, value) ->
            if (TEMPLATE_INTERPOLATION.containsMatchIn(value)) return@mapNotNull null
            val parts = value.split(CLASS_SPLIT).filter { it.isNotBlank() }
            val hasConverted = parts.any { it in allClasses }
            if (!hasConverted) return@mapNotNull null
            val exprs = parts.map { name ->
                if (name in allClasses) nameAccessExpr(name)
                else "'$name'"
            }
            val newExpr = if (exprs.size == 1) exprs.first()
                           else "[" + exprs.joinToString(", ") + "]"
            attr.textRange to ":class=\"$newExpr\""
        }

        // ---- :class / v-bind:class 里的 JS 字符串字面量 → 直接替换 ----
        // （数组元素 / 三元值 / 对象 value，这些场景字面量是独立 JSLiteralExpression PSI 节点）
        val dynamicValueReplacements = findDirectReplaceLiteralRanges(file, allClasses)

        // ---- :class 对象字面量的字符串 key → 计算属性语法 ----
        // （JSProperty 里字符串 key 不是独立 PSI 节点，用文本范围替换 key 部分）
        val objectKeyReplacements = findObjectKeyReplacements(file, allClasses)

        // ---- 合并所有替换，按 range 降序 ----
        val allReplacements = (staticAttrReplacements + dynamicValueReplacements + objectKeyReplacements)
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
     * 收集 `:class` / `v-bind:class` 里的 JS 字符串字面量中，**可以直接替换**（生成 `$style.xxx`）的那些。
     * 覆盖场景：数组元素、三元分支值、对象 value 值。
     * 返回 [(range, replacement), ...]。
     *
     * 对象 key 的字符串字面量**不在此处处理**——它们在 Vue 模板 PSI 里不是独立 JSLiteralExpression
     * 节点，而是直接嵌在 JSProperty.name 里。用 [findObjectKeyReplacements] 单独处理。
     */
    private fun findDirectReplaceLiteralRanges(file: PsiFile, allClasses: Set<String>): List<Pair<TextRange, String>> {
        val dynamicClassRanges = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
            .filter { DYNAMIC_CLASS_ATTR.matches(it.name) }
            .map { it.textRange }
        if (dynamicClassRanges.isEmpty()) return emptyList()

        val allLiterals = runCatching {
            PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java)
        }.getOrDefault(emptyList())

        val result = mutableListOf<Pair<TextRange, String>>()
        for (literal in allLiterals) {
            val r = literal.textRange
            if (dynamicClassRanges.none { it.contains(r) }) continue
            // 跳过那些实际上是 JSProperty key 的字面量——它们不是独立 PSI 节点，
            // 但在 .js/.ts 文件（非 Vue 模板）里可能以字面量形式出现，
            // 这里保守处理：如果 parent 是 JSProperty 且我们是第一个有意义的子节点，
            // 就跳过让 findObjectKeyReplacements 用 JSProperty 路径处理。
            if (isObjectKeyLiteralNode(literal)) continue

            val strVal = literal.stringValue ?: continue
            if (strVal in allClasses) {
                result += r to nameAccessExpr(strVal)
            }
        }
        return result
    }

    /**
     * 收集 `:class` / `v-bind:class` 里 **JS 对象字面量的字符串 key** 的替换范围。
     *
     * 关键发现：在 Vue 模板的 JS 绑定 PSI 里，`{ 'invalid-option': cond }` 的字符串 key
     * **不是独立的 JSLiteralExpression PSI 节点**——JetBrains JS PSI 直接把 key 名存为
     * JSProperty.name 属性（已自动去除引号）。JSProperty 的子节点只有 value，不含 key。
     * 因此必须通过 JSProperty.text 找到 key 字符串的文本位置再做替换。
     *
     * 替换策略：key 是表达式语境（object literal key position），不能直接写 `$style.xxx`
     * 会生成非法 JS `{ $style.xxx: cond }`，必须用计算属性语法 `{ [$style.xxx]: cond }`。
     *
     * 返回 [(keyTextRange, "[\$style.xxx]"), ...] —— 替换的是 key 本身（含引号），
     * 用计算属性包裹的 $style 访问表达式替代。
     */
    private fun findObjectKeyReplacements(file: PsiFile, allClasses: Set<String>): List<Pair<TextRange, String>> {
        val dynamicClassRanges = PsiTreeUtil.findChildrenOfType(file, XmlAttribute::class.java)
            .filter { DYNAMIC_CLASS_ATTR.matches(it.name) }
            .map { it.textRange }
        if (dynamicClassRanges.isEmpty()) return emptyList()

        val allProperties = runCatching {
            PsiTreeUtil.findChildrenOfType(file, JSProperty::class.java)
        }.getOrDefault(emptyList())

        val result = mutableListOf<Pair<TextRange, String>>()
        for (prop in allProperties) {
            val propRange = prop.textRange
            if (dynamicClassRanges.none { it.contains(propRange) }) continue

            // prop.text 形如：'invalid-option': option.invalid
            // 检查 key 是否是带引号的字符串（以 ' 或 " 开头）
            val propText = prop.text ?: continue
            val quote = propText.firstOrNull()
            if (quote != '\'' && quote != '"') continue

            // JSProperty.name 已经是去引号后的 key 值（JetBrains PSI 自动处理）
            val keyName = prop.name ?: continue
            if (keyName !in allClasses) continue

            // 找到 key 字符串（含引号）的精确范围：从 JSProperty 起点开始，
            // 匹配 `'xxx'` 或 `"xxx"` 模式直到闭合引号
            val quotedKeyMatch = Regex("""^['"][^'"]+['"]""").find(propText) ?: continue
            val quotedKeyLength = quotedKeyMatch.value.length

            val keyRange = TextRange(propRange.startOffset, propRange.startOffset + quotedKeyLength)
            val replacement = "[${nameAccessExpr(keyName)}]"
            result += keyRange to replacement
        }
        return result
    }

    /**
     * 判断 JSLiteralExpression 是否为 JSProperty 的 key 节点（兜底路径）。
     * 在 .js/.ts 文件（非 Vue 模板）里，JSProperty 的字符串 key 有时是独立的
     * JSLiteralExpression 子节点。此时 findDirectReplaceLiteralRanges 要跳过它，
     * 让 findObjectKeyReplacements 统一处理（避免生成非法 JS）。
     *
     * Vue 模板里 JSProperty 根本没有 key 子节点，此方法自然返回 false。
     */
    private fun isObjectKeyLiteralNode(literal: JSLiteralExpression): Boolean {
        val parent = literal.parent ?: return false
        if (parent !is JSProperty) return false
        // 跳过前置空白，找到 JSProperty 的第一个有意义子节点——就是 key
        var firstMeaningful: PsiElement? = parent.firstChild
        while (firstMeaningful != null && firstMeaningful.text?.isBlank() == true) {
            firstMeaningful = firstMeaningful.nextSibling
        }
        return firstMeaningful === literal
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
