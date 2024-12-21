package today.bonfire.oss.sop;

/**
 * Provides a factory for creating pooled objects.
 * Implementations should provide a concrete type of T that implements the
 * {@link PoolObject} interface.
 *
 * @param <T> the type of object to be pooled, must implement {@link PoolObject}
 */
public interface PooledObjectFactory<T extends PoolObject> {

  /**
   * Creates an instance of the pooled object.
   * This method is responsible for instantiating and initializing
   * a new object of type T that implements the {@link PoolObject} interface.
   * Implementations should handle any exceptions gracefully.
   *
   * @return a newly created object of type T, never null.
   */
  T createObject();


  /**
   * Activates the pooled object.
   * This method is called when the object is taken from the pool,
   * and is responsible for any necessary activation or initialization setting before borrow.
   *
   * @param obj the object to activate, must not be null.
   */
  void activateObject(T obj);

  /**
   * Validates the pooled object to determine if it can be safely borrowed.
   * This method should return true if the object is in a valid state for
   * reuse in the pool, and false if the object should be discarded.
   * This method should return very quickly and non-blocking,
   * you may set a much extensive/expensive check in {@link #isObjectValid(T)}
   *
   * @param obj the object to validate, must not be null.
   * @return {@code true} if the object is valid and can be reused,
   * {@code false} otherwise.
   */
  boolean isObjectValidForBorrow(T obj);

  /**
   * Checks if the pooled object is still valid.
   * This method should return true if the object is in a valid state,
   * and false if the object should be discarded.
   *
   * @param obj the object to check, must not be null.
   * @return {@code true} if the object is valid,
   * {@code false} otherwise.
   */
  boolean isObjectValid(T obj);


  /**
   * Destroys the pooled object, performing any necessary cleanup operations.
   * This method is called when the object is no longer needed and should
   * release any resources held by the object, ensuring that it can be
   * garbage collected. Implementations should handle any exceptions gracefully.
   *
   * @param obj the object to destroy, must not be null.
   */
  void destroyObject(T obj);

}
