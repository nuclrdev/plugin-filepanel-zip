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
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** System-clipboard bridge for entries that live on a ZIP filesystem. */
final class ArchiveClipboardService {

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
			return root;
		} catch (IOException | RuntimeException e) {
			deleteRecursively(root);
			throw e;
		}
	}

	static List<Path> readPaths() {
		try {
			Transferable contents = Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
			if (contents == null) {
				return List.of();
			}

			if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
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
			}

			if (contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
				Object value = contents.getTransferData(DataFlavor.stringFlavor);
				return value instanceof String text ? existingTextPaths(text) : List.of();
			}
		} catch (IOException | UnsupportedFlavorException | RuntimeException e) {
			return List.of();
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
		private static final DataFlavor[] FLAVORS = { DataFlavor.javaFileListFlavor, DataFlavor.stringFlavor };

		@Override
		public DataFlavor[] getTransferDataFlavors() {
			return FLAVORS.clone();
		}

		@Override
		public boolean isDataFlavorSupported(DataFlavor flavor) {
			return DataFlavor.javaFileListFlavor.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
		}

		@Override
		public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
			if (DataFlavor.javaFileListFlavor.equals(flavor)) {
				return files;
			}
			if (DataFlavor.stringFlavor.equals(flavor)) {
				return text;
			}
			throw new UnsupportedFlavorException(flavor);
		}
	}
}
