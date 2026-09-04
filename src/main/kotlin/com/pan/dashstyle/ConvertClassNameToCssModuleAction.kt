package com.pan.dashstyle

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import java.util.regex.Pattern

/**
 * 在 TSX 文件中 → Refactor → Convert className to CSS Module.
 *
 * 一键迁移传统 CSS 项目到 CSS Modules。
 *
 * 流程：
 * 1. 无选区 → 扫描整个文件；有选区 → 只扫描选中区域
 * 2. 收集所有 className="xxx" / className={'xxx'} 字符串字面量中的类名
 * 3. 查找或创建 CSS Module 文件（*.module.css / .less / .scss）
 * 4. 生成 import styles 语句（如果缺失）
 * 5. 将 className="foo" 替换为 className={styles.foo}
 * 6. 在 CSS Module 文件中生成 .foo { } 规则
 */
class ConvertClassNameToCssModuleAction : AnAction(
    "Convert className to CSS Module"
) {

    companion object {
        private val MODULE_EXTS = listOf(".module.css", ".module.scss", ".module.sass", ".module.less")
        private val PLAIN_EXTS = listOf(".css", ".less", ".scss", ".sass")
        // 匹配 className="..." 或 className='...' 中的类名
        private val CLASS_NAME_SPLIT = Regex("""\s+""")
    }

    // ================================================================
    // 可见性：仅当选中 JSX/TSX 代码且有 className 字面量时启用
    // ================================================================

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = e.getData(CommonDataKeys.PSI_FILE)

        val ext = file?.virtualFile?.extension?.lowercase()
        val isTsx = ext == "tsx"

        e.presentation.isEnabledAndVisible = project != null && isTsx
        e.presentation.text = "Convert className to CSS Module"
    }

    // ================================================================
    // 主入口
    // ================================================================

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.PSI_FILE) ?: return

        val selection = editor.selectionModel
        val (selStart, selEnd) = if (selection.hasSelection()) {
            selection.selectionStart to selection.selectionEnd
        } else {
            // 没有选区 → 扫描整个文件
            0 to file.textRange.endOffset
        }

        // 1. 扫描整个选区/文件，收集 className 字面量中的类名
        val rawClassNames = collectClassNameValues(file, selStart, selEnd)
        if (rawClassNames.isEmpty()) {
            val scope = if (selStart == 0 && selEnd == file.textRange.endOffset) "the whole file" else "selected code"
            Messages.showInfoMessage(
                project,
                "No className string literals found in $scope.\n" +
                        "Make sure you have JSX with className=\"...\" attributes.",
                "Convert className to CSS Module"
            )
            return
        }

        // 去重、排序
        val uniqueNames = rawClassNames.toSortedSet()

        // 2. 确认用户想要转换的类名
        val preview = uniqueNames.joinToString("\n  ", prefix = "  ")
        val confirm = Messages.showYesNoDialog(
            project,
            "Found ${uniqueNames.size} class name(s) to convert:\n$preview\n\n" +
                    "Do you want to proceed?",
            "Convert className to CSS Module",
            Messages.getQuestionIcon()
        )
        if (confirm != Messages.YES) return

        // 3. 查找或创建 CSS Module 文件
        val (moduleFile, isNewFile) = resolveModuleFile(project, file) ?: return

        // 4. 生成 import（如果缺失）
        val importBinding = ensureImportExists(project, file, moduleFile)

        // 5. 替换选中区域中的 className 字面量
        replaceClassNames(project, editor, file, selStart, selEnd, uniqueNames, importBinding)

        // 6. 在 CSS Module 文件中追加规则 → 只对新建空文件追加，复制的文件已有内容不需要追加
        if (isNewFile) {
            appendCssRules(project, moduleFile, uniqueNames)
        }

        Messages.showInfoMessage(
            project,
            "Converted ${uniqueNames.size} class name(s) to CSS Module.\n" +
                    "Import: `import $importBinding from './${moduleFile.name}'`\n" +
                    "Target: ${moduleFile.path}",
            "Convert className to CSS Module"
        )
    }

    // ================================================================
    // 步骤 1：收集 className 属性值中的类名
    // ================================================================

    /**
     * 扫描 [start, end) 范围内的 PSI 元素，收集所有 className 属性的字符串值。
     * 支持：
     *   - className="foo bar"  →  ["foo", "bar"]
     *   - className={'foo'}    →  ["foo"]
     *   - className={`foo`}    →  fallback 提取静态部分
     */
    data class ClassNameSite(
        val literal: PsiElement, // 字符串字面量 PSI
        val value: String,       // 完整的字符串内容
        val startOffset: Int,    // 在文件中的 textOffset
        val endOffset: Int       // 结束偏移
    )

    private fun collectClassNameValues(file: PsiFile, start: Int, end: Int): Set<String> {
        val result = mutableSetOf<String>()

        // 方法 1：通过 PSI 查找 JSXAttribute / JSAttribute
        PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java).forEach { literal ->
            // 跳过不在选中范围内的
            val textRange = literal.textRange
            if (textRange.startOffset < start || textRange.endOffset > end) return@forEach

            // 检查父节点是否是 className 属性
            val parent = literal.parent ?: return@forEach
            if (!isClassNameAttribute(parent)) return@forEach

            // 提取字符串值
            val strValue = literal.stringValue ?: return@forEach
            val parts = strValue.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
            result += parts
        }

        // 方法 2：文本级 fallback（当 PSI 结构不完整时）
        // 扫描选中文本中的 className="xxx" 或 className='xxx'
        if (result.isEmpty()) {
            val selectedText = file.text.substring(start, end)
            val textPattern = Pattern.compile("""className\s*=\s*["']([^"']*)["']""")
            val matcher = textPattern.matcher(selectedText)
            while (matcher.find()) {
                val value = matcher.group(1).trim()
                if (value.isNotBlank()) {
                    val parts = value.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
                    result += parts
                }
            }
        }

        return result
    }

    /**
     * 判断 PSI 元素是否是 className 属性（兼容多种 JSX/JSAttribute 实现）。
     */
    private fun isClassNameAttribute(element: PsiElement): Boolean {
        val className = element.javaClass.name
        // 新版 JSAttribute / JsxAttribute 等
        if (className.contains("JSAttribute", ignoreCase = true) ||
            className.contains("JsxAttribute", ignoreCase = true) ||
            className.contains("JSXAttribute", ignoreCase = true)
        ) {
            val attrName = runCatching {
                element.javaClass.methods.firstOrNull { m ->
                    m.name == "getName" && m.parameterCount == 0
                }?.invoke(element) as? CharSequence
            }.getOrNull()?.toString()
            return attrName == "className"
        }
        // XmlAttribute（某些版本将 JSX 解析为 XML）
        if (element is XmlAttribute) {
            return element.name == "className"
        }
        // 文本匹配（兜底）
        val text = element.text.trimStart()
        return text.startsWith("className") || text.startsWith("className=")
    }

    // ================================================================
    // 步骤 3：查找或创建 CSS Module 文件
    // ================================================================

    /**
     * 流程：
     * 1. 先查找同目录已有 *.module.* → 直接用
     * 2. 扫描当前 TSX 文件中的 import './xxx.less' / import './xxx.css'（非 module）
     *   a. 如果只有当前文件导入这个文件 → 可以重命名它为 *.module.less
     *   b. 如果有多个文件导入它 → 只能复制一份创建 *.module.less（避免破坏其他文件导入）
     * 3. 否则让用户选择或创建
     */
    private fun resolveModuleFile(project: Project, sourceFile: PsiFile): Pair<VirtualFile, Boolean>? {
        val vf = sourceFile.virtualFile ?: return null
        val parent = vf.parent ?: return null
        val sourceExt = vf.extension?.lowercase()

        // 1. 查找同目录下已有的 *.module.* 文件
        val existingModules = parent.children.filter { child ->
            MODULE_EXTS.any { child.name.endsWith(it, ignoreCase = true) }
        }.filter { it.isValid && !it.isDirectory }

        when (existingModules.size) {
            0 -> {
                // 2. 扫描当前文件中导入的 .css/.less/.scss 文件（非 module）
                val importedPlainFiles = collectImportedPlainFiles(sourceFile, parent)

                when {
                    importedPlainFiles.size == 1 -> {
                        val (file, refCount) = importedPlainFiles.first()
                        val newName = renameToModule(file.name)

                        if (refCount == 1) {
                            // 只有当前文件导入 → 可以直接重命名
                            val ans = Messages.showYesNoDialog(
                                project,
                                "Found imported file `${file.name}`.\n" +
                                        "It's only imported in this file. Rename it to `$newName`?",
                                "Convert className to CSS Module",
                                Messages.getQuestionIcon()
                            )
                            if (ans == Messages.YES) {
                                val renamed = runCatching {
                                    file.rename(null, newName)
                                    file
                                }.getOrNull()
                                if (renamed != null) return Pair(renamed, false) // 重命名，已有内容
                            }
                            // 用户选 No，或者重命名失败 → 复制一份新建
                            return copyToModule(project, parent, file, vf.nameWithoutExtension, sourceExt)
                        } else {
                            // 多个文件导入 → 只能创建新文件，不能破坏其他文件
                            val ans = Messages.showYesNoDialog(
                                project,
                                "Found imported file `${file.name}`.\n" +
                                        "It's imported in $refCount files (other than this one), so we can't rename it.\n" +
                                        "Create a copy `$newName` for CSS Module?",
                                "Convert className to CSS Module",
                                Messages.getQuestionIcon()
                            )
                            if (ans != Messages.YES) return null
                            return copyToModule(project, parent, file, vf.nameWithoutExtension, sourceExt)
                        }
                    }
                    importedPlainFiles.size > 1 -> {
                        // 多个导入文件 → 让用户选择
                        val candidates = importedPlainFiles.map { "${it.first.name} (${it.second} imports)" }.toTypedArray()
                        val rawFiles = importedPlainFiles.map { it.first }.toList()
                        val idx = Messages.showChooseDialog(
                            project,
                            "Found multiple imported CSS files. Which one to use for CSS Module?",
                            "Convert className to CSS Module",
                            Messages.getQuestionIcon(),
                            candidates,
                            candidates[0]
                        )
                        if (idx < 0) return null
                        val chosen = rawFiles[idx]
                        val (_, refCount) = importedPlainFiles[idx]
                        val newName = renameToModule(chosen.name)

                        if (refCount == 1) {
                            val ans = Messages.showYesNoDialog(
                                project,
                                "`${chosen.name}` is only imported in this file. Rename it to `$newName`?",
                                "Convert className to CSS Module",
                                Messages.getQuestionIcon()
                            )
                            if (ans == Messages.YES) {
                                val renamed = runCatching {
                                    chosen.rename(null, newName)
                                    chosen
                                }.getOrNull()
                                if (renamed != null) return Pair(renamed, false)
                            }
                        }
                        return copyToModule(project, parent, chosen, vf.nameWithoutExtension, sourceExt)
                    }
                    else -> {
                        // 没有找到导入 → 回退到：查找同目录下同名非 module 文件
                        val baseName = vf.nameWithoutExtension
                        val plainFiles = parent.children.filter { child ->
                            PLAIN_EXTS.any { child.name.endsWith(it, ignoreCase = true) } &&
                                    !MODULE_EXTS.any { child.name.endsWith(it, ignoreCase = true) }
                        }

                        if (plainFiles.isNotEmpty()) {
                            val candidates = plainFiles.map { it.name }
                            val idx = Messages.showChooseDialog(
                                project,
                                "No imported CSS found. Would you like to rename one to *.module.*?\n" +
                                        "Choose a file:",
                                "Convert className to CSS Module",
                                Messages.getQuestionIcon(),
                                candidates.toTypedArray(),
                                candidates.firstOrNull() ?: ""
                            )
                            if (idx < 0) return null
                            val chosen = plainFiles[idx]
                            val refCount = countReferences(chosen, project)
                            val newName = renameToModule(chosen.name)

                            if (refCount <= 1) {
                                val renamed = runCatching {
                                    chosen.rename(null, newName)
                                    chosen
                                }.getOrNull()
                                if (renamed != null) return Pair(renamed, false)
                            }
                            return copyToModule(project, parent, chosen, baseName, sourceExt)
                        } else {
                            val newFile = createModuleFile(project, parent, baseName, sourceExt)
                            return if (newFile != null) Pair(newFile, true) else null // 新建
                        }
                    }
                }
            }
            1 -> {
                return Pair(existingModules[0], false) // 已有
            }
            else -> {
                val candidates = existingModules.map { it.name }.toTypedArray()
                val idx = Messages.showChooseDialog(
                    project,
                    "Multiple CSS Module files found. Choose one:",
                    "Convert className to CSS Module",
                    Messages.getQuestionIcon(),
                    candidates,
                    candidates[0]
                )
                if (idx < 0) return null
                return Pair(existingModules[idx], false)
            }
        }
    }

    /**
     * 收集当前 TSX 文件中导入的非 module CSS 文件（import './xxx.less'）。
     * 返回：(VirtualFile, referenceCount)
     */
    private fun collectImportedPlainFiles(
        sourceFile: PsiFile,
        parentDir: VirtualFile
    ): List<Pair<VirtualFile, Int>> {
        val result = mutableListOf<Pair<VirtualFile, Int>>()

        PsiTreeUtil.findChildrenOfType(sourceFile, ES6ImportDeclaration::class.java).forEach { imp ->
            val text = imp.importModuleText ?: return@forEach
            val filePath = text.trim('"', '\'')
            if (filePath.isEmpty()) return@forEach

            // 只处理相对路径导入（./ 或 ../）
            if (!filePath.startsWith("./") && !filePath.startsWith("../")) return@forEach

            val ext = filePath.substringAfterLast('.', "")
            if (ext.lowercase() !in listOf("css", "less", "scss", "sass")) return@forEach

            // 是否已经是 module
            if (filePath.contains(".module.", ignoreCase = true)) return@forEach

            // 解析文件
            val resolved = resolveRelativePath(parentDir, filePath) ?: return@forEach
            if (!resolved.isValid || resolved.isDirectory) return@forEach

            val refCount = countReferences(resolved, sourceFile.project)
            result.add(resolved to refCount)
        }

        return result.distinctBy { it.first }
    }

    /**
     * 统计这个 CSS 文件在当前项目中有多少个导入引用。
     * 大于 1 → 不能重命名，只能复制。
     */
    private fun countReferences(file: VirtualFile, project: Project): Int {
        val psiFile = PsiManager.getInstance(project).findFile(file) ?: return 1
        val scope = GlobalSearchScope.projectScope(project)
        return ReferencesSearch.search(psiFile, scope).findAll().size
    }

    /**
     * 从父目录解析相对路径 './xxx.less' / '../xxx.less' → 得到实际 VirtualFile。
     */
    private fun resolveRelativePath(parentDir: VirtualFile, relativePath: String): VirtualFile? {
        var current = parentDir
        val segments = relativePath.split('/')
        for (seg in segments) {
            when (seg) {
                "." -> continue
                ".." -> current = current.parent ?: continue
                else -> {
                    current = current.findChild(seg) ?: return null
                }
            }
        }
        return if (current.isValid && !current.isDirectory) current else null
    }

    /**
     * 将已有 plain CSS 文件复制一份为 *.module.*，保留原有内容。
     */
    private fun copyToModule(
        project: Project,
        parent: VirtualFile,
        source: VirtualFile,
        fallbackBaseName: String,
        sourceExt: String?
    ): Pair<VirtualFile, Boolean>? {
        val newName = renameToModule(source.name)
        val content = source.contentsToByteArray()
        return runCatching {
            val newFile = parent.createChildData(this, newName)
            newFile.setBinaryContent(content)
            Pair(newFile, false) // 复制，已有内容
        }.getOrElse {
            // 复制失败，fallback 创建空文件
            val baseName = source.nameWithoutExtension
            val newFile = createModuleFile(project, parent, baseName, source.extension ?: sourceExt)
            if (newFile != null) Pair(newFile, true) else null
        }
    }

    private fun createModuleFile(project: Project, parent: VirtualFile, baseName: String, sourceExt: String? = null): VirtualFile? {
        val ext = when (sourceExt?.lowercase()) {
            "less" -> ".module.less"
            "scss" -> ".module.scss"
            "sass" -> ".module.sass"
            else -> ".module.css"
        }
        val newName = "$baseName$ext"
        return runCatching {
            parent.createChildData(this, newName)
        }.getOrNull()
    }

    private fun renameToModule(oldName: String): String {
        for (ext in PLAIN_EXTS) {
            if (oldName.endsWith(ext, ignoreCase = true)) {
                val base = oldName.substring(0, oldName.length - ext.length)
                // 避免双 module
                if (base.endsWith(".module")) return oldName
                return "$base.module$ext"
            }
        }
        // 未知扩展名，直接加 .module
        val dotIdx = oldName.lastIndexOf('.')
        return if (dotIdx >= 0) {
            oldName.substring(0, dotIdx) + ".module" + oldName.substring(dotIdx)
        } else {
            "$oldName.module.css"
        }
    }

    // ================================================================
    // 步骤 4：确保 import 语句存在或更新
    // ================================================================

    /**
     * 确保 TSX 文件有 `import styles from './xxx.module.less'`。
     *
     * 处理三种情况：
     * 1. 已有 import 指向 module 文件 → 直接返回绑定名
     * 2. 有 import 指向原始文件（如 `'./index.less'`）→ 更新为 `import xxx from './index.module.less'`
     * 3. 都没有 → 在文件头部新增
     */
    private fun ensureImportExists(project: Project, file: PsiFile, moduleVf: VirtualFile): String {
        val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return "styles"

        // 先收集所有 import 声明
        val imports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        val relativeModulePath = computeRelativeImportPath(file.virtualFile!!, moduleVf)
        val moduleFileName = moduleVf.name
        // 原始文件名（去掉 .module. 部分）
        val originalFileName = moduleFileName.replace(".module.", ".")

        // 1. 检查是否已有 import 指向该 module 文件
        for (imp in imports) {
            val moduleText = imp.importModuleText ?: continue
            val from = moduleText.trim('"', '\'')
            if (from.endsWith(moduleFileName, ignoreCase = true) ||
                from.contains(moduleFileName.replaceFirst(".module.", ".").replace(".", ""), ignoreCase = true)
            ) {
                val bindings = imp.importedBindings
                val defaultBinding = bindings.firstOrNull()
                return defaultBinding?.name ?: "styles"
            }
        }

        // 2. 查找是否有 import 指向原始文件（如 `import './index.less'`）
        for (imp in imports) {
            val moduleText = imp.importModuleText ?: continue
            val from = moduleText.trim('"', '\'')
            if (from.endsWith(originalFileName, ignoreCase = true)) {
                val importStatement = imp.text
                val newImport = if (importStatement.contains("from")) {
                    // 已经有绑定：import foo from './index.less'
                    // → 保持绑定名，只改路径
                    importStatement.replace(originalFileName, moduleFileName)
                } else {
                    // 纯 side-effect import：import './index.less'
                    // → 改为 import styles from './index.module.less'
                    val relativePath = computeRelativeImportPath(file.virtualFile!!, moduleVf)
                    "import styles from '$relativePath'"
                }

                // 用 document 替换
                WriteCommandAction.writeCommandAction(project, file)
                    .withName("Update CSS Module import")
                    .run<Nothing> {
                        val start = imp.textRange.startOffset
                        val end = imp.textRange.endOffset
                        document.replaceString(start, end, newImport)
                        PsiDocumentManager.getInstance(project).commitDocument(document)
                    }

                // 提取绑定名
                if (newImport.startsWith("import styles")) return "styles"
                val bindingName = newImport.removePrefix("import ").substringBefore(" from").trim()
                return bindingName.ifEmpty { "styles" }
            }
        }

        // 3. 都没有 → 新增 import 语句
        WriteCommandAction.writeCommandAction(project, file)
            .withName("Add CSS Module import")
            .run<Nothing> {
                val firstImport = imports.firstOrNull()
                val insertOffset = if (firstImport != null) {
                    imports.last().textRange.endOffset
                } else {
                    0
                }
                val prefix = if (insertOffset > 0) "\n" else ""
                val importStmt = "import styles from '$relativeModulePath'"
                document.insertString(insertOffset, "$prefix$importStmt\n")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }

        return "styles"
    }

    private fun computeRelativeImportPath(source: VirtualFile, target: VirtualFile): String {
        val sourceParent = source.parent ?: return "./${target.name}"
        val sourcePath = sourceParent.path
        val targetPath = target.path

        if (targetPath.startsWith(sourcePath)) {
            val rel = targetPath.substring(sourcePath.length).trimStart('/')
            return "./$rel"
        }

        // 不同目录 → 计算相对路径
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

    // ================================================================
    // 步骤 5：替换 className 字面量
    // ================================================================

    private fun replaceClassNames(
        project: Project,
        editor: Editor,
        file: PsiFile,
        selStart: Int,
        selEnd: Int,
        classNames: Set<String>,
        importBinding: String
    ) {
        // 收集所有需要替换的 site
        val sites = mutableListOf<ClassNameSite>()

        PsiTreeUtil.findChildrenOfType(file, JSLiteralExpression::class.java).forEach { literal ->
            val textRange = literal.textRange
            if (textRange.startOffset < selStart || textRange.endOffset > selEnd) return@forEach
            val parent = literal.parent ?: return@forEach
            if (!isClassNameAttribute(parent)) return@forEach

            val strValue = literal.stringValue ?: return@forEach
            val parts = strValue.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
            val matched = parts.filter { it in classNames }
            if (matched.isNotEmpty()) {
                sites.add(
                    ClassNameSite(
                        literal = literal,
                        value = strValue,
                        startOffset = textRange.startOffset,
                        endOffset = textRange.endOffset
                    )
                )
            }
        }

        // 如果 PSI 没有找到，回退到文本搜索
        if (sites.isEmpty()) {
            val selectedText = file.text.substring(selStart, selEnd)
            val textPattern = Pattern.compile("""className\s*=\s*["']([^"']*)["']""")
            val matcher = textPattern.matcher(selectedText)
            while (matcher.find()) {
                val value = matcher.group(1).trim()
                if (value.isNotBlank()) {
                    val parts = value.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
                    val matched = parts.filter { it in classNames }
                    if (matched.isNotEmpty()) {
                        val absStart = selStart + matcher.start(1)
                        val absEnd = selStart + matcher.end(1)
                        sites.add(
                            ClassNameSite(
                                literal = file.findElementAt(absStart) ?: file,
                                value = value,
                                startOffset = absStart - 1, // 包含引号
                                endOffset = absEnd + 1
                            )
                        )
                    }
                }
            }
        }

        // 执行替换
        if (sites.isEmpty()) return

        WriteCommandAction.writeCommandAction(project, file)
            .withName("Convert className to CSS Module")
            .run<Nothing> {
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run
                // 从后往前替换，避免 offset 漂移
                val sortedSites = sites.sortedByDescending { it.startOffset }
                for (site in sortedSites) {
                    val parts = site.value.split(CLASS_NAME_SPLIT).filter { it.isNotBlank() }
                    val matched = parts.filter { it in classNames }

                    if (matched.size == 1) {
                        // className="foo" → className={styles.foo}
                        val name = matched.first()
                        val kebab = Util.camelToKebab(name)
                        val access = if (kebab.contains("-")) {
                            "$importBinding[\"$kebab\"]"
                        } else {
                            "$importBinding.$name"
                        }
                        // 替换整个属性值部分
                        val attrStart = findAttributeStart(file, site.startOffset)
                        val attrEnd = findAttributeEnd(file, site.endOffset)
                        if (attrStart >= 0 && attrEnd > attrStart) {
                            val attrText = document.getText(com.intellij.openapi.util.TextRange(attrStart, attrEnd))
                            val newAttr = attrText.replaceFirst(
                                Regex("""["'].*?["']"""),
                                "{$access}"
                            )
                            document.replaceString(attrStart, attrEnd, newAttr)
                        }
                    } else {
                        // 多个 class：className="foo bar" → className={clsx(styles.foo, styles.bar)}
                        val accessParts = matched.map { name ->
                            val kebab = Util.camelToKebab(name)
                            if (kebab.contains("-")) {
                                "$importBinding[\"$kebab\"]"
                            } else {
                                "$importBinding.$name"
                            }
                        }
                        val clsxArgs = accessParts.joinToString(", ")
                        val attrStart = findAttributeStart(file, site.startOffset)
                        val attrEnd = findAttributeEnd(file, site.endOffset)
                        if (attrStart >= 0 && attrEnd > attrStart) {
                            val attrText = document.getText(com.intellij.openapi.util.TextRange(attrStart, attrEnd))
                            val newAttr = attrText.replaceFirst(
                                Regex("""["'].*?["']"""),
                                "{clsx($clsxArgs)}"
                            )
                            document.replaceString(attrStart, attrEnd, newAttr)
                        }
                    }
                }
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
    }

    private fun findAttributeStart(file: PsiFile, literalStart: Int): Int {
        val text = file.text
        var pos = literalStart - 1
        while (pos >= 0 && text[pos] != '=' && text[pos] != ' ' && text[pos] != '\t' && text[pos] != '\n') {
            pos--
        }
        // 找到 className 的开头
        while (pos >= 0 && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n')) {
            pos--
        }
        // 从 = 往前找属性名
        val eqPos = text.indexOf('=', pos)
        if (eqPos < 0) return -1
        // 找到 className 的开头
        var nameStart = eqPos - 1
        while (nameStart >= 0 && text[nameStart] != ' ' && text[nameStart] != '\t' && text[nameStart] != '\n' && text[nameStart] != '<') {
            nameStart--
        }
        return nameStart + 1
    }

    private fun findAttributeEnd(file: PsiFile, literalEnd: Int): Int {
        val text = file.text
        var pos = literalEnd
        // 跳过引号闭合
        if (pos < text.length && (text[pos] == '"' || text[pos] == '\'')) pos++
        // 跳过 > 或 / 或空格
        return pos
    }

    // ================================================================
    // 步骤 6：在 CSS Module 文件中追加规则
    // ================================================================

    private fun appendCssRules(project: Project, moduleVf: VirtualFile, classNames: Set<String>) {
        WriteCommandAction.writeCommandAction(project)
            .withName("Add CSS Module rules")
            .run<Nothing> {
                val psiFile = PsiManager.getInstance(project).findFile(moduleVf) ?: return@run
                val document = FileDocumentManager.getInstance().getDocument(moduleVf) ?: return@run

                val sb = StringBuilder()
                // 如果文件已有内容，先加空行
                if (document.textLength > 0 && !document.text.endsWith("\n")) {
                    sb.append("\n")
                }

                for (name in classNames) {
                    val kebab = if (name.contains("-")) name else Util.camelToKebab(name)
                    sb.append("\n.$kebab {\n\n}\n")
                }

                val insertOffset = document.textLength
                document.insertString(insertOffset, sb.toString())
                FileDocumentManager.getInstance().saveDocument(document)
            }
    }
}