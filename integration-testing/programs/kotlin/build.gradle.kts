import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // The Kotlin SDK's own Kotlin version (its gradle/libs.versions.toml).
    kotlin("jvm") version "2.2.20"
    application
}

// Any version: the composite build substitutes the included Kotlin SDK checkout for these modules.
val acp = "0.0.0-included"
val ktor = "3.1.3"

dependencies {
    implementation("com.agentclientprotocol:acp:$acp")
    implementation("com.agentclientprotocol:acp-ktor-client:$acp")
    implementation("com.agentclientprotocol:acp-ktor-server:$acp")
    implementation("io.ktor:ktor-client-cio:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("io.ktor:ktor-server-websockets:$ktor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // kotlin-logging (the SDK's logger) binds to slf4j; slf4j-simple logs to stderr, never stdout.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}

// The runtime JDK may be 17 (the suite's JDK); the SDK itself targets 1.8.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        optIn.add("com.agentclientprotocol.annotations.UnstableApi")
        optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
    }
}

application {
    applicationName = "kt-peer"
    mainClass.set("interop.MainKt")
}
