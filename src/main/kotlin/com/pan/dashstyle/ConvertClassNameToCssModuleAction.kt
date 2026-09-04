package com.pan.dashstyle

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSLiteralExpression
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute
import com.intellij.lang.css.CSSLanguage
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
        val moduleFile = resolveModuleFile(project, file) ?: return

        // 4. 生成 import（如果缺失）
        val importBinding = ensureImportExists(project, file, moduleFile)

        // 5. 替换选中区域中的 className 字面量
        replaceClassNames(project, editor, file, selStart, selEnd, uniqueNames, importBinding)

        // 6. 在 CSS Module 文件中追加规则
        appendCssRules(project, moduleFile, uniqueNames)

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

    private fun resolveModuleFile(project: Project, sourceFile: PsiFile): VirtualFile? {
        val vf = sourceFile.virtualFile ?: return null
        val parent = vf.parent ?: return null
        val sourceExt = vf.extension?.lowercase()

        // 1. 查找同目录下已有的 *.module.* 文件
        val existingModules = parent.children.filter { child ->
            MODULE_EXTS.any { child.name.endsWith(it, ignoreCase = true) }
        }.filter { it.isValid && !it.isDirectory }

        when (existingModules.size) {
            0 -> {
                // 2. 没有已有的 module 文件 → 查找同名的 .less / .css（非 module）
                val baseName = vf.nameWithoutExtension
                val plainFiles = parent.children.filter { child ->
                    PLAIN_EXTS.any { child.name.endsWith(it, ignoreCase = true) } &&
                            !MODULE_EXTS.any { child.name.endsWith(it, ignoreCase = true) }
                }

                if (plainFiles.isNotEmpty()) {
                    // 2a. 有同名/其他 .less/.css 文件 → 询问是否重命名为 .module.less
                    val candidates = plainFiles.map { it.name }
                    val idx = Messages.showChooseDialog(
                        project,
                        "No CSS Module file found. Would you like to rename one to *.module.*?\n" +
                                "Choose a file to rename:",
                        "Convert className to CSS Module",
                        Messages.getQuestionIcon(),
                        candidates.toTypedArray(),
                        candidates.firstOrNull() ?: ""
                    )
                    if (idx < 0) return null
                    val chosen = plainFiles[idx]
                    val newName = renameToModule(chosen.name)
                    return runCatching {
                        chosen.rename(null, newName)
                        chosen
                    }.getOrElse {
                        // 重命名失败，直接创建新文件
                        createModuleFile(project, parent, baseName, sourceExt)
                    }
                } else {
                    // 2b. 直接创建新的 module 文件
                    return createModuleFile(project, parent, baseName, sourceExt)
                }
            }
            1 -> {
                // 使用已有的 module 文件
                return existingModules[0]
            }
            else -> {
                // 多个 module 文件 → 让用户选择
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
                return existingModules[idx]
            }
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
    // 步骤 4：确保 import 语句存在
    // ================================================================

    private fun ensureImportExists(project: Project, file: PsiFile, moduleVf: VirtualFile): String {
        // 检查是否已有 import
        val existingImports = PsiTreeUtil.findChildrenOfType(file, ES6ImportDeclaration::class.java)
        for (imp in existingImports) {
            val moduleText = imp.importModuleText ?: continue
            val from = moduleText.trim('"', '\'')
            if (from.endsWith(moduleVf.name, ignoreCase = true) ||
                from.contains(moduleVf.nameWithoutExtension)
            ) {
                // 已经有 import → 提取绑定名
                val bindings = imp.importedBindings
                val defaultBinding = bindings.firstOrNull()
                return defaultBinding?.name ?: "styles"
            }
        }

        // 没有 import → 生成
        val relativePath = computeRelativeImportPath(file.virtualFile!!, moduleVf)
        val importText = "import styles from '$relativePath'"

        // 在文件头部插入 import（第一个 import 声明之后，或文件开头）
        WriteCommandAction.writeCommandAction(project, file)
            .withName("Add CSS Module import")
            .run<Nothing> {
                val document = PsiDocumentManager.getInstance(project).getDocument(file) ?: return@run
                val firstImport = existingImports.firstOrNull()
                val insertOffset = if (firstImport != null) {
                    // 在最后一个 import 之后插入
                    val lastImport = existingImports.last()
                    lastImport.textRange.endOffset
                } else {
                    // 在文件开头插入
                    0
                }
                val prefix = if (insertOffset > 0) "\n" else ""
                document.insertString(insertOffset, "$prefix$importText\n")
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }

        return "styles"
    }

    /**
     * 计算相对导入路径（从 sourceFile 到 moduleFile）。
     * 示例：'./Button.module.css'
     */
    private fun computeRelativeImportPath(source: VirtualFile, target: VirtualFile): String {
        val sourceParent = source.parent ?: return "./${target.name}"
        val sourcePath = sourceParent.path
        val targetPath = target.path

        // 如果 target 在 sourceParent 下
        if (targetPath.startsWith(sourcePath)) {
            val rel = targetPath.substring(sourcePath.length).trimStart('/')
            return "./$rel"
        }

        // 不同目录
        val sourceSegments = sourcePath.split('/').filter { it.isNotBlank() }
        val targetSegments = targetPath.split('/').filter { it.isNotBlank() }

        // 找公共前缀
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