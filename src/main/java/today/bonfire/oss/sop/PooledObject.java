package today.bonfire.oss.sop;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Wrapper the pool keeps around every pooled object. It records lifecycle timestamps and
 * usage counters and is what the factory's {@link PooledObjectFactory#shouldEvict} hook and
 * the pool's statistics expose.
 * <p>
 * Instances are compared by identity. The pool never calls {@code equals} or {@code hashCode}
 * on the wrapped object.
 *
 * @param <T> the type of the pooled object
 */
public final class PooledObject<T> {

  /** Where an object is in its lifecycle. */
  public enum State {
    /** Available in the idle queue. */
    IDLE,
    /** Handed out to a caller. */
    BORROWED,
    /** Temporarily removed from the idle queue while the evictor validates it. */
    TESTING,
    /** Destroyed, or about to be. */
    INVALID
  }

  private final    T          object;
  private final    long       id;
  private final    long       creationTime;
  private final    AtomicLong borrowCount = new AtomicLong();
  private volatile long       lastBorrowedTime;
  private volatile long       lastReturnedTime;
  // Monotonic clock for every duration the pool acts on, so wall-clock steps (NTP) cannot
  // make objects look abandoned or expired. The epoch-millis fields above are informational.
  private volatile long       borrowedSinceNanos;
  private volatile long       idleSinceNanos;
  private volatile State      state       = State.IDLE;
  private volatile boolean    broken;

  PooledObject(T object, long id) {
    this.object           = Objects.requireNonNull(object, "Pool object cannot be null");
    this.id               = id;
    this.creationTime     = System.currentTimeMillis();
    this.lastReturnedTime = creationTime;
    this.idleSinceNanos   = System.nanoTime();
  }

  /** @return the wrapped object */
  public T object() {
    return object;
  }

  /** @return a pool-local sequence number, assigned at creation, useful in logs */
  public long id() {
    return id;
  }

  /** @return creation time in epoch milliseconds */
  public long creationTime() {
    return creationTime;
  }

  /** @return the last time this object was borrowed, in epoch milliseconds, or 0 if never */
  public long lastBorrowedTime() {
    return lastBorrowedTime;
  }

  /** @return the last time this object became idle, in epoch milliseconds */
  public long lastReturnedTime() {
    return lastReturnedTime;
  }

  /** @return how many times this object has been borrowed */
  public long borrowCount() {
    return borrowCount.get();
  }

  /** @return milliseconds since this object last became idle (monotonic clock); meaningful only while idle */
  public long idleTime() {
    return (System.nanoTime() - idleSinceNanos) / 1_000_000L;
  }

  /** @return milliseconds since this object was last borrowed (monotonic clock); meaningful only while borrowed */
  public long borrowedTime() {
    return (System.nanoTime() - borrowedSinceNanos) / 1_000_000L;
  }

  long idleNanos() {
    return System.nanoTime() - idleSinceNanos;
  }

  long borrowedNanos() {
    return System.nanoTime() - borrowedSinceNanos;
  }

  /** @return the current lifecycle state */
  public State state() {
    return state;
  }

  /** @return whether a caller flagged this object as broken on return */
  public boolean isBroken() {
    return broken;
  }

  void markBorrowed() {
    state              = State.BORROWED;
    borrowedSinceNanos = System.nanoTime();
    lastBorrowedTime   = System.currentTimeMillis();
    borrowCount.incrementAndGet();
  }

  void markIdle() {
    state            = State.IDLE;
    idleSinceNanos   = System.nanoTime();
    lastReturnedTime = System.currentTimeMillis();
  }

  void markTesting() {
    state = State.TESTING;
  }

  /** Back to idle after an eviction test, without touching the idle timestamp. */
  void markIdleAfterTest() {
    state = State.IDLE;
  }

  void markInvalid() {
    state = State.INVALID;
  }

  void markBroken() {
    broken = true;
  }

  @Override
  public String toString() {
    return "PooledObject{id=" + id + ", state=" + state + ", borrows=" + borrowCount.get() + '}';
  }
}
