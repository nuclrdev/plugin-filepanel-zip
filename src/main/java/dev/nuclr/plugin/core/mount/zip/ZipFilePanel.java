package dev.nuclr.plugin.core.mount.zip;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.Border;
import javax.swing.table.DefaultTableCellRenderer;

import dev.nuclr.platform.plugin.NuclrResourcePath;

/**
 * Swing panel that displays the contents of an archive (or an extracted temp directory)
 * as a navigable table.
 *
 * <p>Keyboard shortcuts mirror Norton Commander conventions:
 * <ul>
 *   <li>Enter / double-click — open folder or file</li>
 *   <li>F5 — copy selected entries to the opposite panel</li>
 *   <li>F6 — move selected entries to the opposite panel</li>
 *   <li>F7 — create a new folder inside the archive</li>
 *   <li>F8 / Delete — delete selected entries from the archive</li>
 *   <li>Space — toggle the selection mark on the current entry</li>
 *   <li>Left / Right — page up / page down</li>
 * </ul>
 */
public class ZipFilePanel extends JPanel {

	private static final long serialVersionUID = 1L;

	final JTable table;
	private final JLabel statusLabel;
	private final JLabel pathLabel;
	private final ZipFilePanelModel model;
	private final Border inactiveBorder;
	private final Border activeBorder;
	private final FileNameHighlighter fileNameHighlighter;
	private final ZipFilePanelPlugin provider;

	private Path currentDirectory;
	private boolean selectTopOnFocus;

	public ZipFilePanel(ZipFilePanelPlugin provider) {
		this.provider = provider;
		model = new ZipFilePanelModel();
		table = new JTable(model);
		statusLabel = new JLabel(" ");
		pathLabel = new JLabel(" ");
		inactiveBorder = BorderFactory.createEmptyBorder(4, 4, 4, 4);
		activeBorder = BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(
						UIManager.getColor("Table.selectionBackground") != null
								? UIManager.getColor("Table.selectionBackground")
								: java.awt.Color.GRAY),
				BorderFactory.createEmptyBorder(3, 3, 3, 3));
		fileNameHighlighter = new FileNameHighlighter(table.getForeground());

		setLayout(new BorderLayout(0, 4));
		setBorder(inactiveBorder);

		pathLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
		statusLabel.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));

		table.setFillsViewportHeight(true);
		table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
		table.getTableHeader().setReorderingAllowed(false);
		table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
		table.getColumnModel().getColumn(1).setPreferredWidth(100);
		table.getColumnModel().getColumn(1).setMaxWidth(120);
		table.getColumnModel().getColumn(2).setPreferredWidth(160);
		table.getColumnModel().getColumn(2).setMaxWidth(180);
		table.setDefaultRenderer(Object.class, new EntryRenderer());
		applyUiFonts();

		wireKeyboardActions();

		table.getSelectionModel().addListSelectionListener(e -> {
			if (!e.getValueIsAdjusting()) {
				updateStatus();
			}
		});
		table.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
					openSelectedEntry();
				}
			}
		});

		add(pathLabel, BorderLayout.NORTH);
		add(new JScrollPane(table), BorderLayout.CENTER);
		add(statusLabel, BorderLayout.SOUTH);
	}

	// -------------------------------------------------------------------------
	// Keyboard wiring
	// -------------------------------------------------------------------------

	private void wireKeyboardActions() {
		bind("ENTER",  "openSelected",   this::openSelectedEntry);
		bind("F5",     "copySelected",   this::requestCopyOut);
		bind("F6",     "moveSelected",   this::requestMoveOut);
		bind("F7",     "makeFolder",     this::promptCreateFolder);
		bind("F8",     "deleteSelected", this::promptDeleteSelection);
		bind("DELETE", "deleteKey",      this::promptDeleteSelection);
		bind("SPACE",  "toggleMark",     this::toggleSelectionMark);
		bind("LEFT",   "pageUp",         () -> moveSelectionByPage(-1));
		bind("RIGHT",  "pageDown",       () -> moveSelectionByPage(1));
	}

	private void bind(String keyStroke, String actionName, Runnable action) {
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke(keyStroke), actionName);
		table.getActionMap().put(actionName, new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				action.run();
			}
		});
	}

	// -------------------------------------------------------------------------
	// Focus & appearance
	// -------------------------------------------------------------------------

	@Override
	public void updateUI() {
		super.updateUI();
		if (table != null) {
			applyUiFonts();
		}
	}

	public void focusTable() {
		if (model.getRowCount() > 0 && table.getSelectedRow() < 0) {
			table.setRowSelectionInterval(0, 0);
		}
		table.requestFocusInWindow();
	}

	public void setPluginFocused(boolean focused) {
		setBorder(focused ? activeBorder : inactiveBorder);
		if (focused) {
			applyDeferredTopSelection();
			focusTable();
			SwingUtilities.invokeLater(() -> {
				applyDeferredTopSelection();
				focusTable();
			});
		}
		revalidate();
		repaint();
	}

	// -------------------------------------------------------------------------
	// Directory display
	// -------------------------------------------------------------------------

	public void showDirectory(Path path) {
		showDirectory(path, null, false);
	}

	public void showArchiveRoot(Path path) {
		showDirectory(path, null, true);
	}

	public void showDirectory(Path path, Path selectedPath) {
		showDirectory(path, selectedPath, false);
	}

	private void showDirectory(Path path, Path selectedPath, boolean preferTopSelection) {
		currentDirectory = path;
		pathLabel.setText(path == null ? " " : path.toString());
		model.setEntries(readEntries(path));

		if (model.getRowCount() == 0) {
			selectTopOnFocus = false;
			statusLabel.setText(" ");
			return;
		}
		if (preferTopSelection) {
			selectTopOnFocus = true;
			selectFirstRowAndScrollToTop();
			return;
		}
		if (selectedPath != null && selectPath(selectedPath)) {
			selectTopOnFocus = false;
			return;
		}
		selectTopOnFocus = false;
		selectFirstRowAndScrollToTop();
	}

	public Path getCurrentDirectory() {
		return currentDirectory;
	}

	// -------------------------------------------------------------------------
	// Selection helpers
	// -------------------------------------------------------------------------

	public NuclrResourcePath getSelectedResource() {
		List<NuclrResourcePath> selected = getSelectedResources();
		return selected.isEmpty() ? null : selected.get(0);
	}

	public List<NuclrResourcePath> getSelectedResources() {
		List<NuclrResourcePath> resources = new ArrayList<>();
		for (ZipFilePanelModel.Entry entry : getSelectedEntries()) {
			if (!entry.isParent()) {
				resources.add(provider.toResource(entry.getPath()));
			}
		}
		return resources;
	}

	public Path getSelectedPath() {
		NuclrResourcePath resource = getSelectedResource();
		return resource != null ? resource.getPath() : null;
	}

	/** Returns all selected entries, excluding the ".." parent row. */
	public List<ZipFilePanelModel.Entry> getSelectedEntries() {
		int[] selectedRows = table.getSelectedRows();
		List<ZipFilePanelModel.Entry> entries = new ArrayList<>();
		for (int viewRow : selectedRows) {
			ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(viewRow));
			if (!entry.isParent()) {
				entries.add(entry);
			}
		}
		return entries;
	}

	// -------------------------------------------------------------------------
	// Operations triggered by keyboard / menu
	// -------------------------------------------------------------------------

	/** F5: request that selected entries be copied to the opposite panel. */
	private void requestCopyOut() {
		List<NuclrResourcePath> selected = getSelectedResources();
		if (selected.isEmpty()) {
			return;
		}
		provider.emitCopyRequest(selected);
	}

	/** F6: request that selected entries be moved to the opposite panel. */
	private void requestMoveOut() {
		List<NuclrResourcePath> selected = getSelectedResources();
		if (selected.isEmpty()) {
			return;
		}
		provider.emitMoveRequest(selected);
	}

	/** F7: ask the user for a folder name, then create it in the archive. */
	void promptCreateFolder() {
		if (currentDirectory == null) {
			return;
		}
		if (!provider.isWritableArchiveDirectory(currentDirectory)) {
			statusLabel.setText("This archive format is read-only — changes are not persisted.");
			return;
		}
		String name = JOptionPane.showInputDialog(
				this,
				"New folder name:",
				"Create Folder",
				JOptionPane.PLAIN_MESSAGE);
		if (name == null || name.isBlank()) {
			return;
		}
		provider.getWriteService().createFolder(
				currentDirectory, name.strip(), this,
				() -> showDirectory(currentDirectory));
	}

	/** F8 / Delete: confirm then delete selected entries from the archive. */
	void promptDeleteSelection() {
		if (currentDirectory == null) {
			return;
		}
		if (!provider.isWritableArchiveDirectory(currentDirectory)) {
			statusLabel.setText("This archive format is read-only — changes are not persisted.");
			return;
		}
		List<ZipFilePanelModel.Entry> entries = getSelectedEntries();
		if (entries.isEmpty()) {
			return;
		}
		List<Path> paths = new ArrayList<>();
		for (ZipFilePanelModel.Entry entry : entries) {
			paths.add(entry.getPath());
		}
		provider.getWriteService().deleteEntries(
				paths, this,
				() -> showDirectory(currentDirectory));
	}

	/** Space: toggle the selection mark on the focused row without moving the cursor. */
	private void toggleSelectionMark() {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		if (table.isRowSelected(row)) {
			table.removeRowSelectionInterval(row, row);
		} else {
			table.addRowSelectionInterval(row, row);
		}
		// Move focus to next row for convenient multi-select
		int next = Math.min(row + 1, model.getRowCount() - 1);
		table.setRowSelectionInterval(next, next);
		table.scrollRectToVisible(table.getCellRect(next, 0, true));
	}

	// -------------------------------------------------------------------------
	// Entry reading
	// -------------------------------------------------------------------------

	private List<ZipFilePanelModel.Entry> readEntries(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return List.of();
		}

		List<ZipFilePanelModel.Entry> entries = new ArrayList<>();

		// Add ".." row — either back to the archive source or to the parent directory
		Path archiveSource = provider.getArchiveSource(directory);
		Path archiveRoot   = provider.getArchiveRoot(directory);
		boolean atArchiveRoot = archiveRoot != null && archiveRoot.equals(directory);

		if (atArchiveRoot && archiveSource != null && archiveSource.getParent() != null) {
			entries.add(ZipFilePanelModel.Entry.parent(archiveSource.getParent(), archiveSource));
		} else if (directory.getParent() != null) {
			entries.add(ZipFilePanelModel.Entry.parent(directory.getParent(), directory));
		}

		// List the directory contents
		try (var stream = Files.list(directory)) {
			stream
				.sorted(Comparator
					.comparing((Path path) -> !isContainer(path))
					.thenComparing(
						path -> path.getFileName() == null ? path.toString() : path.getFileName().toString(),
						String.CASE_INSENSITIVE_ORDER))
				.forEach(path -> entries.add(toEntry(path)));
		} catch (IOException ex) {
			entries.clear();
			statusLabel.setText("Cannot read " + directory + ": " + ex.getMessage());
		}

		return entries;
	}

	private ZipFilePanelModel.Entry toEntry(Path path) {
		boolean directory = Files.isDirectory(path);
		boolean archive   = !directory && provider.isArchivePath(path);
		long sizeBytes    = 0L;
		FileTime modified = null;
		try {
			if (!directory) {
				sizeBytes = Files.size(path);
			}
			modified = Files.getLastModifiedTime(path);
		} catch (IOException ignored) {
			// keep listing usable even if attributes fail
		}
		String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
		return new ZipFilePanelModel.Entry(path, name, directory, false, archive, sizeBytes, modified, null);
	}

	private boolean isContainer(Path path) {
		return Files.isDirectory(path) || provider.isArchivePath(path);
	}

	// -------------------------------------------------------------------------
	// Entry open
	// -------------------------------------------------------------------------

	private void openSelectedEntry() {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));

		if (entry.isParent()) {
			// ".." row: leave archive or navigate to parent directory
			if (provider.isArchiveRoot(currentDirectory) && provider.popPanelLayer()) {
				return;
			}
			showDirectory(entry.getPath(), entry.getReturnSelectionPath());
			return;
		}

		// Nested archive: push a new panel layer
		if (entry.isArchive() && provider.pushArchivePanel(entry.getPath())) {
			return;
		}

		// Regular directory or archive (push failed) — navigate inline
		if (entry.isDirectory() || entry.isArchive()) {
			Path browsable = provider.resolveBrowsablePath(entry.getPath());
			if (browsable != null) {
				showDirectory(browsable);
			}
			return;
		}

		// Regular file — open with the default OS application
		openFileWithDefaultApplication(entry.getPath());
	}

	private void openFileWithDefaultApplication(Path path) {
		if (path == null || !Files.isRegularFile(path)) {
			return;
		}
		if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
			statusLabel.setText("Desktop file open is not supported on this platform.");
			return;
		}
		try {
			// Files inside a ZIP NIO filesystem must be materialized to a real temp file first
			Path openablePath = path.getFileSystem().equals(FileSystems.getDefault())
					? path
					: provider.materializeFile(path);
			Desktop.getDesktop().open(openablePath.toFile());
		} catch (Exception ex) {
			statusLabel.setText("Cannot open file: " + ex.getMessage());
		}
	}

	// -------------------------------------------------------------------------
	// Status bar
	// -------------------------------------------------------------------------

	private void updateStatus() {
		int row = table.getSelectedRow();
		if (row < 0) {
			statusLabel.setText(currentDirectory == null ? " " : currentDirectory.toString());
			return;
		}
		ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.isParent()) {
			statusLabel.setText("Go to parent directory");
			return;
		}
		String type = entry.isDirectory()
				? "Folder"
				: entry.isArchive()
				? "Archive"
				: humanReadableSize(entry.getSizeBytes());
		statusLabel.setText(entry.getName() + "  |  " + type);
	}

	// -------------------------------------------------------------------------
	// Navigation helpers
	// -------------------------------------------------------------------------

	private void moveSelectionByPage(int direction) {
		int rowCount = model.getRowCount();
		if (rowCount == 0) {
			return;
		}
		int current     = Math.max(0, table.getSelectedRow());
		int visibleRows = Math.max(1, table.getVisibleRect().height / Math.max(1, table.getRowHeight()));
		int target      = Math.max(0, Math.min(rowCount - 1, current + visibleRows * direction));
		table.setRowSelectionInterval(target, target);
		table.scrollRectToVisible(table.getCellRect(target, 0, true));
	}

	private boolean selectPath(Path selectedPath) {
		for (int row = 0; row < model.getRowCount(); row++) {
			if (selectedPath.equals(model.getEntryAt(row).getPath())) {
				selectRow(row);
				return true;
			}
		}
		return false;
	}

	private void selectFirstRowAndScrollToTop() {
		selectRow(0);
		if (table.getParent() instanceof JViewport viewport) {
			viewport.setViewPosition(new Point(0, 0));
		}
	}

	private void applyDeferredTopSelection() {
		if (!selectTopOnFocus || model.getRowCount() == 0) {
			return;
		}
		selectFirstRowAndScrollToTop();
		selectTopOnFocus = false;
	}

	private void selectRow(int row) {
		table.setRowSelectionInterval(row, row);
		table.scrollRectToVisible(table.getCellRect(row, 0, true));
	}

	// -------------------------------------------------------------------------
	// UI utilities
	// -------------------------------------------------------------------------

	private void applyUiFonts() {
		Font tableFont = UIManager.getFont("Table.font");
		Font labelFont = UIManager.getFont("Label.font");
		Font base = tableFont != null ? tableFont : labelFont != null ? labelFont : getFont();
		if (base == null) {
			base = new Font(Font.DIALOG, Font.PLAIN, 12);
		}
		table.setFont(base);
		table.getTableHeader().setFont(base);
		pathLabel.setFont(base);
		statusLabel.setFont(base);
		table.setRowHeight(Math.max(20, table.getFontMetrics(base).getHeight() + 6));
	}

	private static String humanReadableSize(long sizeBytes) {
		if (sizeBytes < 1024) {
			return sizeBytes + " B";
		}
		double value = sizeBytes;
		String[] units = { "KB", "MB", "GB", "TB", "PB" };
		int unitIndex = -1;
		while (value >= 1024 && unitIndex < units.length - 1) {
			value /= 1024;
			unitIndex++;
		}
		return String.format(java.util.Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

	// -------------------------------------------------------------------------
	// Table cell renderer
	// -------------------------------------------------------------------------

	private static final class EntryRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(
				JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {

			Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			ZipFilePanel panel = (ZipFilePanel) SwingUtilities.getAncestorOfClass(ZipFilePanel.class, table);
			ZipFilePanelModel model = (ZipFilePanelModel) table.getModel();
			ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));

			boolean bold = entry.isDirectory() || entry.isArchive();
			c.setFont(table.getFont().deriveFont(bold ? Font.BOLD : Font.PLAIN));

			if (c instanceof JLabel label) {
				label.setHorizontalAlignment(column == 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
				if (!isSelected && column == 0 && panel != null) {
					label.setForeground(panel.fileNameHighlighter.colorFor(entry));
				}
			}
			return c;
		}
	}

	public NuclrResourcePath getCurrentResource() {
		return provider.toResource(currentDirectory);
	}

}
