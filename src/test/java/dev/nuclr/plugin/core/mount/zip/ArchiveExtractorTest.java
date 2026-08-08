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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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
	void separatorNormalizingZipExtractionRebuildsBackslashFolders() throws Exception {
		Path archive = createZip("backslash-folders.zip", Map.of("folder\\file.txt", "content"));
		Path destination = Files.createDirectory(tempDir.resolve("backslash-folders"));

		ArchiveExtractor.extractZipNormalizingSeparators(archive, destination, StandardCharsets.UTF_8,
				new ArchiveExtractionBudget(10, 100, 100));

		assertEquals("content", Files.readString(destination.resolve("folder/file.txt")));
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
		assertTrue(Files.notExists(destination.resolve("large.txt")));
	}

	@Test
	void cancelledGzipExtractionRemovesPartialOutput() throws Exception {
		Path archive = tempDir.resolve("cancelled.txt.gz");
		byte[] content = new byte[128 * 1024];
		try (var gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
			gzip.write(content);
		}
		Path destination = Files.createDirectory(tempDir.resolve("cancelled-gzip"));
		Path extracted = destination.resolve("cancelled.txt");

		assertThrows(ArchiveExtractor.ExtractionCancelledException.class,
				() -> ArchiveExtractor.extractGzip(archive, destination,
						new ArchiveExtractionBudget(10, content.length, content.length),
						() -> fileHasContent(extracted)));

		assertTrue(Files.notExists(extracted));
	}

	@Test
	void threadInterruptionCancelsExtractionBeforeOutputIsCreated() throws Exception {
		Path archive = tempDir.resolve("interrupted.txt.gz");
		try (var gzip = new GZIPOutputStream(Files.newOutputStream(archive))) {
			gzip.write("content".getBytes(StandardCharsets.UTF_8));
		}
		Path destination = Files.createDirectory(tempDir.resolve("interrupted-gzip"));

		Thread.currentThread().interrupt();
		try {
			assertThrows(ArchiveExtractor.ExtractionCancelledException.class,
					() -> ArchiveExtractor.extractGzip(archive, destination));
		} finally {
			Thread.interrupted();
		}

		assertTrue(Files.notExists(destination.resolve("interrupted.txt")));
	}

	@Test
	void zipInspectionCanBeCancelledDuringCentralDirectoryReads() throws Exception {
		Path archive = createZip("inspect.zip",
				linkedEntries("first.txt", "first", "second.txt", "second", "third.txt", "third"));
		var polls = new AtomicInteger();

		assertThrows(ArchiveExtractor.ExtractionCancelledException.class,
				() -> ArchiveExtractor.detectZipEntryCharset(archive,
						() -> polls.incrementAndGet() >= 5));

		assertTrue(polls.get() >= 5);
	}

	@ParameterizedTest(name = "{0}")
	@EnumSource(value = EncryptionMethod.class, names = { "ZIP_STANDARD", "AES" })
	void streamedZipExtractionHandlesEncryptionAndWrongPasswords(EncryptionMethod encryptionMethod)
			throws Exception {
		String prefix = encryptionMethod.name().toLowerCase(java.util.Locale.ROOT);
		Path source = Files.writeString(tempDir.resolve(prefix + "-secret.txt"), "secret");
		Path archive = tempDir.resolve(prefix + "-secret.zip");
		var parameters = new ZipParameters();
		parameters.setEncryptFiles(true);
		parameters.setEncryptionMethod(encryptionMethod);
		try (var zip = new ZipFile(archive.toFile(), "correct".toCharArray())) {
			zip.addFile(source.toFile(), parameters);
		}

		Path destination = Files.createDirectory(tempDir.resolve(prefix + "-correct-password"));
		ArchiveExtractor.extractZip(archive, destination, "correct".toCharArray(), StandardCharsets.UTF_8,
				new ArchiveExtractionBudget(10, 100, 100));
		assertEquals("secret", Files.readString(destination.resolve(source.getFileName())));

		Path wrongDestination = Files.createDirectory(tempDir.resolve(prefix + "-wrong-password"));
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

	private static boolean fileHasContent(Path file) {
		try {
			return Files.exists(file) && Files.size(file) > 0;
		} catch (IOException e) {
			return false;
		}
	}
}
