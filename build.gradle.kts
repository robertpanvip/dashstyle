plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    // 升级到最新版（当前 2025 年 12 月最新是 2.10.5 或更高）
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "com.pan"
version = "1.0.5"

repositories {
    // 国内镜像优先
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
    intellijPlatform {
        webstorm("2025.3")  // 或 "2025.2"，根据你想测试的版本
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)

        // Add necessary plugin dependencies for compilation here, example:
        //bundledPlugin("com.intellij.java")
        bundledPlugin("JavaScript") // 让 PSI API 可用
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "251"
        }

        changeNotes = """
      Initial version
    """.trimIndent()
    }
}

tasks {
    // Set the JVM compatibility versions
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions.jvmTarget = "21"
    }

    publishPlugin {
        token.set(providers.gradleProperty("intellijPublishToken"))
    }
}
