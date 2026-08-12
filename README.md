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
| 🔤 Charset detection | Scores ZIP entry names against UTF-8, CP866 and windows-1251 to recover mangled names |
| 📦 Nested archives | Archives inside archives are materialised to temp files and mounted recursively |
| 👁️ View / Quick view | F3/F4 and quick view work on entries inside archives |
| 📋 Copy (F5) | Extract entries out to the opposite panel, or add files into a writable NIO-mounted archive |
| ✂️ Move (F6) | Move entries within a writable NIO-mounted archive |
| 🗑️ Delete (F8) | Supported inside NIO-mounted ZIP-family archives |
| 📁 New folder (F7) | Supported inside NIO-mounted ZIP-family archives |
| 📌 Clipboard | Copy entries or their in-archive paths to the system clipboard |
| 🔤 Sorting | Name / extension / size / modified / unsorted, plus the sort dialog (`Ctrl+F3`…`Ctrl+F12`) |
| 🛡️ Extraction limits | Entry-count, per-entry, total-size and expansion-ratio budgets guard against zip bombs |
| 🚧 Traversal protection | TAR/RAR entries are resolved under the target directory; `../` escapes are rejected |
| ↩️ Exit archive | Navigate to `..` at the archive root to close and return to the parent panel |

### 🛡️ Extraction budget

Temp extraction is bounded on both the sizes an archive *declares* and the bytes actually written, so forged metadata cannot bypass the limits. The defaults can be overridden with system properties:

| Property | Default |
|---|---|
| `nuclr.archive.extraction.maxEntries` | 100,000 entries |
| `nuclr.archive.extraction.maxEntryBytes` | 8 GiB |
| `nuclr.archive.extraction.maxTotalBytes` | 20 GiB |
| `nuclr.archive.extraction.maxExpansionRatio` | 1,000× |

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
├── ZipFilePanelPlugin.java        plugin entry point, navigation, mount management
├── ArchiveExtractor.java          extraction engine for RAR, TAR, GZ
├── ArchiveExtractionBudget.java   entry/size/ratio limits for temp extraction
├── ArchiveCopyService.java        F5 copy out of and into archives
├── ArchiveMoveService.java        F6 move within a mounted archive
├── ArchiveClipboardService.java   copy entries / in-archive paths to the clipboard
├── ArchiveNuclrResource.java      NuclrResource wrapper for archive entries
├── ArchiveType.java               format detection and type enum
├── ZipFileNuclrResource.java      NuclrResource wrapper for extracted temp files
├── SwingDialogRunner.java         EDT-safe dialog helper for background work
├── DeleteDialogs.java             delete confirmation dialogs
└── service/
    ├── DeleteService.java
    └── MakeNewFolderService.java
```

## 📚 Dependencies

| Library | Version | Purpose |
|---|---|---|
| `dev.nuclr:platform-sdk` | `3.0.2` | Nuclr platform interfaces |
| `zip4j` | `2.11.5` | Encrypted ZIP handling |
| `commons-compress` | `1.28.0` | TAR, GZ, BZ2 extraction |
| `junrar` | `7.5.8` | RAR extraction |
| `jackson-databind` | `2.21.1` | Manifest / metadata JSON |

## 📜 License

Apache License 2.0 — see [LICENSE](LICENSE).
