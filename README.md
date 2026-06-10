# 🗜️ Archive File Panel

An official [Nuclr Commander](https://nuclr.dev) plugin that lets the file panel browse the contents of common archive formats. Navigate into ZIP, JAR, RAR, TAR, and other archives as if they were directories — from either panel.

## 🧩 Supported formats

| Extension(s) | Handling |
|---|---|
| `.zip`, `.jar`, `.war`, `.ear` | NIO mount (in-place, writable for unencrypted archives) |
| `.zip` (encrypted) | Password prompt → extract to temp (read-only) |
| `.rar` | Extract to temp via junrar (read-only) |
| `.tar`, `.gz`, `.tgz`, `.tar.gz` | Extract to temp via Commons Compress (read-only) |

> 💡 NIO-mounted archives support write operations (delete, create folder) without extraction. Extracted archives are read-only.

## ✨ What it does

| Feature | Details |
|---|---|
| 📂 Archive navigation | Enter any supported archive from the opposite panel |
| 🔓 Encrypted ZIP | Password prompt with retry on wrong password |
| 🔤 Charset detection | Detects encoding issues in ZIP entry names |
| 📦 Nested archives | Archives inside archives are materialised to temp files |
| 👁️ Quick view | Files inside archives participate in the normal quick-view flow |
| 🗑️ Delete | Supported inside NIO-mounted ZIP-family archives |
| 📁 New folder | Supported inside NIO-mounted ZIP-family archives |
| ↩️ Exit archive | Navigate to `..` at the archive root to close and return to the parent panel |

## 📥 Installation

Copy the signed plugin archive and detached signature into the Nuclr Commander `plugins/` directory:

```text
filepanel-zip-<version>.zip
filepanel-zip-<version>.zip.sig
```

Nuclr Commander verifies the RSA-SHA256 signature against `nuclr-cert.pem` on load. The plugin becomes available immediately without a restart.

## ⚙️ How it works

`ZipFilePanelPlugin` implements `FilePanelNuclrPlugin`. When an archive is entered, the plugin detects its type via `ArchiveType` and chooses a strategy: unencrypted ZIP-family archives are mounted in-place via the Java NIO ZIP filesystem provider for instant, writable access. Encrypted ZIPs, RARs, and TAR variants are extracted to a temp directory managed per-mount. Charset detection for ZIP entry names uses heuristics to handle common encoding issues. Nested archives are materialised to temp `.zip` / `.tar.gz` etc. files so they can be recursively opened.

## 🗂️ Source layout

```text
src/main/java/dev/nuclr/plugin/core/mount/zip/
├── ZipFilePanelPlugin.java    plugin entry point, navigation, mount management
├── ArchiveExtractor.java      extraction engine for RAR, TAR, GZ
├── ArchiveNuclrResource.java  NuclrResource wrapper for archive entries
├── ArchiveType.java           format detection and type enum
├── FileNuclrResource.java     NuclrResource wrapper for extracted temp files
├── DeleteDialogs.java         delete confirmation dialogs
└── service/
    ├── DeleteService.java
    └── MakeNewFolderService.java
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.1` | Nuclr platform interfaces |
| `zip4j` | `2.11.5` | Encrypted ZIP handling |
| `commons-compress` | `1.28.0` | TAR, GZ, BZ2 extraction |
| `junrar` | `7.5.8` | RAR extraction |

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
