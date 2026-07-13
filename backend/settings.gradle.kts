plugins {
    // Memungkinkan Gradle mengunduh JDK toolchain (Java 25) otomatis
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "behavio"

include(
    "core-engine",
    "adapter-persistence",
    "adapter-web",
    "adapter-signature",
    "adapter-webhook",
    "app",
)
