// adapter-web: inbound adapter — Spring MVC (Admin API + per-port simulasi) + SSE.
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
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.validation)
    implementation("org.springframework:spring-tx")   // @Transactional untuk eksekusi pipeline atomik
    testImplementation(libs.spring.boot.starter.test)
}
