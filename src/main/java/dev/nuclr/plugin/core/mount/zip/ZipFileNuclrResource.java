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
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
final class ZipFileNuclrResource {

	public static final String KeyPath = "ZipPluginPath";

	public static NuclrResource build(final Path path) {

		final var r = new NuclrResource();
		r.getMetadata().put(KeyPath, path);

		try {
			r.setName(path.getFileName().toString());
		} catch (Exception e) {
			r.setName(path == null ? "" : path.toString());
		}
		
		r.setFullPath(getFullPath(path));
		r.setUuid(path.toAbsolutePath().toString());
		r.setFolder(Files.isDirectory(path));
		r.setLength(getLength(path));
		r.setSystem(isSystem(path));
		r.setLink(isLink(path));
		r.setHidden(isHidden(path));
		r.setLastModifiedDateTime(getLastModifiedDateTime(path));
		r.setCreatedDateTime(getCreateDateTime(path));
		r.setLastAccessDateTime(getLastAccessDateTime(path));
		
		r.getColumnValues().add(r.getName());
		r.getColumnValues().add(r.isFolder() ? "" : String.valueOf(r.getLength()));
		r.getColumnValues().add(r.getLastModifiedDateTime().toString());
		r.getColumnValues().add(r.getCreatedDateTime().toString());

		return r;

	}

	private static boolean isSystem(Path path) {

		try {

			if (!Files.exists(path)) {
				return false;
			}

			// Windows: check DOS system attribute
			var dosAttrs = Files.readAttributes(path, DosFileAttributes.class, LinkOption.NOFOLLOW_LINKS);

			return dosAttrs.isSystem();
		} catch (UnsupportedOperationException e) {
			// Non-Windows file systems usually do not support DOS attributes
			return false;
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean isHidden(Path path) {
		try {
			return Files.exists(path) && Files.isHidden(path);
		} catch (IOException e) {
			return false;
		}
	}

	private static boolean hasParent(Path path) {
		return path != null && path.getParent() != null;
	}

	private static boolean isLink(Path path) {
		return path != null && Files.isSymbolicLink(path);
	}

	private static String getFullPath(Path path) {
		return path != null ? path.toAbsolutePath().normalize().toString() : "";
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
		} catch (IOException e) {
			log.warn("Failed to read last access time for {}: {}", path, e.getMessage());
		}
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

	private static LocalDateTime getCreateDateTime(Path path) {
		try {
			var attrs = Files.readAttributes(path, BasicFileAttributes.class);
			return attrs.creationTime().toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException e) {
			log.warn("Failed to read creation time for {}: {}", path, e.getMessage());
		}
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

	private static LocalDateTime getLastModifiedDateTime(Path path) {
		try {
			return Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
		} catch (IOException e) {
			log.warn("Failed to read last modified time for {}: {}", path, e.getMessage());
		}
		return LocalDateTime.ofEpochSecond(0, 0, ZoneOffset.UTC);
	}

}
