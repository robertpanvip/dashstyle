package com.pan.dashstyle.support

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.css.StylesheetFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

/**
 * CSS Module 文件级操作：查找/创建/复制 module 文件，import 检测与生成。
 *
 * 从 CssModuleResolver 拆出，职责聚焦于文件层面的解析与写入操作，
 * 不涉及 PSI 容器解析（[CssModuleResolver]）或使用端扫描（[CssModuleUsageScanner]）。
 */
object CssModuleFileResolver {

    val PLAIN_EXTS = listOf(".css", ".less", ".scss", ".sass")

    /** 透传核心层的 module 扩展名列表，方便调用方统一引用。 */
    val MODULE_EXTS get() = CssModuleResolver.MODULE_EXTS

    // ================================================================
    // 1. import 文本解析
    // ================================================================

    /**
     * 从 import 语句的原始文本中提取 module 路径（兼容 PSI 不返回 importModuleText 的情况）。
     * 匹配 `import ... from "..."` / `import ... from '...'` / `import "..."` / `import '...'`
     */
    fun extractModulePathFromText(text: String): String? {
        val pattern = java.util.regex.Pattern.compile("""from\s*["']([^"']+)["']""")
        val m = pattern.matcher(text)
        if (m.find()) return m.group(1)
        val sideEffect = java.util.regex.Pattern.compile("""^import\s*["']([^"']+)["']""")
        val m2 = sideEffect.matcher(text.trim())
        if (m2.find()) return m2.group(1)
        return null
    }

    /**
     * 从父目录解析相对路径 './xxx.less' / '../xxx.less' → 得到实际 VirtualFile。
     */
    fun resolveRelativePath(baseDir: VirtualFile, relativePath: String): VirtualFile? {
        var current = baseDir
        for (seg in relativePath.replace('\\', '/').split('/')) {
            when (seg) {
                "", "." -> continue
                ".." -> { current = current.parent ?: return null }
                else -> { current = current.findChild(seg) ?: return null }
            }
        }
        return if (current.isValid && !current.isDirectory) current else null
    }

    // ================================================================
    // 2. 已有 import / module 文件查找
    // ================================================================

    /**
     * 扫描 sourceFile 中的 ES6 import，查找已导入的 CSS Module 文件。
     * 返回 (VirtualFile, bindingName)。
     * 兼容 WebStorm PSI 不返回 importModuleText 的情况（回退到文本匹配）。
     */
    fun findExistingModuleImport(sourceFile: PsiFile): Pair<VirtualFile, String>? {
        val imports = PsiTreeUtil.findChildrenOfType(sourceFile, ES6ImportDeclaration::class.java)
        for (imp in imports) {
            val from = imp.importModuleText?.trim('"', '\'')
                ?: extractModulePathFromText(imp.text)
                ?: continue
            if (CssModuleResolver.MODULE_EXTS.none { from.endsWith(it, ignoreCase = true) }) continue

            val bindings = imp.importedBindings
            val defaultBinding = bindings.firstOrNull()
            val alias = defaultBinding?.name
                ?: imp.importSpecifiers.firstOrNull()?.name
                ?: "styles"

            // 优先通过 PSI reference 解析到真实文件
            val resolvedPsi: PsiFile? = run<PsiFile?> {
                val viaRef = defaultBinding?.reference?.resolve()?.containingFile
                if (viaRef != null) return@run viaRef
                // 按相对路径解析
                val parent = sourceFile.virtualFile?.parent ?: return@run null
                val normFrom = from.trimStart('/')
                val vf = resolveRelativePath(parent, normFrom)
                    ?: parent.findChild(normFrom.substringAfterLast('/'))
                    ?: return@run null
                PsiManager.getInstance(sourceFile.project).findFile(vf)
            }
            if (resolvedPsi?.virtualFile != null) {
                return resolvedPsi.virtualFile!! to alias
            }
        }
        return null
    }

    /**
     * 在 Vue SFC 中查找 `<style module>` 标签及其 alias。
     * 返回 (XmlTag, aliasName)，alias 格式为 "$style" 或 "$xxx"。
     */
    fun findVueStyleModule(file: PsiFile): Pair<XmlTag, String>? {
        if (file !is XmlFile) return null
        val styles = PsiTreeUtil.findChildrenOfType(file, XmlTag::class.java)
            .filter { it.name.equals("style", ignoreCase = true) }
        val mod = styles.firstOrNull { it.getAttribute("module") != null }
        if (mod != null) {
            val modValue = mod.getAttributeValue("module")
            val alias = if (modValue.isNullOrBlank()) "\$style" else "\$$modValue"
            return mod to alias
        }
        val any = styles.firstOrNull()
        if (any != null) return any to "\$style"
        return null
    }

    /**
     * 在同目录查找与源文件同名的 module 文件（如 App.tsx → App.module.css）。
     */
    fun findSameNameModuleFile(sourceFile: PsiFile): VirtualFile? {
        val vf = sourceFile.virtualFile ?: return null
        val parent = vf.parent ?: return null
        val base = vf.nameWithoutExtension
        for (suf in CssModuleResolver.MODULE_EXTS) {
            val child = parent.findChild("$base$suf")
            if (child != null && child.isValid) return child
        }
        return null
    }

    /**
     * 同目录候选 CSS Module 文件（F5 创建 import、F6 找不到 import 时新建 class 用）
     */
    fun findCandidateModuleFiles(sourceFile: PsiFile): List<Pair<VirtualFile, String /* suggest alias */>> {
        val vf = sourceFile.virtualFile ?: return emptyList()
        val parent = vf.parent ?: return emptyList()
        val base = vf.nameWithoutExtension
        val exact = mutableListOf<Pair<VirtualFile, String>>()
        val others = mutableListOf<Pair<VirtualFile, String>>()
        for (child in parent.children) {
            val ext = child.extension ?: continue
            val name = child.name
            if (!CssModuleResolver.MODULE_EXTS.any { name.endsWith(it, ignoreCase = true) }) continue
            if (name.startsWith(base)) exact += child to "styles"
            else others += child to "styles" + (others.size + 1).toString().takeIf { others.size > 0 }.orEmpty()
        }
        return exact + others
    }

    // ================================================================
    // 3. import 生成 / 更新
    // ================================================================

    /**
     * 统一的 import 检测与生成：检查 sourceFile 是否已有 import 指向 moduleVf，
     * 有则返回 binding 名；没有则追加 `import styles from '...'`。
     *
     * 纯 PSI 写入：使用 [PsiFileFactory] 创建新 import 节点，通过 `element.replace()` /
     * `file.addAfter()` 修改 PSI 树，不经过 Document API，避免 Document+PSI 混合写入。
     */
    fun ensureImportExists(project: Project, sourceFile: PsiFile, moduleVf: VirtualFile): String {
        val imports = PsiTreeUtil.findChildrenOfType(sourceFile, ES6ImportDeclaration::class.java)
        val moduleFileName = moduleVf.name
        val originalFileName = moduleFileName.replace(".module.", ".")
        val relativeModulePath = computeRelativeImportPath(sourceFile.virtualFile!!, moduleVf)

        // 1. 已有 import 指向该 module 文件 → 直接返回 binding 名
        for (imp in imports) {
            val from = imp.importModuleText?.trim('"', '\'')
                ?: extractModulePathFromText(imp.text)
                ?: continue
            if (from.endsWith(moduleFileName, ignoreCase = true) ||
                from.endsWith("/$moduleFileName", ignoreCase = true)
            ) {
                val bindings = imp.importedBindings
                return bindings.firstOrNull()?.name ?: "styles"
            }
        }

        // 2. 有 import 指向原始文件（如 `import './index.less'`）→ PSI replace 更新路径
        for (imp in imports) {
            val from = imp.importModuleText?.trim('"', '\'')
                ?: extractModulePathFromText(imp.text)
                ?: continue
            if (from.endsWith(originalFileName, ignoreCase = true)) {
                val importStatement = imp.text
                val newImportText = if (importStatement.contains("from")) {
                    importStatement.replace(originalFileName, moduleFileName)
                } else {
                    "import styles from '$relativeModulePath'"
                }
                val newImportPsi = createImportPsi(project, sourceFile, newImportText)
                if (newImportPsi != null) {
                    WriteCommandAction.writeCommandAction(project, sourceFile)
                        .withName("Update CSS Module import")
                        .run<Nothing> {
                            imp.replace(newImportPsi)
                        }
                }
                if (newImportText.startsWith("import styles")) return "styles"
                val bindingName = newImportText.removePrefix("import ").substringBefore(" from").trim()
                return bindingName.ifEmpty { "styles" }
            }
        }

        // 3. 都没有 → PSI add 在最后一个 import 之后追加
        val importText = "import styles from '$relativeModulePath'"
        val newImportPsi = createImportPsi(project, sourceFile, importText)
        if (newImportPsi != null) {
            WriteCommandAction.writeCommandAction(project, sourceFile)
                .withName("Add CSS Module import")
                .run<Nothing> {
                    appendImportDeclaration(project, sourceFile, newImportPsi)
                }
        }
        return "styles"
    }

    /**
     * 纯 PSI 追加一条 import 声明到 sourceFile 的 module-scope：
     *  - 已有 import → 插到最后一条 import 之后；
     *  - 没有 import → 插到文件最顶部（shebang / @ts-nocheck 之前，import 本来就应在最前）；
     *  - 空文件 → 直接 add。
     * 调用方必须已持有写动作（WriteCommandAction 内）。
     * 返回插入后的 import 节点；失败返回 null。
     */
    fun appendImportDeclaration(
        project: Project,
        sourceFile: PsiFile,
        newImportPsi: ES6ImportDeclaration
    ): PsiElement? {
        val imports = PsiTreeUtil.findChildrenOfType(sourceFile, ES6ImportDeclaration::class.java).toList()
        return when {
            imports.isNotEmpty() -> {
                val last = imports.maxByOrNull { it.textRange.endOffset } ?: return null
                last.parent.addAfter(newImportPsi, last)
            }
            sourceFile.firstChild != null -> sourceFile.addBefore(newImportPsi, sourceFile.firstChild)
            else -> sourceFile.add(newImportPsi)
        }
    }

    /** 便捷重载：从 import 文本创建 PSI 节点并追加（调用方须持有写动作）。 */
    fun appendImportDeclaration(project: Project, sourceFile: PsiFile, importText: String): PsiElement? {
        val newImportPsi = createImportPsi(project, sourceFile, importText) ?: return null
        return appendImportDeclaration(project, sourceFile, newImportPsi)
    }

    /**
     * 从文本创建 [ES6ImportDeclaration] PSI 节点（用于 PSI 原子写入）。
     * 使用与 sourceFile 相同的 language，保证 TSX/JSX/Vue 等文件的 import 语法兼容。
     */
    internal fun createImportPsi(project: Project, sourceFile: PsiFile, importText: String): ES6ImportDeclaration? {
        val ext = sourceFile.virtualFile?.extension ?: "js"
        val dummyFile = PsiFileFactory.getInstance(project)
            .createFileFromText("_dummy.$ext", sourceFile.language, "$importText\n")
        return PsiTreeUtil.findChildrenOfType(dummyFile, ES6ImportDeclaration::class.java).firstOrNull()
    }

    // ================================================================
    // 4. plain CSS 文件扫描与统计
    // ================================================================

    /**
     * 收集 sourceFile 中导入的非 module CSS 文件（import './xxx.less'）。
     * 返回 (VirtualFile, referenceCount) 列表。
     */
    fun collectImportedPlainFiles(sourceFile: PsiFile, parentDir: VirtualFile): List<Pair<VirtualFile, Int>> {
        val result = mutableListOf<Pair<VirtualFile, Int>>()
        PsiTreeUtil.findChildrenOfType(sourceFile, ES6ImportDeclaration::class.java).forEach { imp ->
            val from = imp.importModuleText?.trim('"', '\'')
                ?: extractModulePathFromText(imp.text)
                ?: return@forEach
            if (from.isEmpty()) return@forEach
            if (!from.startsWith("./") && !from.startsWith("../")) return@forEach
            val ext = from.substringAfterLast('.', "")
            if (ext.lowercase() !in listOf("css", "less", "scss", "sass")) return@forEach
            if (from.contains(".module.", ignoreCase = true)) return@forEach
            val resolved = resolveRelativePath(parentDir, from) ?: return@forEach
            if (!resolved.isValid || resolved.isDirectory) return@forEach
            val refCount = countReferences(resolved, sourceFile.project)
            result.add(resolved to refCount)
        }
        return result.distinctBy { it.first }
    }

    /**
     * 统计 CSS 文件在项目中的导入引用数（>1 则不能重命名，只能复制）。
     */
    fun countReferences(file: VirtualFile, project: Project): Int {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return 1
        val scope = GlobalSearchScope.projectScope(project)
        return ReferencesSearch.search(psiFile, scope).findAll().size
    }

    // ================================================================
    // 5. module 文件创建 / 复制 / 重命名
    // ================================================================

    /**
     * 将 plain CSS 文件名转为 module 文件名（foo.less → foo.module.less）。
     */
    fun renameToModule(oldName: String): String {
        for (ext in PLAIN_EXTS) {
            if (oldName.endsWith(ext, ignoreCase = true)) {
                val base = oldName.substring(0, oldName.length - ext.length)
                if (base.endsWith(".module")) return oldName
                return "$base.module$ext"
            }
        }
        val dotIdx = oldName.lastIndexOf('.')
        return if (dotIdx >= 0) {
            oldName.substring(0, dotIdx) + ".module" + oldName.substring(dotIdx)
        } else {
            "$oldName.module.css"
        }
    }

    /**
     * 新建空 module 文件。
     */
    fun createModuleFile(parent: VirtualFile, baseName: String, sourceExt: String? = null): VirtualFile? {
        val ext = when (sourceExt?.lowercase()) {
            "less" -> ".module.less"
            "scss" -> ".module.scss"
            "sass" -> ".module.sass"
            else -> ".module.css"
        }
        val newName = "$baseName$ext"
        return runCatching {
            parent.createChildData(CssModuleFileResolver, newName)
        }.getOrNull()
    }

    /**
     * 复制 plain CSS 文件为 module 文件，保留原有内容。
     */
    fun copyToModule(
        parent: VirtualFile,
        source: VirtualFile,
        fallbackBaseName: String,
        sourceExt: String?
    ): Pair<VirtualFile, Boolean>? {
        val newName = renameToModule(source.name)
        val content = source.contentsToByteArray()
        return runCatching {
            val newFile = parent.createChildData(CssModuleFileResolver, newName)
            newFile.setBinaryContent(content)
            Pair(newFile, false)
        }.getOrElse {
            val baseName = source.nameWithoutExtension
            val newFile = createModuleFile(parent, baseName, source.extension ?: sourceExt)
            if (newFile != null) Pair(newFile, true) else null
        }
    }

    /**
     * 计算从 source 文件到 target 文件的相对 import 路径。
     */
    fun computeRelativeImportPath(source: VirtualFile, target: VirtualFile): String {
        val sourceParent = source.parent ?: return "./${target.name}"
        val sourcePath = sourceParent.path
        val targetPath = target.path
        if (targetPath.startsWith(sourcePath)) {
            val rel = targetPath.substring(sourcePath.length).trimStart('/')
            return "./$rel"
        }
        val sourceSegments = sourcePath.split('/').filter { it.isNotBlank() }
        val targetSegments = targetPath.split('/').filter { it.isNotBlank() }
        var commonLen = 0
        while (commonLen < sourceSegments.size && commonLen < targetSegments.size &&
            sourceSegments[commonLen] == targetSegments[commonLen]
        ) {
            commonLen++
        }
        val upCount = sourceSegments.size - commonLen
        val up = (1..upCount).joinToString("") { "../" }
        val down = targetSegments.drop(commonLen).joinToString("/")
        return "./$up$down"
    }
}
