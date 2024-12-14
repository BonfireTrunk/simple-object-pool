package today.bonfire.oss.sop;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class PooledEntity<T extends PoolEntity> {
  private final T       object;
  private       boolean broken;
  private       long    lastUsageTime;
  private       boolean borrowed;

  PooledEntity(T object, Long id) {
    this.object = object;
    object.setEntityId(id);
    this.lastUsageTime = System.currentTimeMillis();
    this.borrowed      = false;
    this.broken        = false;  // Initialize broken state


  }

  Long getEntityId() {
    return object.getEntityId();
  }

  void borrow() {
    borrowed      = true;
    lastUsageTime = System.currentTimeMillis();
  }

  void markIdle() {
    borrowed      = false;
    lastUsageTime = System.currentTimeMillis();
  }

  boolean isAbandoned(long now, long abandonmentTimeoutMillis) {
    return borrowed && (now - lastUsageTime) >= abandonmentTimeoutMillis;
  }
}
