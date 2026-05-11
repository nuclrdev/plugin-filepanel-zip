package dev.nuclr.plugin.core.mount.zip;

import java.awt.BorderLayout;
import java.awt.Dialog;
import java.awt.Window;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResourcePath;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.exception.ZipException;

@Data
@Slf4j
public class ZipFilePanelPlugin implements FilePanelNuclrPlugin, NuclrEventListener {

	private String uuid = java.util.UUID.randomUUID().toString();
	
	// -------------------------------------------------------------------------
	// Plugin metadata constants
	// -------------------------------------------------------------------------

	public static final String  PLUGIN_ID          = "dev.nuclr.plugin.core.mount.zip";
	private static final String PLUGIN_NAME        = "Archive Panel";
	private static final String PLUGIN_VERSION     = "1.0.0";
	private static final String PLUGIN_DESCRIPTION = "Browse ZIP, JAR, WAR, EAR, RAR, TAR and GZ archives in the file panel.";
	private static final String PLUGIN_AUTHOR      = "Nuclr Development Team";
	private static final String PLUGIN_LICENSE     = "Apache-2.0";
	private static final String PLUGIN_WEBSITE     = "https://nuclr.dev";
	private static final String PLUGIN_PAGE_URL    = "https://nuclr.dev/plugins/core/filepanel-zip.html";
	private static final String PLUGIN_DOC_URL     = PLUGIN_PAGE_URL;
	private static final String PLUGIN_UNLOAD_EVENT = "plugin.unload";

	// -------------------------------------------------------------------------
	// Event type constants
	//
	// MENU_ACTION_EVENT_TYPE  — emitted by the Commander when the user presses a
	//                           function key while this panel is focused.
	// COPY_RESOURCES_EVENT    — we emit this; Commander routes it to the opposite
	//                           panel as "fs.copy".
	// MOVE_RESOURCES_EVENT    — we emit this; Commander routes it to the opposite
	//                           panel as "fs.move".
	// FS_COPY_EVENT           — the Commander sends this to US when the opposite
	//                           panel wants to copy files into our directory.
	// FS_MOVE_EVENT           — same as FS_COPY_EVENT but for move.
	// -------------------------------------------------------------------------

	private static final String MENU_ACTION_EVENT_TYPE = "dev.nuclr.plugin.core.mount.zip.menuAction";
	private static final String FS_COPY_EVENT          = "fs.copy";
	private static final String FS_MOVE_EVENT          = "fs.move";
	private static final String THEME_UPDATED_EVENT    = "dev.nuclr.platform.theme.updated";

	/**
	 * Metadata key that tells the Commander which plugin class should handle
	 * a panel-stack push for an archive resource.
	 */
	private static final String PANEL_STACK_PROVIDER_CLASS = "commander.panelStack.providerClass";

	// -------------------------------------------------------------------------
	// Archive format classification
	// -------------------------------------------------------------------------

	private static final Set<String> ZIP_FAMILY_EXTENSIONS =
			Set.of(".zip", ".jar", ".war", ".ear");
	private static final List<Charset> LEGACY_ZIP_NAME_CHARSETS = List.of(
			Charset.forName("UTF-8"),
			Charset.forName("CP866"),
			Charset.forName("windows-1251"));

	private static final Set<String> ALL_ARCHIVE_EXTENSIONS =
			Set.of(".zip", ".jar", ".war", ".ear", ".rar", ".tar", ".gz", ".tgz");

	// -------------------------------------------------------------------------
	// Runtime state
	// -------------------------------------------------------------------------

	/** NIO ZIP filesystems currently mounted, keyed by their jar: URI. */
	private final ConcurrentHashMap<URI, FileSystem> mountedFileSystems = new ConcurrentHashMap<>();

	/** For each mounted ZIP filesystem, the real-filesystem path of the archive file. */
	private final ConcurrentHashMap<FileSystem, Path> mountedArchiveSources = new ConcurrentHashMap<>();

	/** For each mounted ZIP filesystem, the real file opened by the ZIP provider. */
	private final ConcurrentHashMap<FileSystem, Path> mountedArchiveBackingFiles = new ConcurrentHashMap<>();

	/**
	 * Extracted temp directories for archives that cannot be mounted via NIO
	 * (RAR, TAR, encrypted ZIP).  Key = temp dir, value = original archive path.
	 */
	private final ConcurrentHashMap<Path, Path> extractedArchiveRoots = new ConcurrentHashMap<>();

	/** Temp files created to materialize virtual-filesystem entries for Desktop.open(). */
	private final Set<Path> materializedTempFiles = ConcurrentHashMap.newKeySet();

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResourcePath currentFolder;

	// =========================================================================
	// NuclrPlugin — lifecycle
	// =========================================================================

	@Override
	public void preinit(NuclrPluginContext context) {
		this.context = context;
		log.info("Archive panel plugin loaded");
	}

	@Override
	public NuclrPluginContext getContext() {
		return context;
	}

	@Override
	public void init() {
		if (context != null) {
			context.getEventBus().subscribe(this);
		}
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		closeMountedFileSystems();
		deleteExtractedTempDirs();
		deleteMaterializedTempFiles();
		log.info("Archive panel plugin unloaded");
	}

	private void closeMountedFileSystems() {
		List<FileSystem> fileSystems = new ArrayList<>(mountedArchiveSources.keySet());
		fileSystems.sort(Comparator.comparingInt(this::archiveDepth).reversed());
		for (FileSystem fs : fileSystems) {
			try {
				closeMountedFileSystem(fs);
			} catch (IOException ignored) { }
		}
		mountedFileSystems.clear();
		mountedArchiveSources.clear();
		mountedArchiveBackingFiles.clear();
	}

	private void deleteExtractedTempDirs() {
		for (Path tempDir : extractedArchiveRoots.keySet()) {
			deleteRecursively(tempDir);
		}
		extractedArchiveRoots.clear();
	}

	private void deleteMaterializedTempFiles() {
		for (Path tempFile : materializedTempFiles) {
			try { Files.deleteIfExists(tempFile); } catch (IOException ignored) { }
		}
		materializedTempFiles.clear();
	}

	@Override
	public String id()          { return PLUGIN_ID; }
	@Override
	public String name()        { return PLUGIN_NAME; }
	@Override
	public String version()     { return PLUGIN_VERSION; }
	@Override
	public String description() { return PLUGIN_DESCRIPTION; }
	@Override
	public String author()      { return PLUGIN_AUTHOR; }
	@Override
	public String license()     { return PLUGIN_LICENSE; }
	@Override
	public String website()     { return PLUGIN_WEBSITE; }
	@Override
	public String pageUrl()     { return PLUGIN_PAGE_URL; }
	@Override
	public String docUrl()      { return PLUGIN_DOC_URL; }
	@Override
	public Developer developer() { return Developer.Official; }
	@Override
	public boolean singleton()  { return false; }

	// =========================================================================
	// NuclrPlugin — focus
	// =========================================================================

	@Override
	public boolean onFocusGained() {
		focused = true;
		return true;
	}

	@Override
	public void onFocusLost() {
		focused = false;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	// =========================================================================
	// NuclrPlugin — resource handling
	// =========================================================================

	@Override
	public boolean supports(NuclrResourcePath resource) {
		return resource != null && resource.getPath() != null && isArchivePath(resource.getPath());
	}

	@Override
	public boolean openResource(NuclrResourcePath resource, AtomicBoolean cancelled) {
		if (resource == null || resource.getPath() == null) {
			unloadCurrentInstance();
			return false;
		}
		if (cancelled != null && cancelled.get()) {
			unloadCurrentInstance();
			return false;
		}
		Path browsable = resolveBrowsablePath(resource.getPath());
		if (browsable == null) {
			unloadCurrentInstance();
			return false;
		}
		if (shouldExitArchive(resource, browsable)) {
			unloadCurrentInstance();
			return false;
		}
		this.currentFolder = toResource(browsable);
		return true;
	}

	@Override
	public void closeResource() {
		// No per-resource teardown needed; unload() handles global cleanup.
	}

	@Override
	public void updateTheme(NuclrThemeScheme themeScheme) {
	}

	// =========================================================================
	// NuclrPlugin — drive list & menu
	// =========================================================================

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResourcePath source) {
		boolean isDir = source != null && source.getPath() != null && Files.isDirectory(source.getPath());
		List<NuclrMenuResource> items = new ArrayList<>();
		items.add(menu("Help",                      "F1"));
		items.add(menu("Copy",                      "F5"));
		items.add(menu(isDir ? "Move" : "Move",     "F6"));
		items.add(menu("Make Folder",               "F7"));
		items.add(menu("Delete",                    "F8"));
		items.add(menu("Quit",                      "F10"));
		items.add(menu("Plugins",                   "F11"));
		return items;
	}

	private NuclrMenuResource menu(String name, String keyStroke) {
		return new NuclrMenuResource(name, keyStroke, MENU_ACTION_EVENT_TYPE);
	}

	@Override
	public PluginRoots getPluginRoots() {
		return null; // no roots; this plugin only opens in response to user navigation into archives
	}

	@Override
	public List<NuclrResourcePath> getChildrenForCurrentResource() {
		Path directory = currentFolder != null ? currentFolder.getPath() : null;
		if (directory == null || !Files.isDirectory(directory)) {
			return List.of();
		}
		List<NuclrResourcePath> children = new ArrayList<>();
		if (isArchiveRoot(directory)) {
			NuclrResourcePath parentEntry = createArchiveRootParentResource(directory);
			if (parentEntry != null) {
				children.add(parentEntry);
			}
		}
		try (var stream = Files.list(directory)) {
			children.addAll(stream
					.map(this::toResource)
					.toList());
			return children;
		} catch (IOException ex) {
			log.error("Failed to list children for resource: {}", currentFolder, ex);
			return List.of();
		}
	}

	@Override
	public String getCurrentLocationDisplayText() {
		Path currentPath = currentFolder != null ? currentFolder.getPath() : null;
		if (currentPath == null) {
			return " ";
		}
		Path archiveSource = getArchiveSource(currentPath);
		if (archiveSource == null) {
			return currentPath.toString();
		}
		Path archiveRoot = getArchiveRoot(currentPath);
		if (archiveRoot == null || archiveRoot.equals(currentPath)) {
			return archiveSource.toString();
		}
		Path relative = archiveRoot.relativize(currentPath);
		String suffix = relative.toString().replace('\\', '/');
		return suffix.isBlank() ? archiveSource.toString() : archiveSource + "/" + suffix;
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResourcePath> selectedResources) {
		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}
		if (selectedResources.size() == 1) {
			NuclrResourcePath resource = selectedResources.get(0);
			Path path = resource.getPath();
			boolean directory = path != null && Files.isDirectory(path);
			String type = directory ? "Folder" : humanReadableSize(sizeBytes(resource, directory));
			String name = resource.getName() != null && !resource.getName().isBlank()
					? resource.getName()
					: path == null ? "" : path.getFileName() == null ? path.toString() : path.getFileName().toString();
			return name + "  |  " + type;
		}
		long totalBytes = 0L;
		int fileCount = 0;
		int folderCount = 0;
		for (NuclrResourcePath resource : selectedResources) {
			Path path = resource.getPath();
			if (path != null && Files.isDirectory(path)) {
				folderCount++;
			} else {
				fileCount++;
				totalBytes += sizeBytes(resource, false);
			}
		}
		return "Bytes: " + humanReadableSize(totalBytes)
				+ ",  files: " + fileCount
				+ ",  folders: " + folderCount;
	}

	// =========================================================================
	// NuclrEventListener — incoming event bus messages
	// =========================================================================

	@Override
	public boolean isMessageSupported(String type) {
		return true;
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> event) {

		// Never process our own emissions
		if (source == this) {
			return;
		}

		log.debug("handleMessage type={}", type);

		// ------------------------------------------------------------------
		// Incoming copy: the opposite panel wants to copy files into our
		// current directory.  Only handle when we are the focused (target) panel.
		// ------------------------------------------------------------------
		if (FS_COPY_EVENT.equals(type) && focused) {
			List<NuclrResourcePath> paths = extractResourceList(event, "paths");
			if (!paths.isEmpty()) {
				if (handleCopyIntoArchive(paths, null)) {
					markAccepted(event);
				}
			}
			return;
		}

		// ------------------------------------------------------------------
		// Incoming move: same as copy, but the source panel also deletes its
		// files afterwards (handled by the source panel's move engine).
		// ------------------------------------------------------------------
		if (FS_MOVE_EVENT.equals(type) && focused) {
			List<NuclrResourcePath> paths = extractResourceList(event, "paths");
			if (!paths.isEmpty()) {
				// The source refresh callback is not passed through the event bus;
				// our responsibility is just to receive the files.
				handleCopyIntoArchive(paths, null);
			}
			return;
		}

	}

	/**
	 * Add incoming files (from the opposite panel) into our current archive directory.
	 *
	 * <p>Only supported when the current directory lives in a mounted NIO ZIP
	 * filesystem (i.e. writes persist to the archive).  For extracted temp
	 * directories (TAR, RAR) the operation is rejected with a status message.
	 */
	private boolean handleCopyIntoArchive(List<NuclrResourcePath> resources, Runnable onSourceRefresh) {
		Path targetDir = this.currentFolder.getPath();
		if (targetDir == null) {
			return false;
		}
		if (!isWritableArchiveDirectory(targetDir)) {
			log.info("Copy-into ignored: archive is read-only (extracted temp dir)");
			return false;
		}

		List<Path> sources = resources.stream()
				.filter(r -> r != null && r.getPath() != null)
				.map(NuclrResourcePath::getPath)
				.collect(Collectors.toList());

		if (sources.isEmpty()) {
			return false;
		}
/*
		writeService.addFiles(targetDir, sources, panel, () -> {
			refreshCurrentResourceAfterWrite();
			if (onSourceRefresh != null) {
				onSourceRefresh.run();
			}
		});
		*/
		return true;
	}

	private static void markAccepted(Map<String, Object> event) {
		if (event != null && event.get("accepted") instanceof AtomicBoolean accepted) {
			accepted.set(true);
		}
	}

	// =========================================================================
	// Copy / move emission (called from ZipFilePanel via keyboard)
	// =========================================================================

	/**
	 * Emit a copy request so the Commander routes it to the opposite panel.
	 * The opposite panel will call its own copy workflow with our selected
	 * resources as the sources.
	 */
	void emitCopyRequest(List<NuclrResourcePath> selectedResources) {
		if (context == null || selectedResources.isEmpty()) {
			return;
		}
		
		AtomicBoolean accepted = new AtomicBoolean(false);
		Map<String, Object> payload = new HashMap<>();
		payload.put("paths", selectedResources);
		payload.put("accepted", accepted);
		context.getEventBus().emit(this, "fs.copy", payload);
		log.info("Emitted copy request for {} item(s)", selectedResources.size());
	}

	/**
	 * Emit a move request so the Commander routes it to the opposite panel.
	 * The opposite panel copies the files then deletes them from their source
	 * (which, for archive NIO paths, means the ZIP entries get deleted).
	 */
	void emitMoveRequest(List<NuclrResourcePath> selectedResources) {
		if (context == null || selectedResources.isEmpty()) {
			return;
		}
		
		List<NuclrResourcePath> resources = selectedResources;
		AtomicBoolean accepted = new AtomicBoolean(false);
		Map<String, Object> payload = new HashMap<>();
		payload.put("paths", resources);
		payload.put("accepted", accepted);
		// payload.put("refreshSource", (Runnable) this::refreshCurrentResourceAfterWrite);
		context.getEventBus().emit(this, "fs.move", payload);
		
		log.info("Emitted move request for {} item(s)", selectedResources.size());
	}

	// =========================================================================
	// Panel stack (open nested archive / go back)
	// =========================================================================

	/**
	 * Ask the Commander to push a new archive panel on top of the current one.
	 *
	 * @return {@code true} if the event was emitted
	 */
	public boolean pushArchivePanel(Path archivePath) {
		if (context == null || !isArchivePath(archivePath)) {
			return false;
		}
		context.getEventBus().emit("fs.path.opened", Map.of("path", archivePath));
		return true;
	}

	/**
	 * Ask the Commander to pop the current archive panel layer (go back to caller).
	 *
	 * @return {@code true} if the event was emitted
	 */
	public boolean popPanelLayer() {
		if (context == null) {
			return false;
		}
		Path selectionPath = getArchiveSource(this.currentFolder.getPath());
		context.getEventBus().emit("fs.path.closed", Map.of("uuid", uuid));
		return true;
	}

	/**
	 * Ask the host to unload this plugin instance.
	 *
	 * @return {@code true} if the event was emitted
	 */
	public boolean unloadCurrentInstance() {
		if (context == null) {
			return false;
		}
		Path selectionPath = getArchiveSource(this.currentFolder.getPath());
		Map<String, Object> event = new HashMap<>();
		event.put("uuid", uuid);
		if (selectionPath != null) {
			event.put("selectionPath", selectionPath);
		}
		context.getEventBus().emit(PLUGIN_UNLOAD_EVENT, event);
		return true;
	}

	// =========================================================================
	// Archive mount / resolution (called from ZipFilePanel)
	// =========================================================================

	public boolean isArchivePath(Path path) {
		return archiveType(path) != null;
	}

	/**
	 * Return a browsable {@link Path} for the given path:
	 * <ul>
	 *   <li>If it is already a directory — return it as-is.</li>
	 *   <li>If it is a supported archive — mount or extract it and return the root.</li>
	 *   <li>Otherwise — return {@code null}.</li>
	 * </ul>
	 */
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
			} catch (IOException ex) {
				log.error("Failed to mount archive {}: {}", path, ex.getMessage());
				return null;
			}
		}
		return null;
	}

	/**
	 * Return the real-filesystem path of the archive file that contains {@code path},
	 * or {@code null} if {@code path} is not inside any mounted/extracted archive.
	 */
	public Path getArchiveSource(Path path) {
		if (path == null) {
			return null;
		}
		// Check NIO-mounted ZIP filesystems first
		Path mountedSource = mountedArchiveSources.get(path.getFileSystem());
		if (mountedSource != null) {
			return mountedSource;
		}
		// Then check extracted temp directories
		for (Map.Entry<Path, Path> entry : extractedArchiveRoots.entrySet()) {
			if (path.normalize().startsWith(entry.getKey().normalize())) {
				return entry.getValue();
			}
		}
		return null;
	}

	/**
	 * Return the root path of the archive that contains {@code path},
	 * or {@code null} if {@code path} is not inside any archive.
	 */
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

	/**
	 * Return {@code true} if {@code path} is the root directory of a mounted or
	 * extracted archive (i.e. ".." would exit the archive).
	 */
	public boolean isArchiveRoot(Path path) {
		Path root = getArchiveRoot(path);
		return root != null && root.equals(path);
	}

	/**
	 * Return {@code true} if {@code directory} is inside a mounted NIO ZIP
	 * filesystem — meaning write operations on it will persist to the archive file.
	 *
	 * <p>Returns {@code false} for extracted temp directories (TAR, RAR, encrypted ZIP),
	 * where writes only affect the temp copy and are lost when the plugin is unloaded.
	 */
	public boolean isWritableArchiveDirectory(Path directory) {
		if (directory == null) {
			return false;
		}
		// NIO ZIP filesystem paths are NOT on the default filesystem
		return !directory.getFileSystem().equals(FileSystems.getDefault());
	}

	/**
	 * Flush writes for the archive containing {@code directory} and remount it so the
	 * returned path is backed by a live filesystem after a mutation.
	 */
	public Path refreshArchiveDirectory(Path directory) {
		if (directory == null || directory.getFileSystem().equals(FileSystems.getDefault())) {
			return directory;
		}

		FileSystem fileSystem = directory.getFileSystem();
		Path archiveRoot = fileSystem.getPath("/");
		Path relativePath = archiveRoot.relativize(directory);
		Path archiveSource = mountedArchiveSources.get(fileSystem);
		if (archiveSource == null) {
			return directory;
		}

		try {
			closeMountedFileSystem(fileSystem);
			Path remountedRoot = resolveBrowsablePath(archiveSource);
			if (remountedRoot == null) {
				return null;
			}
			return resolveRelative(remountedRoot, relativePath);
		} catch (IOException ex) {
			log.error("Failed to refresh archive directory {}", directory, ex);
			return null;
		}
	}

	/**
	 * Materialize a virtual-filesystem entry (e.g. a file inside a mounted ZIP)
	 * as a real temp file so that {@code Desktop.open()} can open it.
	 *
	 * <p>The temp file is registered for deletion on {@link #unload()}.
	 */
	public Path materializeFile(Path path) throws IOException {
		if (path == null) {
			return null;
		}
		if (path.getFileSystem().equals(FileSystems.getDefault())) {
			return path; // already a real file, nothing to do
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
		materializedTempFiles.add(tempFile);
		Files.copy(path, tempFile, StandardCopyOption.REPLACE_EXISTING);
		return tempFile;
	}

	// =========================================================================
	// Resource construction helpers
	// =========================================================================

	public NuclrResourcePath toResource(Path path) {
		NuclrResourcePath resource = new NuclrResourcePath();
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

	private NuclrResourcePath createArchiveRootParentResource(Path directory) {
		Path archiveSource = getArchiveSource(directory);
		if (archiveSource == null || archiveSource.getParent() == null) {
			return null;
		}
		NuclrResourcePath resource = toResource(archiveSource.getParent());
		resource.setName("..");
		return resource;
	}

	private boolean shouldExitArchive(NuclrResourcePath resource, Path browsable) {
		if (this.currentFolder == null) {
			return false;
		}
		var currentDirectory = this.currentFolder.getPath();
		
		if (currentDirectory == null || !isArchiveRoot(currentDirectory)) {
			return false;
		}
		Path archiveSource = getArchiveSource(currentDirectory);
		if (archiveSource == null) {
			return false;
		}
		Path archiveParent = archiveSource.getParent();
		if (archiveParent == null) {
			return false;
		}
		Path requestedPath = resource != null ? resource.getPath() : null;
		return archiveParent.equals(browsable) && archiveParent.equals(requestedPath);
	}

	private static long sizeBytes(NuclrResourcePath resource, boolean directory) {
		if (resource == null || directory) {
			return 0L;
		}
		Path path = resource.getPath();
		if (path != null) {
			try {
				return Files.size(path);
			} catch (IOException ignored) {
				// Fall back to the resource payload below.
			}
		}
		return resource.getSizeBytes();
	}

	private static String humanReadableSize(long sizeBytes) {
		if (sizeBytes < 1024) {
			return sizeBytes + " B";
		}
		double value = sizeBytes;
		String[] units = {"KB", "MB", "GB", "TB", "PB"};
		int unitIndex = -1;
		while (value >= 1024 && unitIndex < units.length - 1) {
			value /= 1024;
			unitIndex++;
		}
		return String.format(Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

	/** Build a resource that carries the panel-stack metadata the Commander needs. */
	private NuclrResourcePath toStackResource(Path path) {
		NuclrResourcePath resource = toResource(path);
		Map<String, String> metadata = new HashMap<>();
		metadata.put(PANEL_STACK_PROVIDER_CLASS, getClass().getName());
		resource.setMetadata(metadata);
		return resource;
	}

	// =========================================================================
	// Archive mounting (private implementation)
	// =========================================================================

	private Path mountArchive(Path archivePath) throws IOException {
		// If the archive itself lives inside another virtual filesystem (nested archive),
		// we must materialize it to a real temp file first so we can open it.
		Path mountSource = materializeArchiveIfNeeded(archivePath);
		ArchiveType type = archiveType(archivePath);
		if (type == null) {
			return null;
		}

		if (type.usesNioZipFilesystem()) {
			return mountNioZipFilesystem(mountSource, archivePath);
		}

		// TAR, RAR, GZIP — must be fully extracted to a temp directory
		return mountByExtraction(mountSource, archivePath, type);
	}

	private Path mountNioZipFilesystem(Path archiveFile, Path originalArchivePath) throws IOException {
		// Encrypted ZIPs cannot be opened by the NIO ZIP provider — extract instead
		if (isEncryptedZip(archiveFile)) {
			Path existingRoot = findExtractedRoot(originalArchivePath);
			if (existingRoot != null) {
				return existingRoot;
			}
			return extractEncryptedZipWithPrompt(archiveFile, originalArchivePath);
		}

		URI uri = URI.create("jar:" + archiveFile.toUri());

		// Reuse an already-open filesystem from this instance or any other (e.g. a
		// stale instance that was never unloaded).  FileSystems.getFileSystem() lets
		// us adopt a JVM-level mount without throwing FileSystemAlreadyExistsException.
		FileSystem existing = mountedFileSystems.get(uri);
		if (existing == null) {
			try {
				existing = FileSystems.getFileSystem(uri);
			} catch (java.nio.file.FileSystemNotFoundException ignored) {
				// Not mounted yet — will create below
			}
		}
		if (existing != null && existing.isOpen()) {
			mountedFileSystems.putIfAbsent(uri, existing);
			mountedArchiveSources.putIfAbsent(existing, originalArchivePath);
			mountedArchiveBackingFiles.putIfAbsent(existing, archiveFile);
			return existing.getPath("/");
		}

		FileSystem newFs;
		try {
			newFs = FileSystems.newFileSystem(uri, Map.of());
		} catch (IOException ex) {
			if (shouldFallbackToZipExtraction(ex)) {
				log.warn("Falling back to ZIP extraction for {}: {}", originalArchivePath, ex.getMessage());
				Path existingRoot = findExtractedRoot(originalArchivePath);
				if (existingRoot != null) {
					return existingRoot;
				}
				Charset zipCharset = detectZipEntryCharset(archiveFile);
				return extractToTempDir(archiveFile, originalArchivePath, "nuclr-zip-",
						(src, target) -> extractZip(src, target, zipCharset));
			}
			throw ex;
		}

		// Guard against a race: another thread may have mounted it first
		FileSystem previous = mountedFileSystems.putIfAbsent(uri, newFs);
		if (previous != null && previous.isOpen()) {
			try { newFs.close(); } catch (IOException ignored) { }
			return previous.getPath("/");
		}

		mountedArchiveSources.put(newFs, originalArchivePath);
		mountedArchiveBackingFiles.put(newFs, archiveFile);
		return newFs.getPath("/");
	}

	private boolean shouldFallbackToZipExtraction(IOException error) {
		Throwable current = error;
		while (current != null) {
			String message = current.getMessage();
			if (message != null) {
				String normalized = message.toLowerCase(Locale.ROOT);
				if (normalized.contains("invalid cen header") || normalized.contains("bad entry name")) {
					return true;
				}
			}
			current = current.getCause();
		}
		return false;
	}

	private Charset detectZipEntryCharset(Path archivePath) {
		Charset bestCharset = Charset.forName("UTF-8");
		int bestScore = Integer.MIN_VALUE;

		for (Charset charset : LEGACY_ZIP_NAME_CHARSETS) {
			try {
				ZipFile zipFile = configuredZipFile(archivePath, charset);
				int score = scoreZipNames(zipFile.getFileHeaders());
				if (score > bestScore) {
					bestScore = score;
					bestCharset = charset;
				}
			} catch (Exception ex) {
				log.debug("Cannot inspect ZIP names with charset {}: {}", charset, ex.getMessage());
			}
		}

		log.info("Using ZIP entry charset {} for {}", bestCharset, archivePath);
		return bestCharset;
	}

	private int scoreZipNames(List<net.lingala.zip4j.model.FileHeader> fileHeaders) {
		int score = 0;
		for (net.lingala.zip4j.model.FileHeader header : fileHeaders) {
			String name = header != null ? header.getFileName() : null;
			if (name == null || name.isBlank()) {
				score -= 5;
				continue;
			}
			score += scoreName(name);
		}
		return score;
	}

	private int scoreName(String name) {
		int score = 0;
		for (int i = 0; i < name.length(); i++) {
			char ch = name.charAt(i);
			if (isCyrillic(ch)) {
				score += 4;
			} else if (Character.isLetterOrDigit(ch)) {
				score += 1;
			} else if (" ./_+-()[]{}".indexOf(ch) >= 0) {
				score += 0;
			} else if ("¤¬ßÑГ".indexOf(ch) >= 0) {
				score -= 4;
			} else if (Character.isISOControl(ch) || ch == '\uFFFD') {
				score -= 6;
			}
		}
		return score;
	}

	private boolean isCyrillic(char ch) {
		return Character.UnicodeBlock.of(ch) == Character.UnicodeBlock.CYRILLIC;
	}

	private ZipFile configuredZipFile(Path archivePath, Charset charset) {
		ZipFile zipFile = new ZipFile(archivePath.toFile());
		zipFile.setCharset(charset);
		return zipFile;
	}

	private ZipFile configuredZipFile(Path archivePath, char[] password, Charset charset) {
		ZipFile zipFile = new ZipFile(archivePath.toFile(), password);
		zipFile.setCharset(charset);
		return zipFile;
	}

	private Path mountByExtraction(Path archiveFile, Path originalArchivePath, ArchiveType type) throws IOException {
		// Reuse an earlier extraction of the same archive
		Path existingRoot = findExtractedRoot(originalArchivePath);
		if (existingRoot != null) {
			return existingRoot;
		}
		return extractToTempDir(archiveFile, originalArchivePath, "nuclr-archive-",
				(src, target) -> extractArchive(src, type, target));
	}

	private int archiveDepth(FileSystem fileSystem) {
		Path source = mountedArchiveSources.get(fileSystem);
		if (source == null || source.getFileSystem().equals(FileSystems.getDefault())) {
			return 0;
		}
		return 1 + archiveDepth(source.getFileSystem());
	}

	private void closeMountedFileSystem(FileSystem fileSystem) throws IOException {
		if (fileSystem == null) {
			return;
		}

		Path originalArchivePath = mountedArchiveSources.remove(fileSystem);
		Path backingArchiveFile = mountedArchiveBackingFiles.remove(fileSystem);
		mountedFileSystems.entrySet().removeIf(entry -> entry.getValue().equals(fileSystem));

		if (fileSystem.isOpen()) {
			fileSystem.close();
		}

		if (backingArchiveFile != null
				&& originalArchivePath != null
				&& !backingArchiveFile.equals(originalArchivePath)) {
			Files.copy(backingArchiveFile, originalArchivePath, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	private Path resolveRelative(Path base, Path relative) {
		Path result = base;
		for (Path segment : relative) {
			result = result.resolve(segment.toString());
		}
		return result;
	}

	@FunctionalInterface
	private interface ArchiveExtractor {
		void extract(Path source, Path targetDir) throws IOException;
	}

	private Path extractToTempDir(
			Path archiveFile,
			Path originalArchivePath,
			String prefix,
			ArchiveExtractor extractor) throws IOException {

		Path tempDir = Files.createTempDirectory(prefix);
		tempDir.toFile().deleteOnExit();
		try {
			extractor.extract(archiveFile, tempDir);
			extractedArchiveRoots.put(tempDir, originalArchivePath);
			return tempDir;
		} catch (IOException | RuntimeException ex) {
			deleteRecursively(tempDir);
			throw ex;
		}
	}

	private Path materializeArchiveIfNeeded(Path archivePath) throws IOException {
		if (archivePath == null || archivePath.getFileSystem().equals(FileSystems.getDefault())) {
			return archivePath;
		}
		return materializeFile(archivePath);
	}

	private boolean isEncryptedZip(Path archivePath) {
		try {
			return configuredZipFile(archivePath, Charset.forName("UTF-8")).isEncrypted();
		} catch (Exception ignored) {
			return false;
		}
	}

	private Path findExtractedRoot(Path archivePath) {
		return extractedArchiveRoots.entrySet().stream()
				.filter(e -> archivePath.equals(e.getValue()))
				.map(Map.Entry::getKey)
				.filter(Files::isDirectory)
				.findFirst()
				.orElse(null);
	}

	/**
	 * Show a password dialog for the given archive and return the entered password,
	 * or {@code null} if the user cancels.
	 *
	 * <p>Safe to call from any thread — dispatches to the EDT via
	 * {@link SwingUtilities#invokeAndWait} when called from a background thread,
	 * because {@code openResource} is typically invoked on a virtual thread by
	 * the Commander while the EDT stays free.
	 */
	private char[] promptForArchivePassword(Path archivePath) {
		String archiveName = archivePath != null && archivePath.getFileName() != null
				? archivePath.getFileName().toString()
				: "archive";

		char[][] result = { null };

		Runnable showDialog = () -> {
			JPasswordField passwordField = new JPasswordField(24);
			// Request focus inside the dialog so the user can type immediately
			passwordField.addAncestorListener(new javax.swing.event.AncestorListener() {
				@Override public void ancestorAdded(javax.swing.event.AncestorEvent e) {
					passwordField.requestFocusInWindow();
				}
				@Override public void ancestorRemoved(javax.swing.event.AncestorEvent e) { }
				@Override public void ancestorMoved(javax.swing.event.AncestorEvent e) { }
			});
			int choice = JOptionPane.showConfirmDialog(
					null,
					new Object[] { "Enter password for \"" + archiveName + "\":", passwordField },
					"Archive Password",
					JOptionPane.OK_CANCEL_OPTION,
					JOptionPane.PLAIN_MESSAGE);
			if (choice == JOptionPane.OK_OPTION) {
				result[0] = passwordField.getPassword();
			}
		};

		if (SwingUtilities.isEventDispatchThread()) {
			showDialog.run();
		} else {
			try {
				SwingUtilities.invokeAndWait(showDialog);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			} catch (java.lang.reflect.InvocationTargetException ex) {
				log.warn("Password dialog threw an exception", ex.getCause());
			}
		}

		return result[0];
	}

	/**
	 * Show an error dialog informing the user that the password they entered was wrong.
	 * Safe to call from any thread.
	 */
	private void showArchivePasswordError(Path archivePath) {
		String archiveName = archivePath != null && archivePath.getFileName() != null
				? archivePath.getFileName().toString()
				: "archive";

		Runnable showDialog = () -> JOptionPane.showMessageDialog(
				null,
				"Incorrect password for \"" + archiveName + "\". Please try again.",
				"Wrong Password",
				JOptionPane.ERROR_MESSAGE);

		if (SwingUtilities.isEventDispatchThread()) {
			showDialog.run();
		} else {
			SwingUtilities.invokeLater(showDialog);
			// invokeLater is sufficient here because this is called from a catch block
			// inside extractEncryptedZipWithPrompt, which loops back to the next prompt.
			// The next prompt uses invokeAndWait, which implicitly waits for all prior
			// invokeLater tasks to finish first, so the error dialog will always appear
			// before the next password prompt.
		}
	}

	private boolean isWrongPasswordError(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof ZipException zipException
					&& zipException.getType() == ZipException.Type.WRONG_PASSWORD) {
				return true;
			}
			String message = current.getMessage();
			if (message != null && message.toLowerCase(Locale.ROOT).contains("wrong password")) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static final class InvalidArchivePasswordException extends IOException {
		private static final long serialVersionUID = 1L;

		private InvalidArchivePasswordException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	// =========================================================================
	// Archive extraction (TAR, RAR, GZIP, encrypted ZIP)
	// =========================================================================

	private void extractArchive(Path archivePath, ArchiveType type, Path targetDir) throws IOException {
		switch (type) {
			case RAR   -> extractRar(archivePath, targetDir);
			case TAR   -> extractTar(archivePath, targetDir);
			case GZIP  -> extractGzip(archivePath, targetDir);
			default    -> throw new IOException("Unsupported extraction type: " + type);
		}
	}

	private void extractZip(Path archivePath, Path targetDir) throws IOException {
		extractZip(archivePath, targetDir, Charset.forName("UTF-8"));
	}

	private void extractZip(Path archivePath, Path targetDir, Charset charset) throws IOException {
		try {
			configuredZipFile(archivePath, charset).extractAll(targetDir.toString());
		} catch (Exception ex) {
			if (isWrongPasswordError(ex)) {
				throw new InvalidArchivePasswordException("Wrong password!", ex);
			}
			throw ex instanceof IOException ioEx ? ioEx : new IOException("Cannot extract ZIP", ex);
		}
	}

	private void extractZip(Path archivePath, Path targetDir, char[] password) throws IOException {
		extractZip(archivePath, targetDir, password, Charset.forName("UTF-8"));
	}

	private void extractZip(Path archivePath, Path targetDir, char[] password, Charset charset) throws IOException {
		try {
			configuredZipFile(archivePath, password, charset).extractAll(targetDir.toString());
		} catch (Exception ex) {
			if (isWrongPasswordError(ex)) {
				throw new InvalidArchivePasswordException("Wrong password!", ex);
			}
			throw ex instanceof IOException ioEx ? ioEx : new IOException("Cannot extract ZIP", ex);
		}
	}

	private Path extractEncryptedZipWithPrompt(Path archiveFile, Path originalArchivePath) throws IOException {
		Charset zipCharset = detectZipEntryCharset(archiveFile);
		while (true) {
			char[] password = promptForArchivePassword(originalArchivePath);
			if (password == null) {
				return null;
			}
			try {
				return runExtractionWithProgressIfOnEdt(archiveFile, originalArchivePath, password, zipCharset);
			} catch (InvalidArchivePasswordException ex) {
				log.info("Invalid password for archive {}", originalArchivePath);
				showArchivePasswordError(originalArchivePath);
			} finally {
				Arrays.fill(password, '\0');
			}
		}
	}

	/**
	 * Extract the encrypted ZIP archive with {@code password}, showing a modal
	 * "please wait" dialog when called from the EDT so the UI doesn't appear frozen.
	 *
	 * <p>When called from a background thread the extraction runs synchronously on
	 * that thread — no dialog is needed because the EDT is already free.
	 *
	 * <p>The modal dialog uses Swing's secondary event loop ({@code setVisible(true)}
	 * on an {@code APPLICATION_MODAL} dialog) to keep the EDT alive and responsive
	 * while the background virtual thread does the actual I/O work.
	 */
	private Path runExtractionWithProgressIfOnEdt(
			Path archiveFile,
			Path originalArchivePath,
			char[] password,
			Charset zipCharset) throws IOException {

		IOException[] errorHolder  = { null };
		Path[]        resultHolder = { null };

		Runnable doExtract = () -> {
			try {
				resultHolder[0] = extractToTempDir(archiveFile, originalArchivePath, "nuclr-zip-",
						(src, target) -> extractZip(src, target, password, zipCharset));
			} catch (IOException ex) {
				errorHolder[0] = ex;
			}
		};

		if (!SwingUtilities.isEventDispatchThread()) {
			// We are already on a background thread — the EDT is free.
			// Extract synchronously right here.
			doExtract.run();
		} else {
			// We are on the EDT.  Run extraction on a virtual thread and show a
			// modal dialog so the EDT keeps pumping events (secondary event loop).
			JDialog waitDialog = buildExtractionWaitDialog();
			AtomicBoolean done = new AtomicBoolean(false);

			Thread.ofVirtual().start(() -> {
				doExtract.run();
				done.set(true);
				// Dispose on the EDT to close the secondary event loop.
				SwingUtilities.invokeLater(waitDialog::dispose);
			});

			// setVisible(true) blocks the EDT here via secondary event loop.
			// It unblocks when waitDialog.dispose() is called above.
			if (!done.get()) {
				waitDialog.setVisible(true);
			}
		}

		if (errorHolder[0] != null) {
			throw errorHolder[0];
		}
		return resultHolder[0];
	}

	/**
	 * Build the modal "Extracting Archive…" dialog shown while the extraction
	 * virtual thread is running.  The dialog has no controls — the user must wait.
	 */
	private JDialog buildExtractionWaitDialog() {
		JDialog dialog = new JDialog(null, "Extracting Archive", Dialog.ModalityType.APPLICATION_MODAL);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		JLabel label = new JLabel("Decrypting and extracting, please wait\u2026");
		label.setBorder(BorderFactory.createEmptyBorder(24, 32, 24, 32));
		dialog.add(label, BorderLayout.CENTER);

		dialog.pack();
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(null);
		return dialog;
	}

	private void extractTar(Path archivePath, Path targetDir) throws IOException {
		try (InputStream raw = Files.newInputStream(archivePath);
			 TarArchiveInputStream tar = new TarArchiveInputStream(new BufferedInputStream(raw))) {
			extractTarStream(tar, targetDir);
		}
	}

	private void extractGzip(Path archivePath, Path targetDir) throws IOException {
		try (InputStream raw = Files.newInputStream(archivePath);
			 GzipCompressorInputStream gz = new GzipCompressorInputStream(new BufferedInputStream(raw))) {

			String name = archivePath.getFileName() == null
					? ""
					: archivePath.getFileName().toString().toLowerCase(Locale.ROOT);

			if (name.endsWith(".tar.gz") || name.endsWith(".tgz")) {
				try (TarArchiveInputStream tar = new TarArchiveInputStream(gz)) {
					extractTarStream(tar, targetDir);
				}
				return;
			}

			// Plain .gz — decompress the single wrapped file
			String outputName = stripGzExtension(
					archivePath.getFileName() == null ? "archive.gz" : archivePath.getFileName().toString());
			Path outputPath = safeResolve(targetDir, outputName);
			ensureParentDirs(outputPath);
			try (var out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
				IOUtils.copy(gz, out);
			}
		}
	}

	private void extractTarStream(TarArchiveInputStream tar, Path targetDir) throws IOException {
		TarArchiveEntry entry;
		while ((entry = tar.getNextTarEntry()) != null) {
			if (entry.isSymbolicLink()) {
				// Skip symlinks — their targets may not exist in the extraction dir
				log.debug("Skipping TAR symlink: {}", entry.getName());
				continue;
			}
			Path outputPath = safeResolve(targetDir, entry.getName());
			if (outputPath == null) {
				log.warn("Skipping unsafe TAR entry: {}", entry.getName());
				continue;
			}
			if (entry.isDirectory()) {
				Files.createDirectories(outputPath);
			} else {
				ensureParentDirs(outputPath);
				try (var out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
					IOUtils.copy(tar, out);
				}
			}
		}
	}

	private void extractRar(Path archivePath, Path targetDir) throws IOException {
		try (Archive rar = new Archive(archivePath.toFile())) {
			FileHeader entry;
			while ((entry = rar.nextFileHeader()) != null) {
				String entryName = entry.getFileNameString();
				Path outputPath = safeResolve(targetDir, entryName);
				if (outputPath == null) {
					log.warn("Skipping unsafe RAR entry: {}", entryName);
					continue;
				}
				if (entry.isDirectory()) {
					Files.createDirectories(outputPath);
				} else {
					ensureParentDirs(outputPath);
					try (var out = Files.newOutputStream(outputPath, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
						rar.extractFile(entry, out);
					}
				}
			}
		} catch (Exception ex) {
			throw ex instanceof IOException ioEx ? ioEx : new IOException("Cannot extract RAR archive", ex);
		}
	}

	// =========================================================================
	// Path safety & helpers
	// =========================================================================

	/**
	 * Resolve {@code entryName} under {@code targetDir}, rejecting path-traversal
	 * attacks (entries containing ".." or absolute paths).
	 *
	 * @return the resolved path, or {@code null} if the entry is unsafe
	 */
	private static Path safeResolve(Path targetDir, String entryName) {
		if (entryName == null || entryName.isBlank()) {
			return null;
		}
		// Normalize separators and strip leading slashes
		String normalized = entryName.replace('\\', '/');
		while (normalized.startsWith("/")) {
			normalized = normalized.substring(1);
		}
		if (normalized.isBlank()) {
			return null;
		}
		Path resolved = targetDir.resolve(normalized).normalize();
		// Reject path traversal
		return resolved.startsWith(targetDir.normalize()) ? resolved : null;
	}

	private static void ensureParentDirs(Path path) throws IOException {
		Path parent = path.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	private static String stripGzExtension(String fileName) {
		String lower = fileName.toLowerCase(Locale.ROOT);
		if (lower.endsWith(".gz") && fileName.length() > 3) {
			return fileName.substring(0, fileName.length() - 3);
		}
		return fileName + ".out";
	}

	// =========================================================================
	// Archive type classification
	// =========================================================================

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
		return null;
	}

	private enum ArchiveType {
		ZIP_FAMILY, RAR, TAR, GZIP;

		/** Only ZIP-family archives are mounted via the Java NIO ZIP filesystem. */
		boolean usesNioZipFilesystem() {
			return this == ZIP_FAMILY;
		}
	}

	// =========================================================================
	// Recursive delete (used during unload cleanup)
	// =========================================================================

	private void deleteRecursively(Path root) {
		try (var stream = Files.walk(root)) {
			stream.sorted(Comparator.reverseOrder()).forEach(path -> {
				try { Files.deleteIfExists(path); } catch (IOException ignored) { }
			});
		} catch (IOException ignored) { }
	}

	// =========================================================================
	// Event payload helpers
	// =========================================================================

	@SuppressWarnings("unchecked")
	private static List<NuclrResourcePath> extractResourceList(Map<String, Object> event, String key) {
		if (event == null) {
			return List.of();
		}
		Object value = event.get(key);
		if (value instanceof List<?> list) {
			return (List<NuclrResourcePath>) list;
		}
		return List.of();
	}

	@Override
	public NuclrResourcePath getCurrentResource() {
		return currentFolder;
	}

	@Override
	public String uuid() {
		return uuid;
	}
}
