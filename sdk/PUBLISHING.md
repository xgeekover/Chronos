# Publishing the Chronos SDKs

All three SDKs are **publish-ready** — packaging metadata + a CI workflow are in place. Publishing
itself requires registry credentials, which only the maintainer can provide (as repo secrets, or
locally as env vars). Version is `0.1.0` across all three; bump it in each manifest before release.

| SDK | Package | Registry | Manifest |
|-----|---------|----------|----------|
| Python | `chronos-sdk` | PyPI | `sdk/sdk-python/pyproject.toml` |
| .NET | `Chronos.Sdk` | NuGet | `sdk/sdk-dotnet/Chronos.Sdk.csproj` |
| Java | `io.chronos:sdk-java` | Maven (GitHub Packages / OSSRH / Nexus) | `sdk/sdk-java/build.gradle.kts` |

## CI (recommended)

`.github/workflows/publish-sdks.yml` runs on a version tag (`git tag v0.1.0 && git push --tags`)
or via **Actions → publish-sdks → Run workflow**. Configure these repo secrets/variables first:

- `PYPI_TOKEN` — a PyPI API token (used as `__token__`).
- `NUGET_API_KEY` — a nuget.org API key.
- `CHRONOS_MAVEN_USER` / `CHRONOS_MAVEN_PASSWORD` — Maven repo credentials; and the **variable**
  `CHRONOS_MAVEN_URL` (e.g. `https://maven.pkg.github.com/<org>/chronos`).

## Manual publish (local)

```bash
# Python → PyPI
cd sdk/sdk-python && python -m build && twine upload dist/*        # TWINE_USERNAME=__token__ TWINE_PASSWORD=<token>

# .NET → NuGet
dotnet pack sdk/sdk-dotnet/Chronos.Sdk.csproj -c Release -o nupkg
dotnet nuget push "nupkg/*.nupkg" --api-key <key> --source https://api.nuget.org/v3/index.json

# Java → Maven repo (set CHRONOS_MAVEN_URL/USER/PASSWORD; without a URL it publishes to build/repo)
CHRONOS_MAVEN_URL=… CHRONOS_MAVEN_USER=… CHRONOS_MAVEN_PASSWORD=… \
  ./gradlew :sdk-java:publishMavenPublicationToRemoteRepository
```

> Note: a Chronos maintainer must run these with real credentials — the tooling can't publish on
> your behalf without them. The packaging is verified (POM/wheel/nupkg build cleanly).
