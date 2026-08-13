pluginManagement {
    repositories {
        // Gradle Plugin Portal + 国内镜像（腾讯第一优先，符合用户要求；阿里云 fallback）
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/") }
        maven { url = uri("https://mirrors.cloud.tencent.com/nexus/repository/gradle-plugins/") }
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin/") }
        maven { url = uri("https://maven.aliyun.com/repository/public/") }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "DashStyle"
