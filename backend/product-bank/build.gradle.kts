// product-bank: irisan vertikal produk BANK — domain, blueprint, persistence (schema
// "bank"), handler SNAP, dan Admin API khusus bank. Terpisah penuh dari :product-qris;
// keduanya hanya berbagi mesin generik (:core-engine, :adapter-persistence, :adapter-web).
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
    implementation(libs.spring.boot.starter.data.jpa)
    implementation("com.fasterxml.jackson.core:jackson-databind")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
