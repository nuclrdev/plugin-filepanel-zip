/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.mount.zip;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Recognised archive families, plus the rules for detecting them from a file
 * name and deciding how they should be opened.
 *
 * <p>{@link #ZIP_FAMILY} archives are opened in place through the Java NIO
 * {@code ZipFileSystem} provider; every other family is extracted to a
 * temporary directory before it can be browsed.
 */
enum ArchiveType {

	/** {@code .zip}, {@code .jar}, {@code .war}, {@code .ear} — mountable via NIO. */
	ZIP_FAMILY,
	/** {@code .rar} — extracted with junrar. */
	RAR,
	/** {@code .tar} — extracted with commons-compress. */
	TAR,
	/** {@code .tar.gz}, {@code .tgz} — gzip-wrapped tar, extracted with commons-compress. */
	TAR_GZ,
	/** {@code .gz} — single gzip-compressed file, extracted with commons-compress. */
	GZIP,
	/** Anything this plugin does not recognise as an archive. */
	UNKNOWN;

	/** Detect the archive family from a file name (case-insensitive). */
	static ArchiveType of(String fileName) {

		if (fileName == null) {
			return UNKNOWN;
		}

		final String name = fileName.toLowerCase(Locale.ROOT);

		if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
			return TAR_GZ;
		}
		if (name.endsWith(".tar")) {
			return TAR;
		}
		if (name.endsWith(".gz")) {
			return GZIP;
		}
		if (name.endsWith(".rar")) {
			return RAR;
		}
		if (name.endsWith(".zip") || name.endsWith(".jar") || name.endsWith(".war") || name.endsWith(".ear")) {
			return ZIP_FAMILY;
		}

		return UNKNOWN;
	}

	/** Detect the archive family from the file name of a path. */
	static ArchiveType of(Path path) {
		if (path == null || path.getFileName() == null) {
			return UNKNOWN;
		}
		return of(path.getFileName().toString());
	}

	/** True if this is a recognised archive family this plugin can browse. */
	boolean isArchive() {
		return this != UNKNOWN;
	}

	/** True if the file is named like a recognised archive. */
	static boolean isArchiveFile(Path path) {
		return of(path).isArchive();
	}

	/** True if this family is browsed through the NIO {@code ZipFileSystem}. */
	boolean usesNioZipFilesystem() {
		return this == ZIP_FAMILY;
	}
}
