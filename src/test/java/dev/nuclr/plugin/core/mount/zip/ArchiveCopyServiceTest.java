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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.swing.SwingUtilities;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
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
	void copyActionDoesNotBlockSwingsEventDispatchThread() throws Exception {
		var source = new ZipFilePanelPlugin();
		var destination = new BlockingZipPlugin();
		var actionReturned = new CountDownLatch(1);

		SwingUtilities.invokeLater(() -> {
			try {
				source.act(destination, "filepanel.copy", List.of(), null, new HashMap<>(), null);
			} finally {
				actionReturned.countDown();
			}
		});

		boolean receiverStarted = destination.started.await(5, TimeUnit.SECONDS);
		boolean returnedBeforeReceiverFinished = actionReturned.await(1, TimeUnit.SECONDS);
		destination.release.countDown();
		boolean receiverFinished = destination.finished.await(5, TimeUnit.SECONDS);
		source.unload();
		destination.unload();

		assertTrue(receiverStarted);
		assertTrue(returnedBeforeReceiverFinished);
		assertTrue(receiverFinished);
		assertFalse(destination.calledOnEdt);
	}

	@Test
	void acceptCopyDoesNotBlockSwingsEventDispatchThread() throws Exception {
		Path archive = tempDir.resolve("background-copy.zip");
		try (var ignored = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			// Create a valid empty ZIP.
		}
		Path incoming = Files.writeString(tempDir.resolve("background.txt"), "background");
		var context = new TestContext(new RecordingEventBus());
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);
		plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
		var callback = new BlockingCallback();
		var actionReturned = new CountDownLatch(1);

		SwingUtilities.invokeLater(() -> {
			try {
				plugin.act(null, "accept.copy", List.of(new ZipFileNuclrResource(context, incoming)), null,
						new HashMap<>(), callback);
			} finally {
				actionReturned.countDown();
			}
		});

		boolean copyStarted = callback.started.await(5, TimeUnit.SECONDS);
		boolean returnedBeforeCopyFinished = actionReturned.await(1, TimeUnit.SECONDS);
		callback.release.countDown();
		boolean copyFinished = callback.finished.await(5, TimeUnit.SECONDS);
		flushEdt();
		String copied = Files.readString(plugin.getCurrentResource().getPath().resolve("background.txt"));
		plugin.unload();

		assertTrue(copyStarted);
		assertTrue(returnedBeforeCopyFinished);
		assertTrue(copyFinished);
		assertFalse(callback.calledOnEdt);
		assertEquals("background", copied);
	}

	@Test
	void queuedZipCopyKeepsTheDestinationFolderFromDispatchTime() throws Exception {
		Path archive = tempDir.resolve("captured-destination.zip");
		try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Files.createDirectory(zip.getPath("/nested"));
		}
		Path incoming = Files.writeString(tempDir.resolve("captured.txt"), "captured");
		var context = new TestContext(new RecordingEventBus());
		var source = new ZipFilePanelPlugin();
		var destination = new NavigatingZipPlugin();
		destination.preinit(context);
		destination.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
		Path destinationRoot = destination.getCurrentResource().getPath();
		destination.navigateBeforeAccept(
				ArchiveNuclrResource.build(context, destinationRoot.resolve("nested")));

		try {
			source.act(destination, "filepanel.copy",
					List.of(new ZipFileNuclrResource(context, incoming)), null, new HashMap<>(), null);

			assertEquals("captured", Files.readString(destinationRoot.resolve("captured.txt")));
			assertFalse(Files.exists(destinationRoot.resolve("nested/captured.txt")));
		} finally {
			source.unload();
			destination.unload();
		}
	}

	@Test
	void queuedZipMoveKeepsTheDestinationFolderFromDispatchTime() throws Exception {
		Path sourceArchive = tempDir.resolve("captured-move-source.zip");
		try (var zip = FileSystems.newFileSystem(sourceArchive, Map.of("create", "true"))) {
			Files.writeString(zip.getPath("/moving.txt"), "moving");
		}
		Path destinationArchive = tempDir.resolve("captured-move-destination.zip");
		try (var zip = FileSystems.newFileSystem(destinationArchive, Map.of("create", "true"))) {
			Files.createDirectory(zip.getPath("/nested"));
		}
		var context = new TestContext(new RecordingEventBus());
		var source = new ZipFilePanelPlugin();
		var destination = new NavigatingZipPlugin();
		source.preinit(context);
		destination.preinit(context);
		source.openResource(new ZipFileNuclrResource(context, sourceArchive), new AtomicBoolean(false));
		destination.openResource(
				new ZipFileNuclrResource(context, destinationArchive), new AtomicBoolean(false));
		Path sourceRoot = source.getCurrentResource().getPath();
		Path destinationRoot = destination.getCurrentResource().getPath();
		destination.navigateBeforeAccept(
				ArchiveNuclrResource.build(context, destinationRoot.resolve("nested")));

		try {
			NuclrResource moving = ArchiveNuclrResource.build(context, sourceRoot.resolve("moving.txt"));
			source.act(destination, "filepanel.move", List.of(moving), moving, new HashMap<>(), null);

			assertFalse(Files.exists(sourceRoot.resolve("moving.txt")));
			assertEquals("moving", Files.readString(destinationRoot.resolve("moving.txt")));
			assertFalse(Files.exists(destinationRoot.resolve("nested/moving.txt")));
		} finally {
			source.unload();
			destination.unload();
		}
	}

	@Test
	void crossArchiveCopyDoesNotOvertakeDestinationQueue() throws Exception {
		Path archive = tempDir.resolve("ordered-destination.zip");
		try (var ignored = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			// Create a valid empty ZIP.
		}
		Path held = Files.writeString(tempDir.resolve("held-order.txt"), "held");
		Path queued = Files.writeString(tempDir.resolve("queued-order.txt"), "queued");
		Path cross = Files.writeString(tempDir.resolve("cross-order.txt"), "cross");
		var context = new TestContext(new RecordingEventBus());
		var source = new ZipFilePanelPlugin();
		var destination = new OrderingZipPlugin("cross-order.txt");
		destination.preinit(context);
		destination.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
		var blocker = new BlockingCallback();
		var completionOrder = new CopyOnWriteArrayList<String>();
		var queuedCallback = new OrderingCallback("queued", completionOrder);
		var crossCallback = new OrderingCallback("cross", completionOrder);

		try {
			SwingUtilities.invokeAndWait(() -> destination.act(null, "accept.copy",
					List.of(new ZipFileNuclrResource(context, held)), null, new HashMap<>(), blocker));
			assertTrue(blocker.started.await(5, TimeUnit.SECONDS));
			SwingUtilities.invokeAndWait(() -> destination.act(null, "accept.copy",
					List.of(new ZipFileNuclrResource(context, queued)), null, new HashMap<>(), queuedCallback));

			Thread crossThread = Thread.startVirtualThread(() -> source.act(destination, "filepanel.copy",
					List.of(new ZipFileNuclrResource(context, cross)), null, new HashMap<>(), crossCallback));
			boolean crossReachedDestination = destination.crossAcceptEntered.await(5, TimeUnit.SECONDS);
			blocker.release.countDown();
			boolean queuedFinished = queuedCallback.finished.await(5, TimeUnit.SECONDS);
			boolean crossFinished = crossCallback.finished.await(5, TimeUnit.SECONDS);
			crossThread.join(5_000);

			assertTrue(crossReachedDestination);
			assertTrue(queuedFinished);
			assertTrue(crossFinished);
			assertFalse(crossThread.isAlive());
			assertEquals(List.of("queued", "cross"), completionOrder);
		} finally {
			blocker.release.countDown();
			source.unload();
			destination.unload();
		}
	}

	@Test
	void unloadingDestinationReleasesCrossCallerQueuedOnItsExecutor() throws Exception {
		Path archive = tempDir.resolve("unload-queued-destination.zip");
		try (var ignored = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			// Create a valid empty ZIP.
		}
		Path held = Files.writeString(tempDir.resolve("held-unload.txt"), "held");
		Path cross = Files.writeString(tempDir.resolve("cross-unload.txt"), "cross");
		var context = new TestContext(new RecordingEventBus());
		var source = new ZipFilePanelPlugin();
		var destination = new OrderingZipPlugin("cross-unload.txt");
		destination.preinit(context);
		destination.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
		var blocker = new UninterruptibleBlockingCallback();
		var unloadStarted = new CountDownLatch(1);
		var unloadReturned = new CountDownLatch(1);

		SwingUtilities.invokeAndWait(() -> destination.act(null, "accept.copy",
				List.of(new ZipFileNuclrResource(context, held)), null, new HashMap<>(), blocker));
		assertTrue(blocker.started.await(5, TimeUnit.SECONDS));
		Thread crossThread = Thread.startVirtualThread(() -> source.act(destination, "filepanel.copy",
				List.of(new ZipFileNuclrResource(context, cross)), null, new HashMap<>(), null));
		assertTrue(destination.crossAcceptEntered.await(5, TimeUnit.SECONDS));

		SwingUtilities.invokeLater(() -> {
			unloadStarted.countDown();
			destination.unload();
			unloadReturned.countDown();
		});
		assertTrue(unloadStarted.await(5, TimeUnit.SECONDS));
		crossThread.join(5_000);
		boolean crossCallerReleased = !crossThread.isAlive();
		blocker.release.countDown();
		boolean unloadFinished = unloadReturned.await(5, TimeUnit.SECONDS);
		source.unload();

		assertTrue(crossCallerReleased);
		assertTrue(unloadFinished);
	}

	@Test
	void unloadWaitsForActiveMutationWithoutFreezingSwing() throws Exception {
		Path archive = tempDir.resolve("unload-active.zip");
		try (var ignored = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			// Create a valid empty ZIP.
		}
		Path incoming = Files.writeString(tempDir.resolve("held.txt"), "held");
		var context = new TestContext(new RecordingEventBus());
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);
		plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
		var callback = new UninterruptibleBlockingCallback();

		SwingUtilities.invokeAndWait(() -> plugin.act(null, "accept.copy",
				List.of(new ZipFileNuclrResource(context, incoming)), null, new HashMap<>(), callback));
		assertTrue(callback.started.await(5, TimeUnit.SECONDS));

		var unloadStarted = new CountDownLatch(1);
		var unloadReturned = new CountDownLatch(1);
		var swingProbe = new CountDownLatch(1);
		SwingUtilities.invokeLater(() -> {
			unloadStarted.countDown();
			plugin.unload();
			unloadReturned.countDown();
		});
		assertTrue(unloadStarted.await(5, TimeUnit.SECONDS));
		SwingUtilities.invokeLater(swingProbe::countDown);

		boolean swingStayedResponsive = swingProbe.await(5, TimeUnit.SECONDS);
		boolean unloadReturnedBeforeMutation = unloadReturned.getCount() == 0;
		callback.release.countDown();
		boolean unloadFinished = unloadReturned.await(5, TimeUnit.SECONDS);

		assertTrue(swingStayedResponsive);
		assertFalse(unloadReturnedBeforeMutation);
		assertTrue(unloadFinished);
		try (var remounted = FileSystems.newFileSystem(archive)) {
			assertFalse(Files.exists(remounted.getPath("/held.txt")));
		}
	}

	@Test
	void asynchronousRenamePublishesSelectionBeforeActionReturns() throws Exception {
		Path archive = tempDir.resolve("rename-action.zip");
		try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Files.writeString(zip.getPath("/before.txt"), "content");
		}
		var context = new TestContext(new RecordingEventBus());
		var plugin = new NonInteractiveRenamePlugin();
		plugin.preinit(context);
		plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
		NuclrResource source = ArchiveNuclrResource.build(context,
				plugin.getCurrentResource().getPath().resolve("before.txt"));
		var payload = new HashMap<String, Object>();

		SwingUtilities.invokeAndWait(() -> plugin.act(
				null, "filepanel.move", List.of(source), source, payload, null));

		assertEquals(Boolean.TRUE, payload.get("result.refresh"));
		NuclrResource selected = assertInstanceOf(
				NuclrResource.class, payload.get("result.refresh.selected.resource"));
		assertEquals("after.txt", selected.getName());
		assertTrue(Files.exists(selected.getPath()));
		plugin.unload();
	}

	@Test
	void copyOutOfTheArchiveRefreshesTheReceivingPanel() throws Exception {
		var bus = new RecordingEventBus();
		var source = new ZipFilePanelPlugin();
		source.preinit(new TestContext(bus));
		var destination = new RecordingZipPlugin();

		source.act(destination, "filepanel.copy", List.of(), null, new HashMap<>(), null);
		flushEdt();

		assertEquals("refresh.plugin.file.panel", bus.type);
		assertEquals(destination.uuid(), bus.event.get("plugin.uuid"));
	}

	@Test
	void theReceivingPanelIsRefreshedEvenWhenTheCopyBlowsUp() throws Exception {
		var bus = new RecordingEventBus();
		var source = new ZipFilePanelPlugin();
		source.preinit(new TestContext(bus));
		var destination = new ThrowingZipPlugin();

		assertThrows(IllegalStateException.class,
				() -> source.act(destination, "filepanel.copy", List.of(), null, new HashMap<>(), null));
		flushEdt();

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
	void f6MenuUsesTheSdkMoveAction() {
		var move = new ZipFilePanelPlugin().menuItems(null).stream()
				.filter(item -> "F6".equals(item.getFunctionKey()))
				.findFirst().orElseThrow();

		assertEquals("filepanel.move", move.getEventType());
	}

	@Test
	void viewAndEditMenusUseTheSdkActionTypes() {
		var actions = new ZipFilePanelPlugin().menuItems(null).stream()
				.filter(item -> "F3".equals(item.getFunctionKey()) || "F4".equals(item.getFunctionKey()))
				.collect(Collectors.toMap(NuclrMenuResource::getFunctionKey, NuclrMenuResource::getEventType));

		assertEquals(Map.of("F3", "filepanel.view", "F4", "filepanel.edit"), actions);
	}

	@Test
	void viewActionRequestsTheMainPanelViewer() throws Exception {
		Path file = Files.writeString(tempDir.resolve("view.txt"), "view");
		var bus = new RecordingEventBus();
		var context = new TestContext(bus);
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);
		NuclrResource resource = new ZipFileNuclrResource(context, file);

		plugin.act(null, "filepanel.view", List.of(), resource, new HashMap<>(), null);

		assertEquals("mainpanel.view", bus.type);
		assertEquals(resource, bus.event.get("resource"));
	}

	@Test
	void editActionRequestsTheMainPanelEditorForAWritableArchive() throws Exception {
		Path archive = tempDir.resolve("editable.zip");
		try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Files.writeString(zip.getPath("/edit.txt"), "edit");
		}
		var bus = new RecordingEventBus();
		var context = new TestContext(bus);
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);
		try {
			plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
			NuclrResource resource = ArchiveNuclrResource.build(context,
					plugin.getCurrentResource().getPath().resolve("edit.txt"));

			plugin.act(null, "filepanel.edit", List.of(), resource, new HashMap<>(), null);

			assertEquals("mainpanel.edit", bus.type);
			assertEquals(resource, bus.event.get("resource"));
		} finally {
			plugin.unload();
		}
	}

	@Test
	void moveActionForwardsTheSdkAcceptMoveHandshake() throws Exception {
		Path archive = tempDir.resolve("move-source.zip");
		try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Files.writeString(zip.getPath("/move.txt"), "move");
		}
		var source = new ZipFilePanelPlugin();
		var bus = new RecordingEventBus();
		var context = new TestContext(bus);
		source.preinit(context);
		var destination = new RecordingZipPlugin();
		try {
			source.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));
			NuclrResource resource = ArchiveNuclrResource.build(context,
					source.getCurrentResource().getPath().resolve("move.txt"));

			source.act(destination, "filepanel.move", List.of(resource), resource, new HashMap<>(), null);

			assertEquals("accept.move", destination.actionType);
		} finally {
			source.unload();
		}
	}

	@Test
	void acceptMoveWritesIntoAnOpenZipAndRemovesTheSource() throws Exception {
		Path archive = tempDir.resolve("move-target.zip");
		try (var ignored = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			// Create a valid empty ZIP.
		}
		Path sourceFolder = Files.createDirectories(tempDir.resolve("incoming/nested"));
		Files.writeString(sourceFolder.resolve("incoming.txt"), "incoming");
		Path source = sourceFolder.getParent();
		var bus = new RecordingEventBus();
		var context = new TestContext(bus);
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);
		try {
			plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false));

			plugin.act(null, "accept.move", List.of(new ZipFileNuclrResource(context, source)), null,
					new HashMap<>(), null);
			flushEdt();

			assertFalse(Files.exists(source));
			assertEquals("incoming", Files.readString(
					plugin.getCurrentResource().getPath().resolve("incoming/nested/incoming.txt")));
			assertEquals("refresh.plugin.file.panel", bus.type);
			assertEquals(plugin.uuid(), bus.event.get("plugin.uuid"));
		} finally {
			plugin.unload();
		}
	}

	@Test
	void renamesEntriesInsideAZipFilesystem() throws Exception {
		Path archive = tempDir.resolve("rename.zip");
		try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
			Path source = Files.writeString(zip.getPath("/before.txt"), "content");

			Path renamed = ArchiveMoveService.rename(source, "after.txt", null);

			assertFalse(Files.exists(source));
			assertEquals("content", Files.readString(renamed));
		}
	}

	@Test
	void failedMoveCopyLeavesTheSourceUntouched() throws Exception {
		Path source = Files.createDirectories(tempDir.resolve("source/nested"));
		Path sourceFile = Files.writeString(source.resolve("file.txt"), "content");
		Path destination = Files.createDirectory(tempDir.resolve("destination"));
		Files.writeString(destination.resolve("nested"), "blocks the source folder");

		assertThrows(IOException.class,
				() -> ArchiveMoveService.moveInto(destination, List.of(source), null));

		assertEquals("content", Files.readString(sourceFile));
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
		flushEdt();

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

	@Test
	void cancellingArchiveOpeningPopsTheNewPanel() throws Exception {
		Path archive = tempDir.resolve("cancelled.tar");
		try (var tar = new TarArchiveOutputStream(Files.newOutputStream(archive))) {
			var entry = new TarArchiveEntry("file.txt");
			entry.setSize(4);
			tar.putArchiveEntry(entry);
			tar.write("data".getBytes(StandardCharsets.UTF_8));
			tar.closeArchiveEntry();
		}

		var bus = new RecordingEventBus();
		var context = new TestContext(bus);
		var plugin = new ZipFilePanelPlugin();
		plugin.preinit(context);

		Thread.currentThread().interrupt();
		try {
			assertNull(plugin.openResource(new ZipFileNuclrResource(context, archive), new AtomicBoolean(false)));
		} finally {
			Thread.interrupted();
		}

		assertEquals("plugin.unload", bus.type);
		assertEquals(plugin.uuid(), bus.event.get("uuid"));
		assertInstanceOf(NuclrResource.class, bus.event.get("selectionResource"));
	}

	@Test
	void zipNestedInsideAnExtractedArchiveRemainsReadOnly() throws Exception {
		Path nestedZip = tempDir.resolve("nested.zip");
		try (var zip = FileSystems.newFileSystem(nestedZip, Map.of("create", "true"))) {
			Files.writeString(zip.getPath("/inside.txt"), "inside");
		}

		Path parentTar = tempDir.resolve("parent.tar");
		try (var tar = new TarArchiveOutputStream(Files.newOutputStream(parentTar))) {
			var entry = new TarArchiveEntry("nested.zip");
			entry.setSize(Files.size(nestedZip));
			tar.putArchiveEntry(entry);
			Files.copy(nestedZip, tar);
			tar.closeArchiveEntry();
		}

		var context = new TestContext(new RecordingEventBus());
		var parent = new ZipFilePanelPlugin();
		var child = new ZipFilePanelPlugin();
		parent.preinit(context);
		child.preinit(context);
		try {
			var parentData = parent.openResource(new ZipFileNuclrResource(context, parentTar),
					new AtomicBoolean(false));
			NuclrResource nestedResource = parentData.getEntries().stream()
					.filter(resource -> "nested.zip".equals(resource.getName()))
					.findFirst().orElseThrow();

			assertTrue(nestedResource.getMetadata(ArchiveNuclrResource.KeyReadOnlySource, Boolean.FALSE));
			assertTrue(nestedResource.getPath().getFileSystem() == FileSystems.getDefault());

			child.openResource(nestedResource, new AtomicBoolean(false));

			assertFalse(child.isWritableArchive());
		} finally {
			child.unload();
			parent.unload();
		}
	}

	private static final class CancellingPasswordPlugin extends ZipFilePanelPlugin {
		@Override
		char[] promptPassword(String archiveName, boolean retry) {
			return null;
		}
	}

	private static final class NonInteractiveRenamePlugin extends ZipFilePanelPlugin {
		@Override
		Path renameArchiveEntry(NuclrResource source, NuclrPluginCallback callback) {
			try {
				return ArchiveMoveService.rename(source.getPath(), "after.txt", callback);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

	private static void flushEdt() throws Exception {
		SwingUtilities.invokeAndWait(() -> { });
	}

	private static final class RecordingZipPlugin extends ZipFilePanelPlugin {
		private String actionType;

		@Override
		public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
			this.actionType = actionType;
		}
	}

	private static final class BlockingZipPlugin extends ZipFilePanelPlugin {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch finished = new CountDownLatch(1);
		private volatile boolean calledOnEdt;

		@Override
		public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
			calledOnEdt = SwingUtilities.isEventDispatchThread();
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			} finally {
				finished.countDown();
			}
		}
	}

	private static final class NavigatingZipPlugin extends ZipFilePanelPlugin {
		private NuclrResource folderToOpen;

		private void navigateBeforeAccept(NuclrResource folder) {
			folderToOpen = folder;
		}

		@Override
		public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
			if (("accept.copy".equals(actionType) || "accept.move".equals(actionType))
					&& folderToOpen != null) {
				NuclrResource folder = folderToOpen;
				folderToOpen = null;
				openResource(folder, new AtomicBoolean(false));
			}
			super.act(other, actionType, selectedResources, focusedResource, data, callback);
		}
	}

	private static final class OrderingZipPlugin extends ZipFilePanelPlugin {
		private final String crossFileName;
		private final CountDownLatch crossAcceptEntered = new CountDownLatch(1);

		private OrderingZipPlugin(String crossFileName) {
			this.crossFileName = crossFileName;
		}

		@Override
		public void act(BaseNuclrPlugin other, String actionType, List<NuclrResource> selectedResources,
				NuclrResource focusedResource, Map<String, Object> data, NuclrPluginCallback callback) {
			if ("accept.copy".equals(actionType) && ArchiveCopyService
					.selectedPaths(selectedResources, focusedResource).stream()
					.anyMatch(path -> path.getFileName() != null
							&& crossFileName.equals(path.getFileName().toString()))) {
				crossAcceptEntered.countDown();
			}
			super.act(other, actionType, selectedResources, focusedResource, data, callback);
		}
	}

	private static final class BlockingCallback implements NuclrPluginCallback {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);
		private final CountDownLatch finished = new CountDownLatch(1);
		private volatile boolean calledOnEdt;

		@Override
		public void onStart(String description) {
			calledOnEdt = SwingUtilities.isEventDispatchThread();
			started.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		@Override public void onProgress(long current, long total) { }
		@Override public void onComplete() { finished.countDown(); }
		@Override public void onError(String description, Exception e) { finished.countDown(); }
		@Override public boolean isCancelled() { return false; }
	}

	private static final class UninterruptibleBlockingCallback implements NuclrPluginCallback {
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		@Override
		public void onStart(String description) {
			started.countDown();
			boolean interrupted = false;
			while (true) {
				try {
					release.await();
					break;
				} catch (InterruptedException e) {
					interrupted = true;
				}
			}
			if (interrupted) {
				Thread.currentThread().interrupt();
			}
		}

		@Override public void onProgress(long current, long total) { }
		@Override public void onComplete() { }
		@Override public void onError(String description, Exception e) { }
		@Override public boolean isCancelled() { return false; }
	}

	private static final class OrderingCallback implements NuclrPluginCallback {
		private final String label;
		private final List<String> completionOrder;
		private final CountDownLatch finished = new CountDownLatch(1);

		private OrderingCallback(String label, List<String> completionOrder) {
			this.label = label;
			this.completionOrder = completionOrder;
		}

		@Override public void onStart(String description) { }
		@Override public void onProgress(long current, long total) { }
		@Override public void onComplete() {
			completionOrder.add(label);
			finished.countDown();
		}
		@Override public void onError(String description, Exception e) { finished.countDown(); }
		@Override public boolean isCancelled() { return false; }
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
