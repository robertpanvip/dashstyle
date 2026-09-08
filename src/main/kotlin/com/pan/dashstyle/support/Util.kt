package com.pan.dashstyle.support

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSVariable
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/**
 * Vue SFC 和文件状态相关工具方法。
 * 命名转换 → NamingUtil，选择器 → CssSelectorUtil，颜色 → ColorUtil。
 */
class Util {
    companion object {

        fun findScriptTag(file: PsiFile): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { it.name.equals("script", ignoreCase = true) }
        }

        fun findModuleStyleTag(file: PsiFile): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { tag ->
                    tag.name.equals("style", ignoreCase = true) &&
                            tag.getAttribute("module") != null
                }
        }

        fun isUseCssModuleFromVue(initializer: JSCallExpression): Boolean {
            val methodExpr = initializer.methodExpression
            val resolved0: PsiElement? = methodExpr?.reference?.resolve() ?: return false
            var resolved: PsiElement? = resolved0
            if (resolved is ES6ImportSpecifierAlias) resolved = resolved.findAliasedElement()
            val cf = resolved?.containingFile ?: return false
            val virtualFile = cf.virtualFile ?: cf.originalFile?.virtualFile
            val filePath = virtualFile?.path?.lowercase() ?: return false
            return filePath.contains("node_modules/@vue")
        }

        fun findTagInFile(file: PsiFile, tagName: String): XmlTag? {
            return PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
                .firstOrNull { it.name.equals(tagName, ignoreCase = true) }
        }

        fun findVariableDeclarationByName(name: String, scriptTag: XmlTag?): JSVariable? {
            if (scriptTag === null || name.isBlank()) return null

            val topLevelBlocks = PsiTreeUtil.collectElements(scriptTag) { ele ->
                ele.text.trim().isNotEmpty() &&
                        ele.parent.javaClass.simpleName == "VueScriptSetupEmbeddedContentImpl"
            }

            return topLevelBlocks
                .flatMap { block ->
                    PsiTreeUtil.findChildrenOfType(block, JSVariable::class.java)
                        .filter { it.name == name }
                }
                .maxByOrNull { it.textOffset }
        }

        @JvmStatic
        fun hasPendingExternalModification(vf: VirtualFile): Boolean {
            val doc = FileDocumentManager.getInstance().getDocument(vf) ?: return false
            return !FileDocumentManager.getInstance().isDocumentUnsaved(doc) &&
                    doc.modificationStamp != vf.modificationStamp
        }

        /** [hasPendingExternalModification] 的 PsiFile 重载：无 VirtualFile 时视为无外部改动。 */
        @JvmStatic
        fun hasPendingExternalModification(psi: PsiFile?): Boolean {
            val vf = psi?.virtualFile ?: return false
            return hasPendingExternalModification(vf)
        }

        /**
         * 写入前统一守卫：返回第一个「磁盘已被外部修改、编辑器尚未重载」的文件；全部新鲜返回 null。
         *
         * 背景：该窗口内做 PSI/Document 写入会让 document 变为 unsaved，从而抑制平台对本文件的
         * 自动 reload——用户随后保存时，磁盘上的外部改动就会被旧内容+本次编辑整体覆盖丢失。
         * 因此所有写路径（action / intention / quickfix / copy-paste 注入）在写入前必须调用，
         * 检出 stale 时中止写入并提示用户先 Reload from Disk。
         */
        @JvmStatic
        fun findStaleFileForWrite(files: Collection<VirtualFile?>): VirtualFile? =
            files.firstOrNull { it != null && it.isValid && hasPendingExternalModification(it) }
    }
}
