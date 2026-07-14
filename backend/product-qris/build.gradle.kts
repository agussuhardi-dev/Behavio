// product-qris: irisan vertikal produk QRIS (PJP) — domain, blueprint, persistence
// (schema "qris"), handler SNAP MPM, dan Admin API khusus QRIS. Terpisah penuh dari
// :product-bank; keduanya hanya berbagi mesin generik.
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
    implementation(project(":adapter-persistence"))
    implementation(project(":adapter-web"))
    implementation(project(":adapter-webhook"))
    implementation(libs.spring.boot.starter.web)
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework:spring-tx")
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
