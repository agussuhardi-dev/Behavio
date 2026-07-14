// adapter-persistence: outbound adapter — JPA/Postgres + Liquibase.
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
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.liquibase)   // membawa liquibase-core + autoconfig
    implementation("com.fasterxml.jackson.core:jackson-databind")  // codec definisi scenario
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
}
