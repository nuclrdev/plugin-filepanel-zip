package dev.nuclr.plugin.core.mount.zip;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.SecondaryLoop;
import java.awt.Toolkit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

import org.junit.jupiter.api.Test;

class SwingDialogRunnerTest {

	@Test
	void interruptCancelsDialogStillQueuedOnEdt() throws Exception {
		var blockerEntered = new CountDownLatch(1);
		var releaseEdt = new CountDownLatch(1);
		SwingUtilities.invokeLater(() -> {
			blockerEntered.countDown();
			await(releaseEdt);
		});
		assertTrue(blockerEntered.await(5, TimeUnit.SECONDS));

		var dialogRan = new AtomicBoolean();
		var completed = new AtomicBoolean(true);
		var interruptRestored = new AtomicBoolean();
		Thread worker = Thread.ofPlatform().start(() -> {
			completed.set(SwingDialogRunner.runAndWait("queued test dialog", () -> dialogRan.set(true), () -> { }));
			interruptRestored.set(Thread.currentThread().isInterrupted());
		});

		try {
			assertTrue(awaitState(worker, Thread.State.WAITING));
			worker.interrupt();
			assertTrue(awaitInterruptObserved(worker));
		} finally {
			releaseEdt.countDown();
		}
		worker.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse(worker.isAlive());
		assertFalse(completed.get());
		assertFalse(dialogRan.get());
		assertTrue(interruptRestored.get());
	}

	@Test
	void interruptClosesDialogAlreadyRunningOnEdt() throws Exception {
		var dialogEntered = new CountDownLatch(1);
		var loop = new AtomicReference<SecondaryLoop>();
		var cancelRan = new AtomicBoolean();
		var dialogReturned = new AtomicBoolean();
		var completed = new AtomicBoolean(true);
		var interruptRestored = new AtomicBoolean();

		Thread worker = Thread.ofPlatform().start(() -> {
			completed.set(SwingDialogRunner.runAndWait("visible test dialog", () -> {
				SecondaryLoop secondaryLoop = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
				loop.set(secondaryLoop);
				dialogEntered.countDown();
				secondaryLoop.enter();
				dialogReturned.set(true);
			}, () -> {
				cancelRan.set(true);
				SecondaryLoop secondaryLoop = loop.get();
				if (secondaryLoop != null) {
					secondaryLoop.exit();
				}
			}));
			interruptRestored.set(Thread.currentThread().isInterrupted());
		});

		assertTrue(dialogEntered.await(5, TimeUnit.SECONDS));
		worker.interrupt();
		worker.join(TimeUnit.SECONDS.toMillis(5));
		assertFalse(worker.isAlive());
		assertFalse(completed.get());
		assertTrue(cancelRan.get());
		assertTrue(dialogReturned.get());
		assertTrue(interruptRestored.get());
	}

	private static boolean awaitState(Thread thread, Thread.State state) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (thread.isAlive() && thread.getState() != state && System.nanoTime() < deadline) {
			Thread.sleep(1);
		}
		return thread.getState() == state;
	}

	/**
	 * Waits until {@code thread} has consumed its pending interrupt and parked again. The runner
	 * only marks a queued dialog cancelled from inside its {@code InterruptedException} handler, so
	 * releasing the EDT any earlier lets the dialog task run before it sees the cancellation.
	 */
	private static boolean awaitInterruptObserved(Thread thread) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (!thread.isInterrupted() && thread.getState() == Thread.State.WAITING) {
				return true;
			}
			Thread.sleep(1);
		}
		return false;
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}
}
