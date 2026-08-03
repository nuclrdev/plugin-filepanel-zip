/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

*/
package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;

/** Filesystem-provider-neutral recursive copying used by F5 and clipboard paste. */
final class ArchiveCopyService {

	private ArchiveCopyService() {
	}

	static List<Path> selectedPaths(List<NuclrResource> selectedResources, NuclrResource focusedResource) {
		List<NuclrResource> resources = selectedResources != null && !selectedResources.isEmpty()
				? selectedResources
				: focusedResource != null ? List.of(focusedResource) : List.of();

		var paths = new ArrayList<Path>();
		for (var resource : resources) {
			if (resource != null && resource.getPath() != null && !"..".equals(resource.getName())) {
				paths.add(resource.getPath());
			}
		}
		return paths;
	}

	/** Copy each source below {@code destination}; existing entries are replaced. */
	static boolean copyInto(Path destination, List<Path> sources, NuclrPluginCallback callback) throws IOException {
		if (destination == null || !Files.isDirectory(destination) || sources == null || sources.isEmpty()) {
			return false;
		}

		long total = countEntries(sources);
		long[] completed = { 0L };
		if (callback != null) {
			callback.onStart("Copying into archive");
		}

		for (Path source : sources) {
			if (cancelled(callback)) {
				return false;
			}
			if (source == null || !Files.exists(source)) {
				continue;
			}

			Path name = source.getFileName();
			if (name == null) {
				continue;
			}
			Path target = destination.resolve(name.toString());
			if (sameFile(source, target)) {
				target = availableCopyTarget(destination, name.toString());
			}

			if (Files.isDirectory(source)) {
				if (sameFileSystem(source, target)
						&& target.toAbsolutePath().normalize().startsWith(source.toAbsolutePath().normalize())) {
					throw new IOException("Cannot copy a folder into itself: " + source);
				}
				copyDirectory(source, target, callback, completed, total);
			} else if (Files.isRegularFile(source)) {
				Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
				progress(callback, ++completed[0], total);
			}
		}

		if (callback != null) {
			callback.onComplete();
		}
		return !cancelled(callback);
	}

	private static void copyDirectory(Path source, Path target, NuclrPluginCallback callback,
			long[] completed, long total) throws IOException {
		Files.walkFileTree(source, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (cancelled(callback)) {
					return FileVisitResult.TERMINATE;
				}
				Files.createDirectories(resolveRelative(target, source.relativize(dir)));
				progress(callback, ++completed[0], total);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (cancelled(callback)) {
					return FileVisitResult.TERMINATE;
				}
				Path destination = resolveRelative(target, source.relativize(file));
				Files.copy(file, destination, StandardCopyOption.REPLACE_EXISTING);
				progress(callback, ++completed[0], total);
				return FileVisitResult.CONTINUE;
			}
		});
	}

	private static long countEntries(List<Path> sources) throws IOException {
		long total = 0L;
		for (Path source : sources) {
			if (source == null || !Files.exists(source)) {
				continue;
			}
			if (Files.isDirectory(source)) {
				try (var walk = Files.walk(source)) {
					total += walk.count();
				}
			} else {
				total++;
			}
		}
		return total;
	}

	private static Path resolveRelative(Path base, Path relative) {
		Path result = base;
		for (Path segment : relative) {
			result = result.resolve(segment.toString());
		}
		return result;
	}

	private static Path availableCopyTarget(Path destination, String fileName) {
		int dot = fileName.lastIndexOf('.');
		String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
		String suffix = dot > 0 ? fileName.substring(dot) : "";
		for (int number = 1; ; number++) {
			String label = stem + " (copy" + (number == 1 ? "" : " " + number) + ")" + suffix;
			Path candidate = destination.resolve(label);
			if (!Files.exists(candidate)) {
				return candidate;
			}
		}
	}

	private static boolean sameFile(Path first, Path second) {
		try {
			return Files.exists(second) && Files.isSameFile(first, second);
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static boolean sameFileSystem(Path first, Path second) {
		return first.getFileSystem().equals(second.getFileSystem());
	}

	private static boolean cancelled(NuclrPluginCallback callback) {
		return callback != null && callback.isCancelled();
	}

	private static void progress(NuclrPluginCallback callback, long current, long total) {
		if (callback != null) {
			callback.onProgress(current, total);
		}
	}
}
