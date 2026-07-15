// adapter-persistence: outbound adapter — MESIN KONFIGURASI GENERIK (JdbcClient) +
// Liquibase. Tak mengenal produk apa pun: schema jadi parameter (SchemaTables), katalog
// operasi & blueprint datang dari ProductCatalog milik :product-*. JPA sengaja TIDAK
// dipakai di sini — @Table(schema=...) yang statis akan memaksa entity diduplikasi
// per-schema dan mesin ini ikut jadi dua salinan.
plugins {
    alias(libs.plugins.spring.dep.mgmt)
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}")
    }
}

dependencies {
    implementation(project(":core-engine"))
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.spring.boot.liquibase)   // membawa liquibase-core + autoconfig
    implementation("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.core:jackson-databind")  // codec definisi scenario
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
