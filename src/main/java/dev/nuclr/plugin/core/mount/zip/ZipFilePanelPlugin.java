package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;

import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.FilePanelNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

@Data
@Slf4j
public class ZipFilePanelPlugin implements FilePanelNuclrPlugin, NuclrEventListener {

	private String uuid = java.util.UUID.randomUUID().toString();

	// -------------------------------------------------------------------------
	// Plugin metadata constants
	// -------------------------------------------------------------------------

	public static final String PLUGIN_ID = "dev.nuclr.plugin.core.mount.zip";
	private static final String PLUGIN_NAME = "Archive Panel";
	private static final String PLUGIN_VERSION = "1.0.0";
	private static final String PLUGIN_DESCRIPTION = "Browse ZIP, JAR, WAR and EAR archives in the file panel.";
	private static final String PLUGIN_AUTHOR = "Nuclr Development Team";
	private static final String PLUGIN_LICENSE = "Apache-2.0";
	private static final String PLUGIN_WEBSITE = "https://nuclr.dev";
	private static final String PLUGIN_PAGE_URL = "https://nuclr.dev/plugins/core/filepanel-zip.html";
	private static final String PLUGIN_DOC_URL = PLUGIN_PAGE_URL;
	private static final String PLUGIN_UNLOAD_EVENT = "plugin.unload";

	// -------------------------------------------------------------------------
	// Event type constants
	// -------------------------------------------------------------------------

	private static final String Path = "Path";

	// -------------------------------------------------------------------------
	// Runtime state
	// -------------------------------------------------------------------------

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResource currentFolder;
	private volatile Thread shutdownHook;
	private FileSystem fs;
	private Path archivePath;

	private static final List<String> ColumnNames = List.of("Name", "Size", "Date", "Time");

	private final Map<String, NuclrResourceData> archiveDataCache = new ConcurrentHashMap<>();

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
		if (shutdownHook == null) {
			shutdownHook = new Thread(this::closeFileSystem, PLUGIN_ID + "-shutdown");
			try {
				Runtime.getRuntime().addShutdownHook(shutdownHook);
			} catch (IllegalStateException ignored) {
				// JVM is already shutting down.
			}
		}
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}

		closeFileSystem();

		Thread hook = shutdownHook;
		if (hook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			} catch (IllegalStateException ignored) {
				// JVM is already shutting down; the hook is running
			}
			shutdownHook = null;
		}
		log.info("Archive panel plugin unloaded");
	}

	@Override
	public String id() {
		return PLUGIN_ID;
	}

	@Override
	public String name() {
		return PLUGIN_NAME;
	}

	@Override
	public String version() {
		return PLUGIN_VERSION;
	}

	@Override
	public String description() {
		return PLUGIN_DESCRIPTION;
	}

	@Override
	public String author() {
		return PLUGIN_AUTHOR;
	}

	@Override
	public String license() {
		return PLUGIN_LICENSE;
	}

	@Override
	public String website() {
		return PLUGIN_WEBSITE;
	}

	@Override
	public String pageUrl() {
		return PLUGIN_PAGE_URL;
	}

	@Override
	public String docUrl() {
		return PLUGIN_DOC_URL;
	}

	@Override
	public Developer developer() {
		return Developer.Official;
	}

	@Override
	public boolean singleton() {
		return false;
	}

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

	private boolean isArchive(NuclrResource resource) {

		// Main Archive file (e.g. .zip, .jar, .tar, etc.)
		Path path = resource.getMetadata(Path, null);
		ArchiveType type = archiveType(path);

		if (path != null
				&& Files.isRegularFile(path)
				&& Files.isReadable(path)
				&& type != null
				&& type.usesNioZipFilesystem()) return true;

		return false;
	}
	
	private boolean isArchiveEntry(NuclrResource resource) {

		// Internal archive entry (e.g. "file.zip!/path/inside/archive.txt")
		var internalArchiveEntry = resource.getMetadata(PLUGIN_ID, false);
		
		return internalArchiveEntry;
	}

	@Override
	public boolean supports(NuclrResource resource) {

		if (resource == null) {
			return false;
		}

		if (isArchive(resource)) {
 			return true;
		}

		if (isArchiveEntry(resource)) {
			return true;
		}

		return false;

	}

	private ArchiveType archiveType(Path path) {
		
		if (path == null || path.getFileName() == null) {
			return null;
		}

		Locale locale = context == null || context.getLocale() == null ? Locale.ROOT : context.getLocale();
		String fileName = path.getFileName().toString().toLowerCase(locale);

		if (fileName.endsWith(".zip") || fileName.endsWith(".jar") || fileName.endsWith(".war")
				|| fileName.endsWith(".ear")) {
			return ArchiveType.ZIP_FAMILY;
		}
		if (fileName.endsWith(".rar")) {
			return ArchiveType.RAR;
		}
		if (fileName.endsWith(".tar")) {
			return ArchiveType.TAR;
		}
		if (fileName.endsWith(".gz") || fileName.endsWith(".tgz") || fileName.endsWith(".tar.gz")) {
			return ArchiveType.GZIP;
		}
		return null;
	}

	private static boolean isCancelled(AtomicBoolean cancelled) {
		return (cancelled != null && cancelled.get()) || Thread.currentThread().isInterrupted();
	}

	@Override
	public String uuid() {
		return uuid;
	}

	@Override
	public void closeResource() {
		closeFileSystem();
		currentFolder = null;
	}

	@Override
	public NuclrResource getCurrentResource() {
		return this.currentFolder;
	}

	@Override
	public void handleMessage(Object source, String type, Map<String, Object> eventData, NuclrPluginCallback callback) {
		// TODO Auto-generated method stub
		
	}

	@Override
	public boolean isMessageSupported(String type) {
		// TODO Auto-generated method stub
		return false;
	}

	@Override
	public NuclrResourceData openResource(NuclrResource parent, NuclrResource resource, AtomicBoolean cancelled) {

		if (isCancelled(cancelled) || resource == null) {
			return null;
		}

		// Open main archive file, show root entries
		if (isArchive(resource)) {
			Path path = resource.getMetadata(Path, null);
			ArchiveType type = archiveType(path);

			if (type == null || !type.usesNioZipFilesystem()) {
				log.warn("Archive type is not supported by the current file-system mount path: {}", path);
				return null;
			}

			if (!path.equals(archivePath)) {
				closeFileSystem();
				archivePath = path;
			}

			if (fs == null || false == fs.isOpen()) {
				try {
					fs = FileSystems.newFileSystem(path);
					archiveDataCache.clear();
				} catch (IOException e) {
					log.error("Failed to open archive {}: {}", path, e.getMessage());
					return null;
				}
			}

			return openArchiveEntry(resource, "/", cancelled);

		}

		// Archive entry
		if (isArchiveEntry(resource)) {
			Path entryPath = resource.getMetadata(ZipFileNuclrResource.KeyPath, null);
			String fullPath = entryPath == null ? resource.getFullPath() : entryPath.toString();
			return openArchiveEntry(resource, fullPath, cancelled);
		}
		
		this.currentFolder = null;
		return null;

	}

	private NuclrResourceData openArchiveEntry(NuclrResource resource, String entryPath, AtomicBoolean cancelled) {

		if (isCancelled(cancelled) || fs == null || !fs.isOpen()) {
			return null;
		}

		Path path = fs.getPath(entryPath == null || entryPath.isBlank() ? "/" : entryPath).normalize();
		if (!Files.isDirectory(path)) {
			return null;
		}

		String cacheKey = cacheKey(path);
		if (archiveDataCache.containsKey(cacheKey)) {
			log.info("Using cached data for archive entry: {}", cacheKey);
			return archiveDataCache.get(cacheKey);
		}

		log.info("Opening archive entry: {}", cacheKey);

		var data = new NuclrResourceData();
		data.setColumnNames(ColumnNames);

		addParentEntry(data, path);

		try (var stream = Files.list(path)) {
			stream.forEach(p -> {
				if (!isCancelled(cancelled)) {
					data.getEntries().add(convert(p));
				}
			});
		} catch (IOException e) {
			log.error("Failed to read entries from archive {} at {}: {}", archivePath, cacheKey, e.getMessage());
		}

		archiveDataCache.put(cacheKey, data);

		this.currentFolder = resource;

		return data;

	}

	private NuclrResource convert(Path p) {
		var r = ZipFileNuclrResource.build(p);
		r.getMetadata().put(PLUGIN_ID, true);
		r.getMetadata().put(ZipFileNuclrResource.KeyPath, p);
		r.setFullPath(cacheKey(p));
		r.setUuid(archivePath + "!" + cacheKey(p));
		return r;
	}

	private void addParentEntry(NuclrResourceData data, Path path) {
		if (isRoot(path)) {
			Path parentPath = archivePath == null ? null : archivePath.getParent();
			if (parentPath != null) {
				NuclrResource parent = ZipFileNuclrResource.build(parentPath);
				parent.setParent(true);
				parent.setName("..");
				parent.setFullPath("..");
				parent.getMetadata().remove(ZipFileNuclrResource.KeyPath);
				parent.getMetadata().put(Path, parentPath);
				parent.getColumnValues().set(0, "..");
				data.getEntries().add(parent);
			}
			return;
		}

		Path parentPath = path.getParent();
		if (parentPath == null) {
			parentPath = fs.getPath("/");
		}

		NuclrResource parent = ZipFileNuclrResource.build(parentPath);
		parent.setParent(true);
		parent.setName("..");
		parent.setFullPath("..");
		parent.getMetadata().put(PLUGIN_ID, true);
		parent.getMetadata().put(ZipFileNuclrResource.KeyPath, parentPath);
		parent.getColumnValues().set(0, "..");
		data.getEntries().add(parent);
	}

	private static boolean isRoot(Path path) {
		return path != null && path.getParent() == null;
	}

	private static String cacheKey(Path path) {
		String value = path == null ? "/" : path.toString().replace('\\', '/');
		if (value.isBlank()) {
			return "/";
		}
		return value.startsWith("/") ? value : "/" + value;
	}

	private void closeFileSystem() {
		FileSystem current = fs;
		fs = null;
		archivePath = null;
		archiveDataCache.clear();
		if (current != null && current.isOpen()) {
			try {
				current.close();
			} catch (IOException e) {
				log.warn("Failed to close file system: {}", e.getMessage());
			}
		}
	}

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		return null;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		return currentFolder == null ? "" : currentFolder.getFullPath();
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		// TODO Auto-generated method stub
		return null;
	}
}
