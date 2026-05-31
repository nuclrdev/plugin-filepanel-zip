package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
	private static final String PLUGIN_DESCRIPTION = "Browse ZIP, JAR, WAR, EAR, RAR, TAR and GZ archives in the file panel.";
	private static final String PLUGIN_AUTHOR = "Nuclr Development Team";
	private static final String PLUGIN_LICENSE = "Apache-2.0";
	private static final String PLUGIN_WEBSITE = "https://nuclr.dev";
	private static final String PLUGIN_PAGE_URL = "https://nuclr.dev/plugins/core/filepanel-zip.html";
	private static final String PLUGIN_DOC_URL = PLUGIN_PAGE_URL;
	private static final String PLUGIN_UNLOAD_EVENT = "plugin.unload";

	// -------------------------------------------------------------------------
	// Event type constants
	// -------------------------------------------------------------------------

	private static final String MENU_ACTION_EVENT_TYPE = "dev.nuclr.plugin.core.mount.zip.menuAction";
	private static final String FS_COPY_EVENT = "fs.copy";
	private static final String FS_MOVE_EVENT = "fs.move";
	private static final String PATH_METADATA_KEY = "Path";

	// -------------------------------------------------------------------------
	// Runtime state
	// -------------------------------------------------------------------------

	private NuclrPluginContext context;
	private boolean focused;
	private NuclrResource currentFolder;
	private volatile Thread shutdownHook;
	private FileSystem fs;

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
		Runtime.getRuntime().addShutdownHook(shutdownHook);
	}

	@Override
	public void unload() {
		if (context != null) {
			context.getEventBus().unsubscribe(this);
		}
		
		if (fs != null) {
			try {
				fs.close();
			} catch (IOException e) {
				log.warn("Failed to close file system: {}", e.getMessage());
			}
		}
		
		Thread hook = shutdownHook;
		if (hook != null) {
			try {
				Runtime.getRuntime().removeShutdownHook(hook);
			} catch (IllegalStateException ignored) {
				// JVM is already shutting down; the hook is running
			}
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
		Path path = resource.getMetadata(PATH_METADATA_KEY, null);
		
		if (path != null
				&& Files.isRegularFile(path)
				&& Files.isReadable(path)
				&& archiveType(path) != null) return true;

		return false;
	}
	
	private boolean isArchiveEntry(NuclrResource resource) {

		// Internal archive entry (e.g. "file.zip!/path/inside/archive.txt")
		var internalArchiveEntry = resource.getMetadata(PLUGIN_ID, false);

		if (internalArchiveEntry != null) {
			return true;
		}
		
		return false;
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

	private static ArchiveType archiveType(Path path) {
		
		if (path == null || path.getFileName() == null) {
			return null;
		}

		String fileName = path.getFileName().toString().toLowerCase(Locale.ROOT);
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
		// TODO Auto-generated method stub
		
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
	public NuclrResourceData openResource(NuclrResource resource, AtomicBoolean cancelled) {

		// Open main archive file, show root entries
		if (isArchive(resource)) {
			
			Path path = resource.getMetadata(PATH_METADATA_KEY, null);
			
			if (path != null && !isCancelled(cancelled)) {
				
				ArchiveType type = archiveType(path);
				
				if (type != null) {
					this.currentFolder = resource;
					return openArchive(path, type, resource, cancelled);
				}
				
			}
			
		} 
		
		// Archive entry
		if (isArchiveEntry(resource)) {
			this.currentFolder = resource;
			return openArchiveEntry(resource, cancelled);
		}
		
		this.currentFolder = null;
		
		return null;
		
	}

	private NuclrResourceData openArchiveEntry(NuclrResource resource, AtomicBoolean cancelled) {
		
		var path = resource.getMetadata(ZipFileNuclrResource.KeyPath, null);
		
		log.info("Opening archive entry: {}", path);
		
		return new NuclrResourceData(); // TODO: implement this
		
	}
	
	private static List<String> ColumnNames = List.of("Name", "Size", "Date", "Time");

	private NuclrResourceData openArchive(Path zipPath, ArchiveType type, NuclrResource resource, AtomicBoolean cancelled) {
		
		log.info("Opening archive: {}, type={}", zipPath, type);
		
		var data = new NuclrResourceData();
		data.setColumnNames(ColumnNames);
		
		// Parent folder (will be opened by filepanel-fs plugin)
		var parent = ZipFileNuclrResource.build(zipPath.getParent());
		parent.setParent(true);
		parent.setName("..");
		parent.setFullPath("..");
		parent.getMetadata().put(PATH_METADATA_KEY, zipPath.getParent());
		parent.getColumnValues().set(0, "..");
		data.getEntries().add(parent);		
		
		try {

			fs = FileSystems.newFileSystem(zipPath);

			Path root = fs.getPath("/");

			try (Stream<Path> stream = Files.list(root)) {

				var list = stream
					.map(p -> convert(p))
					.map(p->{
						p.getMetadata().put(ZipFileNuclrResource.KeyPath, p);
						return p;
					})
					.collect(Collectors.toList());

				data.getEntries().addAll(list);

			} catch (Exception e) {
				log.error("Failed to read entries from archive {}: {}", zipPath, e.getMessage());
			}

		} catch (Exception e) {
			log.error("Failed to open archive {}: {}", zipPath, e.getMessage());
		}
		
		return data;
		
	}
	
	private NuclrResource convert(Path p) {
		var r = ZipFileNuclrResource.build(p);
		r.getMetadata().put(PLUGIN_ID, true);
		r.getMetadata().put(ZipFileNuclrResource.KeyPath, p);
		return r;
	}

	@Override
	public MenuItemsHolder getPluginMenuItems() {
		return null;
	}

	@Override
	public String getCurrentLocationDisplayText() {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public String getSelectionSummaryText(List<NuclrResource> selectedResources) {
		// TODO Auto-generated method stub
		return null;
	}
}
