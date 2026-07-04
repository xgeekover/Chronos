// gateway — external WebSocket + protobuf gateway for the multi-language SDK (§9).
// Spring-free: holds the protocol (generated from proto/chronos.proto), frame codec, subscription
// registry and the broadcaster. The Spring WebSocket endpoint lives in the app module.

plugins {
    alias(libs.plugins.protobuf)
}

dependencies {
    api(project(":core-engine")) // TagValue, TagUpdateListener, CurrentValueCache, HistoryStore
    api(libs.protobuf.java)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
}

// Generate Java from the single shared schema at repo-root proto/.
sourceSets {
    main {
        proto {
            srcDir(rootProject.layout.projectDirectory.dir("proto"))
        }
    }
}
