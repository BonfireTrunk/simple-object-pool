package today.bonfire.oss.sop;

/**
 * Creates, validates and destroys the objects managed by a {@link SimpleObjectPool}.
 * <p>
 * Only {@link #createObject()} and {@link #destroyObject(Object)} are required. The other
 * methods have no-op or always-valid defaults so a minimal factory is two methods.
 * <p>
 * The pool never holds a lock that borrowers or returners contend while calling any method on
 * this interface, so implementations may perform blocking I/O. Each method is called from at most
 * one thread at a time for a given object. Blocking calls must have their own timeouts: a
 * {@code createObject} that never returns holds a capacity slot forever.
 *
 * @param <T> the type of object to be pooled
 */
public interface PooledObjectFactory<T> {

  /**
   * Creates a new object. Called with no pool lock held. Any exception thrown propagates
   * unchanged to the caller of {@link SimpleObjectPool#borrowObject()} once creation
   * retries are exhausted.
   *
   * @return a new object, never null
   */
  T createObject();

  /**
   * Releases any resources held by the object. Called exactly once per object, from the
   * thread that removed it from the pool. Exceptions are logged and swallowed.
   *
   * @param obj the object to destroy
   */
  void destroyObject(T obj);

  /**
   * Prepares an object that is about to be handed to a borrower. Called on every borrow of an
   * existing idle object, and before each validation during an idle test. An exception marks
   * the object invalid and it is destroyed.
   *
   * @param obj the object to activate
   */
  default void activateObject(T obj) {
  }

  /**
   * Resets an object that is being returned to the idle queue, and after each validation
   * during an idle test. An exception marks the object invalid and it is destroyed.
   *
   * @param obj the object to passivate
   */
  default void passivateObject(T obj) {
  }

  /**
   * A cheap check run on borrow when {@link SimpleObjectPoolConfig#testOnBorrow()} is on.
   * Returning false destroys the object and the borrower tries the next one.
   * Keep this fast; put expensive checks in {@link #isObjectValid(Object)}.
   *
   * @param obj the object to check
   * @return true if the object may be borrowed
   */
  default boolean isObjectValidForBorrow(T obj) {
    return true;
  }

  /**
   * A thorough check run on create ({@code testOnCreate}), on return ({@code testOnReturn})
   * and during idle tests ({@code testWhileIdle}). Returning false destroys the object.
   *
   * @param obj the object to check
   * @return true if the object is still usable
   */
  default boolean isObjectValid(T obj) {
    return true;
  }

  /**
   * A per-object eviction hook consulted for every idle object examined by an eviction run,
   * before the idle-timeout rule and before validation. Returning true evicts the object
   * regardless of idle time or {@code minPoolSize}. Use it to retire objects on external
   * signals, for example a server asking clients to reconnect.
   *
   * @param obj  the idle object
   * @param info the pool's bookkeeping for that object
   * @return true to evict and destroy the object
   */
  default boolean shouldEvict(T obj, PooledObject<T> info) {
    return false;
  }
}
