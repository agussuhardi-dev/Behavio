// app: perakitan (wiring) + Spring Boot runtime. Merakit core + mesin generik + produk.
plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":core-engine"))
    implementation(project(":adapter-persistence"))
    implementation(project(":adapter-web"))
    implementation(project(":adapter-signature"))
    implementation(project(":adapter-webhook"))
    implementation(project(":product-bank"))
    implementation(project(":product-qris"))

    implementation(libs.spring.boot.starter.actuator)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")  // JdbcClient utk wiring worker outbox
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("behavio.jar")
}
