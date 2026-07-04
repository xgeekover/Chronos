// core-api — Chronos extension points (SPI) + immutable value objects.
// Framework-free except for the PF4J marker interface (ADR-001).
// Adapters depend ONLY on this module; it depends on nothing in core-engine/app.

dependencies {
    api(libs.pf4j) // DeviceAdapter extends org.pf4j.ExtensionPoint
}
