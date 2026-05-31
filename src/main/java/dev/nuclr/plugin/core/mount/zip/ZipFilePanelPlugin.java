package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

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

	@Override
	public boolean supports(NuclrResource resource) {
		if (resource == null) {
			return false;
		}

		Path path = resource.getMetadata(PATH_METADATA_KEY, null);
		return path != null
				&& Files.isRegularFile(path)
				&& Files.isReadable(path)
				&& archiveType(path) != null;
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
		// TODO Auto-generated method stub
		return null;
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

		Path path = resource.getMetadata(PATH_METADATA_KEY, null);

		if (path != null && !isCancelled(cancelled)) {
			ArchiveType type = archiveType(path);
			if (type != null) {
				return openArchive(path, type, resource, cancelled);
			}
		}
		
		return null;
	}
	
	// Read only one directory level at a time
	private List<Path> listDirectory(FileSystem fs, String dirPath) throws IOException {
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(fs.getPath(dirPath))) {
			List<Path> entries = new ArrayList<>();
			stream.forEach(entries::add);
			return entries;
		}
	}

	private NuclrResourceData openArchive(Path path, ArchiveType type, NuclrResource resource, AtomicBoolean cancelled) {
		log.info("Opening archive: {}, type={}", path, type);
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
