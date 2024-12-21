package today.bonfire.oss.sop;

/**
 * Represents an entity that can be managed by a {@link SimpleObjectPool}.  Each implementing class must provide a unique identifier.
 */
public interface PoolObject {

  /**
   * Retrieves the unique identifier for this entity.
   * This ID is used internally by the object pool for tracking and management.
   * It must remain consistent for the lifetime of the object.
   *
   * @return The unique identifier for this entity.
   */
  Long getEntityId();

  /**
   * Sets the unique identifier for this entity.
   * This method should only be called by the {@link SimpleObjectPool} implementation.
   * Directly setting this value may lead to unexpected behavior.
   * <p>
   * Example:
   * <pre>{@code
   * public class MyPooledObject implements PoolEntity {
   *   private Long entityId;
   *
   *   @Override
   *   public Long getEntityId() {
   *     return entityId;
   *   }
   *
   *   @Override
   *   public void setEntityId(Long entityIdValue) {
   *     this.entityId = entityIdValue;
   *   }
   * }
   * }</pre>
   *
   * @param entityIdValue The unique identifier to set for this entity.
   */
  void setEntityId(Long entityIdValue);

}
