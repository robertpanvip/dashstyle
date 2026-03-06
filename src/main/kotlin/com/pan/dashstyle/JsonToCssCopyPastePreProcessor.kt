package com.pan.dashstyle

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.intellij.codeInsight.editorActions.CopyPastePreProcessor
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.RawText
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFile
import com.intellij.psi.xml.XmlTag
import org.jetbrains.annotations.NotNull

class JsonToCssCopyPastePreProcessor : CopyPastePreProcessor {

    private val gson = Gson()

    override fun preprocessOnCopy(
        file: PsiFile?,
        startOffsets: IntArray?,
        endOffsets: IntArray?,
        text: String?
    ): String? {
        // 我们不处理复制操作，返回 null 表示不干预
        return null
    }

    @NotNull
    override fun preprocessOnPaste(
        project: Project?,
        file: PsiFile?,
        editor: Editor?,
        text: String?,
        rawText: RawText?
    ): String {
        if (file == null || editor == null || text == null) {
            return text ?: ""
        }

        if (!isSupportedContext(editor, file)) {
            return text
        }

        val trimmed = text.trim()
        if (!looksLikeJsonStyleObject(trimmed)) {
            return text
        }
        return convertJsonToCss(trimmed)
    }
    // 支持 .css / .less / .scss /.styl 文件
    private val supportedExtensions = setOf(".css", ".less", ".scss", ".styl")
    private fun isSupportedContext(editor: Editor, file: PsiFile): Boolean {
        val virtualFile = file.virtualFile ?: return false
        val fileName = virtualFile.name.lowercase()

        if (supportedExtensions.any { fileName.endsWith(it) }) {
            return true
        }

        // 支持 .vue 文件中的 <style> 标签内部
        if (fileName.endsWith(".vue")) {
            val offset = editor.caretModel.offset
            val elementAtCaret = file.findElementAt(offset) ?: return false

            // 向上查找最近的 XmlTag，看是否是 <style>
            var current: com.intellij.psi.PsiElement? = elementAtCaret
            while (current != null) {
                if (current is XmlTag) {
                    val tagName = current.name.lowercase()
                    if (tagName == "style") {
                        // 可选：进一步检查是否是 <style lang="scss"> 或 lang="less" 等
                        // 但这里简单判断 name 即可
                        return true
                    }
                    // 如果已经到了 <template> 或 <script> 就不要继续向上找了
                    if (tagName == "template" || tagName == "script") {
                        return false
                    }
                }
                current = current.parent
            }
        }

        return false
    }

    private fun looksLikeJsonStyleObject(text: String): Boolean {
        val trimmed = text.trim()
        if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
            return false
        }
        try {
            gson.fromJson(trimmed, JsonObject::class.java)
            return true
        } catch (e: JsonParseException) {
            return false
        }
    }

    private fun convertJsonToCss(jsonStr: String): String {
        val obj = try {
            gson.fromJson(jsonStr, JsonObject::class.java)
        } catch (e: Exception) {
            return jsonStr  // 解析失败时原样返回，避免破坏用户输入
        }

        val lines = mutableListOf<String>()

        for ((key, element) in obj.entrySet()) {
            // 假设值是字符串；如果 JSON 中有数字/布尔等，可根据需要扩展
            val valueStr = when {
                element.isJsonPrimitive -> element.asString
                else -> element.toString()  // fallback
            }

            val kebabKey = key.replace(Regex("([a-z])([A-Z])"), "$1-$2").lowercase()

            // 可选增强：纯数字值自动加 px（排除已有单位或百分比等）
            val finalValue = if (valueStr.matches(Regex("^\\d+$")) &&
                valueStr != "0" &&
                !valueStr.contains(Regex("[a-zA-Z%]+"))
            ) {
                "${valueStr}px"
            } else {
                valueStr
            }

            lines.add("  $kebabKey: $finalValue;")
        }

        // 每行前面加两个空格（常见缩进），最后加换行
        return if (lines.isNotEmpty()) {
            lines.joinToString("\n") + "\n"
        } else {
            ""
        }
    }
}