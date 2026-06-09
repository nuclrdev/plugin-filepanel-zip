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
package dev.nuclr.plugin.core.mount.zip.service;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;

import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.extern.slf4j.Slf4j;

/**
 * Deletes entries from an open (writable) archive mounted as a NIO Zip filesystem.
 *
 * <p>Deletion is recursive for folders (deepest-first) and always permanent: an archive entry
 * has no recycle bin, so the soft/physical distinction does not apply here. Runs synchronously
 * on the caller's (background) thread, reporting progress and honouring cancellation through
 * {@link NuclrPluginCallback}, and prompting via {@link ErrorPrompt} on failure.
 */
@Slf4j
public final class DeleteService {

	/** Per-item failure prompt. Return true to skip and continue, false to abort the operation. */
	@FunctionalInterface
	public interface ErrorPrompt {
		boolean onError(NuclrResource item, Exception e);
	}

	private DeleteService() {
	}

	public static void delete(List<NuclrResource> sources, NuclrPluginCallback cb, ErrorPrompt errorPrompt) {

		for (NuclrResource src : sources) {

			if (cb != null && cb.isCancelled()) {
				return;
			}

			Path path = src.getPath();
			if (path == null) {
				continue;
			}

			String name = displayName(src, path);
			if (cb != null) {
				cb.onStart(name);
			}

			try {
				deleteRecursively(path, cb);
				if (cb != null && cb.isCancelled()) {
					return; // cancelled mid-item: it may be only partially deleted, do not report success
				}
				if (cb != null) {
					cb.onComplete();
				}
			} catch (Exception e) {
				log.warn("Failed to delete [{}]: {}", path, e.getMessage(), e);
				if (cb != null) {
					cb.onError(name, e);
				}
				boolean skip = errorPrompt == null || errorPrompt.onError(src, e);
				if (!skip) {
					return; // Abort
				}
			}
		}
	}

	private static void deleteRecursively(Path path, NuclrPluginCallback cb) throws IOException {

		if (cb != null && cb.isCancelled()) {
			return;
		}

		BasicFileAttributes attrs;
		try {
			attrs = Files.readAttributes(path, BasicFileAttributes.class);
		} catch (NoSuchFileException alreadyGone) {
			return;
		}

		if (attrs.isDirectory()) {
			try (DirectoryStream<Path> children = Files.newDirectoryStream(path)) {
				for (Path child : children) {
					if (cb != null && cb.isCancelled()) {
						return;
					}
					deleteRecursively(child, cb);
				}
			}
		}

		if (cb != null && cb.isCancelled()) {
			return; // cancelled before removing this entry (a directory may be left partially emptied)
		}

		Files.delete(path);
		if (cb != null) {
			cb.onProgress(1, -1);
		}
	}

	private static String displayName(NuclrResource src, Path path) {
		if (src.getName() != null && !src.getName().isBlank()) {
			return src.getName();
		}
		Path fileName = path.getFileName();
		return fileName != null ? fileName.toString() : path.toString();
	}
}
