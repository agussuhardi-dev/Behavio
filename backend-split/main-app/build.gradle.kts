// main-app: launcher Spring Boot tipis. Merakit produk (simulator=Bank, nanti qris) +
// menyediakan DataSource, schema platform (port_registry), dan health/config.
// Bergantung pada module produk; produk TIDAK bergantung balik → graf tanpa siklus.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":simulator"))
    implementation(project(":qris"))

    implementation(libs.spring.boot.starter.web)   // @RestController lintas-produk (health/sink) + advice
    implementation(libs.spring.boot.starter.actuator)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    // Tetap "behavio.jar": main-app merakit SEMUA produk (bank + qris), bukan bank saja.
    // Nama ini juga yang dipakai deploy/deploy.sh — jangan diubah tanpa menyesuaikannya.
    archiveFileName.set("behavio.jar")
}
