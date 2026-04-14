package dev.nuclr.plugin.core.mount.zip;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JComponent;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/**
 * Performs write operations on archive filesystems in background virtual threads.
 *
 * <p>Supported operations:
 * <ul>
 *   <li>{@link #addFiles} — copy real-filesystem files into an archive directory.</li>
 *   <li>{@link #deleteEntries} — remove entries from an archive.</li>
 *   <li>{@link #createFolder} — create a directory inside an archive.</li>
 * </ul>
 *
 * <p>All methods are intended to be called from the Swing EDT.  They show
 * progress dialogs and start background threads internally, then call
 * {@code onComplete} back on the EDT when done.
 *
 * <p>Works with any {@link java.nio.file.FileSystem} that supports writes.
 * For ZIP-family archives mounted via Java NIO, writes are persisted directly
 * into the archive file.  For extracted temp directories (TAR, RAR), writes
 * affect the temp directory only and are NOT persisted to the original archive.
 */
@Slf4j
final class ArchiveWriteService {

	// -------------------------------------------------------------------------
	// Add files into an archive directory
	// -------------------------------------------------------------------------

	/**
	 * Copy {@code sources} (real-filesystem paths) into {@code archiveTargetDir}
	 * (an archive NIO path or a temp-dir path).
	 *
	 * <p>Directories are copied recursively.  Existing entries with the same name
	 * are overwritten.
	 *
	 * @param archiveTargetDir  current directory inside the archive — files land here
	 * @param sources           real-filesystem files/dirs to copy in
	 * @param parent            Swing component for dialog ownership
	 * @param onComplete        called on the EDT when the operation finishes
	 *                          (whether by completion, error, or cancellation)
	 */
	void addFiles(Path archiveTargetDir, List<Path> sources, JComponent parent, Runnable onComplete) {
		if (sources == null || sources.isEmpty()) {
			return;
		}

		ArchiveProgressDialog dialog = new ArchiveProgressDialog(parent, "Copying into Archive");
		AtomicBoolean cancelled = new AtomicBoolean(false);
		dialog.setCancelAction(() -> cancelled.set(true));

		Thread.ofVirtual().start(() -> {
			try {
				runAddFiles(archiveTargetDir, sources, dialog, cancelled);
			} catch (Exception ex) {
				log.error("Failed to add files to archive", ex);
				showError(parent, "Copy into Archive", ex.getMessage());
			} finally {
				SwingUtilities.invokeLater(() -> {
					dialog.close();
					onComplete.run();
				});
			}
		});

		dialog.show();
	}

	private static void runAddFiles(
			Path archiveTargetDir,
			List<Path> sources,
			ArchiveProgressDialog dialog,
			AtomicBoolean cancelled) throws IOException {

		// Pre-scan to get a total entry count for the progress bar
		List<Path> allFiles = scanAllFiles(sources);
		dialog.startCounting("Copying...", allFiles.size());

		AtomicInteger done = new AtomicInteger(0);

		for (Path source : sources) {
			if (cancelled.get()) {
				break;
			}
			if (Files.isRegularFile(source)) {
				copyFileIntoArchive(source, archiveTargetDir, dialog, done, allFiles.size(), cancelled);
			} else if (Files.isDirectory(source)) {
				copyDirectoryIntoArchive(source, archiveTargetDir, dialog, done, allFiles.size(), cancelled);
			}
		}
	}

	private static void copyFileIntoArchive(
			Path source,
			Path archiveTargetDir,
			ArchiveProgressDialog dialog,
			AtomicInteger done,
			int total,
			AtomicBoolean cancelled) throws IOException {

		String fileName = source.getFileName().toString();
		Path target = archiveTargetDir.resolve(fileName);
		Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
		dialog.update(source, done.incrementAndGet(), total);
	}

	private static void copyDirectoryIntoArchive(
			Path sourceDir,
			Path archiveTargetParent,
			ArchiveProgressDialog dialog,
			AtomicInteger done,
			int total,
			AtomicBoolean cancelled) throws IOException {

		// The source directory itself becomes a subfolder under archiveTargetParent
		Path archiveDirRoot = archiveTargetParent.resolve(sourceDir.getFileName().toString());
		Files.createDirectories(archiveDirRoot);

		Files.walkFileTree(sourceDir, new SimpleFileVisitor<>() {

			@Override
			public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
				if (cancelled.get()) {
					return FileVisitResult.TERMINATE;
				}
				Path relative = sourceDir.relativize(dir);
				Path target = resolveRelative(archiveDirRoot, relative);
				Files.createDirectories(target);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
				if (cancelled.get()) {
					return FileVisitResult.TERMINATE;
				}
				Path relative = sourceDir.relativize(file);
				Path target = resolveRelative(archiveDirRoot, relative);
				Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
				dialog.update(file, done.incrementAndGet(), total);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				log.warn("Cannot read {} during archive copy: {}", file, exc.getMessage());
				return FileVisitResult.CONTINUE;
			}
		});
	}

	/**
	 * Resolve a relative path segment by segment so the provider of each
	 * intermediate path is always the archive filesystem, not the source filesystem.
	 */
	private static Path resolveRelative(Path base, Path relative) {
		Path result = base;
		for (Path segment : relative) {
			result = result.resolve(segment.toString());
		}
		return result;
	}

	// -------------------------------------------------------------------------
	// Delete entries from an archive
	// -------------------------------------------------------------------------

	/**
	 * Delete {@code paths} from the archive.
	 *
	 * <p>Directories are deleted recursively (deepest entries first so that parent
	 * directories are empty before they are removed).
	 *
	 * @param paths       archive paths to delete (NIO paths inside the archive FS)
	 * @param parent      Swing component for dialog ownership
	 * @param onComplete  called on the EDT when the operation finishes
	 */
	void deleteEntries(List<Path> paths, JComponent parent, Runnable onComplete) {
		if (paths == null || paths.isEmpty()) {
			return;
		}

		if (!confirmDelete(parent, paths)) {
			return;
		}

		ArchiveProgressDialog dialog = new ArchiveProgressDialog(parent, "Deleting from Archive");
		AtomicBoolean cancelled = new AtomicBoolean(false);
		dialog.setCancelAction(() -> cancelled.set(true));

		Thread.ofVirtual().start(() -> {
			try {
				runDeleteEntries(paths, dialog, cancelled);
			} catch (Exception ex) {
				log.error("Failed to delete entries from archive", ex);
				showError(parent, "Delete from Archive", ex.getMessage());
			} finally {
				SwingUtilities.invokeLater(() -> {
					dialog.close();
					onComplete.run();
				});
			}
		});

		dialog.show();
	}

	private static void runDeleteEntries(
			List<Path> paths,
			ArchiveProgressDialog dialog,
			AtomicBoolean cancelled) throws IOException {

		// Collect all entries to delete (expand directories)
		List<Path> allToDelete = new ArrayList<>();
		for (Path path : paths) {
			if (Files.isDirectory(path)) {
				collectAllUnderDirectory(path, allToDelete);
			} else {
				allToDelete.add(path);
			}
		}

		// Sort deepest paths first so directories are empty before deletion
		allToDelete.sort(Comparator.comparingInt(p -> -p.getNameCount()));

		dialog.startCounting("Deleting...", allToDelete.size());
		AtomicInteger done = new AtomicInteger(0);

		for (Path path : allToDelete) {
			if (cancelled.get()) {
				break;
			}
			try {
				Files.deleteIfExists(path);
				dialog.update(path, done.incrementAndGet(), allToDelete.size());
			} catch (IOException ex) {
				log.warn("Could not delete archive entry {}: {}", path, ex.getMessage());
			}
		}
	}

	private static void collectAllUnderDirectory(Path dir, List<Path> collector) throws IOException {
		Files.walkFileTree(dir, new SimpleFileVisitor<>() {
			@Override
			public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
				collector.add(file);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult postVisitDirectory(Path d, IOException exc) {
				collector.add(d);
				return FileVisitResult.CONTINUE;
			}

			@Override
			public FileVisitResult visitFileFailed(Path file, IOException exc) {
				log.warn("Cannot access {} during delete scan: {}", file, exc.getMessage());
				return FileVisitResult.CONTINUE;
			}
		});
	}

	// -------------------------------------------------------------------------
	// Create a folder inside an archive
	// -------------------------------------------------------------------------

	/**
	 * Create a new folder named {@code folderName} inside {@code archiveDir}.
	 *
	 * <p>This is a quick synchronous operation — no progress dialog is shown.
	 *
	 * @param archiveDir  current directory inside the archive
	 * @param folderName  name for the new folder (must not be blank)
	 * @param parent      Swing component for error dialog ownership
	 * @param onComplete  called on the EDT when done (whether success or error)
	 */
	void createFolder(Path archiveDir, String folderName, JComponent parent, Runnable onComplete) {
		if (folderName == null || folderName.isBlank()) {
			return;
		}

		Thread.ofVirtual().start(() -> {
			try {
				Path newDir = archiveDir.resolve(folderName.strip());
				Files.createDirectory(newDir);
			} catch (Exception ex) {
				log.error("Failed to create folder in archive", ex);
				showError(parent, "Create Folder", ex.getMessage());
			} finally {
				SwingUtilities.invokeLater(onComplete);
			}
		});
	}

	// -------------------------------------------------------------------------
	// Confirmation dialog
	// -------------------------------------------------------------------------

	private static boolean confirmDelete(JComponent parent, List<Path> paths) {
		StringBuilder message = new StringBuilder("Delete from archive?\n\n");
		int limit = Math.min(paths.size(), 8);
		for (int i = 0; i < limit; i++) {
			Path p = paths.get(i);
			String name = p.getFileName() != null ? p.getFileName().toString() : p.toString();
			String kind = Files.isDirectory(p) ? "[Folder] " : "[File] ";
			message.append(kind).append(name).append('\n');
		}
		if (paths.size() > limit) {
			message.append("... and ").append(paths.size() - limit).append(" more\n");
		}
		message.append("\nThis cannot be undone.");

		int choice = JOptionPane.showConfirmDialog(
				parent,
				message.toString(),
				"Delete from Archive",
				JOptionPane.YES_NO_OPTION,
				JOptionPane.WARNING_MESSAGE);
		return choice == JOptionPane.YES_OPTION;
	}

	// -------------------------------------------------------------------------
	// Pre-scan helper
	// -------------------------------------------------------------------------

	private static List<Path> scanAllFiles(List<Path> sources) throws IOException {
		List<Path> files = new ArrayList<>();
		for (Path source : sources) {
			if (Files.isRegularFile(source)) {
				files.add(source);
			} else if (Files.isDirectory(source)) {
				Files.walkFileTree(source, new SimpleFileVisitor<>() {
					@Override
					public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
						files.add(file);
						return FileVisitResult.CONTINUE;
					}

					@Override
					public FileVisitResult visitFileFailed(Path file, IOException exc) {
						log.warn("Cannot access {} during scan: {}", file, exc.getMessage());
						return FileVisitResult.CONTINUE;
					}
				});
			}
		}
		return files;
	}

	// -------------------------------------------------------------------------
	// Error helper (safe to call from any thread)
	// -------------------------------------------------------------------------

	private static void showError(JComponent parent, String title, String message) {
		SwingUtilities.invokeLater(() ->
				JOptionPane.showMessageDialog(parent, message, title, JOptionPane.ERROR_MESSAGE));
	}
}
