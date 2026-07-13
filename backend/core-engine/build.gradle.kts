// core-engine: domain murni Behavior Engine.
// TIDAK bergantung pada Spring/JPA — hanya Java. Framework menempel di adapter.
plugins {
    java
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
