package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import dev.nuclr.plugin.mount.ArchiveMountProvider;
import dev.nuclr.plugin.panel.Capabilities;

/**
 * {@link ArchiveMountProvider} for ZIP and JAR archives using the NIO.2
 * built-in ZIP filesystem provider ({@code com.sun.nio.zipfs}).
 *
 * <p>Handles {@code .zip}, {@code .jar}, {@code .war}, and {@code .ear} files.
 * Each unique archive path is mounted at most once; subsequent requests return
 * the same live filesystem root.
 */
public class ZipArchiveMountProvider implements ArchiveMountProvider {

	private static final java.util.Set<String> HANDLED_EXTENSIONS =
			java.util.Set.of(".zip", ".jar", ".war", ".ear");

	private final ConcurrentHashMap<URI, java.nio.file.FileSystem> cache = new ConcurrentHashMap<>();

	@Override
	public boolean canHandle(Path file) {
		if (file == null || file.getFileName() == null) return false;
		String name = file.getFileName().toString().toLowerCase();
		int dot = name.lastIndexOf('.');
		return dot >= 0 && HANDLED_EXTENSIONS.contains(name.substring(dot));
	}

	@Override
	public Path mountAndGetRoot(Path file) throws IOException {
		URI jarUri = URI.create("jar:" + file.toUri() + "!/");

		java.nio.file.FileSystem fs = cache.get(jarUri);
		if (fs != null && fs.isOpen()) {
			return fs.getPath("/");
		}

		fs = FileSystems.newFileSystem(jarUri, Map.of());
		cache.put(jarUri, fs);
		return fs.getPath("/");
	}

	@Override
	public Capabilities capabilities() {
		return Capabilities.zipArchive();
	}

	@Override
	public int priority() {
		return 10;
	}
}
