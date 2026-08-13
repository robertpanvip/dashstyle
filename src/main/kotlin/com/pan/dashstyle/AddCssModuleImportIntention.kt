package com.pan.dashstyle

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
/**
 * #5. 自动补全 styles 导入。
 *
 * 光标停在 `styles.xxx` 或 `styles["xxx"]`，但 `styles` 本身没 resolve →
 * 扫描同目录所有 `*.module.(css|scss|less|sass)` 文件（优先同名文件匹配），
 * 在文件顶部/`<script setup>` 顶部注入一行 `import styles from './Foo.module.css'`。
 *
 * 对于 Vue SFC，import 会被注入到 <script setup> 顶部；没有 <script setup> 就创建一个 <script setup> 块。
 */
@Suppress("UnstableApiUsage")
class AddCssModuleImportIntention : BaseIntentionAction() {

    override fun getText(): String = "Import CSS Module for `styles`"
    override fun getFamilyName(): String = "DashStyle: Add CSS Module import"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val (qualifierName, _, _) = locateUnresolvedQualifier(editor, file) ?: return false
        val candidates = CssModuleResolver.findCandidateModuleFiles(file)
        return candidates.isNotEmpty() && qualifierName.isNotBlank()
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val (qualifierName, targetInsertionScope, isVue) = locateUnresolvedQualifier(editor, file) ?: return
        val candidates = CssModuleResolver.findCandidateModuleFiles(file)
        val chosen = when (candidates.size) {
            0 -> return
            1 -> candidates[0]
            else -> {
                val picked = Messages.showChooseDialog(
                    project,
                    "Choose which CSS Module file to import:",
                    "Import CSS Module",
                    null,
                    candidates.map { it.first.name }.toTypedArray(),
                    candidates[0].first.name
                )
                if (picked < 0) return
                candidates[picked]
            }
        }

        val moduleRelativePath = "./" + chosen.first.name
        val bindingName = chosen.second.takeIf { it.isNotBlank() } ?: qualifierName.takeIf { it.isNotBlank() } ?: "styles"
        val importText = "import $bindingName from '$moduleRelativePath'\n"

        WriteCommandAction.writeCommandAction(project).withName("Import CSS Module").run<Nothing> {
            if (isVue && file is XmlFile) {
                injectImportIntoVue(file, importText, targetInsertionScope)
            } else {
                injectImportIntoJsTs(file, importText)
            }
        }
    }

    // ================================================================
    // locate unresolved qualifier (styles 本身无法 resolve)
    // ================================================================
    private data class Loc(val qualifierName: String, val scope: PsiElement?, val isVue: Boolean)

    private val QUALIFIER_WHITELIST = setOf("styles", "css", "classes", "styled", "style", "moduleStyles")

    private fun locateUnresolvedQualifier(editor: Editor, file: PsiFile): Loc? {
        val offset = editor.caretModel.offset
        val at = file.findElementAt(offset) ?: return null
        val isVue = file is XmlFile && file.name.endsWith(".vue")

        // styles["xxx"] → qualifier 是 styles
        val literal = PsiTreeUtil.getParentOfType(at, JSLiteralExpression::class.java)
        if (literal != null) {
            val idx = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java)
            if (idx != null && idx.indexExpression == literal) {
                val q = idx.qualifier ?: return null
                if (isUnresolved(q, file)) return Loc(q.text, q, isVue)
            }
        }

        // styles.xxx
        // 扩大范围：光标落在 referenceName (xxx) 所在的 JSReferenceExpression 也要能识别其 qualifier
        var ref = PsiTreeUtil.getParentOfType(at, JSReferenceExpression::class.java)
        // 若当前 ref 本身没有 qualifier（它自己就是 xxx，且在更深层里），向上再找一层父 JSReferenceExpression
        var guard = 0
        while (ref != null && ref.qualifier == null && guard < 3) {
            ref = PsiTreeUtil.getParentOfType(ref.parent, JSReferenceExpression::class.java)
            guard++
        }
        if (ref != null && ref.qualifier != null) {
            val q = ref.qualifier!!
            if (isUnresolved(q, file)) return Loc(q.text, q, isVue)
        }

        // 兜底：光标在 `styles` 这个标识符上（无任何成员访问包裹），但它本身是 unresolved
        if (at.node?.elementType?.toString()?.contains("identifier", ignoreCase = true) == true ||
            at is JSReferenceExpression && at.qualifier == null) {
            val text = at.text
            if (text in QUALIFIER_WHITELIST || text == "styles") {
                val anchor: PsiElement = (at as? JSReferenceExpression) ?: at
                if (isUnresolved(anchor, file)) return Loc(text, anchor, isVue)
            }
        }

        return null
    }

    private fun isUnresolved(qualifierExpr: PsiElement, file: PsiFile): Boolean {
        val qName = qualifierExpr.text.takeIf { it.isNotBlank() } ?: return false
        if (qName !in QUALIFIER_WHITELIST) return false

        if (file is XmlFile && file.name.endsWith(".vue")) {
            if (qName.startsWith("$")) return false  // Vue $style 是内置，不在我们处理
        }

        // ① 如果是 JSReferenceExpression → resolve()，但要排除 PsiPackage / PsiDirectory 等假阳性
        val ref = (qualifierExpr as? JSReferenceExpression)?.reference
        if (ref != null) {
            val resolved = runCatching { ref.resolve() }.getOrNull()
            if (resolved != null) {
                val rc = resolved.javaClass.name
                // PsiPackage / PsiDirectory / Fake / light method 等 → 不是真正的 styles 绑定，仍算 unresolved
                // 注意：PsiPackage 类在某些 IDE 版本 FQN 可能不同，这里用字符串匹配兜底
                val isPackage = (resolved::class.java.name).contains("PsiPackage", ignoreCase = true) ||
                    rc.contains("PsiPackage", ignoreCase = true)
                if (!isPackage &&
                    !rc.contains("PsiDirectory", ignoreCase = true) &&
                    !rc.contains("Fake", ignoreCase = true) &&
                    !rc.contains("LightMethod", ignoreCase = true)) {
                    return false
                }
            }
        }

        // ② 同文件里是否真的找不到同名变量 / import → 算 unresolved
        val sameNameVar = PsiTreeUtil.findChildrenOfType(file, JSVariable::class.java)
            .any { it.name == qName }
        if (sameNameVar) return false
        val sameNameImport = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
            .flatMap { it.importedBindings.toList() }
            .any { it.name == qName }
        if (sameNameImport) return false
        // Vue script 内再找一次（有时 JS 树挂在 script tag 下，外层 XmlFile 扫描也会覆盖，这里冗余双保险）
        if (file is XmlFile && file.name.endsWith(".vue")) {
            val scriptTag = Util.findScriptTag(file)
            if (scriptTag != null) {
                val inScriptVar = PsiTreeUtil.findChildrenOfType(scriptTag, JSVariable::class.java)
                    .any { it.name == qName }
                if (inScriptVar) return false
                val inScriptImp = PsiTreeUtil.findChildrenOfType(scriptTag, ES6ImportDeclaration::class.java)
                    .flatMap { it.importedBindings.toList() }
                    .any { it.name == qName }
                if (inScriptImp) return false
            }
        }
        return true
    }

    // ================================================================
    // inject import
    // ================================================================
    private fun injectImportIntoJsTs(file: PsiFile, importText: String) {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        val factory = PsiFileFactory.getInstance(file.project)
        val dummy = factory.createFileFromText(
            "__dashstyle_import__.js",
            com.intellij.lang.Language.findInstance(com.intellij.lang.javascript.JavascriptLanguage::class.java),
            importText + "export {}"
        )
        val newImport = PsiTreeUtil.findChildrenOfType(dummy, ES6ImportDeclaration::class.java).firstOrNull()
            ?: run {
                // fallback 原始文本插入
                val doc = PsiDocumentManager.getInstance(file.project).getDocument(file) ?: return
                doc.insertString(0, importText)
                return
            }

        if (imports.isEmpty()) {
            // 直接插在文件最顶部（在 shebang / package 注释后面先不特殊处理，最简单：顶部插 PSI）
            file.addBefore(newImport, file.firstChild)
        } else {
            file.addAfter(newImport, imports.last())
        }
    }

    private fun injectImportIntoVue(vueFile: XmlFile, importText: String, scope: PsiElement?) {
        val factory = PsiFileFactory.getInstance(vueFile.project)
        val scriptTag = Util.findScriptTag(vueFile)
        if (scriptTag == null) {
            // 没 <script>，造一个 <script setup>，插到 <template> 后面或文件最末尾
            val snippet = "\n<script setup>\n$importText</script>\n"
            val templateTag = Util.findTagInFile(vueFile, "template")
            val tmp = factory.createFileFromText("__tmp__.vue", com.intellij.lang.xml.XMLLanguage.INSTANCE, "<template/>$snippet")
            val newScript = PsiTreeUtil.findChildrenOfType(tmp, com.intellij.psi.xml.XmlTag::class.java)
                .firstOrNull { it.name.equals("script", true) }
            if (newScript != null) {
                if (templateTag != null) vueFile.rootTag?.addAfter(newScript, templateTag)
                else vueFile.add(newScript)
            }
            return
        }

        // 找到 <script> 的 JS 根块，把 import 注入其顶部
        val existingImports = PsiTreeUtil.findChildrenOfType(scriptTag, ES6ImportDeclaration::class.java)
        val dummyFile = factory.createFileFromText(
            "__dashstyle_import__.js",
            com.intellij.lang.Language.findInstance(com.intellij.lang.javascript.JavascriptLanguage::class.java),
            importText + "export {}"
        )
        val newImport = PsiTreeUtil.findChildrenOfType(dummyFile, ES6ImportDeclaration::class.java).firstOrNull()
        if (newImport != null) {
            if (existingImports.isEmpty()) {
                // 在 script 的内容块最开头插入（用文本方式更安全，因为 JSEmbeddedContent 结构复杂）
                val container = findScriptSetupEmbedded(scriptTag) ?: scriptTag
                val firstCodeChild = container.firstChild
                if (firstCodeChild != null) container.addBefore(newImport, firstCodeChild)
                else container.add(newImport)
            } else {
                existingImports.last().parent.addAfter(newImport, existingImports.last())
            }
        } else {
            // fallback：document-based 插入
            val doc = PsiDocumentManager.getInstance(vueFile.project).getDocument(vueFile) ?: return
            val anchor = existingImports.lastOrNull()?.textRange?.endOffset
                ?: (scriptTag.value?.textRange?.startOffset ?: (scope?.textRange?.startOffset ?: 0))
            doc.insertString(anchor, "\n$importText")
        }
    }

    private fun findScriptSetupEmbedded(scriptTag: XmlTag): PsiElement? {
        return PsiTreeUtil.collectElements(scriptTag) { ele ->
            ele.parent.javaClass.simpleName.contains("VueScript", ignoreCase = true) ||
                ele.javaClass.simpleName.contains("EmbeddedContent", ignoreCase = true)
        }.maxByOrNull { it.textRange.length }
    }
}
