// adapter-webhook: outbound adapter — pengirim webhook (persistent outbox + retry).
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
    testImplementation(libs.spring.boot.starter.test)
}
