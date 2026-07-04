// Chronos Java SDK (§9) — a standalone client library (.jar). Depends only on protobuf-java and
// the JDK (java.net.http WebSocket); generates protocol classes from the shared proto schema.

plugins {
    alias(libs.plugins.protobuf)
    `maven-publish`
}

group = "io.chronos"
version = "0.1.0"

dependencies {
    api(libs.protobuf.java)
}

// `./gradlew :sdk-java:publish` pushes to the repo in CHRONOS_MAVEN_URL (GitHub Packages, OSSRH,
// Nexus, …). With no URL set it publishes to build/repo so `publish` is always runnable/testable.
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set("Chronos Java SDK")
                description.set("Chronos IoT historian gateway client — subscribe to and query tags by name (§9).")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }
            }
        }
    }
    repositories {
        maven {
            name = "remote"
            url = uri(System.getenv("CHRONOS_MAVEN_URL")
                ?: layout.buildDirectory.dir("repo").get().asFile.toURI().toString())
            val user = System.getenv("CHRONOS_MAVEN_USER")
            val pass = System.getenv("CHRONOS_MAVEN_PASSWORD")
            if (user != null && pass != null) {
                credentials {
                    username = user
                    password = pass
                }
            }
        }
    }
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

sourceSets {
    main {
        proto {
            srcDir(rootProject.layout.projectDirectory.dir("proto"))
        }
    }
}
