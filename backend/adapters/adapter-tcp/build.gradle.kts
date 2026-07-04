// TCP/Socket protocol plugin — depends ONLY on core-api (불변 규칙 2).
// Reads one line from a raw TCP socket on each collect; no external library needed.
dependencies {
    implementation(project(":core-api"))
}
