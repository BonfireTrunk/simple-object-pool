package today.bonfire.oss.sop;

public interface PoolEntity {

  /**
   * Retrieves the unique identifier of the entity.
   * Failure to maintain this will cause the pool logic to fail and
   * cause issues.
   *
   * @return the unique identifier for the life of the JVM of the entity as a Long.
   */
  Long getEntityId();

  /**
   * Sets the unique identifier of the entity.
   * END USERS SHOULD NOT SET THIS VALUE.
   * This value is set by the library only.
   *
   * @param entityIdValue the unique identifier to set for the entity.
   */
  void setEntityId(Long entityIdValue);

}
