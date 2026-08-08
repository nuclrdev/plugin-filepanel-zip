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

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Enumeration;
import java.util.List;
import java.util.function.BooleanSupplier;

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
	private static final BooleanSupplier NEVER_CANCELLED = () -> false;

	private ArchiveExtractor() {
	}

	/** Thrown when an encrypted ZIP is given the wrong password. */
	static final class WrongPasswordException extends IOException {
		private static final long serialVersionUID = 1L;

		WrongPasswordException(String message) {
			super(message);
		}
	}

	/** Signals normal user cancellation while an archive is being opened or extracted. */
	static final class ExtractionCancelledException extends IOException {
		private static final long serialVersionUID = 1L;

		ExtractionCancelledException() {
			super("Archive extraction cancelled");
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
	static void extractZip(Path source, Path destination, char[] password, Charset charset,
			ArchiveExtractionBudget budget) throws IOException {
		extractZip(source, destination, password, charset, budget, NEVER_CANCELLED);
	}

	static void extractZip(Path source, Path destination, char[] password, Charset charset,
			BooleanSupplier cancelled) throws IOException {
		extractZip(source, destination, password, charset, extractionBudget(source, true, cancelled),
				cancelled);
	}

	static void extractZip(Path source, Path destination, char[] password, Charset charset,
			ArchiveExtractionBudget budget, BooleanSupplier cancelled) throws IOException {

		checkCancelled(cancelled);

		try (InputStream fileIn = Files.newInputStream(source);
				InputStream monitoredIn = cancellable(fileIn, cancelled);
				var zipIn = new net.lingala.zip4j.io.inputstream.ZipInputStream(monitoredIn, password, charset)) {

			while (true) {
				checkCancelled(cancelled);
				final var header = zipIn.getNextEntry();
				checkCancelled(cancelled);
				if (header == null) {
					break;
				}
				final String entryName = header.getFileName();
				// Data-descriptor entries may report zero here; the guarded output
				// stream still enforces the limit against every expanded byte.
				budget.beginEntry(entryName, header.isDirectory() ? 0 : header.getUncompressedSize());
				final var target = safeResolve(destination, entryName);

				if (target.equals(destination.normalize())) {
					// getNextEntry() would drain this implicitly, bypassing both the
					// extraction budget and cancellation checks.
					discardEntry(zipIn, budget, cancelled);
					continue;
				}
				if (header.isDirectory()) {
					discardEntry(zipIn, budget, cancelled);
					Files.createDirectories(target);
					applyZipTimestamp(header, target);
					continue;
				}

				if (target.getParent() != null) {
					Files.createDirectories(target.getParent());
				}
				try (OutputStream out = cancellable(budget.limit(Files.newOutputStream(target)), cancelled)) {
					zipIn.transferTo(out);
				} catch (IOException | RuntimeException e) {
					deletePartialFile(target, e);
					throw e;
				}
				applyZipTimestamp(header, target);
			}
			checkCancelled(cancelled);

		} catch (ZipException e) {
			final var extractionFailure = extractionFailureCause(e);
			if (extractionFailure != null) {
				throw extractionFailure;
			}
			if (e.getType() == ZipException.Type.WRONG_PASSWORD) {
				throw new WrongPasswordException("Wrong password for " + source.getFileName());
			}
			throw new IOException("Failed to extract ZIP: " + source.getFileName(), e);
		}
	}

	/** True if the ZIP archive is encrypted. Returns false if it cannot be read. */
	static boolean isEncrypted(Path source, BooleanSupplier cancelled) throws ExtractionCancelledException {
		try (var zip = openZipForInspection(source, StandardCharsets.ISO_8859_1, cancelled)) {
			final Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntries();
			while (entries.hasMoreElements()) {
				checkCancelled(cancelled);
				if (entries.nextElement().getGeneralPurposeBit().usesEncryption()) {
					checkCancelled(cancelled);
					return true;
				}
			}
			checkCancelled(cancelled);
		} catch (ExtractionCancelledException e) {
			throw e;
		} catch (Exception e) {
			final var extractionFailure = extractionFailureCause(e);
			if (extractionFailure instanceof ExtractionCancelledException cancelledFailure) {
				throw cancelledFailure;
			}
			log.debug("Could not determine encryption for {}: {}", source, e.getMessage());
		}
		return false;
	}

	/**
	 * True if any entry name uses a Windows-style backslash separator. Such
	 * archives violate the ZIP spec (which mandates {@code '/'}); the NIO
	 * {@code ZipFileSystem} then treats {@code '\'} as a literal filename
	 * character, collapsing nested paths like {@code icons\i0.gif} into flat,
	 * unbrowsable root entries. Detecting this lets the plugin fall back to
	 * extraction, which rebuilds the real folder tree. Backslash is ASCII, so the
	 * scan is charset-independent; returns false if the archive cannot be read.
	 */
	static boolean hasBackslashEntryNames(Path source, BooleanSupplier cancelled)
			throws ExtractionCancelledException {
		// Scan with ISO-8859-1: it decodes every byte, so the scan also works on
		// CP866/windows-1251 archives (the DOS-era ZIPs most likely to use '\'),
		// where the default UTF-8 decoding would fail. 0x5C never occurs inside a
		// UTF-8 multi-byte sequence, so this cannot produce false positives either.
		try (var zip = openZipForInspection(source, StandardCharsets.ISO_8859_1, cancelled)) {
			final Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntries();
			while (entries.hasMoreElements()) {
				checkCancelled(cancelled);
				if (entries.nextElement().getName().indexOf('\\') >= 0) {
					checkCancelled(cancelled);
					return true;
				}
			}
			checkCancelled(cancelled);
		} catch (ExtractionCancelledException e) {
			throw e;
		} catch (Exception e) {
			final var extractionFailure = extractionFailureCause(e);
			if (extractionFailure instanceof ExtractionCancelledException cancelledFailure) {
				throw cancelledFailure;
			}
			log.debug("Could not scan entry names for {}: {}", source, e.getMessage());
		}
		return false;
	}

	/**
	 * Extract a ZIP whose entry names may use {@code '\'} (or mixed) separators,
	 * rebuilding a proper nested folder tree. Entry names are normalised through
	 * {@link #safeResolve}, which maps {@code '\'} to {@code '/'} and blocks
	 * {@code ../} traversal.
	 *
	 * @param charset the entry-name charset, or {@code null} for UTF-8
	 */
	static void extractZipNormalizingSeparators(Path source, Path destination, Charset charset,
			ArchiveExtractionBudget budget) throws IOException {
		extractZipNormalizingSeparators(source, destination, charset, budget, NEVER_CANCELLED);
	}

	static void extractZipNormalizingSeparators(Path source, Path destination, Charset charset,
			BooleanSupplier cancelled) throws IOException {
		extractZipNormalizingSeparators(source, destination, charset,
				extractionBudget(source, true, cancelled), cancelled);
	}

	static void extractZipNormalizingSeparators(Path source, Path destination, Charset charset,
			ArchiveExtractionBudget budget, BooleanSupplier cancelled) throws IOException {

		final Charset cs = charset != null ? charset : StandardCharsets.UTF_8;
		try (var zip = openZipForInspection(source, cs, cancelled)) {

			final Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntries();
			while (entries.hasMoreElements()) {

				checkCancelled(cancelled);
				final var entry = entries.nextElement();
				budget.beginEntry(entry.getName(), entry.isDirectory() ? 0 : entry.getSize());
				final var target = safeResolve(destination, entry.getName());

				// Degenerate names ("", "/", "\") resolve to the destination itself.
				if (target.equals(destination.normalize())) {
					continue;
				}

				final String rawName = entry.getName();
				if (entry.isDirectory() || rawName.endsWith("/") || rawName.endsWith("\\")) {
					Files.createDirectories(target);
					continue;
				}

				if (target.getParent() != null) {
					Files.createDirectories(target.getParent());
				}
				try {
					try (InputStream in = zip.getInputStream(entry);
							OutputStream out = cancellable(budget.limit(Files.newOutputStream(target)), cancelled)) {
						in.transferTo(out);
					}
				} catch (IOException | RuntimeException e) {
					deletePartialFile(target, e);
					throw e;
				}
			}
			checkCancelled(cancelled);
		}
	}

	// -------------------------------------------------------------------------
	// RAR (junrar)
	// -------------------------------------------------------------------------

	static void extractRar(Path source, Path destination, BooleanSupplier cancelled) throws IOException {
		final var budget = extractionBudget(source, true, cancelled);
		checkCancelled(cancelled);

		try (var archive = new Archive(source.toFile())) {
			checkCancelled(cancelled);

			while (true) {
				checkCancelled(cancelled);
				final FileHeader header = archive.nextFileHeader();
				checkCancelled(cancelled);
				if (header == null) {
					break;
				}
				final var entryName = header.getFileName();
				budget.beginEntry(entryName, header.isDirectory() ? 0 : header.getFullUnpackSize());
				final var target = safeResolve(destination, entryName);

				if (header.isDirectory()) {
					Files.createDirectories(target);
					continue;
				}

				Files.createDirectories(target.getParent());
				try {
					try (OutputStream os = cancellable(budget.limit(Files.newOutputStream(target)), cancelled)) {
						archive.extractFile(header, os);
					}
				} catch (Exception e) {
					deletePartialFile(target, e);
					throw e;
				}
			}
			checkCancelled(cancelled);

		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			final var extractionFailure = extractionFailureCause(e);
			if (extractionFailure != null) {
				throw extractionFailure;
			}
			throw new IOException("Failed to extract RAR: " + source.getFileName(), e);
		}
	}

	// -------------------------------------------------------------------------
	// TAR / TAR.GZ / GZIP (commons-compress)
	// -------------------------------------------------------------------------

	static void extractTar(Path source, Path destination, boolean gzipped, ArchiveExtractionBudget budget)
			throws IOException {
		extractTar(source, destination, gzipped, budget, NEVER_CANCELLED);
	}

	static void extractTar(Path source, Path destination, boolean gzipped, BooleanSupplier cancelled)
			throws IOException {
		extractTar(source, destination, gzipped, extractionBudget(source, gzipped, cancelled), cancelled);
	}

	static void extractTar(Path source, Path destination, boolean gzipped, ArchiveExtractionBudget budget,
			BooleanSupplier cancelled) throws IOException {

		checkCancelled(cancelled);

		try (InputStream fileIn = Files.newInputStream(source);
				InputStream monitoredIn = cancellable(fileIn, cancelled);
				InputStream in = gzipped ? new GzipCompressorInputStream(monitoredIn) : monitoredIn;
				TarArchiveInputStream tar = new TarArchiveInputStream(in)) {
			checkCancelled(cancelled);

			while (true) {
				checkCancelled(cancelled);
				final TarArchiveEntry entry = tar.getNextEntry();
				checkCancelled(cancelled);
				if (entry == null) {
					break;
				}
				budget.beginEntry(entry.getName(), entry.isDirectory() ? 0 : entry.getSize());

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
				// REPLACE_EXISTING: a TAR may legitimately contain the same path
				// twice (appended archives); the later entry wins.
				try {
					try (OutputStream out = cancellable(budget.limit(Files.newOutputStream(target)), cancelled)) {
						tar.transferTo(out);
					}
				} catch (IOException | RuntimeException e) {
					deletePartialFile(target, e);
					throw e;
				}
			}
			checkCancelled(cancelled);
		}
	}

	/** Extract a single-file GZIP stream (e.g. {@code foo.txt.gz}). */
	static void extractGzip(Path source, Path destination) throws IOException {
		extractGzip(source, destination, NEVER_CANCELLED);
	}

	static void extractGzip(Path source, Path destination, ArchiveExtractionBudget budget) throws IOException {
		extractGzip(source, destination, budget, NEVER_CANCELLED);
	}

	static void extractGzip(Path source, Path destination, BooleanSupplier cancelled) throws IOException {
		extractGzip(source, destination, extractionBudget(source, true, cancelled), cancelled);
	}

	static void extractGzip(Path source, Path destination, ArchiveExtractionBudget budget,
			BooleanSupplier cancelled) throws IOException {

		checkCancelled(cancelled);
		final var outputName = strippedGzipName(source);
		final var target = safeResolve(destination, outputName);
		Files.createDirectories(target.getParent());
		budget.beginEntry(outputName, -1);

		try {
			try (InputStream fileIn = Files.newInputStream(source);
					InputStream monitoredIn = cancellable(fileIn, cancelled);
					GzipCompressorInputStream gz = new GzipCompressorInputStream(monitoredIn);
					OutputStream out = cancellable(budget.limit(Files.newOutputStream(target)), cancelled)) {
				checkCancelled(cancelled);
				gz.transferTo(out);
			}
		} catch (IOException | RuntimeException e) {
			deletePartialFile(target, e);
			throw e;
		}
		checkCancelled(cancelled);
	}

	private static IOException extractionFailureCause(Throwable error) {
		Throwable cause = error;
		while (cause != null) {
			if (cause instanceof ArchiveExtractionBudget.LimitExceededException
					|| cause instanceof ExtractionCancelledException) {
				return (IOException) cause;
			}
			cause = cause.getCause();
		}
		return null;
	}

	private static OutputStream cancellable(OutputStream output, BooleanSupplier cancelled) {
		return new FilterOutputStream(output) {
			@Override
			public void write(int value) throws IOException {
				checkCancelled(cancelled);
				out.write(value);
			}

			@Override
			public void write(byte[] bytes, int offset, int length) throws IOException {
				checkCancelled(cancelled);
				out.write(bytes, offset, length);
			}
		};
	}

	private static void discardEntry(InputStream input, ArchiveExtractionBudget budget,
			BooleanSupplier cancelled) throws IOException {
		try (OutputStream output = cancellable(budget.limit(OutputStream.nullOutputStream()), cancelled)) {
			input.transferTo(output);
		}
	}

	private static InputStream cancellable(InputStream input, BooleanSupplier cancelled) {
		return new FilterInputStream(input) {
			@Override
			public int read() throws IOException {
				checkCancelled(cancelled);
				final int value = in.read();
				checkCancelled(cancelled);
				return value;
			}

			@Override
			public int read(byte[] bytes, int offset, int length) throws IOException {
				checkCancelled(cancelled);
				final int read = in.read(bytes, offset, length);
				checkCancelled(cancelled);
				return read;
			}

			@Override
			public long skip(long count) throws IOException {
				checkCancelled(cancelled);
				final long skipped = in.skip(count);
				checkCancelled(cancelled);
				return skipped;
			}
		};
	}

	private static org.apache.commons.compress.archivers.zip.ZipFile openZipForInspection(Path source,
			Charset charset, BooleanSupplier cancelled) throws IOException {
		checkCancelled(cancelled);
		final SeekableByteChannel channel = new CancellableSeekableByteChannel(
				Files.newByteChannel(source), cancelled);
		try {
			final var zip = org.apache.commons.compress.archivers.zip.ZipFile.builder()
					.setSeekableByteChannel(channel)
					.setCharset(charset)
					.get();
			checkCancelled(cancelled);
			return zip;
		} catch (IOException | RuntimeException e) {
			try {
				channel.close();
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
	}

	/**
	 * Streaming ZIP local headers contain timestamps but not the external
	 * DOS/POSIX attributes stored in the central directory. Applying only the
	 * timestamp also avoids making temporary extracted files read-only, which can
	 * prevent their cleanup on Windows.
	 */
	private static void applyZipTimestamp(net.lingala.zip4j.model.AbstractFileHeader header, Path target)
			throws IOException {
		final long timestamp = header.getLastModifiedTimeEpoch();
		if (timestamp > 0) {
			Files.setLastModifiedTime(target, FileTime.fromMillis(timestamp));
		}
	}

	private static ArchiveExtractionBudget extractionBudget(Path source, boolean compressed,
			BooleanSupplier cancelled) throws IOException {
		checkCancelled(cancelled);
		final var budget = ArchiveExtractionBudget.forArchive(source, compressed);
		checkCancelled(cancelled);
		return budget;
	}

	private static void deletePartialFile(Path target, Throwable failure) {
		try {
			Files.deleteIfExists(target);
		} catch (IOException cleanupFailure) {
			failure.addSuppressed(cleanupFailure);
			log.warn("Failed to delete partial archive entry {}: {}", target, cleanupFailure.getMessage());
		}
	}

	static void checkCancelled(BooleanSupplier cancelled) throws ExtractionCancelledException {
		if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
			throw new ExtractionCancelledException();
		}
	}

	private static final class CancellableSeekableByteChannel implements SeekableByteChannel {
		private final SeekableByteChannel delegate;
		private final BooleanSupplier cancelled;

		CancellableSeekableByteChannel(SeekableByteChannel delegate, BooleanSupplier cancelled) {
			this.delegate = delegate;
			this.cancelled = cancelled;
		}

		@Override
		public int read(ByteBuffer destination) throws IOException {
			checkCancelled(cancelled);
			final int read = delegate.read(destination);
			checkCancelled(cancelled);
			return read;
		}

		@Override
		public int write(ByteBuffer source) throws IOException {
			checkCancelled(cancelled);
			final int written = delegate.write(source);
			checkCancelled(cancelled);
			return written;
		}

		@Override
		public long position() throws IOException {
			checkCancelled(cancelled);
			final long position = delegate.position();
			checkCancelled(cancelled);
			return position;
		}

		@Override
		public SeekableByteChannel position(long newPosition) throws IOException {
			checkCancelled(cancelled);
			delegate.position(newPosition);
			checkCancelled(cancelled);
			return this;
		}

		@Override
		public long size() throws IOException {
			checkCancelled(cancelled);
			final long size = delegate.size();
			checkCancelled(cancelled);
			return size;
		}

		@Override
		public SeekableByteChannel truncate(long size) throws IOException {
			checkCancelled(cancelled);
			delegate.truncate(size);
			checkCancelled(cancelled);
			return this;
		}

		@Override
		public boolean isOpen() {
			return delegate.isOpen();
		}

		@Override
		public void close() throws IOException {
			delegate.close();
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
	static Charset detectZipEntryCharset(Path source, BooleanSupplier cancelled)
			throws ExtractionCancelledException {

		Charset best = StandardCharsets.UTF_8;
		long bestScore = Long.MIN_VALUE;

		for (var charset : CANDIDATE_CHARSETS) {
			checkCancelled(cancelled);

			if (charset == null) {
				continue;
			}

			final long score = scoreCharset(source, charset, cancelled);
			if (score > bestScore) {
				bestScore = score;
				best = charset;
			}
		}
		checkCancelled(cancelled);

		log.info("Detected ZIP entry charset {} for {}", best, source.getFileName());
		return best;
	}

	private static long scoreCharset(Path source, Charset charset, BooleanSupplier cancelled)
			throws ExtractionCancelledException {

		long score = 0;

		try (var zip = openZipForInspection(source, charset, cancelled)) {

			final Enumeration<org.apache.commons.compress.archivers.zip.ZipArchiveEntry> entries = zip.getEntries();
			while (entries.hasMoreElements()) {
				checkCancelled(cancelled);
				score += scoreName(entries.nextElement().getName());
			}
			checkCancelled(cancelled);

		} catch (ExtractionCancelledException e) {
			throw e;
		} catch (Exception e) {
			final var extractionFailure = extractionFailureCause(e);
			if (extractionFailure instanceof ExtractionCancelledException cancelledFailure) {
				throw cancelledFailure;
			}
			// Undecodable entry names surface as IllegalArgumentException from
			// entries(), not only as IOException — either way the charset loses.
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
