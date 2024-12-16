package today.bonfire.oss.sop;

public class TestPoolEntity implements PoolEntity {
  private Long    entityId;
  private boolean markDestroyed = false;

  TestPoolEntity() {}

  @Override
  public Long getEntityId() {
    return entityId;
  }

  @Override
  public void setEntityId(Long id) {
    this.entityId = id;
  }

  public void destroy() {
    markDestroyed = true;
  }

  public boolean isValid() {
    return !markDestroyed;
  }
}
