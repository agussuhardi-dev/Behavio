plugins {
    // Memungkinkan Gradle mengunduh JDK toolchain (Java 25) otomatis
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}

rootProject.name = "behavio-split"

// Pemisahan penuh per simulator (lihat MIGRATION-PLAN.md):
//   simulator = Bank mandiri penuh (bawa salinan engine/adapter sendiri)
//   qris      = QRIS mandiri penuh (menyusul)
//   main-app  = launcher tipis yang merakit produk + platform.port_registry
include(
    "simulator",
    "qris",
    "main-app",
)
