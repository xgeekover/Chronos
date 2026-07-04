// SHELL protocol plugin — depends ONLY on core-api (불변 규칙 2).
// Local execution via ProcessBuilder; remote via Apache MINA SSHD (ADR-006).
dependencies {
    implementation(project(":core-api"))
    implementation(libs.sshd.core)
}
