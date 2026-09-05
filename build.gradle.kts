// 仓库/镜像策略由 `_local_init.gradle.kts`（--init-script 注入）统一管理：
//  - pluginManagement / allprojects.repositories 的 mavenCentral 与 GPP URL 自动改写成腾讯镜像
//  - 追加 JetBrains releases/snapshots 专用仓（专用于 bundledPlugin 与 webstorm SDK）
// 因此 build.gradle.kts 里只写常规的 repositories 占位（mavenCentral + intellijPlatform.defaultRepositories）
// 注：Gradle Kotlin DSL 里 `java` 会解析成 JavaPluginExtension 访问器，遮蔽 java.* 包名，
//     所以文件顶部必须显式 import 需要的 JDK 类。
import java.io.IOException
import java.nio.file.Files

plugins {
    id("java")
    // WebStorm-2025.3 自身是 Kotlin 2.2 metadata。plugin 端使用 2.1.0 编译器，
    // 配合 freeCompilerArgs `-Xsuppress-version-warnings` 容忍更老的 meta 读入。
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.pan"
version = "1.3.1"

repositories {
    // 这两个声明会被 init-script 里的 URL 改写落到腾讯镜像
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        webstorm("2025.3")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
        // ===== 第一类：编译 classpath 必需（主代码直接 import 了它们的类，缺了编译直接失败） =====
        // JavaScript：主代码 10+ 文件 import com.intellij.lang.javascript.psi.*（JSReferenceExpression 等），
        // plugin.xml 也是硬 <depends>JavaScript</depends>。
        bundledPlugin("JavaScript")
        // CSS：主代码 12+ 文件 import com.intellij.psi.css.*（CssRuleset / StylesheetFile 等），
        // plugin.xml 是硬 <depends>com.intellij.css</depends>。
        bundledPlugin("com.intellij.css")

        // ===== 第二类：仅测试沙箱需要（主/测试代码都不直接引用它们的类，编译不需要；
        // 只为沙箱具备对应语言/框架的解析能力。用户安装层面的"可选"由 plugin.xml 的
        // <depends optional="true"> 声明，与这里无关；Gradle 依赖无法按 source set 拆分，
        // "仅测试需要"也只能声明在此） =====
        // Vue：沙箱不装则 .vue 解析为 PsiPlainTextFileImpl，RealVueFileIntegrationTest 与
        //      DashStyleIntegrationTest 的 vue 场景全部失效。plugin.xml 里声明为 optional。
        bundledPlugin("org.jetbrains.plugins.vue")
        // PostCSS：Vue 插件 intellij.vuejs.backend 模块（VueFileType/VueParserDefinition 所在 jar）
        // 的依赖，缺了它该模块被禁用，.vue 仍会解析为纯文本
        //（沙箱日志证据："Module intellij.vuejs.backend is not enabled because dependency
        //  org.intellij.plugins.postcss is not available"）。
        bundledPlugin("org.intellij.plugins.postcss")
        // Angular：纯沙箱能力探针（ProbeVueAngularSandboxTest 的 @Component / Angular 模板 PSI）。
        // plugin.xml 里声明为 optional；主代码对 Angular 零引用。
        bundledPlugin("AngularJS")
        // LESS：主代码只按文件扩展名/语言 ID 字符串识别 .less（零类引用，编译不需要）；
        // 但 LessMixinExpansionTest 等测试需要沙箱有 LESS 语言支持，否则 .less 解析为纯文本。
        bundledPlugin("org.jetbrains.plugins.less")
    }

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5:2.1.0")
    // ============= IDE 集成测试依赖（JUnit4/5 双栈兼容） =============
    // BasePlatformTestCase / LightPlatformCodeInsightFixtureTestCase 是 JUnit4 风格；
    // Gradle 这边已经 useJUnitPlatform()（JUnit5 platform），所以需要 vintage 引擎
    // 来桥接 JUnit4 的 @Test，否则 JUnit4 风格的集成测试永远不会被执行。
    // 注意：不要再加 kotlin-test-junit，因为它和 kotlin-test-junit5 同时声明了
    //   capability 'org.jetbrains.kotlin:kotlin-test-framework-impl'，Gradle 会报
    //   "Cannot select module with conflict on capability"。
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.10.2")
}

// 关键：测试运行期必须用 WebStorm-2025.3 自带的 Kotlin 2.2 stdlib，而不是插件端的 2.1.0。
// 若把插件 2.1.0 的 kotlin-stdlib 放到 test runtime 最前面，IDE/平台代码（按 2.2 编译）
// 调用 SequencesKt.sequenceOf(Object) 等 2.2 新增/变更签名时就会 NoSuchMethodError，
// 整条集成测试被 TestLogger 当成失败（fixed 的 kotlin.stdlib.default.dependency=true 会把它带上）。
// test 编译 classpath 仍保留 stdlib（Kotlin 测试代码需要），只在 runtime 排除，改由 IDE 提供。
configurations {
    testRuntimeClasspath {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }
        changeNotes = """
<h3>1.3.1</h3>
<ul>
    <li>🔧 修复 <code>CreateMissingCssClassIntention</code>（缺失类快速创建）在 Intention 预览触发下可能出现的死锁：通过声明 <code>startInWriteAction = true</code>，让 <code>invoke()</code> 由框架在写锁内调用，避免 preview 的读锁与写锁竞争导致 UI 卡死</li>
    <li>📝 同步插件市场描述、README、FEATURES 说明到最新功能状态（补齐 member access、Inline 抽取、批量迁移等）</li>
</ul>
<h3>1.3.0</h3>
<ul>
    <li>🗑️ 移除 Flex/Grid 布局可视化预览（gutter LineMarker + 交互弹窗）及对应测试</li>
    <li>🗑️ 移除阴影预览（box-shadow / text-shadow gutter 预览）及对应测试</li>
    <li>🗑️ 移除全局 HighlightVisitor（StaticGlobalHighlightVisitor），避免干扰 TypeScript/JavaScript 高亮</li>
    <li>🔧 Java target 升级至 21；修复选择器展开缓存的失效范围，外部修改文件后不再被覆盖</li>
</ul>
    """.trimIndent()
    }
}

// ============ 本地低内存沙箱开关 DASHSTYLE_LOW_MEM（仅本地携带；线上/CI 默认不带，零影响） ============
// 用法：DASHSTYLE_LOW_MEM=1 gradle test
// 背景：本地沙箱 ~6Gi cgroup 无 swap，完整默认配置（3g daemon + 独立 Kotlin daemon + 全量并行任务）
// 叠加测试执行器后可能触发 cgroup OOM killer（daemon 进程被杀，报 "daemon disappeared"）。
// 开关生效内容（不落盘、不进 CI）：
//   1) max-workers 降为 2：限制并行任务数，削平编译+测试叠加的内存峰值；
//   2) Kotlin 编译改为 in-process（Gradle 属性 kotlin.compiler.execution.strategy=in-process）：
//      不再拉起独立 Kotlin daemon（实测常驻 ~800MB+），编译直接在 Gradle daemon 进程内完成
//     （本项目体量小，daemon 堆足够；已实证注入后 compileKotlin 全程无 KotlinCompileDaemon）。
//      注入方式是向 project.ext 写属性 —— KGP 的 PropertiesBuildService 解析该属性时 ext 优先
//      于 gradle 属性，且策略 Provider 惰性求值（任务配置期），build 脚本阶段注入即可生效。
//     （settings 阶段的 startParameter.setProjectProperties 不会流入 project properties，勿走那条路）
//   3) daemon 堆体检：org.gradle.jvmargs（daemon 堆）只能在 daemon 启动前生效（GRADLE_OPTS /
//      命令行 -D / gradle.properties），build 脚本无法热改 —— 若检测到当前 daemon 仍是大堆，
//      给出可选的降堆重启指引（见 gradle.properties 顶部注释）。
val lowMem = providers
    .environmentVariable("DASHSTYLE_LOW_MEM")
    .map { it.isNotBlank() }
    .getOrElse(false)
if (lowMem) {
    // KGP 的 PropertiesBuildService 解析属性时 project.ext 优先于 gradle 属性，
    // 且策略 Provider 是惰性求值（任务配置期），此处（tasks{} 之前）注入仍然来得及。
    // 注意：ExtraPropertiesExtension.get() 在属性不存在时抛异常，必须先用 has() 判断。
    if (!project.extra.has("kotlin.compiler.execution.strategy")) {
        project.extra["kotlin.compiler.execution.strategy"] = "in-process"
        logger.lifecycle("DASHSTYLE_LOW_MEM: Kotlin 编译策略已注入 ext 属性 = in-process（不拉起独立 Kotlin daemon）")
    }
    if (gradle.startParameter.maxWorkerCount > 2) {
        gradle.startParameter.maxWorkerCount = 2
        logger.lifecycle("DASHSTYLE_LOW_MEM: max-workers 已降为 2")
    }
    val daemonMaxMb = Runtime.getRuntime().maxMemory() / 1024L / 1024L
    if (daemonMaxMb > 1536) {
        logger.warn(
            "DASHSTYLE_LOW_MEM=1，但当前 Gradle daemon 堆上限 ${daemonMaxMb}MB（>1536MB）。" +
                "通常限制 workers + in-process 编译后已可跑通；若仍遇 daemon 被 OOM killer 杀掉，" +
                "可再降 daemon 堆重启：GRADLE_OPTS='-Dorg.gradle.jvmargs=-Xmx1024m -XX:MaxMetaspaceSize=384m' gradle --stop 后重跑"
        )
    } else {
        logger.lifecycle("DASHSTYLE_LOW_MEM: daemon 堆上限 ${daemonMaxMb}MB，符合低内存预期")
    }
}

tasks {
    withType<JavaCompile> {
        // 本机/Gradle 用 JDK 17（见 gradle.properties 的 org.gradle.java.home），
        // jvm target 必须 ≤ 运行 JDK，否则 javac 报 "invalid source release: 21"。
        // 插件运行在 WebStorm 的 JBR 21 上，17 字节码向下兼容，不影响功能。
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "17"
            // -Xsuppress-version-warnings：容忍 IDE SDK 中 Kotlin 2.2 metadata 与插件 2.1.0 编译器的
            //   meta 版本差，仅降级为 warning 而不是 error。
            freeCompilerArgs = freeCompilerArgs + listOf(
                "-Xjvm-default=all",
                "-Xfriend-paths=classes/java/main",
                "-Xsuppress-version-warnings"
            )
        }
        // 注意：KGP 2.x 已移除任务级 executionStrategy 属性。低内存时的 "Kotlin 编译 in-process"
        // 开关（不拉独立 Kotlin daemon）由上方 DASHSTYLE_LOW_MEM 块向 project.ext 注入
        // Gradle 属性 kotlin.compiler.execution.strategy=in-process 实现，此处无需任何配置。
    }

    publishPlugin {
        token.set(providers.gradleProperty("intellijPublishToken"))
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
            showStandardStreams = true
        }
    }

    // DashStyle 插件目前没有需要搜索的 UI 选项页。buildSearchableOptions 任务需要起一个 headless IDE
    // 进程做索引，在内存有限的容器里非常容易 crash（JBR exit code 16 = SIGABRT）。
    // 这里直接禁用不影响插件功能：用户安装后在设置搜索框里搜不到 DashStyle 独有 option 而已
    // （DashStyle 目前也没有 configurable 页）。
    buildSearchableOptions {
        enabled = false
    }
}

// ============ Vue LSP 单测布局补丁（幂等，test 前自动执行） ============
// 背景：沙箱装上 vue 插件后，VFS 监听器/意图查询会触发 VueServicesKt.<clinit>：
//   PackageVersion.bundled(VueLspServerPackageDescriptor, "2.2.10",
//       pluginPath = "vuejs/vuejs-backend",                 ← JetBrains 源码仓布局名
//       localPath = "vue-language-tools/language-server/2.2.10")
// 单测模式下 JSPluginPathManager 会先拼 <ideaHome>/plugins/vuejs/vuejs-backend/...
// （存在即成功）；但发行版目录叫 plugins/vuejs-plugin，没有 plugins/vuejs/ 这层别名，
// 路径不存在时回退到 PluginManagerCoreKt.getPluginDistDirByClass()，后者要求 jar
// 直接位于 <plugin>/lib/ 下，而 intellij.vuejs.backend.jar 在 lib/modules/ 下 →
//   IllegalStateException: ".../vuejs-plugin/lib/modules should be lib directory"
// 该异常在 <clinit> 里发生：要么裸传播进测试方法（ExceptionInInitializerError），
// 要么在 EDT 被 TestLoggerFactory 记录并在 tearDown 补抛（TestLoggerAssertionError），
// LoggedErrorProcessor 过滤器拦不住后者 —— 唯一治本方案是让路径解析成功。
// 方案：在本地发行版里创建源码布局别名 plugins/vuejs/vuejs-backend -> plugins/vuejs-plugin
//（发行版里真实存在 vue-language-tools/language-server/2.2.10/bin/vue-language-server.js，
//  别名命中后 clinit 正常返回 BundledVersion，不再走抛 ISE 的回退分支）。
val patchVueLspPluginLayout by tasks.registering {
    doLast {
        val distDir = tasks
            .named<Test>("test").get()
            .classpath
            .mapNotNull { file ->
                val f = file.canonicalFile
                // app.jar（平台核心）位于 <dist>/lib/ 下，由它反推发行版根目录
                if (f.isFile && f.name == "app.jar" && f.parentFile?.name == "lib") f.parentFile?.parentFile else null
            }
            .firstOrNull()
            ?: error("无法从 test classpath 定位 IDE 发行版（lib/app.jar）")
        val alias = distDir.resolve("plugins/vuejs/vuejs-backend")
        if (alias.exists()) {
            logger.lifecycle("Vue LSP 源码布局别名已存在，跳过: $alias")
            return@doLast
        }
        val target = distDir.resolve("plugins/vuejs-plugin")
        require(target.isDirectory) { "发行版里找不到 plugins/vuejs-plugin: $target" }
        distDir.resolve("plugins/vuejs").mkdirs()
        try {
            Files.createSymbolicLink(alias.toPath(), target.toPath())
            logger.lifecycle("已创建 Vue LSP 源码布局别名(软链): $alias -> $target")
        } catch (e: IOException) {
            // Windows 无符号链接权限等场景：退化为只建空目录 —— 对 clinit 而言
            // Files.exists(...) 为真即可通过（BundledVersion 仅记录路径字符串）
            alias.mkdirs()
            logger.lifecycle("符号链接创建失败(${e.message})，已退化为空目录: $alias")
        }
    }
}
tasks.named("test") { dependsOn(patchVueLspPluginLayout) }
