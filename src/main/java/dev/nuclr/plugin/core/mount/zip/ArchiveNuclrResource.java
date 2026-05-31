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
import java.util.UUID;

import dev.nuclr.platform.plugin.NuclrResource;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

@Data
@EqualsAndHashCode(callSuper = false)
@Slf4j
class ArchiveNuclrResource {

	public static final String Name = "Name";
	public static final String Size = "Size";
	public static final String Date = "Date";
	public static final String Time = "Time";
	public static final String Path = "Path";

	public NuclrResource build(Path path) {
		
		var r = new NuclrResource();
		r.setUuid(UUID.randomUUID().toString());
		
		var metadata = r.getMetadata();
		
//		metadata.put(Name, resolveName(path));
//		metadata.put(Size, readSize(path));
		metadata.put(Path, path);

		try {
			var lastModified = Files.getLastModifiedTime(path).toInstant().atZone(ZoneId.systemDefault())
					.toLocalDateTime();
			metadata.put(Date, lastModified.toLocalDate().toString());
			metadata.put(Time, lastModified.toLocalTime().toString());
		} catch (IOException e) {
			log.warn("Failed to read last modified time for {}: {}", path, e.getMessage());
			metadata.put(Date, "");
			metadata.put(Time, "");
		}
		
		return r;
		
	}
}
