package dev.nuclr.plugin.core.mount.zip;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Point;
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

		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("ENTER"), "openSelected");
		table.getActionMap().put("openSelected", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				openSelectedEntry();
			}
		});

		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("LEFT"), "pageUpSelection");
		table.getActionMap().put("pageUpSelection", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveSelectionByPage(-1);
			}
		});
		table.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
				.put(KeyStroke.getKeyStroke("RIGHT"), "pageDownSelection");
		table.getActionMap().put("pageDownSelection", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				moveSelectionByPage(1);
			}
		});

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

	public void showDirectory(Path path) {
		showDirectory(path, null);
	}

	public void showArchiveRoot(Path path) {
		showDirectory(path, null, true);
	}

	public void showDirectory(Path path, Path selectedPath) {
		showDirectory(path, selectedPath, false);
	}

	private void showDirectory(Path path, Path selectedPath, boolean preferParentSelection) {
		currentDirectory = path;
		pathLabel.setText(path == null ? " " : path.toString());
		model.setEntries(readEntries(path));
		if (model.getRowCount() > 0) {
			if (preferParentSelection) {
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
		} else {
			selectTopOnFocus = false;
			statusLabel.setText(" ");
		}
	}

	public Path getCurrentDirectory() {
		return currentDirectory;
	}

	public NuclrResourcePath getSelectedResource() {
		List<NuclrResourcePath> selected = getSelectedResources();
		return selected.isEmpty() ? null : selected.get(0);
	}

	public List<NuclrResourcePath> getSelectedResources() {
		int[] selectedRows = table.getSelectedRows();
		List<NuclrResourcePath> resources = new ArrayList<>();
		for (int selectedRow : selectedRows) {
			ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(selectedRow));
			if (!entry.parent()) {
				resources.add(provider.toResource(entry.path()));
			}
		}
		return resources;
	}

	public Path getSelectedPath() {
		var selectedResource = getSelectedResource();
		return selectedResource != null ? selectedResource.getPath() : null;
	}

	private List<ZipFilePanelModel.Entry> readEntries(Path directory) {
		if (directory == null || !Files.isDirectory(directory)) {
			return List.of();
		}

		List<ZipFilePanelModel.Entry> entries = new ArrayList<>();
		Path archiveSource = provider.getArchiveSource(directory);
		Path archiveRoot = provider.getArchiveRoot(directory);
		if (archiveRoot != null && archiveRoot.equals(directory) && archiveSource != null && archiveSource.getParent() != null) {
			entries.add(ZipFilePanelModel.Entry.parent(archiveSource.getParent(), archiveSource));
		} else if (directory.getParent() != null) {
			entries.add(ZipFilePanelModel.Entry.parent(directory.getParent(), directory));
		}

		try (var stream = Files.list(directory)) {
			stream.sorted(Comparator
					.comparing((Path path) -> !isContainer(path))
					.thenComparing(path -> path.getFileName() == null ? path.toString() : path.getFileName().toString(),
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
		boolean archive = !directory && provider.isArchivePath(path);
		long sizeBytes = 0L;
		FileTime modifiedTime = null;
		try {
			if (!directory) {
				sizeBytes = Files.size(path);
			}
			modifiedTime = Files.getLastModifiedTime(path);
		} catch (IOException ignored) {
			// keep listing usable
		}
		String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
		return new ZipFilePanelModel.Entry(path, name, directory, false, archive, sizeBytes, modifiedTime, null);
	}

	private void openSelectedEntry() {
		int row = table.getSelectedRow();
		if (row < 0) {
			return;
		}
		ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.parent()) {
			if (provider.isArchiveRoot(currentDirectory) && provider.popPanelLayer()) {
				return;
			}
			showDirectory(entry.path(), entry.returnSelectionPath());
			return;
		}
		if (entry.archive() && provider.pushArchivePanel(entry.path())) {
			return;
		}
		if (entry.directory() || entry.archive()) {
			Path browsablePath = provider.resolveBrowsablePath(entry.path());
			if (browsablePath != null) {
				showDirectory(browsablePath);
			}
			return;
		}
		openFileWithDefaultApplication(entry.path());
	}

	private void updateStatus() {
		int row = table.getSelectedRow();
		if (row < 0) {
			statusLabel.setText(currentDirectory == null ? " " : currentDirectory.toString());
			return;
		}
		ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
		if (entry.parent()) {
			statusLabel.setText("Go to parent directory");
			return;
		}
		String type = entry.directory() ? "Folder" : entry.archive() ? "Archive" : humanReadableSize(entry.sizeBytes());
		statusLabel.setText(entry.name() + "  |  " + type);
	}

	private void moveSelectionByPage(int direction) {
		int rowCount = model.getRowCount();
		if (rowCount == 0) {
			return;
		}

		int currentRow = table.getSelectedRow();
		if (currentRow < 0) {
			currentRow = 0;
		}

		int visibleRows = Math.max(1, table.getVisibleRect().height / Math.max(1, table.getRowHeight()));
		int targetRow = currentRow + (visibleRows * direction);
		targetRow = Math.max(0, Math.min(rowCount - 1, targetRow));

		table.setRowSelectionInterval(targetRow, targetRow);
		table.scrollRectToVisible(table.getCellRect(targetRow, 0, true));
	}

	private boolean selectPath(Path selectedPath) {
		for (int row = 0; row < model.getRowCount(); row++) {
			ZipFilePanelModel.Entry entry = model.getEntryAt(row);
			if (selectedPath.equals(entry.path())) {
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

	private boolean isContainer(Path path) {
		return Files.isDirectory(path) || provider.isArchivePath(path);
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
			Path openablePath = path.getFileSystem().equals(FileSystems.getDefault()) ? path : provider.materializeFile(path);
			Desktop.getDesktop().open(openablePath.toFile());
		} catch (Exception ex) {
			statusLabel.setText("Cannot open file: " + ex.getMessage());
		}
	}

	private void applyUiFonts() {
		Font tableFont = UIManager.getFont("Table.font");
		Font labelFont = UIManager.getFont("Label.font");
		Font baseFont = tableFont != null ? tableFont : labelFont != null ? labelFont : getFont();
		if (baseFont == null) {
			baseFont = new Font(Font.DIALOG, Font.PLAIN, 12);
		}
		table.setFont(baseFont);
		table.getTableHeader().setFont(baseFont);
		pathLabel.setFont(baseFont);
		statusLabel.setFont(baseFont);
		table.setRowHeight(Math.max(20, table.getFontMetrics(baseFont).getHeight() + 6));
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
		return String.format(java.util.Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

	private static final class EntryRenderer extends DefaultTableCellRenderer {
		private static final long serialVersionUID = 1L;

		@Override
		public Component getTableCellRendererComponent(
				JTable table,
				Object value,
				boolean isSelected,
				boolean hasFocus,
				int row,
				int column) {
			Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
			ZipFilePanel panel = (ZipFilePanel) SwingUtilities.getAncestorOfClass(ZipFilePanel.class, table);
			ZipFilePanelModel model = (ZipFilePanelModel) table.getModel();
			ZipFilePanelModel.Entry entry = model.getEntryAt(table.convertRowIndexToModel(row));
			component.setFont(table.getFont().deriveFont((entry.directory() || entry.archive()) ? Font.BOLD : Font.PLAIN));
			if (component instanceof JLabel label) {
				label.setHorizontalAlignment(column == 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
				if (!isSelected && column == 0 && panel != null) {
					label.setForeground(panel.fileNameHighlighter.colorFor(entry));
				}
			}
			return component;
		}
	}
}

