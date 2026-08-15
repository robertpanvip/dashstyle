package com.pan.dashstyle

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.TreeMap

/**
 * 「反射嗅探器」—— 纯 JUnit5 Jupiter（能被 `gradle test` 直接发现运行，不需要 vintage）。
 *
 * 目标：跑在 WS-2025.3 SDK + intellijPlatform testFramework(Platform) 沙箱 classpath 下，
 *       把 DashStyle 生产代码和集成测试会用到的「真实签名」反射扫出来，
 *       落地 JSON 到 build/test-results/dashstyle-sniff.json。
 *
 * 拿到这份真实签名后，我们就能把生产代码从「动态代理绕一圈」改成「按真实签名静态绑定」，
 * 也能把 DashStyleIntegrationTest 里那些因签名不匹配而不能用的 API 改成正确的。
 *
 * 运行：
 *   gradle --init-script _local_init.gradle.kts test --tests "com.pan.dashstyle.ReflectorSnifferTest"
 */
class ReflectorSnifferTest {

    data class MethodSig(
        val name: String,
        val returnType: String,
        val parameters: List<String>, // "Class.forName friendly" 类名
        val modifiers: String,       // e.g. "public abstract"
        val exceptions: List<String>
    )

    data class ClassSig(
        val className: String,
        val type: String, // "interface" / "class" / "abstract class"
        val superclass: String?,
        val interfaces: List<String>,
        val constructors: List<MethodSig>,
        val declaredMethods: List<MethodSig>,
        val allPublicMethods: List<MethodSig>
    ) {
        val found = true
    }

    data class SniffReport(
        val generatedAt: String,
        val javaVersion: String,
        val classpathEntries: Int,
        val classes: Map<String, Any>, // className → ClassSig | {found:false, reason}
        val extensionPoints: Map<String, Any>,
        val constants: Map<String, Any>
    )

    @Test
    @DisplayName("Sniff real signatures from WS-2025.3 SDK sandbox")
    fun `sniff real SDK signatures and write json`() {
        val outDir = Path.of("build/test-results").toAbsolutePath()
        Files.createDirectories(outDir)
        val outFile = outDir.resolve("dashstyle-sniff.json")

        val report = SniffReport(
            generatedAt = java.time.Instant.now().toString(),
            javaVersion = System.getProperty("java.version") + " / " + System.getProperty("java.vendor"),
            classpathEntries = runCatching {
                (Thread.currentThread().contextClassLoader as? java.net.URLClassLoader)?.urLs?.size
                    ?: ClassLoader.getSystemResources("").toList().size
            }.getOrDefault(-1),
            classes = sniffClasses(),
            extensionPoints = sniffExtensionPoints(),
            constants = sniffConstants()
        )

        val json = toJson(report)
        Files.write(outFile, json.toByteArray(Charsets.UTF_8),
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
        println("\n===== REFLECTOR SNIFFER DONE: $outFile =====\n")
        println(json)
        // 保证至少写出有效文件 + 最核心的两个类（HighlightVisitor + BasePlatformTestCase）有被探测记录
        Assertions.assertTrue(outFile.toFile().length() > 1000, "sniff json 必须非空")
        Assertions.assertTrue(
            report.classes.keys.any { it.contains("HighlightVisitor") },
            "必须探测到至少一个 HighlightVisitor 变体，实际 keys=${report.classes.keys}"
        )
    }

    // ========================================================================
    // 候选列表：所有我们关心的类名（包名按"从新到旧/从可能到兜底"顺序列出）
    // ========================================================================
    private val candidateClassNames: List<String> = listOfNotNull(
        // A. HighlightVisitor / 类似接口：WebStorm-2025.3 里实际位置
        "com.intellij.codeInsight.daemon.impl.HighlightVisitor",      // 老位置（2024.x-）
        "com.intellij.codeHighlighting.HighlightVisitor",             // 新位置候选
        "com.intellij.codeInsight.daemon.HighlightVisitor",           // 兜底
        "com.intellij.highlightVisitor.HighlightVisitor",             // 扩展点名对齐的极端情况
        // B. TextEditorHighlightingPassFactory：和 HighlightVisitor 常配套
        "com.intellij.codeHighlighting.TextEditorHighlightingPassFactory",
        // C. TestFixture 相关
        "com.intellij.testFramework.fixtures.BasePlatformTestCase",
        "com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase",
        "com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase",
        "com.intellij.testFramework.fixtures.CodeInsightTestFixture",
        // D. Daemon / highlight info（我们集成测试要读字段）
        "com.intellij.codeInsight.daemon.impl.HighlightInfo",
        "com.intellij.lang.annotation.AnnotationHolder",
        // E. 扩展点管理
        "com.intellij.openapi.extensions.ExtensionPointName",
        "com.intellij.openapi.extensions.Extensions",
        "com.intellij.openapi.extensions.ProjectExtensionPoints",
        // F. IDE 启动
        "com.intellij.openapi.startup.StartupActivity",
        "com.intellij.ide.ApplicationLoadListener",
        // G. IntentionAction（DashStyleIntegrationTest 要 filterAvailableIntentions）
        "com.intellij.codeInsight.intention.IntentionAction",
        "com.intellij.codeInsight.intention.PsiElementBaseIntentionAction",
        // H. PsiTreeUtil（已在使用，顺便兜底版本差异）
        "com.intellij.psi.util.PsiTreeUtil",
        // I. LineMarker / gutter 渲染（WebStorm 颜色预览式 gutter 色块）
        "com.intellij.codeInsight.daemon.LineMarkerProvider",
        "com.intellij.codeInsight.daemon.RelatedItemLineMarkerProvider",
        "com.intellij.codeInsight.daemon.LineMarkerInfo",
        "com.intellij.codeInsight.daemon.GutterIconRenderer"
    )

    private fun sniffClasses(): Map<String, Any> {
        val cl = Thread.currentThread().contextClassLoader ?: ReflectorSnifferTest::class.java.classLoader
        val result = TreeMap<String, Any>()
        for (name in candidateClassNames) {
            try {
                val c = Class.forName(name, false, cl)
                result[name] = describeClass(c)
            } catch (t: Throwable) {
                val reason = when (t) {
                    is ClassNotFoundException -> "ClassNotFoundException"
                    is NoClassDefFoundError -> "NoClassDefFoundError: ${t.message}"
                    is LinkageError -> "LinkageError: ${t.message}"
                    else -> "${t.javaClass.simpleName}: ${t.message}"
                }
                result[name] = mapOf("found" to false, "reason" to reason)
            }
        }
        // 再加一个 wildcard 扫描：找所有名字含 HighlightVisitor 的类
        try {
            val wildcard = TreeMap<String, Any>()
            val cl2 = Thread.currentThread().contextClassLoader
            // 沙箱内用 java.class.path + 当前 classloader 名字粗略扫（不会扫所有 jar 内部，但能捕获已经 loaded 的）
            for (pkg in listOf("com.intellij.codeInsight.daemon.impl",
                                "com.intellij.codeHighlighting",
                                "com.intellij.codeInsight.daemon")) {
                runCatching {
                    // ClassLoader 不支持直接 enumerate classes：用 Package.getPackages() 粗略 + 已候选列表
                    Package.getPackage(pkg)
                }
            }
            // 已在候选列表里记录过，这里直接继承
        } catch (_: Throwable) { }
        return result
    }

    private fun describeClass(c: Class<*>): ClassSig {
        val type = when {
            c.isInterface -> "interface"
            Modifier.isAbstract(c.modifiers) -> "abstract class"
            else -> "class"
        }
        return ClassSig(
            className = c.name,
            type = type,
            superclass = c.superclass?.name,
            interfaces = c.interfaces.map { it.name }.sorted(),
            constructors = c.constructors.map { con ->
                MethodSig(
                    name = "<init>",
                    returnType = "void",
                    parameters = con.parameterTypes.map { it.name },
                    modifiers = Modifier.toString(con.modifiers),
                    exceptions = con.exceptionTypes.map { it.name }
                )
            }.sortedBy { it.parameters.size },
            declaredMethods = c.declaredMethods.map(::describeMethod).sortedBy { it.name + "/" + it.parameters.size },
            allPublicMethods = c.methods.map(::describeMethod).sortedBy { it.name + "/" + it.parameters.size }
        )
    }

    private fun describeMethod(m: Method): MethodSig =
        MethodSig(
            name = m.name,
            returnType = m.returnType.name,
            parameters = m.parameterTypes.map { it.name },
            modifiers = Modifier.toString(m.modifiers),
            exceptions = m.exceptionTypes.map { it.name }
        )

    private fun sniffExtensionPoints(): Map<String, Any> {
        val result = TreeMap<String, Any>()
        // 查几个 DashStyle 用的扩展点名，在 WS-2025.3 里真实 ExtensionPointName<T> 的 T 是什么
        val epNames = listOf(
            "com.intellij.highlightVisitor",
            "com.intellij.annotator",
            "com.intellij.lang.documentationProvider",
            "com.intellij.codeInsight.intention.intentionAction"
        )
        for (ep in epNames) {
            result[ep] = runCatching {
                val cl = Thread.currentThread().contextClassLoader ?: ReflectorSnifferTest::class.java.classLoader
                val epnCls = Class.forName("com.intellij.openapi.extensions.ExtensionPointName", false, cl)
                // static <T> ExtensionPointName<T> create(String name)
                val createM = epnCls.methods.firstOrNull {
                    it.name == "create" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
                }
                if (createM != null) {
                    val epn = createM.invoke(null, ep)
                    // epn.name / epn.extensionClass（如果存在）
                    val extensionClass = runCatching {
                        epn.javaClass.methods.firstOrNull { it.name == "getExtensionClass" && it.parameterCount == 0 }
                            ?.invoke(epn)?.toString()
                    }.getOrNull()
                    val className = epn.javaClass.methods.firstOrNull { it.name == "getName" && it.parameterCount == 0 }
                        ?.invoke(epn)?.toString()
                    mapOf(
                        "found" to true,
                        "name" to className,
                        "extensionClass" to extensionClass,
                        "pointToString" to epn.toString()
                    )
                } else {
                    mapOf("found" to false, "reason" to "ExtensionPointName.create(String) not found")
                }
            }.getOrElse { t ->
                mapOf("found" to false, "reason" to (t.message ?: t.javaClass.simpleName))
            }
        }
        return result
    }

    private fun sniffConstants(): Map<String, Any> {
        val result = TreeMap<String, Any>()
        // 查几个 JBColor theme key 是否真的在当前 SDK 下存在（DashStyleDocumentationProvider 里用的）
        val probes = listOf(
            "Hyperlink.linkForeground",
            "Attributes.attributeForeground",
            "Label.foreground",
            "Label.disabledForeground",
            "Separator.separatorColor",
            "Panel.background",
            "Viewport.background",
            "EditorColors.WEAK_WARNING_ATTRIBUTES",
            "Gutter.foreground"
        )
        val cl = Thread.currentThread().contextClassLoader ?: ReflectorSnifferTest::class.java.classLoader
        val jbColorCls = runCatching { Class.forName("com.intellij.ui.JBColor", false, cl) }.getOrNull()
        for (key in probes) {
            result[key] = runCatching {
                val m = jbColorCls?.methods?.firstOrNull {
                    it.name == "namedColor" &&
                            it.parameterCount == 2 &&
                            it.parameterTypes[0] == String::class.java &&
                            it.parameterTypes[1] == java.awt.Color::class.java
                }
                if (m != null) {
                    val sample = java.awt.Color(0x7F, 0x7F, 0x7F)
                    val c = m.invoke(null, key, sample) as? java.awt.Color
                    mapOf("namedColor_resolvable" to true, "rgb" to ("#%06x".format(c?.rgb ?: 0 and 0xFFFFFF)))
                } else {
                    mapOf("namedColor_resolvable" to false, "reason" to "JBColor.namedColor(String,Color) 方法未找到")
                }
            }.getOrElse { t ->
                mapOf("namedColor_resolvable" to false, "reason" to (t.message ?: t.javaClass.simpleName))
            }
        }
        return result
    }

    // --------------------------------------------------------------
    // 极简 JSON 序列化（避免引入 jackson，DashStyle 没这个依赖）
    // --------------------------------------------------------------
    private fun toJson(o: Any?): String = buildString { appendJson(o) }

    private fun StringBuilder.appendJson(o: Any?) {
        when (o) {
            null -> append("null")
            is Boolean, is Number -> append(o.toString())
            is CharSequence -> append('"').append(escape(o.toString())).append('"')
            is Enum<*> -> append('"').append(escape(o.name)).append('"')
            is Map<*, *> -> {
                append('{')
                var first = true
                for ((k, v) in o.entries) {
                    if (!first) append(','); first = false
                    append('"').append(escape(k.toString())).append("\":")
                    appendJson(v)
                }
                append('}')
            }
            is Iterable<*> -> {
                append('[')
                var first = true
                for (e in o) {
                    if (!first) append(','); first = false
                    appendJson(e)
                }
                append(']')
            }
            is Array<*> -> appendJson(o.toList())
            is SniffReport -> appendJson(
                mapOf(
                    "generatedAt" to o.generatedAt,
                    "javaVersion" to o.javaVersion,
                    "classpathEntries" to o.classpathEntries,
                    "classes" to o.classes,
                    "extensionPoints" to o.extensionPoints,
                    "constants" to o.constants
                )
            )
            is ClassSig -> appendJson(
                mapOf(
                    "found" to true,
                    "className" to o.className,
                    "type" to o.type,
                    "superclass" to o.superclass,
                    "interfaces" to o.interfaces,
                    "constructors" to o.constructors.map(::mToMap),
                    "declaredMethods" to o.declaredMethods.map(::mToMap),
                    "allPublicMethods" to o.allPublicMethods.map(::mToMap)
                )
            )
            is MethodSig -> appendJson(mToMap(o))
            else -> append('"').append(escape(o.toString())).append('"')
        }
    }

    private fun mToMap(o: MethodSig): Map<String, Any> =
        mapOf(
            "name" to o.name,
            "returnType" to o.returnType,
            "parameters" to o.parameters,
            "modifiers" to o.modifiers,
            "exceptions" to o.exceptions
        )

    private fun escape(s: String): String =
        s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
}
