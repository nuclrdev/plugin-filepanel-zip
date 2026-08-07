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
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;

import org.apache.commons.io.FileUtils;

import dev.nuclr.platform.plugin.NuclrPluginContext;
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

	/**
	 * Metadata key marking the synthetic {@code ".."} entry that closes the
	 * archive (used at the archive root to pop the panel layer).
	 */
	public static final String KeyCloseArchive = "archive.close";

	/** Marks an entry whose bytes live only in a read-only parent archive view. */
	public static final String KeyReadOnlySource = "archive.readOnlySource";

	private ArchiveNuclrResource() {
	}

	/** Build a resource describing a path inside an archive. */
	public static ZipFileNuclrResource build(NuclrPluginContext ctx, final Path path) {

		final var r = new ZipFileNuclrResource(ctx, path);

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

		addColumnValues(ctx, r);

		return r;
	}

	/**
	 * Build the resource shown for the archive root. Uses the original archive
	 * file name as the display name (the NIO root path has no file name).
	 */
	public static ZipFileNuclrResource buildRoot(NuclrPluginContext ctx, final Path rootPath, final String displayName) {
		final var r = build(ctx, rootPath);
		r.setName(displayName);
		r.setFolder(true);
		return r;
	}

	/**
	 * Build the {@code ".."} entry that navigates up to {@code parentPath} inside
	 * the archive.
	 */
	public static ZipFileNuclrResource buildParent(NuclrPluginContext ctx, final Path parentPath) {
		final var r = build(ctx, parentPath);
		r.setFolder(true);
		r.setName("..");
		return r;
	}

	/**
	 * Build the {@code ".."} entry shown at the archive root: opening it closes
	 * the archive and pops the panel layer rather than navigating.
	 */
	public static ZipFileNuclrResource buildCloseParent(NuclrPluginContext ctx, Path archiveFile) {
		final var r = new ZipFileNuclrResource(ctx, archiveFile);
		r.getMetadata().put(KeyCloseArchive, Boolean.TRUE);
		r.setPath(null);
		r.setFolder(true);
		r.setName("..");
		r.setFullPath("..");
		r.setUuid("archive-close:" + (archiveFile == null ? "" : archiveFile));
		r.setLastModifiedDateTime(epoch());
		r.setCreatedDateTime(epoch());
		r.setLastAccessDateTime(epoch());
		addColumnValues(ctx, r);
		return r;
	}

	private static void addColumnValues(NuclrPluginContext ctx, NuclrResource r) {
		r.getMetadata().put("Name", r.getName());
		r.getMetadata().put("Size", r.isFolder() ? "Folder" : FileUtils.byteCountToDisplaySize(r.getLength()));
		r.getMetadata().put("Date", getDate(ctx.getLocale(), r.getLastModifiedDateTime()));
		// Date and Time are the two halves of the same (modification) timestamp;
		// access time would show the extraction moment for temp-extracted archives.
		r.getMetadata().put("Time", getTime(ctx.getLocale(), r.getLastModifiedDateTime()));
	}

	/** Get time String in a localised format */
	private static String getTime(Locale locale, LocalDateTime date) {
			    return date
			.toLocalTime()
			.format(DateTimeFormatter
				.ofLocalizedTime(FormatStyle.SHORT)
				.withLocale(locale));
	}

	/** Get date String in a localised format */
	private static String getDate(Locale locale, LocalDateTime date) {
	    return date
            .toLocalDate()
            .format(DateTimeFormatter
                .ofLocalizedDate(FormatStyle.SHORT)
                .withLocale(locale));
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
