pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://maven.aliyun.com/repository/gradle-plugin/")
        maven("https://maven.aliyun.com/repository/google/")
        maven("https://maven.aliyun.com/repository/central/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        maven("https://repo1.maven.org/maven2/")
        maven("https://s01.oss.sonatype.org/content/groups/public")
        maven("https://maven.aliyun.com/repository/google/")
        maven("https://maven.aliyun.com/repository/central/")
        maven("https://maven.aliyun.com/repository/public/")
        maven("https://mirrors.cloud.tencent.com/nexus/repository/maven-public/")
    }
}
rootProject.name = "音乐 Music"
include(":app")
// include(":common")
// project(":common").projectDir = File("../android-common/common")
