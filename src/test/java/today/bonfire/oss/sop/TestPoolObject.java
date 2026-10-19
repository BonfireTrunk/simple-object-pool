package today.bonfire.oss.sop;

import java.util.concurrent.atomic.AtomicLong;

/**
 * A plain object for pool tests. It no longer implements any pool interface: v3 tracks objects
 * by identity. The sequence number exists only so tests can tell instances apart in assertions.
 */
public class TestPoolObject {
  private static final AtomicLong SEQUENCE = new AtomicLong();

  private final    long    seq       = SEQUENCE.incrementAndGet();
  private volatile boolean destroyed = false;

  TestPoolObject() {}

  public long seq() {
    return seq;
  }

  public void destroy() {
    destroyed = true;
  }

  public boolean isDestroyed() {
    return destroyed;
  }

  public boolean isValid() {
    return !destroyed;
  }

  @Override
  public String toString() {
    return "TestPoolObject#" + seq + (destroyed ? "(destroyed)" : "");
  }
}
