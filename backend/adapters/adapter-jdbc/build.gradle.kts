// JDBC protocol plugin — depends ONLY on core-api (불변 규칙 2).
// Phase 2: real implementation (HikariCP pool + SQL execution). Phase 4 repackages as a
// PF4J plugin JAR. Bundles JDBC drivers it supports (MariaDB now; Oracle/MS-SQL later).
dependencies {
    implementation(project(":core-api"))
    implementation(libs.hikaricp)
    // Bundled JDBC drivers (the adapter itself is driver-agnostic via jdbcUrl).
    runtimeOnly(libs.mariadb.client)
    runtimeOnly(libs.ojdbc11)
    runtimeOnly(libs.mssql.jdbc)
}
