// iso8583: simulator HOST ISO-8583 (TCP), mandiri penuh — nol dependency ke module lain.
// Codec ditulis sendiri (BUKAN jPOS: AGPL v3 menular bila Behavio di-host untuk pihak lain).
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
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation(libs.spring.boot.liquibase)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.springframework:spring-tx")
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
