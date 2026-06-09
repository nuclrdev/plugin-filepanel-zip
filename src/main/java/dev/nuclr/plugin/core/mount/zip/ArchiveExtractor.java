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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.exception.ZipException;

/**
 * Extracts archives that cannot be browsed in place to a temporary directory.
 *
 * <p>Used for RAR, TAR, TAR.GZ/TGZ, GZIP and encrypted/charset-broken ZIP
 * archives. All TAR/RAR entry names are resolved through {@link #safeResolve}
 * to defend against {@code ../} path-traversal attacks.
 */
@Slf4j
final class ArchiveExtractor {

	/** Cyrillic codepages worth scoring when a ZIP has non-UTF-8 entry names. */
	private static final List<Charset> CANDIDATE_CHARSETS = List.of(StandardCharsets.UTF_8, charset("CP866"),
			charset("windows-1251"));

	private ArchiveExtractor() {
	}

	/** Thrown when an encrypted ZIP is given the wrong password. */
	static final class WrongPasswordException extends IOException {
		private static final long serialVersionUID = 1L;

		WrongPasswordException(String message) {
			super(message);
		}
	}

	// -------------------------------------------------------------------------
	// ZIP (zip4j) — encrypted or charset-broken archives
	// -------------------------------------------------------------------------

	/**
	 * Extract a ZIP-family archive to {@code destination} using zip4j.
	 *
	 * @param password the password, or {@code null} for unencrypted archives
	 * @param charset  the entry-name charset, or {@code null} for the default
	 * @throws WrongPasswordException if the supplied password is rejected
	 */
	static void extractZip(Path source, Path destination, char[] password, Charset charset) throws IOException {

		try (var zipFile = new net.lingala.zip4j.ZipFile(source.toFile())) {

			if (charset != null) {
				zipFile.setCharset(charset);
			}
			if (password != null) {
				zipFile.setPassword(password);
			}

			zipFile.extractAll(destination.toString());

		} catch (ZipException e) {
			if (e.getType() == ZipException.Type.WRONG_PASSWORD) {
				throw new WrongPasswordException("Wrong password for " + source.getFileName());
			}
			throw new IOException("Failed to extract ZIP: " + source.getFileName(), e);
		}
	}

	/** True if the ZIP archive is encrypted. Returns false if it cannot be read. */
	static boolean isEncrypted(Path source) {
		try (var zipFile = new net.lingala.zip4j.ZipFile(source.toFile())) {
			return zipFile.isEncrypted();
		} catch (Exception e) {
			log.debug("Could not determine encryption for {}: {}", source, e.getMessage());
			return false;
		}
	}

	// -------------------------------------------------------------------------
	// RAR (junrar)
	// -------------------------------------------------------------------------

	static void extractRar(Path source, Path destination) throws IOException {

		try (var archive = new Archive(source.toFile())) {

			FileHeader header;
			while ((header = archive.nextFileHeader()) != null) {

				final var entryName = header.getFileName();
				final var target = safeResolve(destination, entryName);

				if (header.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}

				Files.createDirectories(target.getParent());
				try (OutputStream os = Files.newOutputStream(target)) {
					archive.extractFile(header, os);
				}
			}

		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Failed to extract RAR: " + source.getFileName(), e);
		}
	}

	// -------------------------------------------------------------------------
	// TAR / TAR.GZ / GZIP (commons-compress)
	// -------------------------------------------------------------------------

	static void extractTar(Path source, Path destination, boolean gzipped) throws IOException {

		try (InputStream fileIn = Files.newInputStream(source);
				InputStream in = gzipped ? new GzipCompressorInputStream(fileIn) : fileIn;
				TarArchiveInputStream tar = new TarArchiveInputStream(in)) {

			TarArchiveEntry entry;
			while ((entry = tar.getNextEntry()) != null) {

				if (!tar.canReadEntryData(entry)) {
					log.warn("Skipping unreadable TAR entry: {}", entry.getName());
					continue;
				}

				final var target = safeResolve(destination, entry.getName());

				if (entry.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}

				Files.createDirectories(target.getParent());
				Files.copy(tar, target);
			}
		}
	}

	/** Extract a single-file GZIP stream (e.g. {@code foo.txt.gz}). */
	static void extractGzip(Path source, Path destination) throws IOException {

		final var outputName = strippedGzipName(source);
		final var target = safeResolve(destination, outputName);
		Files.createDirectories(target.getParent());

		try (InputStream fileIn = Files.newInputStream(source);
				GzipCompressorInputStream gz = new GzipCompressorInputStream(fileIn)) {
			Files.copy(gz, target);
		}
	}

	private static String strippedGzipName(Path source) {
		final var name = source.getFileName().toString();
		if (name.toLowerCase(java.util.Locale.ROOT).endsWith(".gz")) {
			return name.substring(0, name.length() - ".gz".length());
		}
		return name + ".out";
	}

	// -------------------------------------------------------------------------
	// Charset detection
	// -------------------------------------------------------------------------

	/**
	 * Score the ZIP entry names against UTF-8 and the common Cyrillic codepages
	 * and return the highest-scoring charset, defaulting to UTF-8.
	 */
	static Charset detectZipEntryCharset(Path source) {

		Charset best = StandardCharsets.UTF_8;
		long bestScore = Long.MIN_VALUE;

		for (var charset : CANDIDATE_CHARSETS) {

			if (charset == null) {
				continue;
			}

			final long score = scoreCharset(source, charset);
			if (score > bestScore) {
				bestScore = score;
				best = charset;
			}
		}

		log.info("Detected ZIP entry charset {} for {}", best, source.getFileName());
		return best;
	}

	private static long scoreCharset(Path source, Charset charset) {

		long score = 0;

		try (var zip = new java.util.zip.ZipFile(source.toFile(), charset)) {

			final Enumeration<? extends java.util.zip.ZipEntry> entries = zip.entries();
			while (entries.hasMoreElements()) {
				score += scoreName(entries.nextElement().getName());
			}

		} catch (IOException e) {
			return Long.MIN_VALUE;
		}

		return score;
	}

	private static long scoreName(String name) {

		if (name == null || name.isBlank()) {
			return -5;
		}

		long score = 0;
		for (int i = 0; i < name.length(); i++) {
			final char c = name.charAt(i);
			if (isCyrillic(c)) {
				score += 4;
			} else if (Character.isLetterOrDigit(c)) {
				score += 1;
			} else if (c == '�' || Character.isISOControl(c)) {
				score -= 6;
			}
		}
		return score;
	}

	private static boolean isCyrillic(char c) {
		return Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CYRILLIC;
	}

	// -------------------------------------------------------------------------
	// Path-traversal protection
	// -------------------------------------------------------------------------

	/**
	 * Resolve {@code entryName} under {@code targetDir}, rejecting any path that
	 * escapes the target directory via {@code ../} segments or absolute roots.
	 */
	static Path safeResolve(Path targetDir, String entryName) throws IOException {

		String normalized = entryName.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}

		final Path base = targetDir.normalize();
		final Path resolved = base.resolve(normalized).normalize();

		if (!resolved.startsWith(base)) {
			throw new IOException("Blocked path traversal in archive entry: " + entryName);
		}

		return resolved;
	}

	private static Charset charset(String name) {
		try {
			return Charset.forName(name);
		} catch (Exception e) {
			log.debug("Charset {} not available: {}", name, e.getMessage());
			return null;
		}
	}
}
