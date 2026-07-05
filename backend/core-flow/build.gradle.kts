// core-flow — a Node-RED-style message-passing flow runtime (ADR-001: pure JDK, no Spring, framework-
// free so it stays testable in isolation like core-engine). The app module wires it behind REST.
dependencies {
    // Eclipse Paho for the mqtt in/out nodes (same client the historian's MQTT adapter uses).
    implementation(libs.paho.mqtt)
    // GraalVM polyglot + GraalJS power the JavaScript `function` node (Node-RED-style), sandboxed with
    // host access denied. Runs on stock OpenJDK 17 in interpreted mode (no Graal compiler needed).
    implementation(libs.graaljs.polyglot)
    implementation(libs.graaljs.js)
    // Parsing nodes: Jackson (json), SnakeYAML (yaml), jsoup (html CSS-selector extraction). XML uses the JDK.
    implementation(libs.jackson.databind)
    implementation(libs.snakeyaml)
    implementation(libs.jsoup)
}
