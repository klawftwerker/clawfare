plugins {
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.serialization") version "2.1.10"
    application
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
}

group = "com.clawfare"
version = "0.1.0"

repositories {
    mavenCentral()
}

dependencies {
    // CLI
    implementation("info.picocli:picocli:4.7.6")

    // Database
    implementation("org.xerial:sqlite-jdbc:3.46.1.3")
    implementation("org.jetbrains.exposed:exposed-core:0.55.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.55.0")

    // JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testImplementation("io.mockk:mockk:1.13.13")
}

application {
    mainClass.set("com.clawfare.MainKt")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_22)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_22
    targetCompatibility = JavaVersion.VERSION_22
}

tasks.test {
    useJUnitPlatform()
}

// Ktlint configuration
ktlint {
    version.set("1.4.1")
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

// Combined check task for CI and hooks
tasks.register("codeCheck") {
    description = "Run linting and coverage verification"
    dependsOn("ktlintCheck")
}
