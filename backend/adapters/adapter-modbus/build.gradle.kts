// Modbus TCP protocol plugin — depends ONLY on core-api (불변 규칙 2) + the j2mod library.
// Reads holding/input registers or coils on each collect and returns them as rows.
dependencies {
    implementation(project(":core-api"))
    implementation(libs.j2mod)
}
