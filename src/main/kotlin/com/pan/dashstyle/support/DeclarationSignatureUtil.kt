package com.pan.dashstyle.support

import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssRuleset
import com.intellij.psi.util.PsiTreeUtil

/**
 * CSS 声明签名归一化，供重复声明检测（Inspection / Annotator）和重复块提取（Intention）共用。
 *
 * 统一逻辑：
 * - 属性名 trim + lowercase
 * - 值：hex3→hex6、空白压缩、去尾随逗号、转小写
 * - 排序后以 `|` 拼接（a{b:1;c:2} ≡ a{c:2;b:1}）
 */
object DeclarationSignatureUtil {

    private val RE_HEX3 = Regex("""#([0-9a-fA-F]{3})(?![0-9a-fA-F])""")
    private val RE_WS = Regex("""\s+""")

    /**
     * 归一化单个 CSS 值：hex3→hex6、空白压缩、去尾逗号、转小写。
     */
    fun normalizeValue(raw: String): String {
        var s = raw
        s = RE_HEX3.replace(s) { m ->
            val c = m.groupValues[1]
            "#${c[0]}${c[0]}${c[1]}${c[1]}${c[2]}${c[2]}"
        }
        s = s.replace(RE_WS, " ").trim()
        s = s.removeSuffix(",")
        return s.lowercase()
    }

    /**
     * 归一化单条声明为 `prop:value`。
     */
    fun normalizeDeclaration(prop: String, value: String): String {
        val p = prop.trim().lowercase()
        val v = normalizeValue(value)
        return "$p:$v"
    }

    /**
     * 从 ruleset 的直接 CssDeclaration 子节点计算签名。
     * 返回 `prop:value|prop:value...`（排序），空时返回 null。
     */
    fun computeSignature(ruleset: CssRuleset): String? {
        val block = ruleset.block ?: return null
        val decls = PsiTreeUtil.findChildrenOfType(block, CssDeclaration::class.java)
        if (decls.isEmpty()) return null
        val tokens = decls.mapNotNull { d ->
            val p = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
            val v = d.value?.text?.trim() ?: return@mapNotNull null
            normalizeDeclaration(p, v)
        }.sorted()
        return tokens.joinToString("|").takeIf { it.isNotBlank() }
    }

    /**
     * 从预收集的 CssDeclaration 列表计算签名（调用方已自行筛选直接子节点）。
     */
    fun computeSignatureFromDeclarations(decls: List<CssDeclaration>): String {
        val tokens = decls.mapNotNull { d ->
            val p = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
            val v = d.value?.text?.trim() ?: return@mapNotNull null
            normalizeDeclaration(p, v)
        }.sorted()
        return tokens.joinToString("|")
    }

    /**
     * 返回排序后的声明签名列表（供 Intention 按 List 粒度分组）。
     */
    fun computeSignatureList(ruleset: CssRuleset): List<String> {
        val block = ruleset.block ?: return emptyList()
        val decls = PsiTreeUtil.findChildrenOfType(block, CssDeclaration::class.java)
        return decls.mapNotNull { d ->
            val prop = d.propertyName?.trim()?.lowercase() ?: return@mapNotNull null
            val value = d.value?.text?.trim() ?: return@mapNotNull null
            normalizeDeclaration(prop, value)
        }.sorted()
    }
}
