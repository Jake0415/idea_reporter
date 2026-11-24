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
