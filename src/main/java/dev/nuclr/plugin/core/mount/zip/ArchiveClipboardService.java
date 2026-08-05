/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.

*/
package dev.nuclr.plugin.core.mount.zip;

import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** System-clipboard bridge for entries that live on a ZIP filesystem. */
final class ArchiveClipboardService {
	private static final DataFlavor URI_LIST_FLAVOR = new DataFlavor(
			"text/uri-list;class=java.lang.String;charset=UTF-8", "URI list");
	private static final DataFlavor GNOME_COPIED_FILES_FLAVOR = new DataFlavor(
			"x-special/gnome-copied-files;class=java.lang.String", "GNOME copied files");

	private ArchiveClipboardService() {
	}

	/**
	 * Materialise archive entries on the default filesystem, then publish both a
	 * native file list (for paste) and readable archive paths (for text targets).
	 *
	 * @return the materialisation root, owned by the caller, or {@code null}
	 */
	static Path copy(List<Path> sources, List<String> displayPaths) throws IOException {
		if (sources == null || sources.isEmpty()) {
			return null;
		}

		Path root = Files.createTempDirectory("nuclr-archive-clipboard-");
		try {
			if (!ArchiveCopyService.copyInto(root, sources, null)) {
				deleteRecursively(root);
				return null;
			}
			List<File> files;
			try (var stream = Files.list(root)) {
				files = stream.map(Path::toFile).toList();
			}
			String text = displayPaths == null ? "" : String.join(System.lineSeparator(), displayPaths);
			Toolkit.getDefaultToolkit().getSystemClipboard()
					.setContents(new FilesAndTextTransferable(files, text), null);
			registerDeleteOnExit(root);
			return root;
		} catch (IOException | RuntimeException e) {
			deleteRecursively(root);
			throw e;
		}
	}

	static List<Path> readPaths() {
		try {
			Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
			return readPaths(contents);
		} catch (RuntimeException e) {
			return List.of();
		}
	}

	/** Decode the native clipboard formats used by Java, GNOME and other Linux desktops. */
	static List<Path> readPaths(Transferable contents) {
		if (contents == null) {
			return List.of();
		}

		if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
			try {
				Object value = contents.getTransferData(DataFlavor.javaFileListFlavor);
				if (value instanceof List<?> entries) {
					var paths = new ArrayList<Path>();
					for (Object entry : entries) {
						if (entry instanceof File file && Files.exists(file.toPath())) {
							paths.add(file.toPath());
						}
					}
					if (!paths.isEmpty()) {
						return paths;
					}
				}
			} catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
				// Some Linux clipboard bridges advertise this flavor but cannot render it.
			}
		}

		DataFlavor gnomeFlavor = findFlavor(contents, "x-special", "gnome-copied-files");
		if (gnomeFlavor != null) {
			try {
				List<Path> paths = existingUriPaths(readText(contents, gnomeFlavor));
				if (!paths.isEmpty()) {
					return paths;
				}
			} catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
				// Try the standard URI list next.
			}
		}

		DataFlavor uriFlavor = findFlavor(contents, "text", "uri-list");
		if (uriFlavor != null) {
			try {
				List<Path> paths = existingUriPaths(readText(contents, uriFlavor));
				if (!paths.isEmpty()) {
					return paths;
				}
			} catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
				// Fall back to plain text paths.
			}
		}

		if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
			try {
				Object value = contents.getTransferData(DataFlavor.stringFlavor);
				return value instanceof String text ? existingTextPaths(text) : List.of();
			} catch (IOException | UnsupportedFlavorException | RuntimeException ignored) {
				return List.of();
			}
		}
		return List.of();
	}

	static List<Path> existingTextPaths(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		var paths = new ArrayList<Path>();
		for (String line : text.lines().toList()) {
			String candidate = line.strip();
			if (candidate.length() >= 2 && candidate.startsWith("\"") && candidate.endsWith("\"")) {
				candidate = candidate.substring(1, candidate.length() - 1).strip();
			}
			try {
				Path path = Path.of(candidate);
				if (Files.exists(path)) {
					paths.add(path);
				}
			} catch (InvalidPathException ignored) {
				// Clipboard text is untrusted and may not be a path.
			}
		}
		return paths;
	}

	static List<Path> existingUriPaths(String text) {
		if (text == null || text.isBlank()) {
			return List.of();
		}
		var paths = new ArrayList<Path>();
		for (String line : text.lines().toList()) {
			String candidate = line.strip();
			if (candidate.isEmpty() || candidate.startsWith("#")
					|| "copy".equalsIgnoreCase(candidate) || "cut".equalsIgnoreCase(candidate)) {
				continue;
			}
			try {
				URI uri = URI.create(candidate);
				if ("file".equalsIgnoreCase(uri.getScheme())) {
					Path path = Path.of(uri);
					if (Files.exists(path)) {
						paths.add(path);
					}
				}
			} catch (IllegalArgumentException ignored) {
				// Ignore malformed and non-file clipboard entries.
			}
		}
		return paths;
	}

	private static DataFlavor findFlavor(Transferable contents, String primaryType, String subType) {
		for (DataFlavor flavor : contents.getTransferDataFlavors()) {
			if (primaryType.equalsIgnoreCase(flavor.getPrimaryType())
					&& subType.equalsIgnoreCase(flavor.getSubType())) {
				return flavor;
			}
		}
		return null;
	}

	private static String readText(Transferable contents, DataFlavor flavor)
			throws IOException, UnsupportedFlavorException {
		Object value = contents.getTransferData(flavor);
		if (value instanceof String text) {
			return text;
		}
		if (value instanceof Reader reader) {
			try (reader; var writer = new StringWriter()) {
				reader.transferTo(writer);
				return writer.toString();
			}
		}
		Charset charset = charset(flavor);
		if (value instanceof InputStream stream) {
			try (stream) {
				return new String(stream.readAllBytes(), charset);
			}
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, charset);
		}
		return value == null ? "" : value.toString();
	}

	private static Charset charset(DataFlavor flavor) {
		String name = flavor.getParameter("charset");
		if (name == null || name.isBlank()) {
			return StandardCharsets.UTF_8;
		}
		try {
			return Charset.forName(name);
		} catch (RuntimeException ignored) {
			return StandardCharsets.UTF_8;
		}
	}

	private static void registerDeleteOnExit(Path root) {
		// Linux clipboard data is requested lazily. Do not remove these files when the
		// archive panel unloads; they must survive until the application exits.
		try (var walk = Files.walk(root)) {
			walk.forEach(path -> path.toFile().deleteOnExit());
		} catch (IOException ignored) {
			root.toFile().deleteOnExit();
		}
	}

	static void deleteRecursively(Path root) {
		if (root == null || !Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException ignored) {
					// Best-effort cleanup of clipboard materialisation.
				}
			});
		} catch (IOException ignored) {
			// Best-effort cleanup of clipboard materialisation.
		}
	}

	private record FilesAndTextTransferable(List<File> files, String text) implements Transferable {
		private static final DataFlavor[] FLAVORS = { DataFlavor.javaFileListFlavor, URI_LIST_FLAVOR,
				GNOME_COPIED_FILES_FLAVOR, DataFlavor.stringFlavor };

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return FLAVORS.clone();
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			for (DataFlavor supported : FLAVORS) {
				if (supported.equals(flavor)) {
					return true;
				}
			}
			return false;
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (DataFlavor.javaFileListFlavor.equals(flavor)) {
				return files;
			}
			String uris = files.stream().map(File::toURI).map(URI::toASCIIString)
					.collect(java.util.stream.Collectors.joining("\r\n"));
			if (URI_LIST_FLAVOR.equals(flavor)) {
				return uris;
			}
			if (GNOME_COPIED_FILES_FLAVOR.equals(flavor)) {
				return "copy\n" + uris;
			}
			if (DataFlavor.stringFlavor.equals(flavor)) {
				return text;
			}
			throw new UnsupportedFlavorException(flavor);
		}
	}
}
