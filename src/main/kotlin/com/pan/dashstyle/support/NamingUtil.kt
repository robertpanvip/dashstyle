package com.pan.dashstyle.support

/**
 * CSS class 命名转换：camelCase ↔ kebab-case。
 * 从 Util.kt 拆出，职责聚焦于命名转换。
 */
object NamingUtil {

    fun kebabToCamel(name: String): String {
        if ('-' !in name) return name
        return buildString(name.length) {
            var nextUp = false
            for ((idx, ch) in name.withIndex()) {
                if (ch == '-') {
                    if (idx == 0) continue
                    nextUp = true
                    continue
                }
                append(if (nextUp) ch.uppercaseChar() else ch)
                nextUp = false
            }
        }
    }

    fun camelToKebab(name: String): String {
        // 特例 1：全大写字母（ABC / HTTP / ID）→ 每字符间插 '-'，保留原大写
        if (name.length >= 2 && name.all { it.isLetter() && it.isUpperCase() }) {
            return name.mapIndexed { i, c -> if (i == 0) "$c" else "-$c" }.joinToString("")
        }
        // 特例 2：不含大写 → 原样
        var hasUpper = false
        for (ch in name) if (ch.isUpperCase()) { hasUpper = true; break }
        if (!hasUpper) return name

        return buildString(name.length + 4) {
            for ((idx, ch) in name.withIndex()) {
                when {
                    ch.isUpperCase() -> {
                        val boundary = when {
                            idx == 0 -> false
                            !name[idx - 1].isUpperCase() -> true
                            idx + 1 < name.length && name[idx + 1].isLowerCase() -> true
                            else -> false
                        }
                        if (boundary) append('-')
                        append(ch.lowercaseChar())
                    }
                    else -> append(ch)
                }
            }
        }.removePrefix("-")
    }
}
