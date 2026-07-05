// app — Spring Boot 4 application: REST + WebSocket (FE), persistence (metadata DB),
// security, and wiring of the engine. Boots the platform.

plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dep.mgmt)
}

dependencies {
    implementation(project(":core-engine")) // brings core-api transitively (api dependency)
    implementation(project(":core-flow"))    // Node-RED-style message-passing flow runtime
    implementation(project(":gateway"))      // external WS + protobuf gateway (§9)

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-quartz") // Task scheduler (ADR-003)
    implementation("org.springframework.boot:spring-boot-starter-security") // §11 RBAC/auth (Phase 7)
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server") // JWT (§11)
    implementation("io.micrometer:micrometer-registry-prometheus")          // §11 metrics (Phase 7)
    // OpenTelemetry: distributed tracing (OTLP) + OTLP metrics export to a collector (§11 observability).
    // Spring Boot 4 split tracing/OTLP auto-config into dedicated modules (like spring-boot-flyway),
    // so they must be added explicitly alongside the Micrometer/OTel libraries.
    implementation("org.springframework.boot:spring-boot-micrometer-tracing")
    implementation("org.springframework.boot:spring-boot-opentelemetry")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")
    implementation("io.micrometer:micrometer-registry-otlp")

    implementation(libs.jgit) // Projects: server-side git versioning of the flow library (Node-RED Projects)

    // Built-in adapters are wired at the composition root as runtime-only deps and discovered via
    // ServiceLoader — the core never imports them at compile time (불변 규칙 2). Additional
    // protocols can also be dropped in as PF4J plugin JARs at runtime (CompositePluginRuntime).
    runtimeOnly(project(":adapters:adapter-jdbc"))
    runtimeOnly(project(":adapters:adapter-file"))
    runtimeOnly(project(":adapters:adapter-api"))
    runtimeOnly(project(":adapters:adapter-shell"))
    runtimeOnly(project(":adapters:adapter-mqtt"))
    runtimeOnly(project(":adapters:adapter-modbus"))
    runtimeOnly(project(":adapters:adapter-tcp"))

    // Metadata DB: PostgreSQL + Flyway (ADR-009). Versions managed by the Spring Boot BOM.
    // Spring Boot 4 moved autoconfigurations into per-technology modules: the Flyway
    // autoconfig now lives in spring-boot-flyway (flyway-core alone won't trigger migration).
    implementation("org.springframework.boot:spring-boot-flyway")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // Tests boot against a real PostgreSQL via Testcontainers (§4, ADR-009).
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    // Testcontainers 2.x renamed every module with a `testcontainers-` prefix.
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
    // E2E source DBs for the JDBC adapter (MariaDB native; Oracle/MS-SQL via emulation on arm64).
    testImplementation("org.testcontainers:testcontainers-mariadb")
    testImplementation("org.testcontainers:testcontainers-oracle-free")
    testImplementation("org.testcontainers:testcontainers-mssqlserver")
    testImplementation(project(":sdk-java")) // gateway E2E drives the real Java SDK
}

// Keep the everyday `test`/`build` fast: the default run excludes the heavy DB containers
// (Oracle, MS-SQL — the latter is emulated on arm64). Run them explicitly with `heavyDbTest`.
tasks.named<Test>("test") {
    useJUnitPlatform { excludeTags("heavydb") }
}

tasks.register<Test>("heavyDbTest") {
    description = "E2E collection tests against heavy DB containers (Oracle, MS-SQL)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform { includeTags("heavydb") }
    shouldRunAfter("test")
}

// Keep per-node SQLite history files written by tests under build/ (cleaned by `clean`).
tasks.withType<Test>().configureEach {
    systemProperty("chronos.history.data-dir",
        layout.buildDirectory.dir("test-history").get().asFile.absolutePath)
}

// Stable boot jar name for the Docker image COPY (Phase 7).
tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar") {
    archiveFileName.set("chronos-app.jar")
}
