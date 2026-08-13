package com.pan.dashstyle

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.*
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.annotations.NotNull
import java.util.concurrent.atomic.AtomicReference

/**
 * #7. 拷贝 TSX 片段时把对应的 class CSS 也拷到目标文件。
 *
 * 原理（只使用 CopyPastePreProcessor 双钩子 + invokeLater 调度，不依赖 CopyPastePostProcessor<T> 复杂泛型接口）：
 *  - `preprocessOnCopy`: 遍历被选中的 PSI，找到所有 className={styles.xxx} / styles["xxx"] /
 *    :class="$style.xxx" 等引用点，找到对应的 ruleset，序列化出 class + declarations。
 *    在复制文本末尾附一段注释 `/* __DS_CSS_BUNDLE__: <base64 json> */`
 *  - `preprocessOnPaste`: 检测 marker → 解析 JSON → 先从文本里剥离 marker 注释返回 →
 *    通过 invokeLater 等文档粘贴落地后，再用 WriteCommandAction 执行 CSS bundle 注入：
 *      a) 如果目标文件已经有对应 CSS Module import（相同 from 路径 或 相同 binding + 候选解析）→
 *         append CSS rules 到目标 Module 文件末尾
 *      b) 如果没有，就在源文件同目录找 <BaseName>.module.* → 有就追加，没有就新建并在目标文件顶部 prepend import
 */
class CssBundleCopyPastePostProcessor : CopyPastePreProcessor {

    companion object {
        private val LOG = Logger.getInstance(CssBundleCopyPastePostProcessor::class.java)
        private const val MARKER_BEGIN = "/* __DS_CSS_BUNDLE__:"
        private const val MARKER_END = " */"
        private val MARKER_RE = Regex("""/\* __DS_CSS_BUNDLE__:([A-Za-z0-9+/=\n\r]+?) \*/""")
        private val gson = Gson()

        data class CssClassRule(
            @SerializedName("selector") val selector: String,
            @SerializedName("declarations") val declarations: List<String>
        )

        data class Bundle(
            @SerializedName("rules") val rules: List<CssClassRule>,
            @SerializedName("sourceModule") val sourceModule: ImportMeta?
        )

        data class ImportMeta(
            @SerializedName("binding") val binding: String,
            @SerializedName("from") val from: String
        )
    }

    override fun preprocessOnCopy(
        file: PsiFile?,
        starts: IntArray?,
        ends: IntArray?,
        text: String?
    ): String? {
        if (file == null || starts == null || ends == null || text == null) return null
        val bundle = collectBundle(file, starts, ends) ?: return text
        if (bundle.rules.isEmpty()) return text
        val json = gson.toJson(bundle)
        val base64 = java.util.Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        return text + "\n" + MARKER_BEGIN + base64 + MARKER_END
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

        // 1. 解析 bundle（解析失败至少先把 marker strip 掉避免污染粘贴内容）
        val jsonStr = runCatching {
            String(java.util.Base64.getDecoder().decode(m.groupValues[1]), Charsets.UTF_8)
        }.getOrNull()
        val bundle = jsonStr?.let { runCatching { gson.fromJson(it, Bundle::class.java) }.getOrNull() }

        // 2. 精确移除 marker，不要乱改其他换行（避免把原本干净的 JSX 拆成畸形）
        //    marker 之前一般是 `\n` 或 `\r\n`，连同这条换行一起删掉，否则粘贴末尾会多出一行空行
        val rangeToRemove = if (m.range.first > 0 && text[m.range.first - 1] == '\n') {
            val prevIdx = m.range.first - 1
            val withCr = prevIdx > 0 && text[prevIdx - 1] == '\r'
            if (withCr) (prevIdx - 1)..m.range.last else prevIdx..m.range.last
        } else {
            m.range.first..m.range.last
        }
        val stripped = text.removeRange(rangeToRemove)

        // 3. 如果有 bundle，调度：等粘贴提交到文档之后，再注入 CSS rules
        //    （用 AtomicReference 把 context 搬过去，避免闭包可变变量警告）
        if (bundle != null) {
            val ctxRef = AtomicReference(Triple(project, file, bundle))
            ApplicationManager.getApplication().invokeLater {
                val (p, f, b) = ctxRef.get() ?: return@invokeLater
                if (!p.isDisposed && f.isValid && b.rules.isNotEmpty()) {
                    runCatching {
                        WriteCommandAction.writeCommandAction(p)
                            .withName("Append pasted CSS Module rules")
                            .run<Nothing> { applyBundle(p, f, b) }
                    }.onFailure { t ->
                        LOG.warn("DashStyle #7 applyBundle failed", t)
                    }
                }
            }
        }

        return stripped
    }

    // ================================================================
    // copy 端：收集 CSS bundle
    // ================================================================
    private fun collectBundle(file: PsiFile, starts: IntArray, ends: IntArray): Bundle? {
        val rules = mutableMapOf<String, CssClassRule>()
        var importMeta: ImportMeta? = null

        val candidates = collectUsageSites(file, starts, ends)
        for (site in candidates) {
            val requestedName = when (site) {
                is JSLiteralExpression -> site.stringValue ?: continue
                is JSReferenceExpression -> site.referenceName ?: continue
                else -> continue
            }
            val resolved = CssModuleResolver.resolveClassName(site, requestedName) ?: continue
            val expandedCls = ".${resolved.kebabName}"
            if (expandedCls in rules) continue
            val block = resolved.ruleset.block ?: continue
            val decls = PsiTreeUtil.findChildrenOfType(block, CssDeclaration::class.java)
                .map { d ->
                    val p = d.propertyName ?: ""
                    val v = d.value?.text ?: ""
                    "  $p: $v;"
                }
            rules[expandedCls] = CssClassRule(expandedCls, decls)

            if (importMeta == null) {
                when (val c = resolved.container) {
                    is CssModuleResolver.CssContainer.ImportedFile -> {
                        val from = computeRelPath(file.virtualFile, c.virtualFile) ?: continue
                        importMeta = ImportMeta(c.importBindingName, from)
                    }
                    else -> {}
                }
            }
        }
        return Bundle(rules.values.toList(), importMeta)
    }

    private fun computeRelPath(
        srcVf: com.intellij.openapi.vfs.VirtualFile?,
        tgtVf: com.intellij.openapi.vfs.VirtualFile
    ): String? {
        val parent = srcVf?.parent ?: return null
        val rel = java.nio.file.Path.of(parent.path)
            .relativize(java.nio.file.Path.of(tgtVf.path)).toString()
        val normalized = rel.replace('\\', '/')
        return if (normalized.startsWith("../") || normalized.startsWith("./"))
            normalized else "./$normalized"
    }

    private fun collectUsageSites(
        file: PsiFile,
        starts: IntArray,
        ends: IntArray
    ): List<PsiElement> {
        val out = mutableListOf<PsiElement>()
        for (i in starts.indices) {
            val s = starts[i]
            val e = ends[i]
            val startEl = file.findElementAt(s) ?: continue
            val endEl = file.findElementAt((e - 1).coerceAtLeast(s)) ?: startEl
            val root = PsiTreeUtil.findCommonParent(startEl, endEl) ?: file
            PsiTreeUtil.processElements(root) { el ->
                val tr = el.textRange
                val inRange = tr.startOffset in s..e ||
                    tr.endOffset in s..e ||
                    (tr.startOffset <= s && tr.endOffset >= e)
                if (inRange) {
                    if (el is JSLiteralExpression) {
                        val idx = PsiTreeUtil.getParentOfType(
                            el, JSIndexedPropertyAccessExpression::class.java
                        )
                        if (idx != null && idx.indexExpression == el) out += el
                    }
                    if (el is JSReferenceExpression && el.qualifier != null) {
                        val p = el.parent
                        if (p !is JSIndexedPropertyAccessExpression &&
                            (p !is JSCallExpression || p.methodExpression != el)
                        ) out += el
                    }
                }
                true
            }
        }
        return out
    }

    // ================================================================
    // paste 端：应用 bundle
    // ================================================================
    private fun applyBundle(project: Project, file: PsiFile, bundle: Bundle) {
        if (bundle.rules.isEmpty()) return
        val cssText = bundle.rules.joinToString("\n") { rule ->
            "${rule.selector} {\n${rule.declarations.joinToString("\n")}\n}"
        } + "\n"

        // 策略 1：如果 sourceModule 存在，且目标文件里已经有 "同 from" 或 "同 binding+候选文件" → 追加到那个文件
        if (bundle.sourceModule != null) {
            val container = resolveImportedContainer(project, file, bundle.sourceModule)
            if (container != null) {
                appendRulesToContainer(project, container, cssText)
                return
            }
        }

        // 策略 2：找目标文件旁边已有的同名模块文件 BaseName.module.*
        val existing = CssModuleResolver.findCandidateModuleFiles(file).firstOrNull()
        if (existing != null) {
            val psi = PsiManager.getInstance(project).findFile(existing.first)
            if (psi != null) {
                appendRulesToContainer(
                    project,
                    CssModuleResolver.CssContainer.ImportedFile(
                        psi, existing.first, existing.second, null
                    ),
                    cssText
                )
                ensureStylesImported(project, file, existing.first, existing.second)
                return
            }
        }

        // 策略 3：新建 BaseName.module.css，然后注入 import
        val vf = file.virtualFile ?: return
        val parent = vf.parent ?: return
        val newFileName = vf.nameWithoutExtension + ".module.css"
        val newFile = runCatching {
            parent.findOrCreateChildData(this, newFileName)
        }.getOrNull() ?: run {
            try {
                parent.createChildData(this, newFileName)
            } catch (t: Throwable) {
                LOG.warn("failed to create new CSS module file", t)
                return
            }
        }
        newFile.setBinaryContent(cssText.toByteArray(Charsets.UTF_8))
        ApplicationManager.getApplication().runWriteAction {
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(newFile.toNioPath())
        }
        ensureStylesImported(project, file, newFile, "styles")
    }

    private fun appendRulesToContainer(
        project: Project,
        container: CssModuleResolver.CssContainer,
        cssText: String
    ) {
        val targetPsiFile: PsiFile? = when (container) {
            is CssModuleResolver.CssContainer.ImportedFile -> container.psiFile
            is CssModuleResolver.CssContainer.VueStyleTag -> container.containingFile
            is CssModuleResolver.CssContainer.LocalObjectLiteral -> null
        }
        val factory = PsiFileFactory.getInstance(project)
        val tmp = factory.createFileFromText(
            "__dashstyle_bundle__.css",
            com.intellij.lang.css.CSSLanguage.INSTANCE,
            cssText
        )
        if (container is CssModuleResolver.CssContainer.VueStyleTag) {
            val last = container.styleTag.lastChild
            if (last != null) container.styleTag.addAfter(tmp.firstChild, last)
            else container.styleTag.add(tmp.firstChild)
        } else if (targetPsiFile != null) {
            val last = targetPsiFile.lastChild
            if (last != null) targetPsiFile.addAfter(tmp.firstChild, last)
            else targetPsiFile.add(tmp.firstChild)
        }
    }

    private fun resolveImportedContainer(
        project: Project,
        file: PsiFile,
        srcMeta: ImportMeta
    ): CssModuleResolver.CssContainer.ImportedFile? {
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val from = (imp.importModuleText ?: continue).trim('"', '\'')
            if (!pathsEquivalent(from, srcMeta.from)) continue
            val named = imp.namedImports
            val defaultBinding = imp.importedBindings.firstOrNull { b ->
                named == null || !PsiTreeUtil.isAncestor(named, b, false)
            } ?: continue
            val viaRef = defaultBinding.reference?.resolve()?.containingFile
            val vf = viaRef?.virtualFile ?: run {
                val parent = file.virtualFile?.parent ?: return@run null
                val rel = from.trimStart('/')
                val f = parent.findFileByRel(rel)
                    ?: parent.findChild(rel.substringAfterLast('/'))
                f?.let { PsiManager.getInstance(project).findFile(it)?.virtualFile }
            } ?: continue
            val psi = PsiManager.getInstance(project).findFile(vf) ?: continue
            return CssModuleResolver.CssContainer.ImportedFile(
                psi, vf, defaultBinding.name ?: srcMeta.binding, imp
            )
        }
        return null
    }

    private fun ensureStylesImported(
        project: Project,
        file: PsiFile,
        targetCssVf: com.intellij.openapi.vfs.VirtualFile,
        bindingName: String
    ) {
        val from = computeRelPath(file.virtualFile, targetCssVf) ?: return

        // Vue / 非 JS/TS：沿用保守的 PSI 策略，但 import 只插在 script 根块而非光标所在处
        val fileName = file.name.orEmpty()
        val isJsTsLike = fileName.endsWith(".js") || fileName.endsWith(".jsx") ||
            fileName.endsWith(".ts") || fileName.endsWith(".tsx")

        // 避免重复插入：和 #3 CssModuleImportCopyPasteProcessor 的等价性判断保持一致
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        if (imports.any { imp ->
                pathsEquivalent(
                    (imp.importModuleText ?: return@any false).trim('"', '\''),
                    from
                )
            }) return

        val importText = "import $bindingName from '$from'\n"

        if (!isJsTsLike && file is XmlFile && fileName.endsWith(".vue")) {
            // Vue 场景（insert in script setup root，非 template/script body 内部）
            val script = Util.findScriptTag(file)
            if (script != null) {
                val existing = PsiTreeUtil.findChildrenOfType(script, ES6ImportDeclaration::class.java)
                if (existing.isEmpty()) {
                    // 用 Document 的方式往 <script> 内容开头插，跳过 PSI 树以免插到子 body
                    val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
                    val embedded = findEmbeddedContent(script)
                    val startInDoc = embedded?.textRange?.startOffset
                        ?: (script.value?.textRange?.startOffset
                            ?: script.textRange.startOffset + "<script>".length)
                    doc.insertString(startInDoc, "\n$importText")
                    PsiDocumentManager.getInstance(project).commitDocument(doc)
                } else {
                    existing.last().parent.addAfter(
                        makeImportPsi(project, importText) ?: return, existing.last()
                    )
                }
            } else {
                val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return
                doc.insertString(0, "\n<script setup>\n$importText</script>\n")
                PsiDocumentManager.getInstance(project).commitDocument(doc)
            }
            return
        }

        // ******** 关键修复：JS/TS/JSX/TSX 一律走 Document 插入（module scope，与粘贴位置绝对无关） ********
        // 不调用 file.addBefore(newImport, file.firstChild)：
        //  当 file 是内嵌 JSXExpression 或 PSI 被格式化成「文件根节点的 firstChild 是 function」时，
        //  PSI.addBefore 会把 import 插到该 function body 第一行之前（用户报告的 `function Z() { import styles from ...`），
        //  导致 import 落在函数作用域内而非模块顶部。
        val doc = PsiDocumentManager.getInstance(project).getDocument(file)
        if (doc != null) {
            val topLevelImports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
                .filter { it.parent == file || it.parent?.parent == file }
            if (topLevelImports.isEmpty()) {
                // module scope 的 0 偏移插入 —— 必然在任何 function/class/statement 之前
                doc.insertString(0, importText)
            } else {
                val last = topLevelImports.maxByOrNull { it.textRange.endOffset } ?: return
                doc.insertString(last.textRange.endOffset, "\n$importText")
            }
            PsiDocumentManager.getInstance(project).commitDocument(doc)
        } else {
            // 极端 fallback：没有 Document。仍避免用 file.firstChild（函数体内），改用 file.add 后再内部重排
            val newImport = makeImportPsi(project, importText) ?: return
            file.addBefore(newImport, file.firstChild)
        }
    }

    private fun makeImportPsi(project: Project, importText: String): ES6ImportDeclaration? {
        val factory = PsiFileFactory.getInstance(project)
        val dummy = factory.createFileFromText(
            "__dashstyle_import__.js",
            com.intellij.lang.Language.findInstance(com.intellij.lang.javascript.JavascriptLanguage::class.java),
            importText + "export {}"
        )
        return PsiTreeUtil.findChildrenOfType(dummy, ES6ImportDeclaration::class.java).firstOrNull()
    }

    private fun findEmbeddedContent(scriptTag: XmlTag): PsiElement? {
        return PsiTreeUtil.collectElements(scriptTag) { ele ->
            ele.parent.javaClass.simpleName.contains("VueScript", ignoreCase = true) ||
                ele.javaClass.simpleName.contains("EmbeddedContent", ignoreCase = true)
        }.maxByOrNull { it.textRange.length }
    }

    private fun pathsEquivalent(a: String, b: String): Boolean {
        val na = a.removePrefix("./").replace('\\', '/').trimEnd('/')
        val nb = b.removePrefix("./").replace('\\', '/').trimEnd('/')
        return na == nb
    }

    private fun com.intellij.openapi.vfs.VirtualFile.findFileByRel(
        rel: String
    ): com.intellij.openapi.vfs.VirtualFile? {
        var cur: com.intellij.openapi.vfs.VirtualFile? = this
        for (seg in rel.replace('\\', '/').split('/')) {
            if (seg.isEmpty() || seg == ".") continue
            if (seg == "..") {
                cur = cur?.parent
                continue
            }
            cur = cur?.findChild(seg) ?: return null
        }
        return cur
    }
}
