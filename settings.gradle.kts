plugins {
    // Resolves the JDK 17 toolchain (ADR-001); auto-provisions it if a CI host lacks one.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "chronos"

// JVM modules live physically under backend/ while the Gradle build is rooted at the repo top.
include(
    "core-api",
    "core-engine",
    "core-flow",
    "app",
    "gateway",
    "adapters:adapter-jdbc",
    "adapters:adapter-file",
    "adapters:adapter-shell",
    "adapters:adapter-api",
    "adapters:adapter-script",
    "adapters:adapter-mqtt",
    "adapters:adapter-modbus",
    "adapters:adapter-tcp",
    "sdk-java",
)

project(":core-api").projectDir = file("backend/core-api")
project(":core-engine").projectDir = file("backend/core-engine")
project(":core-flow").projectDir = file("backend/core-flow")
project(":app").projectDir = file("backend/app")
project(":gateway").projectDir = file("backend/gateway")
project(":adapters").projectDir = file("backend/adapters")
project(":adapters:adapter-jdbc").projectDir = file("backend/adapters/adapter-jdbc")
project(":adapters:adapter-file").projectDir = file("backend/adapters/adapter-file")
project(":adapters:adapter-shell").projectDir = file("backend/adapters/adapter-shell")
project(":adapters:adapter-api").projectDir = file("backend/adapters/adapter-api")
project(":adapters:adapter-script").projectDir = file("backend/adapters/adapter-script")
project(":adapters:adapter-mqtt").projectDir = file("backend/adapters/adapter-mqtt")
project(":adapters:adapter-modbus").projectDir = file("backend/adapters/adapter-modbus")
project(":adapters:adapter-tcp").projectDir = file("backend/adapters/adapter-tcp")
project(":sdk-java").projectDir = file("sdk/sdk-java")
