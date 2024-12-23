package today.bonfire.oss.sop;

public class TestPoolObject implements PoolObject {
  private Long    entityId;
  private boolean markDestroyed = false;

  TestPoolObject() {}

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
