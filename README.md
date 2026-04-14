# Nuclr File Panel Archive Plugin

`filepanel-zip` is an official Nuclr plugin that lets the file panel browse common archive formats with the current Nuclr panel plugin SDK.

It currently supports:
- `.zip`
- `.jar`
- `.war`
- `.ear`
- `.rar`
- `.tar`
- `.gz`
- `.tgz`
- `.tar.gz`

## What It Does

The plugin now registers a `PanelProviderPlugin` (`ZipFilePanelProvider`) that:
- Starts from normal filesystem roots, like the local filesystem panel.
- Lets the user enter supported archive files directly from the panel.
- Mounts non-encrypted archives through the Java NIO ZIP filesystem provider.
- Extracts encrypted ZIP-family archives, plus RAR/TAR/GZ archives, to a temporary directory so they can still be browsed.
- Lets files inside archives participate in quick view and external open flows.

## Plugin Metadata

- Plugin ID: `dev.nuclr.plugin.core.mount.zip`
- Name: `Archive Panel`
- Version: `1.0.0`
- SDK dependency: `dev.nuclr:platform-sdk:2.0.1` (`provided`)
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
src/main/java/dev/nuclr/plugin/core/mount/zip/ZipFilePanelProvider.java
src/main/java/dev/nuclr/plugin/core/mount/zip/ZipFilePanel.java
src/main/java/dev/nuclr/plugin/core/mount/zip/ZipFilePanelModel.java
src/main/resources/plugin.json
src/assembly/plugin.xml
pom.xml
```

## License

Apache License 2.0. See [LICENSE](LICENSE).
