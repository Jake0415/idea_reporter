import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.ideacollector"
    compileSdk = 37
    buildToolsVersion = "36.0.0"
    defaultConfig {
        applicationId = "app.ideacollector"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = if (providers.gradleProperty("phoneRunMinify").isPresent) {
            "app.ideacollector.ShrunkSdkInstrumentation"
        } else "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes {
        debug {
            if (providers.gradleProperty("phoneRunDeviceTest").isPresent) applicationIdSuffix = ".r2fixr1test"
            if (providers.gradleProperty("phoneRunMinify").isPresent) {
                isDebuggable = false
                isMinifyEnabled = true
                proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro", "src/androidTest/r8-target-rules.pro")
            }
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("androidTest").assets.srcDir("schemas")
    packaging.resources.excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE.md", "META-INF/LICENSE.md")
}

/**
 * 개인 빌드 전용(2026-11-26 사용자 결정): `-PembedEnv`를 줄 때만 루트 `.env`의 허용된 키를 debug APK 자산
 * `env-seed.properties`로 넣는다. 앱은 첫 실행 때 Keystore로 옮긴다(EnvSeed.kt). 값은 build/ 아래에만 생기고
 * 출력·로그에 찍지 않는다. release 빌드와 플래그 없는 빌드에는 들어가지 않는다.
 */
abstract class EnvSeedTask : DefaultTask() {
    @get:InputFile @get:PathSensitive(PathSensitivity.NONE) abstract val envFile: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction fun write() {
        val allowed = setOf("ANTHROPIC_API_KEY", "OPENAI_API_KEY", "GITHUB_TOKEN", "MAIL_TO", "SMTP_USERNAME",
            "SMTP_PASSWORD", "REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET", "PRODUCTHUNT_TOKEN")
        val values = Properties()
        envFile.get().asFile.readLines(Charsets.UTF_8).forEach { raw ->
            val line = raw.trim().removePrefix("export ").trim()
            if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@forEach
            val name = line.substringBefore('=').trim()
            var value = line.substringAfter('=').trim()
            if (value.length >= 2 && (value.first() == '"' && value.last() == '"' || value.first() == '\'' && value.last() == '\'')) {
                value = value.substring(1, value.length - 1)
            }
            if (name in allowed && value.isNotBlank()) values.setProperty(name, value)
        }
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        dir.resolve("env-seed.properties").outputStream().use { stream -> values.store(stream, "") }
        logger.lifecycle("env-seed: ${values.size} keys embedded")
    }
}

if (providers.gradleProperty("embedEnv").isPresent) {
    val envSeed = tasks.register<EnvSeedTask>("generateEnvSeed") {
        envFile.set(rootProject.layout.projectDirectory.file(".env"))
        outputDir.set(layout.buildDirectory.dir("generated/envSeed"))
        outputs.cacheIf { false }
    }
    androidComponents.onVariants(androidComponents.selector().withBuildType("debug")) { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(envSeed, EnvSeedTask::outputDir)
    }
}

kotlin { jvmToolchain(17) }
ksp { arg("room.schemaLocation", file("schemas").path) }

dependencies {
    implementation(project(":core"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore)
    implementation(libs.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    androidTestImplementation("jakarta.mail:jakarta.mail-api:2.1.5")
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.work.testing)
    androidTestImplementation("androidx.room:room-testing:${libs.versions.room.get()}")
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
