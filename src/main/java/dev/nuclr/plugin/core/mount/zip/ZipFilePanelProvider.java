package dev.nuclr.plugin.core.mount.zip;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JComponent;

import com.fasterxml.jackson.databind.ObjectMapper;

import dev.nuclr.plugin.ApplicationPluginContext;
import dev.nuclr.plugin.MenuResource;
import dev.nuclr.plugin.PanelProviderPlugin;
import dev.nuclr.plugin.PluginManifest;
import dev.nuclr.plugin.PluginPathResource;
import dev.nuclr.plugin.event.PluginClosePanelEvent;
import dev.nuclr.plugin.event.PluginOpenItemEvent;
import net.lingala.zip4j.ZipFile;

public class ZipFilePanelProvider implements PanelProviderPlugin {

	private static final Set<String> HANDLED_EXTENSIONS = Set.of(".zip", ".jar", ".war", ".ear");
	private static final String PANEL_STACK_PROVIDER_CLASS_METADATA = "commander.panelStack.providerClass";

	private final ConcurrentHashMap<URI, FileSystem> mountedFileSystems = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<FileSystem, Path> mountedArchiveSources = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<Path, Path> extractedArchiveRoots = new ConcurrentHashMap<>();

	private ApplicationPluginContext context;
	private ZipFilePanel panel;
	private boolean focused;

	@Override
	public PluginManifest getPluginInfo() {
		ObjectMapper objectMapper = context != null ? context.getObjectMapper() : new ObjectMapper();
		try (var is = getClass().getResourceAsStream("/plugin.json")) {
			if (is != null) {
				return objectMapper.readValue(is, PluginManifest.class);
			}
		} catch (Exception ignored) {
			return null;
		}
		return null;
	}

	@Override
	public JComponent getPanel() {
		if (panel == null) {
			panel = new ZipFilePanel(this, this::openDocumentation);
		}
		return panel;
	}

	@Override
	public List<MenuResource> getMenuItems(PluginPathResource source) {
		return List.of();
	}

	@Override
	public void load(ApplicationPluginContext context) {
		this.context = context;
	}

	@Override
	public void unload() {
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
	public List<PluginPathResource> getChangeDriveResources() {
		var resources = new ArrayList<PluginPathResource>();
		FileSystems.getDefault().getRootDirectories().forEach(path -> resources.add(toResource(path)));
		return resources;
	}

	@Override
	public boolean openItem(PluginPathResource resource, AtomicBoolean cancelled) {
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
		ZipFilePanel zipPanel = (ZipFilePanel) getPanel();
		if (isArchiveRoot(browsablePath)) {
			zipPanel.showArchiveRoot(browsablePath);
		} else {
			zipPanel.showDirectory(browsablePath);
		}
		return true;
	}

	public boolean isArchivePath(Path path) {
		if (path == null || path.getFileName() == null) {
			return false;
		}
		String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
		return HANDLED_EXTENSIONS.stream().anyMatch(name::endsWith);
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

	public PluginPathResource toResource(Path path) {
		var resource = new PluginPathResource();
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
		PluginOpenItemEvent event = new PluginOpenItemEvent(this, toStackResource(archivePath));
		context.getEventBus().emit(event);
		return event.isHandled();
	}

	public boolean popPanelLayer() {
		if (context == null) {
			return false;
		}
		PluginClosePanelEvent event = new PluginClosePanelEvent(this);
		context.getEventBus().emit(event);
		return event.isHandled();
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
		if (isEncryptedArchive(mountSource)) {
			Path extractedRoot = extractedArchiveRoots.entrySet().stream()
					.filter(entry -> archivePath.equals(entry.getValue()))
					.map(Map.Entry::getKey)
					.filter(Files::isDirectory)
					.findFirst()
					.orElse(null);
			if (extractedRoot != null) {
				return extractedRoot;
			}
			Path tempDir = Files.createTempDirectory("nuclr-zip-");
			tempDir.toFile().deleteOnExit();
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

	private PluginPathResource toStackResource(Path path) {
		PluginPathResource resource = toResource(path);
		Map<String, String> metadata = new HashMap<>();
		metadata.put(PANEL_STACK_PROVIDER_CLASS_METADATA, getClass().getName());
		resource.setMetadata(metadata);
		return resource;
	}

	private void openDocumentation() {
		PluginManifest pluginInfo = getPluginInfo();
		if (pluginInfo == null || pluginInfo.getDocUrl() == null || pluginInfo.getDocUrl().isBlank()) {
			return;
		}
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
			return;
		}
		try {
			Desktop.getDesktop().browse(URI.create(pluginInfo.getDocUrl()));
		} catch (Exception ignored) {
			// best effort
		}
	}

	@Override
	public void onFocusGained() {
		focused = true;
		((ZipFilePanel) getPanel()).setPluginFocused(true);
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
	public boolean canSupport(PluginPathResource resource) {
		return resource != null && resource.getPath() != null && isArchivePath(resource.getPath());
	}
}
