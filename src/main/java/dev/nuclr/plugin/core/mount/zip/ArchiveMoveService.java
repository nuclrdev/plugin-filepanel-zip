/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

*/
package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/** Provider-neutral move and rename operations for writable archive views. */
@Slf4j
final class ArchiveMoveService {

	private static final String DialogTitle = "Rename/Move";

	private ArchiveMoveService() {
	}

	/** Prompt for a new single-segment name and rename one archive entry in place. */
	static Path renameInPlace(NuclrResource resource, NuclrPluginCallback callback) {
		Path source = resource != null ? resource.getPath() : null;
		if (source == null || "..".equals(resource.getName()) || source.getFileName() == null) {
			return null;
		}

		String newName = promptName(source.getFileName().toString());
		if (newName == null || Thread.currentThread().isInterrupted()) {
			return null;
		}

		try {
			return rename(source, newName, callback);
		} catch (IOException | RuntimeException e) {
			log.warn("Failed to rename archive entry [{}]: {}", source, e.getMessage(), e);
			if (callback != null) {
				callback.onError(resource.getName(), e instanceof Exception ex ? ex : new IOException(e));
			}
			showError(e.getMessage() != null ? e.getMessage() : "Could not rename the archive entry.");
			return null;
		}
	}

	/** Rename without prompting; package-visible for focused filesystem tests. */
	static Path rename(Path source, String newName, NuclrPluginCallback callback) throws IOException {
		if (source == null || source.getParent() == null || source.getFileName() == null) {
			throw new IOException("The archive entry cannot be renamed.");
		}

		String name = newName == null ? "" : newName.trim();
		if (isInvalidSingleName(name)) {
			throw new IOException("The new name must be a single non-empty file or folder name.");
		}

		Path target = source.resolveSibling(name);
		if (source.equals(target)) {
			return source;
		}
		if (Files.exists(target)) {
			throw new FileAlreadyExistsException(target.toString());
		}

		if (callback != null) {
			callback.onStart("Renaming " + source.getFileName());
		}
		Files.move(source, target);
		if (callback != null) {
			callback.onComplete();
		}
		return target;
	}

	/** Copy all sources first; only a completely successful copy is followed by source deletion. */
	static boolean moveInto(Path destination, List<Path> sources, NuclrPluginCallback callback) throws IOException {
		validateMoveSources(destination, sources);
		if (!ArchiveCopyService.copyInto(destination, sources, callback)) {
			return false;
		}

		for (Path source : sources) {
			if (source != null && Files.exists(source)) {
				deleteRecursively(source);
			}
		}
		return true;
	}

	private static void validateMoveSources(Path destination, List<Path> sources) throws IOException {
		if (destination == null || !Files.isDirectory(destination) || sources == null || sources.isEmpty()) {
			throw new IOException("The move source or destination is not available.");
		}
		for (Path source : sources) {
			if (source == null || !Files.exists(source) || source.getFileName() == null
					|| (!Files.isDirectory(source) && !Files.isRegularFile(source))) {
				throw new IOException("The move source is not a file or folder: " + source);
			}
			Path target = destination.resolve(source.getFileName().toString());
			try {
				if (Files.exists(target) && Files.isSameFile(source, target)) {
					throw new IOException("The source is already in the destination folder: " + source);
				}
			} catch (SecurityException e) {
				throw new IOException("Could not validate the move source: " + source, e);
			}
		}
	}

	private static void deleteRecursively(Path source) throws IOException {
		if (Files.isDirectory(source)) {
			try (var walk = Files.walk(source)) {
				for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
					Files.delete(path);
				}
			}
			return;
		}
		Files.delete(source);
	}

	private static boolean isInvalidSingleName(String name) {
		return name.isBlank()
				|| name.equals(".")
				|| name.equals("..")
				|| name.indexOf('/') >= 0
				|| name.indexOf('\\') >= 0
				|| name.indexOf('\0') >= 0;
	}

	private static String promptName(String currentName) {
		final Object[] result = new Object[1];
		runOnEdtAndWait(() -> result[0] = JOptionPane.showInputDialog(null, "New name:", DialogTitle,
				JOptionPane.PLAIN_MESSAGE, null, null, currentName));
		return result[0] == null ? null : result[0].toString();
	}

	private static void showError(String message) {
		runOnEdtAndWait(() -> JOptionPane.showMessageDialog(null, message, DialogTitle, JOptionPane.ERROR_MESSAGE));
	}

	private static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			log.warn("Interrupted while waiting for rename dialog on EDT: {}", e.getMessage(), e);
		} catch (Exception e) {
			log.warn("Failed to run rename dialog on EDT: {}", e.getMessage(), e);
		}
	}
}
