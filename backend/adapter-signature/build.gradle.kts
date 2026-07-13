// adapter-signature: outbound adapter — verifikasi signature SNAP (RSA/HMAC).
// Pure Java crypto (java.security / javax.crypto), implementasi port dari core.
plugins {
    java
}

dependencies {
    implementation(project(":core-engine"))
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
