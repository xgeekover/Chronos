// MQTT protocol plugin — depends ONLY on core-api (불변 규칙 2) + the Eclipse Paho client.
// Holds a per-broker subscription and caches the latest payload per topic; collect() snapshots it.
dependencies {
    implementation(project(":core-api"))
    implementation(libs.paho.mqtt)
}
