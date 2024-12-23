package today.bonfire.oss.sop;

import java.util.Objects;

/**
 * Represents a wrapper for pooled objects that maintains state information about the object's usage.
 * This class tracks whether an object is borrowed, broken, or idle, and manages its usage timestamps.
 *
 * @param <T> The type of the pooled object, must implement PoolObject for proper identification
 */
public class PooledObject<T extends PoolObject> {
  private final    T       object;
  private final    long creationTime;
  private volatile long lastBorrowedTime;
  private volatile long idleFromTime;
  private volatile boolean broken;
  private volatile boolean borrowed;
  private          long timesBorrowed;

  /**
   * Creates a new PooledEntity wrapping the given object with a specified ID.
   * Initializes the object in an idle, non-broken state with current timestamp.
   *
   * @param object The object to be pooled
   * @param id     The unique identifier to assign to this pooled entity
   */
  PooledObject(T object, Long id) {
    Objects.requireNonNull(object, "Pool object cannot be null");
    this.object = object;
    object.setEntityId(id);
    this.creationTime = System.currentTimeMillis();
    this.borrowed     = false;
    this.broken       = false;  // Initialize broken state
  }

  /**
   * Returns the number of times this object has been borrowed from the pool.
   *
   * @return The number of times this object has been borrowed.
   */
  public long timesBorrowed() {
    return timesBorrowed;
  }

  /**
   * Returns the timestamp when this object was created.
   *
   * @return The creation timestamp in milliseconds.
   */
  public long creationTime() {
    return creationTime;
  }

  /**
   * Returns the timestamp when this object was last borrowed from the pool.
   *
   * @return The last borrowed timestamp in milliseconds.
   */
  public long lastBorrowedTime() {
    return lastBorrowedTime;
  }

  /**
   * Checks if this object is marked as broken.
   *
   * @return {@code true} if the object is broken, {@code false} otherwise.
   */
  public boolean isBroken() {
    return broken;
  }

  /**
   * Sets the broken state of this object.
   *
   * @param broken {@code true} if the object should be marked as broken, {@code false} otherwise.
   */
  public void broken(boolean broken) {
    this.broken = broken;
  }

  /**
   * Returns the underlying pooled object.
   *
   * @return The pooled object.
   */
  public T object() {
    return object;
  }

  /**
   * Retrieves the entity ID of the wrapped object.
   *
   * @return The unique identifier of the wrapped object.
   */
  Long id() {
    return object.getEntityId();
  }

  /**
   * Marks this object as borrowed and updates its last borrowed timestamp.
   * This method should be called when the object is taken from the pool.
   */
  void borrow() {
    borrowed = true;
    lastBorrowedTime = System.currentTimeMillis();
    timesBorrowed++;
  }

  /**
   * Marks this object as idle and updates its idle timestamp.
   * This method should be called when the object is returned to the pool.
   */
  void markIdle() {
    borrowed     = false;
    idleFromTime = System.currentTimeMillis();
  }

  /**
   * Checks if this object has been abandoned by comparing its last borrowed time
   * against the specified abandonment timeout.
   *
   * @param abandonmentTimeoutMillis The maximum allowed duration in milliseconds since the object was last borrowed.
   * @return {@code true} if the object is considered abandoned, {@code false} otherwise.
   */
  boolean isAbandoned(long now, long abandonmentTimeoutMillis) {
    return borrowed && ((now - lastBorrowedTime) >= abandonmentTimeoutMillis);
  }

  /**
   * Returns the duration in milliseconds that this object has been idle.
   *
   * @return The duration in milliseconds that this object has been idle.
   */
  public long idlingTime() {
    return System.currentTimeMillis() - idleFromTime;
  }

  @Override
  public int hashCode() {
    return Long.hashCode(id());
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (!(o instanceof PooledObject<?> that)) return false;
    return Objects.equals(id(), that.id());
  }

  /**
   * Returns the number of times this object has been borrowed
   *
   * @return borrow count
   */
  public long borrowCount() {
    return timesBorrowed;
  }
}
