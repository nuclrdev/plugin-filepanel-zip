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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds {@link NuclrResource} instances from {@link Path} objects, whether
 * those paths live in a mounted NIO ZIP filesystem or in a temporary extraction
 * directory.
 *
 * <p>The path is stored under {@link #KeyPath} — the <em>same</em> metadata key
 * used by the local-filesystem panel plugin — so resources interoperate when
 * the user enters an archive from a regular file panel.
 */
@Slf4j
final class ArchiveNuclrResource {

	/** Metadata key holding the backing {@link Path}. Matches the local FS plugin. */
	public static final String KeyPath = "Path";

	/**
	 * Metadata key marking the synthetic {@code ".."} entry that closes the
	 * archive (used at the archive root to pop the panel layer).
	 */
	public static final String KeyCloseArchive = "archive.close";

	private ArchiveNuclrResource() {
	}

	/** Build a resource describing a path inside an archive. */
	public static NuclrResource build(final Path path) {

		final var r = new NuclrResource(path);

		try {
			final var fileName = path.getFileName();
			r.setName(fileName != null ? fileName.toString() : path.toString());
		} catch (Exception e) {
			r.setName(path == null ? "" : path.toString());
		}

		r.setFullPath(getFullPath(path));
		r.setUuid(getFullPath(path));
		r.setFolder(Files.isDirectory(path));
		r.setLength(getLength(path));
		r.setLastModifiedDateTime(getLastModifiedDateTime(path));
		r.setCreatedDateTime(getCreateDateTime(path));
		r.setLastAccessDateTime(getLastAccessDateTime(path));

		addColumnValues(r);

		return r;
	}

	/**
	 * Build the resource shown for the archive root. Uses the original archive
	 * file name as the display name (the NIO root path has no file name).
	 */
	public static NuclrResource buildRoot(final Path rootPath, final String displayName) {
		final var r = build(rootPath);
		r.setName(displayName);
		r.setFolder(true);
		r.getColumnValues().set(0, displayName);
		return r;
	}

	/**
	 * Build the {@code ".."} entry that navigates up to {@code parentPath} inside
	 * the archive.
	 */
	public static NuclrResource buildParent(final Path parentPath) {
		final var r = build(parentPath);
		r.setFolder(true);
		r.setName("..");
		r.getColumnValues().set(0, r.getName());
		return r;
	}

	/**
	 * Build the {@code ".."} entry shown at the archive root: opening it closes
	 * the archive and pops the panel layer rather than navigating.
	 */
	public static NuclrResource buildCloseParent(final Path archiveFile) {
		final var r = new NuclrResource(archiveFile);
		r.getMetadata().put(KeyCloseArchive, Boolean.TRUE);
		r.setFolder(true);
		r.setName("..");
		r.setFullPath("..");
		r.setUuid("archive-close:" + (archiveFile == null ? "" : archiveFile));
		r.setLastModifiedDateTime(epoch());
		r.setCreatedDateTime(epoch());
		r.setLastAccessDateTime(epoch());
		addColumnValues(r);
		r.getColumnValues().set(0, "..");
		return r;
	}

	private static void addColumnValues(NuclrResource r) {
		r.getColumnValues().add(r.getName() == null ? "" : r.getName());
		r.getColumnValues().add(r.isFolder() ? "" : String.valueOf(r.getLength()));
		r.getColumnValues().add(String.valueOf(r.getLastModifiedDateTime()));
		r.getColumnValues().add(String.valueOf(r.getCreatedDateTime()));
	}

	private static String getFullPath(Path path) {
		if (path == null) {
			return "";
		}
		try {
			return path.toAbsolutePath().normalize().toString();
		} catch (Exception e) {
			return path.toString();
		}
	}

	private static long getLength(Path path) {
		try {
			return Files.exists(path) && !Files.isDirectory(path) ? Files.size(path) : 0L;
		} catch (IOException e) {
			return 0L;
		}
	}

	private static LocalDateTime getLastAccessDateTime(Path path) {
		try {
			var attrs = Files.readAttributes(path, BasicFileAttributes.class);
			return attrs.lastAccessTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException | UnsupportedOperationException e) {
			log.debug("Failed to read last access time for {}: {}", path, e.getMessage());
		}
		return epoch();
	}

	private static LocalDateTime getCreateDateTime(Path path) {
		try {
			var attrs = Files.readAttributes(path, BasicFileAttributes.class);
			return attrs.creationTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException | UnsupportedOperationException e) {
			log.debug("Failed to read creation time for {}: {}", path, e.getMessage());
		}
		return epoch();
	}

	private static LocalDateTime getLastModifiedDateTime(Path path) {
		try {
			return Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException | UnsupportedOperationException e) {
			log.debug("Failed to read last modified time for {}: {}", path, e.getMessage());
		}
		return epoch();
	}

	private static LocalDateTime epoch() {
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}
}
