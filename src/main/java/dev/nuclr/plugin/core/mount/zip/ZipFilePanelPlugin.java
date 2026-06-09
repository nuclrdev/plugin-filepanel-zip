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
package dev.nuclr.plugin.core.mount.zip;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;

import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import dev.nuclr.plugin.core.mount.zip.service.DeleteService;
import dev.nuclr.plugin.core.mount.zip.service.MakeNewFolderService;
import lombok.extern.slf4j.Slf4j;

/**
 * File-panel plugin that lets the commander browse archive files
 * (ZIP/JAR/WAR/EAR, RAR, TAR, TAR.GZ/TGZ, GZIP) as if they were folders.
 *
 * <p>One plugin instance backs exactly one top-level archive. ZIP-family
 * archives that are neither encrypted nor charset-broken are mounted in place
 * through the Java NIO {@code ZipFileSystem}; everything else is extracted to a
 * temporary directory. Nested archives (an archive living inside another
 * archive) are routed to a fresh plugin instance, which materialises the entry
 * to a real temp file before opening it.
 *
 * @see ArchiveType
 * @see ArchiveExtractor
 */
@Slf4j
public class ZipFilePanelPlugin implements FilePanelNuclrPlugin, NuclrEventListener {

	// -------------------------------------------------------------------------
	// Plugin metadata
	// -------------------------------------------------------------------------

	public static final String PluginId = "dev.nuclr.plugin.core.mount.zip";
	private static final String PluginName = "Archive Panel";
	private static final String PluginVersion = loadVersion();
	private static final String PluginDescription = "Browse ZIP, JAR, WAR, EAR, RAR, TAR and GZIP archives in the file panel.";
	private static final String PluginAuthor = "Nuclr Development Team";
	private static final String PluginLicense = "Apache-2.0";
	private static final String PluginWebsite = "https://nuclr.dev";
	private static final String PluginPageUrl = "https://nuclr.dev/plugins/core/filepanel-zip.html";
	private static final String PluginDocUrl = PluginPageUrl;

	static final List<String> ColumnNames = List.of("Name", "Size", "Date", "Time");

	/** Commander event emitted to unload this panel layer (see {@code Events}). */
	private static final String EventPluginUnload = "plugin.unload";

	// -------------------------------------------------------------------------
	// Runtime state â€” one instance backs one mounted/extracted archive
	// -------------------------------------------------------------------------

	private final String uuid = UUID.randomUUID().toString();

	private NuclrPluginContext context;

	private boolean focused;

	/** The archive file this instance is browsing (on the real filesystem). */
	private Path archiveFile;

	/** Display name shown in the location bar (the archive file name). */
	private String archiveDisplayName;

	/** The virtual root of the open archive (NIO root or extraction temp dir). */
	private Path archiveRootPath;

	/** The currently displayed folder inside the archive. */
	private NuclrResource currentFolder;

	/** The mounted NIO filesystem, or {@code null} for extracted archives. */
	private FileSystem mountedFileSystem;

	/** The temp directory an archive was extracted into, or {@code null}. */
	private Path extractedTempDir;

	/** Set once the root parent entry requests this panel to close. */
	private boolean closing;

	/** Temp files created when materialising nested archives, for cleanup. */
	private final List<Path> materializedTempFiles = new CopyOnWriteArrayList<>();

	// =========================================================================
	// Lifecycle
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

		if (mountedFileSystem != null) {
			try {
				mountedFileSystem.close();
			} catch (IOException e) {
				log.warn("Failed to close mounted filesystem for {}: {}", archiveDisplayName, e.getMessage());
			}
			mountedFileSystem = null;
		}

		if (extractedTempDir != null) {
			deleteRecursively(extractedTempDir);
			extractedTempDir = null;
		}

		for (var temp : materializedTempFiles) {
			try {
				Files.deleteIfExists(temp);
			} catch (IOException e) {
				log.warn("Failed to delete materialized temp file {}: {}", temp, e.getMessage());
			}
		}
		materializedTempFiles.clear();

		log.info("Archive panel plugin unloaded");
	}

	// =========================================================================
	// Resource opening / navigation
	// =========================================================================

	@Override
	public NuclrResourceData openResource(NuclrResource resourceToOpen,
			AtomicBoolean cancelled) {

		if (resourceToOpen == null || isCancelled(cancelled)) {
			return null;
		}

		// Synthetic ".." at the archive root: close the archive and pop the panel.
		if (resourceToOpen.getMetadata(ArchiveNuclrResource.KeyCloseArchive, Boolean.FALSE)) {
			closing = true;
			emitArchiveClosed();
			resourceToOpen.getMetadata().remove(ArchiveNuclrResource.KeyCloseArchive);
			resourceToOpen.setPath(null);
			return null;
		}

		final Path target = resourceToOpen.getPath();

		if (target == null) {
			return null;
		}

		try {

			// Navigating a directory already inside this open archive.
			if (archiveRootPath != null && Files.isDirectory(target)) {
				return listDirectory(resourceToOpen, target);
			}

			// Entering an archive file (top-level or a materialised nested archive).
			if (!Files.isDirectory(target) && ArchiveType.isArchiveFile(target)) {
				return openArchive(target);
			}

		} catch (IOException e) {
			log.error("Failed to open archive resource {}: {}", target, e.getMessage(), e);
			showError("Could not open archive", e.getMessage());
		}

		return null;
	}

	/** Mount or extract an archive file and return its root listing. */
	private NuclrResourceData openArchive(Path target) throws IOException {

		// If the archive lives inside another virtual filesystem, copy it out first.
		Path realFile = materializeIfNeeded(target);

		final ArchiveType type = ArchiveType.of(realFile);

		final Path root = type.usesNioZipFilesystem() ? openZipFamily(realFile) : extractArchive(realFile, type);

		if (root == null) {
			return null;
		}

		this.archiveFile = target;
		this.archiveRootPath = root;
		this.archiveDisplayName = target.getFileName() != null ? target.getFileName().toString() : target.toString();
		this.currentFolder = ArchiveNuclrResource.buildRoot(context, root, archiveDisplayName);
		this.closing = false;

		return listDirectory(currentFolder, root);
	}

	/**
	 * Open a ZIP-family archive. Tries an in-place NIO mount first, then a mount
	 * with a detected entry-name charset, and finally falls back to temp
	 * extraction (handling encrypted archives via a password prompt).
	 */
	private Path openZipFamily(Path file) throws IOException {

		if (ArchiveExtractor.isEncrypted(file)) {
			return extractEncryptedZip(file);
		}

		try {
			return mountZip(file, null);
		} catch (IOException first) {
			log.info("Default ZIP mount failed for {} ({}); retrying with detected charset",
					file.getFileName(), first.getMessage());
		}

		final Charset detected = ArchiveExtractor.detectZipEntryCharset(file);

		try {
			return mountZip(file, detected);
		} catch (IOException second) {
			log.info("Charset ZIP mount failed for {} ({}); extracting instead",
					file.getFileName(), second.getMessage());
			return extractToTempDir(dir -> ArchiveExtractor.extractZip(file, dir, null, detected));
		}
	}

	private Path mountZip(Path file, Charset charset) throws IOException {
		final Map<String, ?> env = charset == null ? Map.of() : Map.of("encoding", charset.name());
		final FileSystem fs = FileSystems.newFileSystem(file, env);
		this.mountedFileSystem = fs;
		return fs.getPath("/");
	}

	private Path extractEncryptedZip(Path file) throws IOException {

		final Charset charset = ArchiveExtractor.detectZipEntryCharset(file);

		char[] password = promptPassword(file.getFileName().toString(), false);

		while (password != null) {
			final char[] attempt = password;
			try {
				return extractToTempDir(dir -> ArchiveExtractor.extractZip(file, dir, attempt, charset));
			} catch (ArchiveExtractor.WrongPasswordException wrong) {
				password = promptPassword(file.getFileName().toString(), true);
			}
		}

		// User cancelled the password prompt.
		return null;
	}

	private Path extractArchive(Path file, ArchiveType type) throws IOException {
		return switch (type) {
			case RAR -> extractToTempDir(dir -> ArchiveExtractor.extractRar(file, dir));
			case TAR -> extractToTempDir(dir -> ArchiveExtractor.extractTar(file, dir, false));
			case TAR_GZ -> extractToTempDir(dir -> ArchiveExtractor.extractTar(file, dir, true));
			case GZIP -> extractToTempDir(dir -> ArchiveExtractor.extractGzip(file, dir));
			default -> throw new IOException("Unsupported archive type: " + type);
		};
	}

	private interface ExtractionTask {
		void extract(Path destination) throws IOException;
	}

	private Path extractToTempDir(ExtractionTask task) throws IOException {
		final Path dir = Files.createTempDirectory("nuclr-archive-" + UUID.randomUUID());
		this.extractedTempDir = dir;
		task.extract(dir);
		return dir;
	}

	/**
	 * If {@code target} is not on the default filesystem (i.e. it is a nested
	 * archive inside an already-mounted ZIP), copy it out to a real temp file so
	 * the NIO/extraction providers can open it.
	 */
	private Path materializeIfNeeded(Path target) throws IOException {

		if (target.getFileSystem() == FileSystems.getDefault()) {
			return target;
		}

		final String name = target.getFileName() != null ? target.getFileName().toString() : "archive";
		final Path temp = Files.createTempFile("nuclr-nested-" + UUID.randomUUID() + "-", "-" + name);
		Files.copy(target, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		materializedTempFiles.add(temp);

		log.info("Materialized nested archive {} to {}", name, temp);
		return temp;
	}

	/** Build the listing for a directory inside the open archive. */
	private NuclrResourceData listDirectory(NuclrResource folderResource, Path dir) {

		this.currentFolder = folderResource;

		final var data = new NuclrResourceData();
		data.setColumnNames(ColumnNames);

		final boolean atRoot = dir.equals(archiveRootPath);

		if (atRoot) {
			// ".." at the archive root closes the archive and pops the panel.
			data.getEntries().add(ArchiveNuclrResource.buildCloseParent(context, archiveFile));
		} else if (dir.getParent() != null) {
			data.getEntries().add(ArchiveNuclrResource.buildParent(context, dir.getParent()));
		}

		final var children = new ArrayList<NuclrResource>();
		try (var stream = Files.list(dir)) {
			stream.forEach(p -> children.add(ArchiveNuclrResource.build(context, p)));
		} catch (IOException e) {
			log.error("Failed to list archive directory {}: {}", dir, e.getMessage(), e);
		}

		// Folders first, then files, both alphabetically â€” a familiar panel order.
		children.sort(Comparator.comparing((NuclrResource r) -> !r.isFolder())
				.thenComparing(r -> r.getName(), String.CASE_INSENSITIVE_ORDER));

		data.getEntries().addAll(children);

		return data;
	}

	// =========================================================================
	// Events
	// =========================================================================

	/** Ask the commander to pop this archive panel layer and unload us. */
	private void emitArchiveClosed() {
		if (context == null || context.getEventBus() == null) {
			return;
		}
		log.info("Closing archive panel for {}", archiveDisplayName);
		var event = new HashMap<String, Object>();
		event.put("uuid", uuid);
		if (archiveFile != null) {
			event.put("selectionResource", archiveFile);
		}
		context.getEventBus().emit(this, EventPluginUnload, event);
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> eventData, NuclrPluginCallback callback) {

		if (("delete".equals(type) || "deletePermanent".equals(type)) && eventData != null) {
			handleDelete(eventData, callback);
			return;
		}

		if ("filepanel.makeFolder".equals(type) && eventData != null) {
			if (!focused || currentFolder == null) {
				return;
			}
			if (!isWritableArchive()) {
				showError("Make Folder", "This archive view is read-only.");
				return;
			}
			var createdPath = MakeNewFolderService.makeNewFolder(currentFolder, callback);
			if (createdPath == null) {
				return;
			}
			try {
				eventData.put("createdResource", ArchiveNuclrResource.build(context, createdPath));
			} catch (UnsupportedOperationException ignored) {
				log.debug("Make-folder event payload is immutable; created resource will not be selected.");
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void handleDelete(Map<String, Object> eventData, NuclrPluginCallback callback) {

		// Delivered directly to this (focused) panel's plugin by the commander, so no focus guard
		// is needed â€” the broadcast that used to require it no longer happens.
		if (!(eventData.get("sources") instanceof List<?> list) || list.isEmpty()) {
			return;
		}

		// Entries can only be removed from a writable NIO Zip mount; temp-extracted views
		// (RAR/TAR/encrypted) are read-only, so deleting from them would only touch the
		// throwaway extraction and never the original archive.
		if (!isWritableArchive()) {
			showError("Delete", "This archive view is read-only.");
			return;
		}

		List<NuclrResource> sources = (List<NuclrResource>) list;

		// Plugin-rendered confirmation listing the full paths to be deleted.
		if (!DeleteDialogs.confirmDelete(sources)) {
			return;
		}

		DeleteService.delete(sources, callback, (item, e) -> DeleteDialogs.error(item.getName(), e));
	}

	@Override
	public boolean isMessageSupported(String type) {
		return "filepanel.makeFolder".equals(type) || "delete".equals(type) || "deletePermanent".equals(type);
	}

	@Override
	public List<NuclrMenuResource> menuItems(NuclrResource source) {

		// Mirror the local-filesystem panel's function-bar menu so the archive view behaves the
		// same way. In particular this provides Delete (F8) and Delete Permanently (Shift+F8),
		// which is how those keys reach the delete logic (the function-key bar dispatches them).
		var items = new ArrayList<NuclrMenuResource>();

		boolean isDirectory = source != null && source.getPath() != null && Files.isDirectory(source.getPath());

		addDefaultMenuItems(items, isDirectory);
		addAltMenuItems(items);
		addCtrlMenuItems(items);
		addShiftMenuItems(items);

		return items;
	}

	private static void addDefaultMenuItems(List<NuclrMenuResource> items, boolean isDirectory) {
		items.add(menu("View", "F3", "view"));
		items.add(menu("Edit", "F4", "edit"));
		items.add(menu("Copy", "F5", "copy"));
		items.add(menu(isDirectory ? "Move" : "Rename/Move", "F6", "move"));
		items.add(menu("Make Folder", "F7", "filepanel.makeFolder"));
		items.add(menu("Delete", "F8", "delete"));
		items.add(menu("Quit", "F10", "quit"));
		items.add(menu("Plugins", "F11", "plugins"));
		items.add(menu("Screen", "F12", "screen"));
	}

	private static void addAltMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Left Panel", "Alt+F1", "left"));
		items.add(menu("Right Panel", "Alt+F2", "right"));
		items.add(menu("Find", "Alt+F7", "find"));
		items.add(menu("History", "Alt+F8", "history"));
		items.add(menu("Fullscreen", "Alt+F9", "fullscreen"));
		items.add(menu("Tree", "Alt+F10", "tree"));
		items.add(menu("View History", "Alt+F11", "viewHistory"));
		items.add(menu("Folder History", "Alt+F12", "folderHistory"));
	}

	private static void addCtrlMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Hide Left", "Ctrl+F1", "hideLeft"));
		items.add(menu("Hide Right", "Ctrl+F2", "hideRight"));
		items.add(menu("Sort by name", "Ctrl+F3", "sortByName"));
		items.add(menu("Sort by extension", "Ctrl+F4", "sortByExtension"));
		items.add(menu("Sort by modified", "Ctrl+F5", "sortByModifiedDate"));
		items.add(menu("Sort by size", "Ctrl+F6", "sortBySize"));
		items.add(menu("Unsort", "Ctrl+F7", "unsort"));
		items.add(menu("Sort by create", "Ctrl+F8", "sortByCreateDate"));
		items.add(menu("Sort by access", "Ctrl+F9", "sortByAccessTime"));
		items.add(menu("Sort menu", "Ctrl+F12", "sortMenu"));
	}

	private static void addShiftMenuItems(List<NuclrMenuResource> items) {
		items.add(menu("Delete Permanently", "Shift+F8", "deletePermanent"));
	}

	private static NuclrMenuResource menu(String name, String functionKey, String eventType) {
		return new NuclrMenuResource(name, functionKey, eventType);
	}

	private boolean isWritableArchive() {
		return mountedFileSystem != null
				&& extractedTempDir == null
				&& archiveFile != null
				&& archiveFile.getFileSystem() == FileSystems.getDefault();
	}

	// =========================================================================
	// Routing
	// =========================================================================

	@Override
	public boolean supports(Path path) {

		if (path == null) {
			return archiveRootPath != null && !closing;
		}

		// Navigating directories already inside an open archive.
		if (archiveRootPath != null && isInsideOpenArchive(path) && Files.isDirectory(path)) {
			return true;
		}

		// Archive files inside an already-open archive must be routed through
		// Commander so they get their own plugin instance and stack entry.
		return archiveRootPath == null && ArchiveType.isArchiveFile(path);
	}

	private boolean isInsideOpenArchive(Path path) {
		if (path == null || archiveRootPath == null) {
			return false;
		}
		try {
			return path.toAbsolutePath().normalize().startsWith(archiveRootPath.toAbsolutePath().normalize());
		} catch (Exception e) {
			return false;
		}
	}

	// =========================================================================
	// Location / selection display
	// =========================================================================

	@Override
	public NuclrResource getCurrentResource() {
		return currentFolder;
	}
	
	@Override
	public String getWindowTitle() {

		if (archiveFile == null) {
			return getCurrentLocationDisplayText();
		}
		var title = archiveFile.toAbsolutePath().toString();
		var currentResource = getCurrentResource();
		var currentPath = currentResource != null ? currentResource.getPath() : null;
		if (currentPath != null && archiveRootPath != null) {
			var inside = relativeInsideArchive(currentPath);
			if (!inside.isBlank()) {
				return title + "/" + inside;
			}
		}

		return title;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		if (currentFolder == null) {
			return archiveDisplayName != null ? archiveDisplayName : "";
		}

		final Path path = currentFolder.getPath();

		if (path == null || archiveRootPath == null) {
			return archiveDisplayName != null ? archiveDisplayName : currentFolder.getName();
		}

		final String inside = relativeInsideArchive(path);
		return inside.isEmpty() ? archiveDisplayName : archiveDisplayName + "/" + inside;
	}

	private String relativeInsideArchive(Path path) {
		try {
			final String rel = archiveRootPath.relativize(path).toString().replace('\\', '/');
			return rel;
		} catch (IllegalArgumentException e) {
			return "";
		}
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {

		if (selectedResources == null || selectedResources.isEmpty()) {
			return getCurrentLocationDisplayText();
		}

		if (selectedResources.size() == 1) {
			final var resource = selectedResources.get(0);
			final Path path = resource.getPath();
			final boolean directory = path != null && Files.isDirectory(path);
			final String type = directory ? "Folder" : humanReadableSize(resource.getLength());
			final String name = resource.getName() == null || resource.getName().isBlank() ? "?" : resource.getName();
			return name + "  |  " + type;
		}

		long totalBytes = 0L;
		int fileCount = 0;
		int folderCount = 0;
		for (var resource : selectedResources) {
			final Path path = resource.getPath();
			if (path != null && Files.isDirectory(path)) {
				folderCount++;
			} else {
				fileCount++;
				totalBytes += resource.getLength();
			}
		}
		return "Bytes: " + humanReadableSize(totalBytes) + ",  files: " + fileCount + ",  folders: " + folderCount;
	}

	private static String humanReadableSize(long sizeBytes) {
		if (sizeBytes < 1024) {
			return sizeBytes + " B";
		}
		double value = sizeBytes;
		final String[] units = { "KB", "MB", "GB", "TB", "PB" };
		int unitIndex = -1;
		while (value >= 1024 && unitIndex < units.length - 1) {
			value /= 1024;
			unitIndex++;
		}
		return String.format(Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

	// =========================================================================
	// Focus
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

	@Override
	public void closeResource() {
		// Navigation is driven by openResource; nothing to release per-resource.
	}

	// =========================================================================
	// Metadata accessors
	// =========================================================================

	@Override
	public String id() {
		return PluginId;
	}

	@Override
	public String name() {
		return PluginName;
	}

	@Override
	public String version() {
		return PluginVersion;
	}
	private static String loadVersion() {
		try (var stream = ZipFilePanelPlugin.class.getResourceAsStream("/plugin.properties")) {
			if (stream == null) return "unknown";
			var props = new java.util.Properties();
			props.load(stream);
			return props.getProperty("version", "unknown");
		} catch (java.io.IOException e) {
			return "unknown";
		}
	}

	@Override
	public String description() {
		return PluginDescription;
	}

	@Override
	public String author() {
		return PluginAuthor;
	}

	@Override
	public String license() {
		return PluginLicense;
	}

	@Override
	public String website() {
		return PluginWebsite;
	}

	@Override
	public String pageUrl() {
		return PluginPageUrl;
	}

	@Override
	public String docUrl() {
		return PluginDocUrl;
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public boolean singleton() {
		// Each opened archive needs its own mount, so instances are not shared.
		return false;
	}

	@Override
	public String uuid() {
		return uuid;
	}

	// =========================================================================
	// Helpers
	// =========================================================================

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return cancelled != null && cancelled.get();
	}

	private void deleteRecursively(Path root) {
		if (!Files.exists(root)) {
			return;
		}
		try (var walk = Files.walk(root)) {
			walk.sorted(Comparator.reverseOrder()).forEach(path -> {
				try {
					Files.deleteIfExists(path);
				} catch (IOException e) {
					log.warn("Failed to delete temp archive file {}: {}", path, e.getMessage());
				}
			});
		} catch (IOException e) {
			log.warn("Failed to walk temp archive dir {}: {}", root, e.getMessage());
		}
	}

	private char[] promptPassword(String archiveName, boolean retry) {

		final char[][] result = new char[1][];

		final Runnable prompt = () -> {
			final var passwordField = new JPasswordField(20);
			final var panel = new JPanel(new java.awt.BorderLayout(0, 8));
			final String message = (retry ? "Wrong password. " : "") + "Enter password for \"" + archiveName + "\":";
			panel.add(new JLabel(message), java.awt.BorderLayout.NORTH);
			panel.add(passwordField, java.awt.BorderLayout.CENTER);

			final int choice = JOptionPane.showConfirmDialog(null, panel, "Encrypted Archive",
					JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);

			if (choice == JOptionPane.OK_OPTION) {
				result[0] = passwordField.getPassword();
			}
		};

		runOnEdtAndWait(prompt);

		return result[0] != null && result[0].length > 0 ? result[0] : null;
	}

	private void showError(String title, String message) {
		runOnEdtAndWait(() -> JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE));
	}

	private static void runOnEdtAndWait(Runnable runnable) {
		if (SwingUtilities.isEventDispatchThread()) {
			runnable.run();
			return;
		}
		try {
			SwingUtilities.invokeAndWait(runnable);
		} catch (Exception e) {
			log.warn("Failed to run dialog on EDT: {}", e.getMessage());
		}
	}

	@Override
	public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
			NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
		
		
		
	}
}
