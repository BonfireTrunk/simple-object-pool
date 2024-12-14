package today.bonfire.oss.sop;

public interface PooledObjectFactory<T extends PoolEntity> {

  /**
   * Creates an instance of the pooled object.
   * This method is responsible for instantiating and initializing
   * a new object of type T that implements the {@link PoolEntity} interface.
   *
   * @return a newly created object of type T, never null.
   */
  T createObject();

  /**
   * Validates the pooled object to determine if it can be safely reused.
   * This method should return true if the object is in a valid state for
   * reuse in the pool, and false if the object should be discarded.
   *
   * @param obj the object to validate, must not be null.
   * @return {@code true} if the object is valid and can be reused,
   * {@code false} otherwise.
   */
  boolean isObjectConnected(T obj);

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
   * garbage collected.
   *
   * @param obj the object to destroy, must not be null.
   */
  void destroyObject(T obj);

}
