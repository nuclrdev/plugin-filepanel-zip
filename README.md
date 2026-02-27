# Nuclr File Panel ZIP Plugin

`filepanel-zip` is an official Nuclr plugin that lets the file panel browse archive contents as a mounted filesystem.

It currently supports:
- `.zip`
- `.jar`
- `.war`
- `.ear`

## What It Does

The plugin registers an `ArchiveMountProvider` (`ZipArchiveMountProvider`) that:
- Detects supported archive extensions.
- Mounts archives using Java NIO ZIP filesystem (`jar:` URI, `com.sun.nio.zipfs`).
- Reuses mounted filesystems per archive URI via an internal cache.
- Exposes ZIP-archive capabilities through the Nuclr plugin SDK.

## Plugin Metadata

- Plugin ID: `dev.nuclr.plugin.core.mount.zip`
- Name: `ZIP Archive Panel`
- Version: `1.0.0`
- SDK dependency: `dev.nuclr:plugins-sdk:1.0.0`
- Java version: `21`

Metadata file: `src/main/resources/plugin.json`

## Build

Prerequisites:
- JDK 21
- Maven 3.9+

Build plugin ZIP:

```bash
mvn clean package
```

Run full verification (includes detached signature generation):

```bash
mvn clean verify -Djarsigner.storepass=<password>
```

## Build Artifacts

After `package`, Maven prepares:
- `target/filepanel-zip-1.0.0.jar`
- `target/filepanel-zip-1.0.0.zip`

The plugin ZIP contains:
- plugin JAR in the ZIP root
- `plugin.json` in the ZIP root
- runtime dependencies in `lib/`

After `verify`, Maven also generates:
- `target/filepanel-zip-1.0.0.zip.sig`

## Signing Notes

Signature generation runs in the `verify` phase through `gmavenplus-plugin` and expects:
- PKCS#12 key store at `C:/nuclr/key/nuclr-signing.p12`
- alias `nuclr`
- Maven property `jarsigner.storepass`

If you only need an unsigned plugin package, use `mvn clean package`.

## Install (Local Development)

1. Build the ZIP (`mvn clean package` or `mvn clean verify`).
2. Copy `target/filepanel-zip-1.0.0.zip` into your Nuclr plugins directory.
3. If your environment verifies detached signatures, also copy `target/filepanel-zip-1.0.0.zip.sig`.
4. Restart Nuclr Commander (or reload plugins, if supported).

## Source Layout

```text
src/main/java/dev/nuclr/plugin/core/mount/zip/ZipArchiveMountProvider.java
src/main/resources/plugin.json
src/assembly/plugin.xml
pom.xml
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
