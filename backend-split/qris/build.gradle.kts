// qris: QRIS mandiri penuh. Menggabung salinan sendiri dari core-engine + adapter
// (web/persistence/signature/webhook) + logika produk QRIS. TIDAK bergantung pada module
// lain (MIGRATION-PLAN.md §4). Kode generik yang disalin ada di paket
// id.behavio.qris.platform.*, logika produk di id.behavio.qris.*.
plugins {
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.validation)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.spring.boot.liquibase)   // liquibase-core + autoconfig
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-yaml")  // export OpenAPI
    implementation("org.springframework:spring-tx")
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
