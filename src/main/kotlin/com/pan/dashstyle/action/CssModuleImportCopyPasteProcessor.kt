package com.pan.dashstyle.action

import com.pan.dashstyle.reference.*
import com.pan.dashstyle.inspection.*
import com.pan.dashstyle.support.*
import com.pan.dashstyle.annotator.*

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.JavascriptLanguage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import org.jetbrains.annotations.NotNull

/**
 * #3. 复制 CSS Module class 引用时自动带上 import 变量。
 *
 * 设计说明（2026-08 修复）：
 *  之前的错误做法 —— 在 preprocessOnPaste 返回值里把 importLine 直接 prepend 到粘贴文本前面。
 *  当用户把 <h1 className={styles.test}> 粘贴到某个 JSX 的 div 内部时，返回值 =
 *    "import styles from './App.module.less'\n<h1 className={styles.test}>…</h1>"
 *  结果 import 语句被塞进了 JSX 里，编译出错。
 *  正确做法 —— preprocessOnPaste 只负责「剥离 marker / 返回干净的粘贴文本」，
 *  import 注入这件事必须交给 invokeLater + WriteCommandAction，
 *  在目标文件真正的 module-scope（最后一条 ES6ImportDeclaration 之后 / 没有就文件最顶部）
 *  插入 import 行，和粘贴位置无关。
 */
class CssModuleImportCopyPasteProcessor : CopyPastePreProcessor {

    companion object {
        private const val MARK_BEGIN = "/* __DS_IMPORT_META__:"
        private const val MARK_END = " */"
        private val MARKER_RE = Regex("""/\* __DS_IMPORT_META__:([A-Za-z0-9+/=]+) \*/\s*$""")
        private val gson = Gson()

        data class ImportMeta(
            @SerializedName("binding") val binding: String,
            @SerializedName("from") val from: String, // "./Foo.module.css"
            @SerializedName("absPath") val absPath: String? // 绝对路径可选，给 #7 后续复用
        )
    }

    override fun preprocessOnCopy(
        file: PsiFile?,
        startOffsets: IntArray?,
        endOffsets: IntArray?,
        text: String?
    ): String? {
        if (file == null || text == null || startOffsets == null || endOffsets == null) return null
        val meta = detectAndExtractImportMeta(file, startOffsets, endOffsets) ?: return null
        val json = gson.toJson(meta)
        val base64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return text + "\n" + MARK_BEGIN + base64 + MARK_END
    }

    @NotNull
    override fun preprocessOnPaste(
        project: Project?,
        file: PsiFile?,
        editor: Editor?,
        text: String?,
        rawText: RawText?
    ): String {
        if (project == null || file == null || editor == null || text == null) return text ?: ""

        val m = MARKER_RE.find(text)
        if (m == null) return text

        val base64 = m.groupValues[1]
        val json = runCatching { String(java.util.Base64.getDecoder().decode(base64), Charsets.UTF_8) }.getOrNull()
            ?: return text.removeRange(m.range)
        val meta = runCatching { gson.fromJson(json, ImportMeta::class.java) }.getOrNull()
            ?: return text.removeRange(m.range)

        val stripped = text.removeRange(m.range).trimEnd(' ', '\t', '\n', '\r') + "\n"

        // Vue / 非 JS/TS：保守策略 —— 只剥 marker，不做任何 import 注入
        // （避免塞到 <template> / script 外的错误位置）
        val fileName = file.name.orEmpty()
        if (fileName.endsWith(".vue") ||
            !(fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
              fileName.endsWith(".ts") || fileName.endsWith(".tsx"))) {
            return stripped
        }

        // 已存在等价 import → 直接返回纯粘贴文本
        if (alreadyHasEquivalentImport(file, meta)) return stripped

        // 关键修复：用 invokeLater + WriteCommandAction 在文件真正的 module-scope 插 import，
        // 和 editor.caretModel.offset 在哪里（JSX 内部也好、注释里也好）完全无关。
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed || editor.isDisposed) return@invokeLater
            runCatching {
                WriteCommandAction.runWriteCommandAction(project, "Add CSS Module import", "DashStyle", Runnable {
                    injectImportAtModuleScope(project, file, meta)
                }, file)
            }
        }

        return stripped
    }

    /**
     * 在目标 TSX/JSX 文件的 module-scope（不是 JSX 内部！）插入一行 import。
     * 规则：
     *  - 如果已有 ES6ImportDeclaration 列表非空 → 插到最后一条的紧后面；
     *  - 否则 → 插到文件最开头（prepend，加个换行与后续代码分离）；
     *  - 写完后立刻 commit，让 PSI/文档同步。
     */
    private fun injectImportAtModuleScope(project: Project, file: PsiFile, meta: ImportMeta) {
        val importText = "import ${meta.binding} from '${meta.from}'\n"
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
        if (doc != null) {
            val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java).toList()
            if (imports.isEmpty()) {
                // 插到最开头。如果开头是 shebang / @ts-nocheck 之类，也放在最前面（import 本来就要在前面）。
                doc.insertString(0, importText)
            } else {
                val last = imports.maxByOrNull { it.textRange.endOffset } ?: return
                // 最后一条 import 的末尾，换行之后再加新的 import（保持空行风格，和 formatter 一致）
                val end = last.textRange.endOffset
                val suffix = "\n" + importText
                doc.insertString(end, suffix)
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        } else {
            // 罕见 fallback：走 PSI AST 插入（没有 Document 时，比如内嵌块）。
            val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
            val factory = PsiFileFactory.getInstance(project)
            val dummy = factory.createFileFromText(
                "__dashstyle_import__.js",
                com.intellij.lang.Language.findInstance(JavascriptLanguage::class.java),
                importText + "export {}"
            )
            val newImport = PsiTreeUtil.findChildrenOfType(dummy, ES6ImportDeclaration::class.java)
                .firstOrNull() ?: return
            val first = file.firstChild
            if (imports.isEmpty()) {
                if (first != null) file.addBefore(newImport, first)
                else file.add(newImport)
            } else {
                val last = imports.last()
                last.parent.addAfter(newImport, last)
            }
        }
    }

    // ================================================================
    // copy 端：扫描选区范围内所有 styles.xxx / styles["xxx"]，若都指向同一个 module import → 取第一个
    // ================================================================
    private fun detectAndExtractImportMeta(file: PsiFile, starts: IntArray, ends: IntArray): ImportMeta? {
        val references = collectReferencesInRanges(file, starts, ends)
        if (references.isEmpty()) return null
        val metaSet = mutableSetOf<ImportMeta>()
        for (site in references) {
            val (container, _) = CssModuleResolver.resolveStylesContainer(site) ?: continue
            when (container) {
                is CssModuleResolver.CssContainer.ImportedFile -> {
                    val vf = container.virtualFile
                    val srcVf = file.virtualFile ?: continue
                    val from = computeRelativePath(srcVf, vf) ?: continue
                    metaSet += ImportMeta(container.importBindingName, from, vf.path)
                }
                else -> { /* Vue style 或本地对象不做 import 注入 */ }
            }
        }
        return if (metaSet.size == 1) metaSet.first() else null
    }

    private fun collectReferencesInRanges(file: PsiFile, starts: IntArray, ends: IntArray): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        for (i in starts.indices) {
            val s = starts[i]; val e = ends[i]
            val start = file.findElementAt(s)
            val end = file.findElementAt((e - 1).coerceAtLeast(s))
            val common = if (start != null && end != null) PsiTreeUtil.findCommonParent(start, end) else null
            val root = common ?: file
            PsiTreeUtil.processElements(root) { e2 ->
                val tr = e2.textRange
                if (tr.startOffset in s..e || tr.endOffset in s..e || (tr.startOffset <= s && tr.endOffset >= e)) {
                    if (e2 is JSLiteralExpression) {
                        val idx = PsiTreeUtil.getParentOfType(e2, JSIndexedPropertyAccessExpression::class.java)
                        if (idx != null && idx.indexExpression == e2) out += e2
                    } else if (e2 is JSReferenceExpression && e2.qualifier != null) {
                        val parent = e2.parent
                        if (parent !is JSIndexedPropertyAccessExpression &&
                            (parent !is JSCallExpression || parent.methodExpression != e2)) out += e2
                    }
                }
                true
            }
        }
        return out
    }

    private fun computeRelativePath(srcVf: VirtualFile, targetVf: VirtualFile): String? {
        val parent = srcVf.parent ?: return null
        val rel = java.nio.file.Path.of(parent.path).relativize(java.nio.file.Path.of(targetVf.path)).toString()
        val normalized = rel.replace('\\', '/')
        return if (normalized.startsWith("../") || normalized.startsWith("./")) normalized else "./$normalized"
    }

    // ================================================================
    // paste 端：是否已经有等价 import
    // ================================================================
    private fun alreadyHasEquivalentImport(file: PsiFile, meta: ImportMeta): Boolean {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val from = (imp.importModuleText ?: continue).trim('"', '\'')
            if (from == meta.from || pathsAreEquivalent(from, meta.from)) return true
            val named = imp.namedImports
            val defaultBinding = imp.importedBindings.firstOrNull { b ->
                named == null || !PsiTreeUtil.isAncestor(named, b, false)
            } ?: continue
            if (defaultBinding.name == meta.binding) return true
        }
        return false
    }

    private fun pathsAreEquivalent(a: String, b: String): Boolean {
        val na = a.removePrefix("./").replace('\\', '/').trimEnd('/')
        val nb = b.removePrefix("./").replace('\\', '/').trimEnd('/')
        return na == nb
    }
}
