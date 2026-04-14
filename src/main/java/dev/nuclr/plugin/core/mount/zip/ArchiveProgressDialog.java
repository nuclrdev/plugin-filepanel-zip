package dev.nuclr.plugin.core.mount.zip;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.nio.file.Path;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/**
 * Modeless progress dialog for background archive operations (extract, add, delete).
 *
 * <p>All methods may be called from any thread — they dispatch to the EDT internally.
 * Progress updates are throttled: rapid calls from tight loops are dropped, but the
 * final update (done == total) is always dispatched.
 */
final class ArchiveProgressDialog {

	private static final int DIALOG_WIDTH      = 480;
	private static final int PATH_MAX_CHARS    = 58;
	private static final long UPDATE_INTERVAL_MS = 50;

	private final JDialog      dialog;
	private final JLabel       phaseLabel;
	private final JLabel       itemLabel;
	private final JProgressBar progressBar;
	private final JLabel       countLabel;
	private final JButton      cancelButton;

	private volatile long lastUpdateMs = 0;
	private volatile Runnable cancelAction;

	ArchiveProgressDialog(Component parent, String title) {
		Window owner = SwingUtilities.getWindowAncestor(parent);
		dialog = new JDialog(owner, title, Dialog.ModalityType.MODELESS);
		dialog.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		phaseLabel   = new JLabel(" ");
		itemLabel    = new JLabel(" ");
		countLabel   = new JLabel(" ");
		cancelButton = new JButton("Cancel");

		progressBar = new JProgressBar(0, 100);
		progressBar.setStringPainted(true);
		progressBar.setIndeterminate(true);
		progressBar.setPreferredSize(new Dimension(DIALOG_WIDTH - 32, 20));

		// ---- Content layout -------------------------------------------------
		JPanel content = new JPanel(new GridBagLayout());
		content.setBorder(BorderFactory.createEmptyBorder(12, 16, 8, 16));

		GridBagConstraints gc = new GridBagConstraints();
		gc.fill    = GridBagConstraints.HORIZONTAL;
		gc.weightx = 1.0;
		gc.gridx   = 0;

		gc.gridy = 0; gc.insets = new Insets(0, 0, 10, 0); content.add(phaseLabel,   gc);
		gc.gridy = 1; gc.insets = new Insets(0, 0,  2, 0); content.add(itemLabel,    gc);
		gc.gridy = 2; gc.insets = new Insets(6, 0,  0, 0); content.add(progressBar,  gc);
		gc.gridy = 3; gc.insets = new Insets(6, 0,  0, 0); content.add(countLabel,   gc);

		JPanel buttons = new JPanel(new BorderLayout());
		buttons.setBorder(BorderFactory.createEmptyBorder(10, 16, 12, 16));
		buttons.add(cancelButton, BorderLayout.EAST);

		JPanel root = new JPanel(new BorderLayout());
		root.add(content, BorderLayout.CENTER);
		root.add(buttons, BorderLayout.SOUTH);

		bindEscapeToCancel();

		dialog.setContentPane(root);
		dialog.setMinimumSize(new Dimension(DIALOG_WIDTH, 180));
		dialog.pack();
		dialog.setResizable(false);
		dialog.setLocationRelativeTo(parent);
	}

	// -------------------------------------------------------------------------
	// State transitions (thread-safe — all dispatch to the EDT)
	// -------------------------------------------------------------------------

	/** Switch from indeterminate to a determinate bar and show the phase text. */
	void startCounting(String phaseText, int total) {
		SwingUtilities.invokeLater(() -> {
			phaseLabel.setText(phaseText);
			progressBar.setIndeterminate(false);
			progressBar.setMaximum(Math.max(total, 1));
			progressBar.setValue(0);
			progressBar.setString("0%");
			countLabel.setText("0 of " + total);
		});
	}

	/**
	 * Report that one item has been processed.
	 *
	 * <p>Throttled to avoid flooding the EDT on thousands of small files.
	 * The final update (done >= total) is always sent immediately.
	 */
	void update(Path currentItem, int done, int total) {
		long now = System.currentTimeMillis();
		boolean isLast = done >= total;
		if (!isLast && now - lastUpdateMs < UPDATE_INTERVAL_MS) {
			return;
		}
		lastUpdateMs = now;

		int pct = total > 0 ? Math.min(100, done * 100 / total) : 100;
		String itemText = currentItem != null ? truncate(currentItem.toString(), PATH_MAX_CHARS) : " ";

		SwingUtilities.invokeLater(() -> {
			itemLabel.setText(itemText);
			progressBar.setValue(done);
			progressBar.setString(pct + "%");
			countLabel.setText(done + " of " + total);
		});
	}

	// -------------------------------------------------------------------------
	// Wiring
	// -------------------------------------------------------------------------

	/** Register the runnable invoked when Cancel is clicked or Escape pressed. */
	void setCancelAction(Runnable action) {
		this.cancelAction = action;
		cancelButton.addActionListener(e -> {
			cancelButton.setEnabled(false);
			action.run();
			if (dialog.isVisible()) {
				cancelButton.setEnabled(true);
			}
		});
	}

	void show() {
		dialog.setVisible(true);
		SwingUtilities.invokeLater(cancelButton::requestFocusInWindow);
	}

	void close() {
		dialog.setVisible(false);
		dialog.dispose();
	}

	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	private void bindEscapeToCancel() {
		dialog.getRootPane()
				.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
				.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel");
		dialog.getRootPane().getActionMap().put("cancel", new AbstractAction() {
			private static final long serialVersionUID = 1L;

			@Override
			public void actionPerformed(java.awt.event.ActionEvent e) {
				cancelButton.doClick();
			}
		});
	}

	private static String truncate(String s, int max) {
		return s.length() <= max ? s : "\u2026" + s.substring(s.length() - max + 1);
	}
}
