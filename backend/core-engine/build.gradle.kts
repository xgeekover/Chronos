// core-engine — collection pipeline, plugin runtime, history store, current-value cache.
// Plain library (Spring-free, ADR-002/§4) depending on the SPI in core-api.

dependencies {
    api(project(":core-api"))
    implementation(libs.sqlite.jdbc) // SqliteHistoryStore (ADR-004)
    implementation(libs.json.path)   // JSONPATH extractor in DefaultResultParser
}
