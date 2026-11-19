plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation("com.anthropic:anthropic-java:3.3.0")
    implementation("com.openai:openai-java:4.109.0")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

application {
    // PC 실행 전용 진입점 (앱에서는 쓰지 않음)
    mainClass.set("collector.dev.MainKt")
}

tasks.named<JavaExec>("run") {
    workingDir = rootProject.projectDir
    standardInput = System.`in`
}

tasks.test {
    useJUnitPlatform()
}
