// SCRIPT_JAVA protocol plugin — depends ONLY on core-api (불변 규칙 2).
// Pure-Java dynamic scripts: compiled in-memory with the Eclipse Compiler (ECJ) so it works on a
// JRE (no system JDK needed), then executed with a source-level blocklist + timeout (ADR-005, §11).
// ⚠️ in-process Java is NOT a true sandbox; untrusted scripts need process/container isolation.
dependencies {
    implementation(project(":core-api"))
    implementation(libs.ecj)
}
