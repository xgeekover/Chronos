// Root build — shared configuration for all JVM subprojects.
// Per ADR-002 the Gradle build only covers the backend; the frontend builds via pnpm.

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    // Declared here (without `apply false` it would apply to the root) so versions resolve once.
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dep.mgmt) apply false
}

allprojects {
    group = "io.chronos"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco") // `./gradlew test` emits build/reports/jacoco per module (coverage visibility)

    repositories {
        mavenCentral()
    }

    extensions.configure<JavaPluginExtension> {
        toolchain {
            // ADR-001: develop on OpenJDK 17 (project constraint). Spring Boot 4 / Framework 7
            // baseline is Java 17, so the stack runs unchanged; virtual threads are unavailable.
            languageVersion.set(JavaLanguageVersion.of(17))
        }
    }

    // The `app` module pulls JUnit/AssertJ via spring-boot-starter-test (BOM-managed);
    // every other module gets a plain JUnit setup here so test deps aren't repeated.
    // Type-safe `libs.*` accessors aren't available inside subprojects{}, so the catalog
    // is resolved explicitly via VersionCatalogsExtension.
    if (project.name != "app") {
        val catalog = rootProject.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        dependencies {
            "testImplementation"(platform(catalog.findLibrary("junit-bom").get()))
            "testImplementation"(catalog.findLibrary("junit-jupiter").get())
            "testRuntimeOnly"(catalog.findLibrary("junit-platform-launcher").get())
        }
    }

    // Adapter modules are PF4J plugins: the pf4j annotation processor turns @Extension into a
    // META-INF/extensions.idx so the JAR is discoverable as a dropped plugin (ADR-001).
    if (project.path.startsWith(":adapters:")) {
        val catalog = rootProject.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        dependencies {
            "annotationProcessor"(catalog.findLibrary("pf4j").get())
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("passed", "skipped", "failed")
        }
        finalizedBy("jacocoTestReport")
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.add("-parameters")
    }
}
