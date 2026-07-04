# Adding a Protocol (Adapter)

Extensibility is the core value of Chronos: a new data-source protocol is added as a new module
that implements the SPI in `core-api` — **without touching the core** (invariant rule 2). An
adapter can run two ways, both backed by the same `DeviceAdapter` SPI:

- **Built-in** — bundled on the backend classpath and discovered via `ServiceLoader`.
- **Dropped plugin** — a PF4J plugin JAR placed in the plugins directory and discovered at
  runtime (no rebuild, no core change). Proven by `Pf4jPluginRuntimeTest`.

The app merges both via `CompositePluginRuntime` (built-ins + `chronos.plugins.dir`).

## 1. Create a module

Add `backend/adapters/adapter-<name>` and register it in `settings.gradle.kts` (`include(...)`
+ the `projectDir` mapping). It depends **only** on `core-api` (+ any of its own libraries):

```kotlin
dependencies {
    implementation(project(":core-api"))
    // implementation("...your driver/client...")
}
```

The root build adds the PF4J annotation processor to every `:adapters:*` module automatically.

## 2. Implement `DeviceAdapter` and annotate `@Extension`

```java
@org.pf4j.Extension                       // → generates META-INF/extensions.idx (PF4J discovery)
public class MyDeviceAdapter implements io.chronos.api.adapter.DeviceAdapter {
    public String type() { return "MYPROTO"; }              // matched against Device.adapterType
    public Capabilities capabilities() { return Capabilities.of(TaskType.API_CALL); }
    public void validate(DeviceConfig config) throws AdapterException { /* connection test */ }
    public RawResult collect(DeviceConfig config, CollectRequest request) throws AdapterException {
        return RawResult.ofJson(payload, Instant.now());
    }
}
```

Rules: read params from `config.params()`, secrets from `config.secrets()` (never log them —
`Secrets.toString()` masks values); honor `request.timeout()`; report failures via
`AdapterException` (the pipeline records quality/`CollectionLog`).

## 3. Register for discovery

- **PF4J** (dropped plugin): `@Extension` + the annotation processor produce
  `META-INF/extensions.idx`. The plugin JAR also needs a manifest with `Plugin-Id` and
  `Plugin-Version`.
- **ServiceLoader** (built-in): add
  `src/main/resources/META-INF/services/io.chronos.api.adapter.DeviceAdapter` listing the class,
  and add `runtimeOnly(project(":adapters:adapter-<name>"))` to `backend/app`.

## 4. Mapping (usually no code)

The core `DefaultResultParser` turns `RawResult` + the Task's `MappingRule`s into `TagValue`s.
Extractor kinds: `COLUMN` (rows), `JSONPATH` and `REGEX` (text/JSON), with `transform`
(cast/scale/offset). Provide a custom `ResultParser` only for unusual raw shapes.

## 5. Package as a droppable PF4J plugin JAR

- Set `core-api` and `org.pf4j:pf4j` to **compileOnly** (the host provides them).
- Add manifest attributes `Plugin-Id` / `Plugin-Version`.
- If the adapter has third-party dependencies (JDBC driver, SSHD, Groovy…), build a **fat JAR**
  (e.g. the `com.gradleup.shadow` plugin) so the deps travel with the plugin; PF4J loads it in an
  isolated plugin classloader. Dependency-free adapters (file/api) need only a plain JAR.
- Drop the JAR into `chronos.plugins.dir` → the running backend discovers it on startup.

## Checklist

- [ ] Module depends only on `core-api` (+ its own libs)
- [ ] `@Extension` on the adapter; unique `type()` matching Device `adapterType`
- [ ] `validate()` performs a real connection test; secrets never logged; timeout honored
- [ ] ServiceLoader file (built-in) and/or `Plugin-Id`/`Plugin-Version` manifest (dropped plugin)
- [ ] Unit test covers `type()`/`capabilities()` and a happy-path `collect()`
