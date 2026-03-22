package dev.nuclr.plugin.core.mount.zip;

/**
 * Kept as a stub so old compiled references fail fast with a clear message.
 *
 * <p>The current Plugin SDK no longer exposes the archive mount SPI used by the
 * original implementation. Archive browsing is now provided by
 * {@link ZipFilePanelProvider} via the panel provider API.
 */
@Deprecated
public final class ZipArchiveMountProvider {

	public ZipArchiveMountProvider() {
		throw new UnsupportedOperationException(
				"ZipArchiveMountProvider is obsolete. Use ZipFilePanelProvider with the current Plugin SDK.");
	}
}
