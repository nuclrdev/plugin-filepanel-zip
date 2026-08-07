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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Bounds temporary archive extraction by entry count and expanded byte count.
 * Limits are checked against both declared entry sizes and the bytes actually
 * written, so forged or missing size metadata cannot bypass them.
 */
final class ArchiveExtractionBudget {

	static final String MAX_ENTRIES_PROPERTY = "nuclr.archive.extraction.maxEntries";
	static final String MAX_ENTRY_BYTES_PROPERTY = "nuclr.archive.extraction.maxEntryBytes";
	static final String MAX_TOTAL_BYTES_PROPERTY = "nuclr.archive.extraction.maxTotalBytes";
	static final String MAX_EXPANSION_RATIO_PROPERTY = "nuclr.archive.extraction.maxExpansionRatio";

	private static final long DEFAULT_MAX_ENTRIES = 100_000;
	private static final long DEFAULT_MAX_ENTRY_BYTES = 8L * 1024 * 1024 * 1024;
	private static final long DEFAULT_MAX_TOTAL_BYTES = 20L * 1024 * 1024 * 1024;
	private static final long DEFAULT_MAX_EXPANSION_RATIO = 1_000;
	private static final long MIN_EXPANSION_ALLOWANCE = 64L * 1024 * 1024;

	private final long maxEntries;
	private final long maxEntryBytes;
	private final long maxTotalBytes;

	private long entries;
	private long entryBytes;
	private long totalBytes;
	private String entryName;

	ArchiveExtractionBudget(long maxEntries, long maxEntryBytes, long maxTotalBytes) {
		if (maxEntries <= 0 || maxEntryBytes <= 0 || maxTotalBytes <= 0) {
			throw new IllegalArgumentException("Archive extraction limits must be positive");
		}
		this.maxEntries = maxEntries;
		this.maxEntryBytes = maxEntryBytes;
		this.maxTotalBytes = maxTotalBytes;
	}

	/** Create the configured budget for one archive extraction. */
	static ArchiveExtractionBudget forArchive(Path source, boolean compressed) throws IOException {

		final long maxEntries = positiveLongProperty(MAX_ENTRIES_PROPERTY, DEFAULT_MAX_ENTRIES);
		final long maxEntryBytes = positiveLongProperty(MAX_ENTRY_BYTES_PROPERTY, DEFAULT_MAX_ENTRY_BYTES);
		long maxTotalBytes = positiveLongProperty(MAX_TOTAL_BYTES_PROPERTY, DEFAULT_MAX_TOTAL_BYTES);

		if (compressed) {
			final long maxExpansionRatio = positiveLongProperty(MAX_EXPANSION_RATIO_PROPERTY,
					DEFAULT_MAX_EXPANSION_RATIO);
			final long ratioLimit = saturatedAdd(MIN_EXPANSION_ALLOWANCE,
					saturatedMultiply(Files.size(source), maxExpansionRatio));
			maxTotalBytes = Math.min(maxTotalBytes, ratioLimit);
		}

		return new ArchiveExtractionBudget(maxEntries, maxEntryBytes, maxTotalBytes);
	}

	/** Start accounting for an archive entry. Directories count as entries. */
	void beginEntry(String name, long declaredSize) throws IOException {

		entryName = name;
		if (entries >= maxEntries) {
			throw limitExceeded("entry count exceeds " + maxEntries);
		}
		entries++;
		entryBytes = 0;

		if (declaredSize >= 0) {
			if (declaredSize > maxEntryBytes) {
				throw limitExceeded("entry expands beyond " + maxEntryBytes + " bytes");
			}
			if (declaredSize > maxTotalBytes - totalBytes) {
				throw limitExceeded("total expanded data exceeds " + maxTotalBytes + " bytes");
			}
		}
	}

	/** Wrap an extraction stream so every byte emitted by the decoder is counted. */
	OutputStream limit(OutputStream output) {
		return new FilterOutputStream(output) {
			@Override
			public void write(int value) throws IOException {
				recordBytes(1);
				out.write(value);
			}

			@Override
			public void write(byte[] bytes, int offset, int length) throws IOException {
				recordBytes(length);
				out.write(bytes, offset, length);
			}
		};
	}

	private void recordBytes(long count) throws IOException {
		if (count <= 0) {
			return;
		}
		if (count > maxEntryBytes - entryBytes) {
			throw limitExceeded("entry expands beyond " + maxEntryBytes + " bytes");
		}
		if (count > maxTotalBytes - totalBytes) {
			throw limitExceeded("total expanded data exceeds " + maxTotalBytes + " bytes");
		}
		entryBytes += count;
		totalBytes += count;
	}

	private LimitExceededException limitExceeded(String reason) {
		final String entry = entryName == null ? "" : " at entry '" + entryName + "'";
		return new LimitExceededException("Archive extraction stopped" + entry + ": " + reason);
	}

	private static long positiveLongProperty(String name, long defaultValue) {
		final Long configured = Long.getLong(name);
		return configured != null && configured > 0 ? configured : defaultValue;
	}

	private static long saturatedMultiply(long left, long right) {
		if (left == 0 || right == 0) {
			return 0;
		}
		return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
	}

	private static long saturatedAdd(long left, long right) {
		return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
	}

	static final class LimitExceededException extends IOException {
		private static final long serialVersionUID = 1L;

		LimitExceededException(String message) {
			super(message);
		}
	}
}
