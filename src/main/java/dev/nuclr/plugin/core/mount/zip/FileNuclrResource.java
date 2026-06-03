/*

	Copyright 2026 Sergio, Nuclr (https://nuclthis.dev)
	
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

import org.apache.commons.io.FileUtils;

import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class FileNuclrResource extends NuclrResource {

	public static final List<String> ColumnNames = List.of("Name", "Size", "Date", "Time");

	public FileNuclrResource(NuclrPluginContext ctx, Path path) {

		super(path);

		try {
			this.setName(path.getFileName().toString());
		} catch (Exception e) {
			this.setName(path.toString());
		}
		
		this.setFullPath(getFullPath(path));
		this.setUuid(path.toAbsolutePath().toString());
		this.setFolder(Files.isDirectory(path));
		this.setLength(getLength(path));
		this.setSystem(isSystem(path));
		this.setLink(isLink(path));
		this.setHidden(isHidden(path));
		this.setLastModifiedDateTime(getLastModifiedDateTime(path));
		this.setCreatedDateTime(getCreateDateTime(path));
		this.setLastAccessDateTime(getLastAccessDateTime(path));

		this.getMetadata().put("Name", this.getName());
		this.getMetadata().put("Size", isFolder() ? "Folder" : FileUtils.byteCountToDisplaySize(this.getLength()));
		this.getMetadata().put("Date", getDate(ctx.getLocale(), this.getLastModifiedDateTime()));
		this.getMetadata().put("Time", getTime(ctx.getLocale(), this.getLastAccessDateTime()));

	}
	
	public void setName(String name) {
		this.name = name;
		this.getMetadata().put("Name", name);
	}

	/** Get time String in a localised format */
	private String getTime(Locale locale, LocalDateTime date) {
			    return date
			.toLocalTime()
			.format(DateTimeFormatter
				.ofLocalizedTime(FormatStyle.SHORT)
				.withLocale(locale));
	}

	/** Get date String in a localised format */ 
	private String getDate(Locale locale, LocalDateTime date) {
	    return date
            .toLocalDate()
            .format(DateTimeFormatter
                .ofLocalizedDate(FormatStyle.SHORT)
                .withLocale(locale));
	}
	
	
	
	public InputStream openInputStream(OpenOption... options) throws Exception {
		return Files.newInputStream(path, options);
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
