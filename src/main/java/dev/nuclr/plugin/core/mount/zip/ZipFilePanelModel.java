package dev.nuclr.plugin.core.mount.zip;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import javax.swing.table.AbstractTableModel;

public final class ZipFilePanelModel extends AbstractTableModel {

	private static final long serialVersionUID = 1L;

	private static final DateFormat DATE_TIME_FORMAT =
			DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.getDefault());

	private final List<Entry> entries = new ArrayList<>();

	public void setEntries(List<Entry> newEntries) {
		entries.clear();
		entries.addAll(newEntries);
		fireTableDataChanged();
	}

	public Entry getEntryAt(int row) {
		return entries.get(row);
	}

	@Override
	public int getRowCount() {
		return entries.size();
	}

	@Override
	public int getColumnCount() {
		return 3;
	}

	@Override
	public String getColumnName(int column) {
		return switch (column) {
			case 0 -> "Name";
			case 1 -> "Size";
			case 2 -> "Modified";
			default -> "";
		};
	}

	@Override
	public Object getValueAt(int rowIndex, int columnIndex) {
		Entry entry = entries.get(rowIndex);
		return switch (columnIndex) {
			case 0 -> entry.getName();
			case 1 -> entry.isParent()
					? ""
					: entry.isDirectory()
					? "Folder"
					: entry.isArchive()
					? "Archive"
					: humanReadableSize(entry.getSizeBytes());
			case 2 -> formatModified(entry.getModifiedTime());
			default -> "";
		};
	}

	@Override
	public boolean isCellEditable(int rowIndex, int columnIndex) {
		return false;
	}

	private static String formatModified(FileTime modifiedTime) {
		return modifiedTime == null ? "" : DATE_TIME_FORMAT.format(modifiedTime.toMillis());
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
		return String.format(Locale.ROOT, unitIndex == 0 ? "%.0f %s" : "%.1f %s", value, units[unitIndex]);
	}

	// -------------------------------------------------------------------------
	// Entry — one row in the archive listing
	// -------------------------------------------------------------------------

	public static final class Entry {

		private final Path path;
		private final String name;
		private final boolean directory;
		private final boolean parent;
		private final boolean archive;
		private final long sizeBytes;
		private final FileTime modifiedTime;

		/**
		 * The path the panel should navigate back to after pressing ".." on this entry.
		 * For the ".." row this is the path that was selected before entering the folder.
		 */
		private final Path returnSelectionPath;

		public Entry(
				Path path,
				String name,
				boolean directory,
				boolean parent,
				boolean archive,
				long sizeBytes,
				FileTime modifiedTime,
				Path returnSelectionPath) {
			this.path = path;
			this.name = name;
			this.directory = directory;
			this.parent = parent;
			this.archive = archive;
			this.sizeBytes = sizeBytes;
			this.modifiedTime = modifiedTime;
			this.returnSelectionPath = returnSelectionPath;
		}

		/** Creates the ".." parent-navigation row. */
		public static Entry parent(Path parentPath, Path returnSelectionPath) {
			return new Entry(parentPath, "..", true, true, false, 0L, null, returnSelectionPath);
		}

		public Path getPath()                { return path; }
		public String getName()              { return name; }
		public boolean isDirectory()         { return directory; }
		public boolean isParent()            { return parent; }
		public boolean isArchive()           { return archive; }
		public long getSizeBytes()           { return sizeBytes; }
		public FileTime getModifiedTime()    { return modifiedTime; }
		public Path getReturnSelectionPath() { return returnSelectionPath; }
	}
}
