// The Kotlin interop programs of the cross-SDK suite (integration-testing/README.md, "Contracts").
// They build against a Kotlin SDK checkout through a composite build, so the peer ref under test is
// what runs: no publishing, no version to track. The checkout comes from -PkotlinSdk=<dir> or the
// KOTLIN_SDK environment variable (matrix.json sets it to ${peer.kotlin-sdk}).
rootProject.name = "kt-peer"

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

val kotlinSdk: String = providers.gradleProperty("kotlinSdk")
    .orElse(providers.environmentVariable("KOTLIN_SDK"))
    .orNull
    ?: throw GradleException("Set -PkotlinSdk=<kotlin-sdk checkout> or KOTLIN_SDK")

includeBuild(kotlinSdk)
