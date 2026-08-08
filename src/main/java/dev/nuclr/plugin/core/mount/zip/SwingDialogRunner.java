/*

	Copyright 2026 Sergio, Nuclr (https://nuclr.dev)

	Licensed under the Apache License, Version 2.0 (the "License");
	you may not use this file except in compliance with the License.
	You may obtain a copy of the License at

	http://www.apache.org/licenses/LICENSE-2.0

	Unless required by applicable law or agreed to in writing, software
	distributed under the License is distributed on an "AS IS" BASIS,
	WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
	See the License for the specific language governing permissions and
	limitations under the License.

*/
package dev.nuclr.plugin.core.mount.zip;

import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import lombok.extern.slf4j.Slf4j;

/** Runs a modal Swing dialog from any thread and closes it if the waiting thread is interrupted. */
@Slf4j
public final class SwingDialogRunner {

	private SwingDialogRunner() {
	}

	/**
	 * Runs {@code showDialog} on the EDT and waits for it to finish. If the waiting thread is
	 * interrupted before the task starts, the task is skipped. If it is interrupted while a modal
	 * dialog is visible, {@code cancelDialog} is dispatched on the EDT and this method waits for the
	 * dialog task to finish before returning.
	 *
	 * @return {@code false} when interrupted or when the dialog task fails
	 */
	public static boolean runAndWait(String description, Runnable showDialog, Runnable cancelDialog) {
		Objects.requireNonNull(showDialog, "showDialog");
		Objects.requireNonNull(cancelDialog, "cancelDialog");

		if (Thread.currentThread().isInterrupted()) {
			log.debug("Skipped {} because the waiting thread is already interrupted", description);
			return false;
		}
		if (SwingUtilities.isEventDispatchThread()) {
			showDialog.run();
			return true;
		}

		var cancelled = new AtomicBoolean();
		var failure = new AtomicReference<Throwable>();
		var finished = new CountDownLatch(1);
		SwingUtilities.invokeLater(() -> {
			try {
				if (!cancelled.get()) {
					showDialog.run();
				}
			} catch (Throwable e) {
				failure.set(e);
			} finally {
				finished.countDown();
			}
		});

		boolean interrupted = false;
		while (true) {
			try {
				finished.await();
				break;
			} catch (InterruptedException e) {
				if (!interrupted) {
					cancelled.set(true);
					SwingUtilities.invokeLater(cancelDialog);
				}
				interrupted = true;
			}
		}

		if (interrupted) {
			Thread.currentThread().interrupt();
			log.debug("Interrupted while waiting for {}", description);
			return false;
		}
		if (failure.get() != null) {
			log.warn("Failed to show {}", description, failure.get());
			return false;
		}
		return true;
	}
}
