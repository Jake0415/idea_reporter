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
    implementation("jakarta.mail:jakarta.mail-api:2.1.5")
    implementation("org.eclipse.angus:angus-mail:2.0.5")

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("org.yaml:snakeyaml:2.3")
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
    systemProperty("collector.cli.classpath", sourceSets.main.get().runtimeClasspath.asPath)
}
