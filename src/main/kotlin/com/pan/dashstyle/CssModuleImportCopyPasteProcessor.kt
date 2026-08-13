package com.pan.dashstyle

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import org.jetbrains.annotations.NotNull

/**
 * #3. 复制 CSS Module class 引用时自动带上 import 变量（目标处若已有同名 import 则不重复加）。
 *
 * 实现：
 *  - 复制时（preprocessOnCopy）如果选择的 PSI 范围是 `styles.xxx` 或 `styles["xxx"]` 的引用集合，
 *    且对应的绑定源是 `import styles from './Foo.module.css'` → 在剪贴板 RawText 的 transferable data 上放一份 JSON metadata。
 *    （注：由于 RawText API 不可扩展，我们用一个简单约定：在 text 末尾追加特殊标记注释 `/* __DASHSTYLE_IMPORT_META__ <base64(json)> */`
 *    粘贴时先剥离这个注释，然后按元数据按需注入 import。）
 *  - 粘贴时（preprocessOnPaste）检测到这段元数据 → 判断目标文件是否已经有 `import <binding> from '<from>'`；
 *    若没有，且也没有其他 binding 指向同一个 module 文件 → 在文本最开头 prepend 一行 import。
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

        private val IMPORTED_META_KEY: Key<ImportMeta> = Key.create("dashstyle.pendingImportMeta.v1")
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
        // 粘贴到非 JS/TS/Vue 的地方，这段注释是 CSS-like comment，不会引发语法错误（会被 formatter 提示但可删）
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

        val stripped = text.removeRange(m.range).trimEnd() + "\n"

        // 判断目标是否已有对应 import（同名 binding 或同 from）
        if (alreadyHasEquivalentImport(file, meta)) return stripped

        // 在粘贴文本最开头 prepend 一行 import（而不是直接改文件 — 改文件留给后续粘贴操作应用文档变更）
        val importLine = "import ${meta.binding} from '${meta.from}'\n"
        // 若粘贴位置是 <template> 内部（Vue），import 不能直接插在粘贴文本里（语法错）。
        // 这种情况下放弃 prepend，改用 putUserData + file 的后续 WriteAction 在 script 顶部追加（简单起见留空，避免误粘贴）
        if (file.name?.endsWith(".vue") == true) {
            // 这里只给个占位：更合理的实现通过 postPaste 插入；保守起见不 prepend 到模板内
            return stripped
        }
        return importLine + stripped
    }

    // ================================================================
    // copy 端：扫描选区范围内所有 styles.xxx / styles["xxx"]，若都指向同一个 module import → 取第一个
    // ================================================================
    private fun detectAndExtractImportMeta(file: PsiFile, starts: IntArray, ends: IntArray): ImportMeta? {
        val references = collectReferencesInRanges(file, starts, ends)
        if (references.isEmpty()) return null
        val metaSet = mutableSetOf<ImportMeta>()
        for (site in references) {
            val (container, qualifierName) = CssModuleResolver.resolveStylesContainer(site) ?: continue
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
        // 只有所有引用都指向同一个 {binding,from} 时才注入元数据，避免歧义
        return if (metaSet.size == 1) metaSet.first() else null
    }

    private fun collectReferencesInRanges(file: PsiFile, starts: IntArray, ends: IntArray): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        for (i in starts.indices) {
            val s = starts[i]; val e = ends[i]
            // 取 start 和 end 的 PSI 附近，向上搜索最近的 JSReferenceExpression / JSLiteral parent index access
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

    private fun computeRelativePath(srcVf: com.intellij.openapi.vfs.VirtualFile, targetVf: com.intellij.openapi.vfs.VirtualFile): String? {
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
        // Vue <script setup> 里也可能写在 XmlTag 内部，上面 findChildrenOfType 能递归进去
        // 如果是 script 块内嵌 JSEmbeddedContent，PsiTreeUtil 依然能走到 ES6ImportDeclaration，OK
        return false
    }

    private fun pathsAreEquivalent(a: String, b: String): Boolean {
        val na = a.removePrefix("./").replace('\\', '/').trimEnd('/')
        val nb = b.removePrefix("./").replace('\\', '/').trimEnd('/')
        return na == nb
    }
}
