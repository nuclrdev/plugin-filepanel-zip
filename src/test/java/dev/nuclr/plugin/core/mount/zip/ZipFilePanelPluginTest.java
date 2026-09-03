package dev.nuclr.plugin.core.mount.zip;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nuclr.platform.NuclrSettings;
import dev.nuclr.platform.NuclrThemeScheme;
import dev.nuclr.platform.events.NuclrEventBus;
import dev.nuclr.platform.events.NuclrEventListener;
import dev.nuclr.platform.plugin.NuclrPluginCallback;
import dev.nuclr.platform.plugin.NuclrPluginContext;
import dev.nuclr.platform.plugin.NuclrResource;

class ZipFilePanelPluginTest {

	@TempDir
	Path tempDir;

	@Test
	void failedRarOpenClosesPanelAndSelectsArchiveInPreviousLocation() throws Exception {
		Path brokenRar = Files.writeString(tempDir.resolve("broken.rar"), "not a rar archive");
		var eventBus = new RecordingEventBus();
		var context = new TestPluginContext(eventBus);
		var shownError = new AtomicReference<String>();
		var plugin = new ZipFilePanelPlugin() {
			@Override
			void showError(String title, String message) {
				shownError.set(title);
			}
		};
		plugin.preinit(context);

		var result = plugin.openResource(ArchiveNuclrResource.build(context, brokenRar), new AtomicBoolean());

		assertNull(result);
		assertEquals("Could not open archive", shownError.get());
		assertEquals("plugin.unload", eventBus.type.get());
		NuclrResource selection = (NuclrResource) eventBus.data.get().get("selectionResource");
		assertEquals(brokenRar, selection.getPath());
	}

	private record TestPluginContext(NuclrEventBus eventBus) implements NuclrPluginContext {
		@Override
		public NuclrEventBus getEventBus() {
			return eventBus;
		}

		@Override
		public NuclrThemeScheme getTheme() {
			return null;
		}

		@Override
		public NuclrSettings getSettings() {
			return null;
		}

		@Override
		public Locale getLocale() {
			return Locale.US;
		}
	}

	private static final class RecordingEventBus implements NuclrEventBus {
		private final AtomicReference<String> type = new AtomicReference<>();
		private final AtomicReference<Map<String, Object>> data = new AtomicReference<>();

		@Override
		public void emit(Object source, String type, Map<String, Object> event, NuclrPluginCallback callback) {
			record(type, event);
		}

		@Override
		public void emit(Object source, String type, Map<String, Object> event) {
			record(type, event);
		}

		@Override
		public void emit(String type, Map<String, Object> event, NuclrPluginCallback callback) {
			record(type, event);
		}

		@Override
		public void emit(String type, NuclrPluginCallback callback) {
			record(type, Map.of());
		}

		@Override
		public void emit(String type) {
			record(type, Map.of());
		}

		@Override
		public void subscribe(NuclrEventListener listener) {
		}

		@Override
		public void unsubscribe(NuclrEventListener listener) {
		}

		private void record(String type, Map<String, Object> event) {
			this.type.set(type);
			this.data.set(event);
		}
	}
}
