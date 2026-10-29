package today.bonfire.oss.sop;

import java.time.Duration;
import java.util.function.BooleanSupplier;

/** Small helpers shared by the pool tests. Prefer these over {@code Thread.sleep}. */
public final class TestSupport {
  private TestSupport() {}

  /** Polls every few milliseconds until the condition holds, failing with the message after the timeout. */
  public static void awaitUntil(String what, Duration timeout, BooleanSupplier condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > deadline) {
        throw new AssertionError("Timed out after " + timeout.toMillis() + " ms waiting for: " + what);
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while waiting for: " + what, e);
      }
    }
  }

  /** Asserts the condition never becomes true during the window. Use sparingly; it is inherently slow. */
  public static void assertNeverDuring(String what, Duration window, BooleanSupplier condition) {
    long deadline = System.nanoTime() + window.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        throw new AssertionError("Condition unexpectedly became true: " + what);
      }
      try {
        Thread.sleep(5);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("Interrupted while checking: " + what, e);
      }
    }
  }

  /** Sleeps uninterruptibly; only for simulating work inside factories or tasks. */
  public static void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
