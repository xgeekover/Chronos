// Protocol plugin — depends ONLY on core-api (불변 규칙 2).
// Phase 1: in-tree stub. Phase 4 repackages this as a PF4J plugin JAR
// (core-api becomes compileOnly, plus a plugin manifest + @Extension).
dependencies {
    implementation(project(":core-api"))
}
