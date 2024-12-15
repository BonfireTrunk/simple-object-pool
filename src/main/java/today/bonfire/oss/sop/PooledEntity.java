package today.bonfire.oss.sop;

import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Represents a wrapper for pooled objects that maintains state information about the object's usage.
 * This class tracks whether an object is borrowed, broken, or idle, and manages its usage timestamps.
 *
 * @param <T> The type of the pooled object, must implement PoolEntity
 */
@Data
@Accessors(chain = true)
public class PooledEntity<T extends PoolEntity> {
  private final    T       object;
  private volatile boolean broken;
  private volatile long    lastUsageTime;
  private volatile boolean borrowed;

  /**
   * Creates a new PooledEntity wrapping the given object with a specified ID.
   * Initializes the object in an idle, non-broken state with current timestamp.
   *
   * @param object The object to be pooled
   * @param id     The unique identifier to assign to this pooled entity
   */
  PooledEntity(T object, Long id) {
    this.object = object;
    object.setEntityId(id);
    this.lastUsageTime = System.currentTimeMillis();
    this.borrowed      = false;
    this.broken        = false;  // Initialize broken state
  }

  /**
   * Retrieves the entity ID of the wrapped object.
   *
   * @return The unique identifier of the wrapped object
   */
  Long getEntityId() {
    return object.getEntityId();
  }

  /**
   * Marks this entity as borrowed and updates its last usage timestamp.
   * This method is called when the object is taken from the pool.
   */
  void borrow() {
    borrowed      = true;
    lastUsageTime = System.currentTimeMillis();
  }

  /**
   * Marks this entity as idle and updates its last usage timestamp.
   * This method is called when the object is returned to the pool.
   */
  void markIdle() {
    borrowed      = false;
    lastUsageTime = System.currentTimeMillis();
  }

  /**
   * Checks if this entity has been abandoned by comparing its last usage time
   * against the specified abandonment timeout.
   *
   * @param now                      Current time in milliseconds
   * @param abandonmentTimeoutMillis Maximum allowed duration in milliseconds between usages
   * @return true if the entity is borrowed and hasn't been used for longer than the timeout
   */
  boolean isAbandoned(long now, long abandonmentTimeoutMillis) {
    return borrowed && (now - lastUsageTime) >= abandonmentTimeoutMillis;
  }
}
