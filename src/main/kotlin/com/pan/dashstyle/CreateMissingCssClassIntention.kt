package com.pan.dashstyle

import com.intellij.codeInsight.intention.impl.BaseIntentionAction
import com.intellij.lang.css.CSSLanguage
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextLanguage
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * #6. 缺失 class 快速创建意向动作。
 *
 * 当光标停在 styles.fooBar / styles["foo-bar"] 上，而 resolve 不到对应 CSS class 时，
 * Alt+Enter 提供 "Create missing class in CSS Module"，
 * 在已 import 的目标文件或同目录同名 module 文件中追加 `.foo-bar { }`，并把光标放花括号内。
 */
@Suppress("UnstableApiUsage")
class CreateMissingCssClassIntention : BaseIntentionAction() {

    override fun getText(): String = "Create missing class in CSS Module"
    override fun getFamilyName(): String = "DashStyle: Create missing CSS class"

    override fun isAvailable(project: Project, editor: Editor, file: PsiFile): Boolean {
        val (_, requestedName, containerMaybe, _) = locateContext(editor, file) ?: return false
        if (requestedName.isBlank()) return false
        if (containerMaybe != null) return true  // 有现成容器，直接追加
        // 没有容器，但有候选 module 文件也可以
        return CssModuleResolver.findCandidateModuleFiles(file).isNotEmpty()
    }

    override fun invoke(project: Project, editor: Editor, file: PsiFile) {
        val (site, requestedName, existingContainer, _) = locateContext(editor, file) ?: return
        val kebab = if (requestedName.contains("-")) requestedName else Util.camelToKebab(requestedName)

        // 1) 已有直接关联的 CSS Module 容器 → 直接 append
        val container = existingContainer ?: run {
            // 2) 没有容器 → 尝试找同目录候选 module 文件
            val candidates = CssModuleResolver.findCandidateModuleFiles(file)
            val chosen = when (candidates.size) {
                0 -> {
                    Messages.showErrorDialog(project,
                        "No imported CSS Module and no *.module.{css,scss,less} file found next to ${file.name}.\nAdd an `import styles from './Foo.module.css'` to the file first.",
                        "Create missing class")
                    return
                }
                1 -> candidates[0]
                else -> {
                    val idx = Messages.showChooseDialog(
                        project,
                        "More than one candidate CSS Module file found in the folder. Choose a target:",
                        "Create missing class",
                        null,
                        candidates.map { it.first.name }.toTypedArray(),
                        candidates[0].first.name
                    )
                    if (idx < 0) return
                    candidates[idx]
                }
            }
            val psi = PsiManager.getInstance(project).findFile(chosen.first) ?: run {
                Messages.showErrorDialog(project, "Cannot open ${chosen.first.name} as PSI.", "Create missing class")
                return
            }
            CssModuleResolver.CssContainer.ImportedFile(psi, chosen.first, chosen.second, null)
        }

        val targetFile: VirtualFile? = when (container) {
            is CssModuleResolver.CssContainer.ImportedFile -> container.virtualFile
            is CssModuleResolver.CssContainer.VueStyleTag -> container.containingFile.virtualFile
            is CssModuleResolver.CssContainer.LocalObjectLiteral -> {
                Messages.showWarningDialog(project,
                    "`${container.variableName}` is a local JS object literal (not a CSS module file). DashStyle only supports appending to CSS/SCSS/LESS module files or Vue `<style module>`.",
                    "Create missing class")
                return
            }
        }
        if (targetFile == null) return

        WriteCommandAction.writeCommandAction(project).withName("Create missing CSS class").run<Nothing> {
            when (container) {
                is CssModuleResolver.CssContainer.ImportedFile -> {
                    appendRuleToFile(project, container.psiFile, kebab)
                }
                is CssModuleResolver.CssContainer.VueStyleTag -> {
                    appendRuleToStyleTag(project, container.styleTag, kebab)
                }
                else -> {}
            }
        }

        // 打开目标文件并把光标定位到新建规则的 {} 内
        ApplicationManager.getApplication().invokeLater {
            val psi = when (container) {
                is CssModuleResolver.CssContainer.ImportedFile -> container.psiFile
                is CssModuleResolver.CssContainer.VueStyleTag -> container.containingFile
                else -> return@invokeLater
            }
            val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@invokeLater
            val offset = run {
                val idx = doc.charsSequence.indexOf(".$kebab")
                if (idx < 0) 0 else {
                    val braceOpen = doc.charsSequence.indexOf('{', idx)
                    if (braceOpen >= 0) braceOpen + 1 else idx + kebab.length + 1
                }
            }
            val vf = psi.virtualFile ?: targetFile
            if (vf.isValid) {
                val fd = OpenFileDescriptor(project, vf, offset)
                FileEditorManager.getInstance(project).openTextEditor(fd, true)
            }
        }
    }

    // ----------------------------------------------------------------
    // helpers: locate context
    // ----------------------------------------------------------------
    private data class Context(
        val site: PsiElement,
        val name: String,
        val container: CssModuleResolver.CssContainer?,
        val alreadyHasClass: Boolean
    )

    private fun locateContext(editor: Editor, file: PsiFile): Context? {
        val offset = editor.caretModel.offset
        val at = file.findElementAt(offset) ?: return null

        // A. 字符串索引 styles["xxx"]
        val literal = at as? JSLiteralExpression
            ?: PsiTreeUtil.getParentOfType(at, JSLiteralExpression::class.java)
        if (literal != null) {
            val idx = PsiTreeUtil.getParentOfType(literal, JSIndexedPropertyAccessExpression::class.java)
            if (idx != null && idx.indexExpression == literal) {
                val name = literal.stringValue ?: return null
                val (container, _) = CssModuleResolver.resolveQualifier(idx.qualifier ?: return null, file) ?: (null to "")
                val exist = if (container == null) null else CssModuleResolver.resolveClassName(literal, name)
                if (exist != null) return null  // 已经有了就不提供快速创建
                return Context(literal, name, container, false)
            }
        }

        // B. member access styles.xxx
        val refExpr = at as? JSReferenceExpression
            ?: PsiTreeUtil.getParentOfType(at, JSReferenceExpression::class.java)
        if (refExpr != null && refExpr.qualifier != null && refExpr.referenceName != null) {
            val name = refExpr.referenceName!!
            val (container, _) = CssModuleResolver.resolveQualifier(refExpr.qualifier!!, file) ?: (null to "")
            val exist = if (container == null) null else CssModuleResolver.resolveClassName(refExpr, name)
            if (exist != null) return null
            return Context(refExpr, name, container, false)
        }

        return null
    }

    private fun appendRuleToFile(project: Project, psiFile: PsiFile, kebabName: String) {
        val factory = PsiFileFactory.getInstance(project)
        val rule = "\n.$kebabName {\n}\n"
        val tmp = try {
            factory.createFileFromText("__dashstyle_tmp__.css", CSSLanguage.INSTANCE, rule)
        } catch (_: Throwable) {
            factory.createFileFromText("__dashstyle_tmp__.css", PlainTextLanguage.INSTANCE, rule)
        }
        val last = psiFile.lastChild
        if (last != null) psiFile.addAfter(tmp.firstChild, last) else psiFile.add(tmp.firstChild)
    }

    private fun appendRuleToStyleTag(project: Project, styleTag: XmlTag, kebabName: String) {
        val factory = PsiFileFactory.getInstance(project)
        val rule = "\n.$kebabName {\n}\n"
        val tmp = try {
            factory.createFileFromText("__dashstyle_tmp__.css", CSSLanguage.INSTANCE, rule)
        } catch (_: Throwable) {
            factory.createFileFromText("__dashstyle_tmp__.css", PlainTextLanguage.INSTANCE, rule)
        }
        val ruleElem = tmp.firstChild ?: return
        styleTag.add(ruleElem)
    }
}
