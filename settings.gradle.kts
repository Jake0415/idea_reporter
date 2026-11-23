import java.util.Properties

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ai-idea-collector"

include(":core")
val localSdk = file("local.properties").takeIf { it.isFile }?.inputStream()?.use {
    Properties().apply { load(it) }.getProperty("sdk.dir")
}
val hasAndroidSdk = listOf(System.getenv("ANDROID_HOME"), System.getenv("ANDROID_SDK_ROOT"), localSdk)
    .any { !it.isNullOrBlank() && file(it).isDirectory }
if (hasAndroidSdk && !providers.gradleProperty("withoutApp").isPresent) {
    rootProject.buildFileName = "android-root.gradle.kts"
    include(":app")
}
