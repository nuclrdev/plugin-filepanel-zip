package dev.nuclr.plugin.core.mount.zip;

enum ArchiveType {
	ZIP_FAMILY, RAR, TAR, GZIP;

	boolean usesNioZipFilesystem() {
		return this == ZIP_FAMILY;
	}
}
