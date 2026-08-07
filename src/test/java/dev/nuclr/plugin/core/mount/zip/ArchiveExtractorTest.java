package dev.nuclr.plugin.core.mount.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;

class ArchiveExtractorTest {

	@TempDir
	Path tempDir;

	@Test
	void zipExtractionStopsAtTheEntryCountLimit() throws Exception {
		Path archive = createZip("many.zip", linkedEntries("first.txt", "first", "second.txt", "second"));
		Path destination = Files.createDirectory(tempDir.resolve("many"));

		var error = assertThrows(ArchiveExtractionBudget.LimitExceededException.class,
				() -> ArchiveExtractor.extractZip(archive, destination, null, StandardCharsets.UTF_8,
						new ArchiveExtractionBudget(1, 100, 100)));

		assertTrue(error.getMessage().contains("second.txt"));
		assertEquals("first", Files.readString(destination.resolve("first.txt")));
	}

	@Test
	void separatorNormalizingZipExtractionUsesTheByteBudget() throws Exception {
		Path archive = createZip("backslash.zip", Map.of("folder\\large.txt", "too large"));
		Path destination = Files.createDirectory(tempDir.resolve("backslash"));

		assertThrows(ArchiveExtractionBudget.LimitExceededException.class,
				() -> ArchiveExtractor.extractZipNormalizingSeparators(archive, destination,
						StandardCharsets.UTF_8, new ArchiveExtractionBudget(10, 4, 100)));
	}

	@Test
	void tarExtractionUsesTheSharedTotalByteBudget() throws Exception {
		Path archive = tempDir.resolve("content.tar");
		try (var tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			addTarEntry(tar, "first.txt", "123456");
			addTarEntry(tar, "second.txt", "abcdef");
		}
		Path destination = Files.createDirectory(tempDir.resolve("tar"));

		var error = assertThrows(ArchiveExtractionBudget.LimitExceededException.class,
				() -> ArchiveExtractor.extractTar(archive, destination, false,
						new ArchiveExtractionBudget(10, 10, 10)));

		assertTrue(error.getMessage().contains("second.txt"));
		assertEquals("123456", Files.readString(destination.resolve("first.txt")));
	}

	@Test
	void gzipExtractionChecksActualExpandedBytesWhenNoSizeIsDeclared() throws Exception {
		Path archive = tempDir.resolve("large.txt.gz");
		try (var gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
			gzip.write("more than eight bytes".getBytes(StandardCharsets.UTF_8));
		}
		Path destination = Files.createDirectory(tempDir.resolve("gzip"));

		assertThrows(ArchiveExtractionBudget.LimitExceededException.class,
				() -> ArchiveExtractor.extractGzip(archive, destination,
						new ArchiveExtractionBudget(10, 8, 100)));
		assertTrue(Files.size(destination.resolve("large.txt")) <= 8);
	}

	@Test
	void streamedZipExtractionStillHandlesEncryptionAndWrongPasswords() throws Exception {
		Path source = Files.writeString(tempDir.resolve("secret.txt"), "secret");
		Path archive = tempDir.resolve("secret.zip");
		var parameters = new ZipParameters();
		parameters.setEncryptFiles(true);
		parameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
		try (var zip = new ZipFile(archive.toFile(), "correct".toCharArray())) {
			zip.addFile(source.toFile(), parameters);
		}

		Path destination = Files.createDirectory(tempDir.resolve("correct-password"));
		ArchiveExtractor.extractZip(archive, destination, "correct".toCharArray(), StandardCharsets.UTF_8,
				new ArchiveExtractionBudget(10, 100, 100));
		assertEquals("secret", Files.readString(destination.resolve("secret.txt")));

		Path wrongDestination = Files.createDirectory(tempDir.resolve("wrong-password"));
		assertThrows(ArchiveExtractor.WrongPasswordException.class,
				() -> ArchiveExtractor.extractZip(archive, wrongDestination, "wrong".toCharArray(),
						StandardCharsets.UTF_8, new ArchiveExtractionBudget(10, 100, 100)));
	}

	private Path createZip(String name, Map<String, String> entries) throws IOException {
		Path archive = tempDir.resolve(name);
		try (var zip = new ZipOutputStream(Files.newOutputStream(archive))) {
			for (var entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
				zip.closeEntry();
			}
		}
		return archive;
	}

	private static Map<String, String> linkedEntries(String... namesAndContents) {
		var entries = new LinkedHashMap<String, String>();
		for (int i = 0; i < namesAndContents.length; i += 2) {
			entries.put(namesAndContents[i], namesAndContents[i + 1]);
		}
		return entries;
	}

	private static void addTarEntry(TarArchiveOutputStream tar, String name, String content) throws IOException {
		byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
		var entry = new TarArchiveEntry(name);
		entry.setSize(bytes.length);
		tar.putArchiveEntry(entry);
		tar.write(bytes);
		tar.closeArchiveEntry();
	}
}
