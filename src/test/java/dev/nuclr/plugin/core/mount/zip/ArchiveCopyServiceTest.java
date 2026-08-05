package dev.nuclr.plugin.core.mount.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.BaseNuclrPlugin;
import dev.nuclr.platform.plugin.NuclrMenuResource;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import net.lingala.zip4j.model.ZipParameters;

class ArchiveCopyServiceTest {

	@TempDir
	Path tempDir;

	@Test
	void copiesFilesAndFoldersAcrossFilesystemProviders() throws Exception {
		Path sourceDir = Files.createDirectories(tempDir.resolve("folder/nested"));
		Files.writeString(sourceDir.resolve("one.txt"), "one");
		Files.writeString(sourceDir.getParent().resolve("top.txt"), "top");
		Path archive = tempDir.resolve("target.zip");

		try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			assertTrue(ArchiveCopyService.copyInto(zip.getPath("/"),
					List.of(sourceDir.getParent().resolve("top.txt"), sourceDir.getParent()), null));
			assertEquals("top", Files.readString(zip.getPath("/top.txt")));
			assertEquals("one", Files.readString(zip.getPath("/folder/nested/one.txt")));
		}

		try (var zip = FileSystems.newFileSystem(archive)) {
			Path output = Files.createDirectory(tempDir.resolve("output"));
			assertTrue(ArchiveCopyService.copyInto(output, List.of(zip.getPath("/folder")), null));
			assertEquals("one", Files.readString(output.resolve("folder/nested/one.txt")));
		}
	}

	@Test
	void selfCopyGetsANewNameInsteadOfOverwritingItsSource() throws Exception {
		Path file = Files.writeString(tempDir.resolve("note.txt"), "content");

		assertTrue(ArchiveCopyService.copyInto(tempDir, List.of(file), null));

		assertEquals("content", Files.readString(file));
		assertEquals("content", Files.readString(tempDir.resolve("note (copy).txt")));
	}

	@Test
	void clipboardTextAcceptsOnlyExistingPaths() throws Exception {
		Path first = Files.writeString(tempDir.resolve("first.txt"), "first");
		Path second = Files.createDirectory(tempDir.resolve("second"));

		List<Path> paths = ArchiveClipboardService.existingTextPaths(
				"\"" + first + "\"\n" + second + "\n" + tempDir.resolve("missing"));

		assertEquals(List.of(first, second), paths);
	}

	@Test
	void linuxUriListClipboardAcceptsEncodedFileUris() throws Exception {
		Path first = Files.writeString(tempDir.resolve("first file.txt"), "first");
		Path second = Files.createDirectory(tempDir.resolve("second folder"));
		String uriList = "# copied files\r\n" + first.toUri().toASCIIString() + "\r\n"
				+ second.toUri().toASCIIString() + "\r\nhttps://example.com/not-a-file";
		DataFlavor flavor = new DataFlavor(
				"text/uri-list;class=java.io.InputStream;charset=UTF-8", "URI list");
		Transferable clipboard = new SingleFlavorTransferable(flavor, uriList);

		assertEquals(List.of(first, second), ArchiveClipboardService.readPaths(clipboard));
	}

	@Test
	void gnomeClipboardCopyPrefixIsIgnored() throws Exception {
		Path file = Files.writeString(tempDir.resolve("gnome.txt"), "content");
		DataFlavor flavor = new DataFlavor(
				"x-special/gnome-copied-files;class=java.io.InputStream", "GNOME copied files");
		Transferable clipboard = new SingleFlavorTransferable(flavor,
				"copy\n" + file.toUri().toASCIIString());

		assertEquals(List.of(file), ArchiveClipboardService.readPaths(clipboard));
	}

	@Test
	void linuxUriListIsTriedWhenAdvertisedJavaFileListCannotBeRead() throws Exception {
		Path file = Files.writeString(tempDir.resolve("fallback.txt"), "content");
		DataFlavor uriFlavor = new DataFlavor(
				"text/uri-list;class=java.io.InputStream;charset=UTF-8", "URI list");
		Transferable clipboard = new Transferable() {
			@Override public DataFlavor[] getTransferDataFlavors() {
				return new DataFlavor[] { DataFlavor.javaFileListFlavor, uriFlavor };
			}
			@Override public boolean isDataFlavorSupported(DataFlavor flavor) {
				return DataFlavor.javaFileListFlavor.equals(flavor) || uriFlavor.equals(flavor);
			}
			@Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
				if (DataFlavor.javaFileListFlavor.equals(flavor)) {
					throw new UnsupportedFlavorException(flavor);
				}
				if (uriFlavor.equals(flavor)) {
					return new ByteArrayInputStream(file.toUri().toASCIIString().getBytes(StandardCharsets.UTF_8));
				}
				throw new UnsupportedFlavorException(flavor);
			}
		};

		assertEquals(List.of(file), ArchiveClipboardService.readPaths(clipboard));
	}

	@Test
	void copyActionForwardsTheSdkAcceptCopyHandshake() {
		var source = new ZipFilePanelPlugin();
		var destination = new RecordingZipPlugin();
		var payload = new HashMap<String, Object>();

		source.act(destination, "filepanel.copy", List.of(), null, payload, null);

		assertEquals("accept.copy", destination.actionType);
	}

	@Test
	void copyOutOfTheArchiveRefreshesTheReceivingPanel() {
		var bus = new RecordingEventBus();
		var source = new ZipFilePanelPlugin();
		source.preinit(new TestContext(bus));
		var destination = new RecordingZipPlugin();

		source.act(destination, "filepanel.copy", List.of(), null, new HashMap<>(), null);

		assertEquals("refresh.plugin.file.panel", bus.type);
		assertEquals(destination.uuid(), bus.event.get("plugin.uuid"));
	}

	@Test
	void theReceivingPanelIsRefreshedEvenWhenTheCopyBlowsUp() {
		var bus = new RecordingEventBus();
		var source = new ZipFilePanelPlugin();
		source.preinit(new TestContext(bus));
		var destination = new ThrowingZipPlugin();

		assertThrows(IllegalStateException.class,
				() -> source.act(destination, "filepanel.copy", List.of(), null, new HashMap<>(), null));

		assertEquals("refresh.plugin.file.panel", bus.type);
		assertEquals(destination.uuid(), bus.event.get("plugin.uuid"));
	}

	@Test
	void sortsAreDeclaredWithTheEventTypeTheCommanderParses() {
		var items = new ZipFilePanelPlugin().menuItems(null);

		// The commander only recognises a sort by this event-type prefix; anything else is inert.
		var sorts = items.stream()
				.filter(item -> item.getEventType().startsWith("filepanel.sort:"))
				.collect(Collectors.toMap(NuclrMenuResource::getFunctionKey, NuclrMenuResource::getEventType));

		assertEquals(Map.of(
				"Ctrl+F3", "filepanel.sort:name:Name",
				"Ctrl+F4", "filepanel.sort:ext",
				"Ctrl+F5", "filepanel.sort:modified:Date",
				"Ctrl+F6", "filepanel.sort:size:Size",
				"Ctrl+F7", "filepanel.sort:unsorted",
				"Ctrl+F12", "filepanel.sort:dialog"), sorts);
	}

	@Test
	void everySortColumnIsOneThePanelActuallyShows() {
		var columns = ZipFilePanelPlugin.ColumnNames;

		for (var item : new ZipFilePanelPlugin().menuItems(null)) {
			if (!item.getEventType().startsWith("filepanel.sort:")) {
				continue;
			}
			String[] parts = item.getEventType().substring("filepanel.sort:".length()).split(":", 2);
			if (parts.length == 2) {
				assertTrue(columns.contains(parts[1]),
						"sort maps to column '" + parts[1] + "', which the panel does not render");
			}
		}
	}

	@Test
	void f5MenuUsesTheSdkCopyAction() {
		var plugin = new ZipFilePanelPlugin();

		var copy = plugin.menuItems(null).stream()
				.filter(item -> "F5".equals(item.getFunctionKey()))
				.findFirst().orElseThrow();

		assertEquals("filepanel.copy", copy.getEventType());
	}

	@Test
	void acceptCopyWritesIntoAnOpenZipAndRefreshesItsPanel() throws Exception {
		Path archive = tempDir.resolve("open.zip");
		try (var ignored = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			// Create a valid empty ZIP.
		}
		Path source = Files.writeString(tempDir.resolve("incoming.txt"), "incoming");
		var bus = new RecordingEventBus();
		var context = new TestContext(bus);
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);
		plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));

		plugin.act(null, "accept.copy", List.of(new ZipFileNuclrResource(context, source)), null,
				new HashMap<>(), null);

		assertEquals("incoming", Files.readString(plugin.getCurrentResource().getPath().resolve("incoming.txt")));
		assertEquals("refresh.plugin.file.panel", bus.type);
		assertEquals(plugin.uuid(), bus.event.get("plugin.uuid"));
		plugin.unload();
		try (var zip = FileSystems.newFileSystem(archive)) {
			assertEquals("incoming", Files.readString(zip.getPath("/incoming.txt")));
		}
	}

	@Test
	void cancellingEncryptedZipPasswordPopsTheNewPanel() throws Exception {
		Path content = Files.writeString(tempDir.resolve("secret.txt"), "secret");
		Path archive = tempDir.resolve("secret.zip");
		var parameters = new ZipParameters();
		parameters.setEncryptFiles(true);
		parameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
		try (var zip = new ZipFile(archive.toFile(), "password".toCharArray())) {
			zip.addFile(content.toFile(), parameters);
		}

		var bus = new RecordingEventBus();
		var plugin = new CancellingPasswordPlugin();
		plugin.preinit(new TestContext(bus));
		NuclrResource resource = new ZipFileNuclrResource(new TestContext(bus), archive);

		assertNull(plugin.openResource(resource, new AtomicBoolean(false)));
		assertEquals("plugin.unload", bus.type);
		assertEquals(plugin.uuid(), bus.event.get("uuid"));
		assertInstanceOf(NuclrResource.class, bus.event.get("selectionResource"));
		assertFalse(bus.event.get("selectionResource") instanceof Path);
	}

	private static final class CancellingPasswordPlugin extends ZipFilePanelPlugin {
		@Override
		char[] promptPassword(String archiveName, boolean retry) {
			return null;
		}
	}

	private static final class RecordingZipPlugin extends ZipFilePanelPlugin {
		private String actionType;

		@Override
		public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
			this.actionType = actionType;
		}
	}

	/** Stands in for a receiving plugin whose transfer fails before it can refresh its own panel. */
	private static final class ThrowingZipPlugin extends ZipFilePanelPlugin {
		@Override
		public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
			throw new IllegalStateException("copy failed");
		}
	}

	private record SingleFlavorTransferable(DataFlavor flavor, String text) implements Transferable {
		@Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { flavor }; }
		@Override public boolean isDataFlavorSupported(DataFlavor candidate) { return flavor.equals(candidate); }
		@Override public Object getTransferData(DataFlavor candidate) throws UnsupportedFlavorException {
			if (!isDataFlavorSupported(candidate)) {
				throw new UnsupportedFlavorException(candidate);
			}
			return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
		}
	}

	private record TestContext(NuclrEventBus eventBus) implements NuclrPluginContext {
		@Override public NuclrEventBus getEventBus() { return eventBus; }
		@Override public NuclrThemeScheme getTheme() { return null; }
		@Override public NuclrSettings getSettings() { return null; }
		@Override public Locale getLocale() { return Locale.US; }
	}

	private static final class RecordingEventBus implements NuclrEventBus {
		private String type;
		private Map<String, Object> event;

		@Override
		public void emit(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) {
			this.type = type;
			this.event = event;
		}

		@Override public void emit(Object source, String type, Map<String, Object> event) {
			emit(source, type, event, null);
		}
		@Override public void emit(String type, Map<String, Object> event, NuclrPluginCallback callback) {
			emit(null, type, event, callback);
		}
		@Override public void emit(String type, NuclrPluginCallback callback) { emit(null, type, Map.of(), callback); }
		@Override public void emit(String type) { emit(null, type, Map.of(), null); }
		@Override public void subscribe(NuclrEventListener listener) { }
		@Override public void unsubscribe(NuclrEventListener listener) { }
	}
}
