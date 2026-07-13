// app: perakitan (wiring) + Spring Boot runtime. Merakit core + semua adapter.
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

    implementation(libs.spring.boot.starter.actuator)
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
}

tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("behavio.jar")
}
