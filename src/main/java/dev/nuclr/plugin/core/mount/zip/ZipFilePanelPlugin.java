package dev.nuclr.plugin.core.mount.zip;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrPluginRole;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import dev.nuclr.plugin.event.PluginClosePanelEvent;
import dev.nuclr.plugin.event.PluginOpenItemEvent;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;

@Slf4j
public class ZipFilePanelPlugin implements NuclrPlugin, NuclrEventListener {

	private static final Set<String> ZIP_FAMILY_EXTENSIONS = Set.of(".zip", ".jar", ".war", ".ear");
	private static final Set<String> HANDLED_EXTENSIONS = Set.of(".zip", ".jar", ".war", ".ear", ".rar", ".tar", ".gz",
			".tgz");
	private static final String PANEL_STACK_PROVIDER_CLASS_METADATA = "commander.panelStack.providerClass";

	private final ConcurrentHashMap<URI, FileSystem> mountedFileSystems = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<FileSystem, Path> mountedArchiveSources = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, Path> extractedArchiveRoots = new ConcurrentHashMap<>();

	private NuclrPluginContext context;
	private ZipFilePanel panel;
	private boolean focused;

	@Override
	public JComponent panel() {
		if (panel == null) {
			panel = new ZipFilePanel(this);
		}
		return panel;
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		List<NuclrMenuResource> items = new ArrayList<>();
		items.add(menu("Help", "F1", "help"));
		items.add(menu("Copy", "F5", "copy"));
		items.add(menu("Move", "F6", "move"));
		items.add(menu("Quit", "F10", "quit"));
		items.add(menu("Plugins", "F11", "plugins"));
		return items;
	}

	private NuclrMenuResource menu(String name, String shortcut, String eventType) {
		var m = new ZipMenuResource();
		m.setName(name);
		m.setKeyStroke(shortcut);
		m.setEventType(eventType);
		return m;
	}

	@Override
	public void load(NuclrPluginContext context, boolean template) {

		this.context = context;

		if (false == template) {
			context.getEventBus().subscribe(this);
		}
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		for (FileSystem fileSystem : mountedFileSystems.values()) {
			try {
				fileSystem.close();
			} catch (IOException ignored) {
				// best effort
			}
		}
		mountedFileSystems.clear();
		mountedArchiveSources.clear();
		extractedArchiveRoots.clear();
	}

	@Override
	public List<NuclrResourcePath> getChangeDriveResources() {
		var resources = new ArrayList<NuclrResourcePath>();
		FileSystems.getDefault().getRootDirectories().forEach(path -> resources.add(toResource(path)));
		return resources;
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (cancelled != null && cancelled.get()) {
			return false;
		}
		if (resource == null || resource.getPath() == null) {
			return false;
		}
		Path browsablePath = resolveBrowsablePath(resource.getPath());
		if (browsablePath == null) {
			return false;
		}
		panel.showDirectory(browsablePath);
		return true;
	}

	@Override
	public boolean isMessageSupported(String type) {
		return true;
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> event) {

		// Ignore its own events
		if (source == this || source == panel) {
			return;
		}

		log.info("Received message - Source: {}, Type: {}, Event: {}", source, type, event);

		if (!focused || !(type.equals("ZipMenuActionEvent"))) {
			return;
		}

		if ("fs.copy".equals(type)) {
			// context.getEventBus().emit(new PluginCopyEvent(this,
			// panel.getSelectedResources()).getSourceProvider());
			return;
		}
		if ("fs.move".equals(type)) {
			// context.getEventBus().emit(new PluginMoveEvent(this, ((ZipFilePanel)
			// getPanel()).getSelectedResources()));
			return;
		}
	}

	public boolean isArchivePath(Path path) {
		return archiveType(path) != null;
	}

	public Path resolveBrowsablePath(Path path) {
		if (path == null) {
			return null;
		}
		if (Files.isDirectory(path)) {
			return path;
		}
		if (isArchivePath(path)) {
			try {
				return mountArchive(path);
			} catch (IOException ignored) {
				return null;
			}
		}
		return null;
	}

	public NuclrResourcePath toResource(Path path) {
		var resource = new NuclrResourcePath();
		resource.setPath(path);
		resource.setName(path.getFileName() == null ? path.toString() : path.getFileName().toString());
		try {
			if (Files.isRegularFile(path)) {
				resource.setSizeBytes(Files.size(path));
			}
		} catch (IOException ignored) {
			resource.setSizeBytes(0L);
		}
		return resource;
	}

	public boolean pushArchivePanel(Path archivePath) {
		if (context == null || !isArchivePath(archivePath)) {
			return false;
		}
		var event = new PluginOpenItemEvent(this, toStackResource(archivePath));
		context.getEventBus().emit("PluginOpenItemEvent", event.toEventData());
		return true;
	}

	public boolean popPanelLayer() {
		if (context == null) {
			return false;
		}
		var event = new PluginClosePanelEvent(this).toEvent();
		context.getEventBus().emit("PluginClosePanelEvent", event);
		return true;
	}

	public boolean isArchiveRoot(Path path) {
		Path archiveRoot = getArchiveRoot(path);
		return archiveRoot != null && archiveRoot.equals(path);
	}

	public Path getArchiveSource(Path path) {
		if (path == null) {
			return null;
		}
		Path mountedSource = mountedArchiveSources.get(path.getFileSystem());
		if (mountedSource != null) {
			return mountedSource;
		}
		for (Map.Entry<Path, Path> entry : extractedArchiveRoots.entrySet()) {
			if (path.normalize().startsWith(entry.getKey().normalize())) {
				return entry.getValue();
			}
		}
		return null;
	}

	public Path getArchiveRoot(Path path) {
		if (path == null) {
			return null;
		}
		if (mountedArchiveSources.containsKey(path.getFileSystem())) {
			return path.getFileSystem().getPath("/");
		}
		for (Path root : extractedArchiveRoots.keySet()) {
			if (path.normalize().startsWith(root.normalize())) {
				return root;
			}
		}
		return null;
	}

	public Path materializeFile(Path path) throws IOException {
		if (path == null) {
			return null;
		}
		if (path.getFileSystem().equals(FileSystems.getDefault())) {
			return path;
		}
		String fileName = path.getFileName() == null ? "archive-entry" : path.getFileName().toString();
		String prefix = fileName;
		String suffix = "";
		int dot = fileName.lastIndexOf('.');
		if (dot > 0) {
			prefix = fileName.substring(0, dot);
			suffix = fileName.substring(dot);
		}
		if (prefix.length() < 3) {
			prefix = (prefix + "___").substring(0, 3);
		}
		Path tempFile = Files.createTempFile("nuclr-" + prefix + "-", suffix);
		tempFile.toFile().deleteOnExit();
		Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);
		return tempFile;
	}

	private Path mountArchive(Path archivePath) throws IOException {
		Path mountSource = materializeArchiveIfNeeded(archivePath);
		ArchiveType archiveType = archiveType(archivePath);
		if (archiveType == null) {
			return null;
		}
		if (archiveType.usesZipFileSystem()) {
			if (isEncryptedArchive(mountSource)) {
				Path extractedRoot = findExtractedRoot(archivePath);
				if (extractedRoot != null) {
					return extractedRoot;
				}
				Path tempDir = createTempExtractionRoot("nuclr-zip-");
				new ZipFile(mountSource.toFile()).extractAll(tempDir.toString());
				extractedArchiveRoots.put(tempDir, archivePath);
				return tempDir;
			}

			URI uri = URI.create("jar:" + mountSource.toUri());
			FileSystem existing = mountedFileSystems.get(uri);
			if (existing != null && existing.isOpen()) {
				return existing.getPath("/");
			}

			FileSystem created = FileSystems.newFileSystem(uri, Map.of());
			FileSystem previous = mountedFileSystems.put(uri, created);
			if (previous != null && previous.isOpen()) {
				try {
					created.close();
				} catch (IOException ignored) {
					// keep existing fs
				}
				return previous.getPath("/");
			}
			mountedArchiveSources.put(created, archivePath);
			return created.getPath("/");
		}

		Path extractedRoot = findExtractedRoot(archivePath);
		if (extractedRoot != null) {
			return extractedRoot;
		}
		Path tempDir = createTempExtractionRoot("nuclr-archive-");
		extractArchive(mountSource, archiveType, tempDir);
		extractedArchiveRoots.put(tempDir, archivePath);
		return tempDir;
	}

	private Path materializeArchiveIfNeeded(Path archivePath) throws IOException {
		if (archivePath == null || archivePath.getFileSystem().equals(FileSystems.getDefault())) {
			return archivePath;
		}
		return materializeFile(archivePath);
	}

	private boolean isEncryptedArchive(Path archivePath) {
		try {
			return new ZipFile(archivePath.toFile()).isEncrypted();
		} catch (Exception ignored) {
			return false;
		}
	}

	private Path findExtractedRoot(Path archivePath) {
		return extractedArchiveRoots.entrySet().stream().filter(entry -> archivePath.equals(entry.getValue()))
				.map(Map.Entry::getKey).filter(Files::isDirectory).findFirst().orElse(null);
	}

	private Path createTempExtractionRoot(String prefix) throws IOException {
		Path tempDir = Files.createTempDirectory(prefix);
		tempDir.toFile().deleteOnExit();
		return tempDir;
	}

	private void extractArchive(Path archivePath, ArchiveType archiveType, Path targetDir) throws IOException {
		switch (archiveType) {
		case RAR -> extractRar(archivePath, targetDir);
		case TAR -> extractTar(archivePath, targetDir);
		case GZIP -> extractGzip(archivePath, targetDir);
		default -> throw new IOException("Unsupported archive type: " + archiveType);
		}
	}

	private void extractTar(Path archivePath, Path targetDir) throws IOException {
		try (InputStream inputStream = Files.newInputStream(archivePath);
				BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
				TarArchiveInputStream tarInputStream = new TarArchiveInputStream(bufferedInputStream)) {
			extractTarStream(tarInputStream, targetDir);
		}
	}

	private void extractGzip(Path archivePath, Path targetDir) throws IOException {
		try (InputStream inputStream = Files.newInputStream(archivePath);
				BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
				GzipCompressorInputStream gzipInputStream = new GzipCompressorInputStream(bufferedInputStream)) {
			String name = archivePath.getFileName() == null ? ""
					: archivePath.getFileName().toString().toLowerCase(Locale.ROOT);
			if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
				try (TarArchiveInputStream tarInputStream = new TarArchiveInputStream(gzipInputStream)) {
					extractTarStream(tarInputStream, targetDir);
				}
				return;
			}

			String outputName = stripSingleExtension(
					archivePath.getFileName() == null ? "archive-entry.gz" : archivePath.getFileName().toString(),
					".gz");
			Path outputPath = resolveArchiveEntryPath(targetDir, outputName);
			if (outputPath == null) {
				throw new IOException("Invalid GZ entry name: " + outputName);
			}
			Path parent = outputPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (var outputStream = Files.newOutputStream(outputPath, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				IOUtils.copy(gzipInputStream, outputStream);
			}
		}
	}

	private void extractTarStream(TarArchiveInputStream tarInputStream, Path targetDir) throws IOException {
		TarArchiveEntry entry;
		while ((entry = tarInputStream.getNextTarEntry()) != null) {
			Path outputPath = resolveArchiveEntryPath(targetDir, entry.getName());
			if (outputPath == null) {
				throw new IOException("Invalid TAR entry name: " + entry.getName());
			}
			if (entry.isDirectory()) {
				Files.createDirectories(outputPath);
				continue;
			}
			Path parent = outputPath.getParent();
			if (parent != null) {
				Files.createDirectories(parent);
			}
			try (var outputStream = Files.newOutputStream(outputPath, StandardOpenOption.CREATE,
					StandardOpenOption.TRUNCATE_EXISTING)) {
				IOUtils.copy(tarInputStream, outputStream);
			}
		}
	}

	private void extractRar(Path archivePath, Path targetDir) throws IOException {
		try (Archive archive = new Archive(archivePath.toFile())) {
			FileHeader entry;
			while ((entry = archive.nextFileHeader()) != null) {
				String entryName = entry.getFileNameString();
				Path outputPath = resolveArchiveEntryPath(targetDir, entryName);
				if (outputPath == null) {
					throw new IOException("Invalid RAR entry name: " + entryName);
				}
				if (entry.isDirectory()) {
					Files.createDirectories(outputPath);
					continue;
				}
				Path parent = outputPath.getParent();
				if (parent != null) {
					Files.createDirectories(parent);
				}
				try (var outputStream = Files.newOutputStream(outputPath, StandardOpenOption.CREATE,
						StandardOpenOption.TRUNCATE_EXISTING)) {
					archive.extractFile(entry, outputStream);
				}
			}
		} catch (Exception ex) {
			throw ex instanceof IOException ioException ? ioException
					: new IOException("Cannot extract RAR archive", ex);
		}
	}

	private Path resolveArchiveEntryPath(Path targetDir, String entryName) {
		if (entryName == null || entryName.isBlank()) {
			return null;
		}
		String normalizedName = entryName.replace('\\', '/');
		while (normalizedName.startsWith("/")) {
			normalizedName = normalizedName.substring(1);
		}
		if (normalizedName.isBlank()) {
			return null;
		}
		Path resolvedPath = targetDir.resolve(normalizedName).normalize();
		return resolvedPath.startsWith(targetDir.normalize()) ? resolvedPath : null;
	}

	private ArchiveType archiveType(Path path) {
		if (path == null || path.getFileName() == null) {
			return null;
		}
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
			return ArchiveType.GZIP;
		}
		if (ZIP_FAMILY_EXTENSIONS.stream().anyMatch(name::endsWith)) {
			return ArchiveType.ZIP_FAMILY;
		}
		if (name.endsWith(".rar")) {
			return ArchiveType.RAR;
		}
		if (name.endsWith(".tar")) {
			return ArchiveType.TAR;
		}
		if (name.endsWith(".gz")) {
			return ArchiveType.GZIP;
		}
		return HANDLED_EXTENSIONS.stream().anyMatch(name::endsWith) ? ArchiveType.GZIP : null;
	}

	private static String stripSingleExtension(String fileName, String extension) {
		if (fileName == null || fileName.isBlank()) {
			return "archive-entry";
		}
		String lowerCaseName = fileName.toLowerCase(Locale.ROOT);
		if (lowerCaseName.endsWith(extension) && fileName.length() > extension.length()) {
			return fileName.substring(0, fileName.length() - extension.length());
		}
		return fileName + ".out";
	}

	private enum ArchiveType {
		ZIP_FAMILY, RAR, TAR, GZIP;

		boolean usesZipFileSystem() {
			return this == ZIP_FAMILY;
		}
	}

	private NuclrResourcePath toStackResource(Path path) {
		NuclrResourcePath resource = toResource(path);
		Map<String, String> metadata = new HashMap<>();
		metadata.put(PANEL_STACK_PROVIDER_CLASS_METADATA, getClass().getName());
		resource.setMetadata(metadata);
		return resource;
	}

	@Override
	public boolean onFocusGained() {
		focused = true;
		panel.setPluginFocused(true);
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
		if (panel != null) {
			panel.setPluginFocused(false);
		}
	}

	public boolean isFocused() {
		return focused;
	}

	@Override
	public boolean supports(NuclrResourcePath resource) {
		return resource != null && resource.getPath() != null && isArchivePath(resource.getPath());
	}

	private String name = "Archive Panel";
	private String id = "dev.nuclr.plugin.core.mount.zip";
	private String version = "1.0.0";
	private String description = "Allows browsing ZIP, JAR, WAR, EAR, RAR, TAR and GZ archives in the file panel.";
	private String author = "Nuclr Development Team";
	private String license = "Apache-2.0";
	private String website = "https://nuclr.dev";
	private String pageUrl = "https://nuclr.dev/plugins/core/filepanel-zip.html";
	private String docUrl = "https://nuclr.dev/plugins/core/filepanel-zip.html";

	@Override
	public String id() {
		return id;
	}

	@Override
	public String name() {
		return name;
	}

	@Override
	public String version() {
		return version;
	}

	@Override
	public String description() {
		return description;
	}

	@Override
	public String author() {
		return author;
	}

	@Override
	public String license() {
		return license;
	}

	@Override
	public String website() {
		return website;
	}

	@Override
	public String pageUrl() {
		return pageUrl;
	}

	@Override
	public String docUrl() {
		return docUrl;
	}

	@Override
	public Developer type() {
		return Developer.Official;
	}

	@Override
	public void closeResource() {
	}

	@Override
	public int priority() {
		return 0;
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}

	@Override
	public NuclrPluginRole role() {
		return NuclrPluginRole.FilePanel;
	}

}
