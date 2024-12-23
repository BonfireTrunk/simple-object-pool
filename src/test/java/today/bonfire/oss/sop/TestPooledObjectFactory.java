package today.bonfire.oss.sop;

import lombok.Getter;
import lombok.Setter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A concrete implementation of PooledObjectFactory for testing purposes.
 * This factory provides real behavior instead of mocks, making tests more realistic.
 */
@Getter @Setter
public class TestPooledObjectFactory implements PooledObjectFactory<TestPoolObject> {
  private final    AtomicLong    idCounter                    = new AtomicLong(0);
  private          long          creationDelayMillis          = 0;
  private          long          destroyDelayMillis           = 0;
  private          long          validationDelayMillis        = 0;
  private volatile boolean       failCreation                 = false;
  private volatile boolean       failValidationForBorrow      = false;
  private volatile boolean       failValidation               = false;
  private volatile boolean       failDestroy                  = false;
  private          AtomicInteger validationForBorrowFailCount = new AtomicInteger(0);
  private          AtomicInteger validationFailCount          = new AtomicInteger(0);

  /**
   * Creates a factory where objects remain valid indefinitely
   */
  public TestPooledObjectFactory() {}

  @Override
  public TestPoolObject createObject() {
    if (failCreation) {
      throw new RuntimeException("Simulated creation failure");
    }
    if (creationDelayMillis > 0) {
      try {
        Thread.sleep(creationDelayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    TestPoolObject entity = new TestPoolObject();
    idCounter.incrementAndGet();
    return entity;
  }

  @Override public void activateObject(TestPoolObject obj) {
    // Do nothing
  }

  @Override
  public boolean isObjectValidForBorrow(TestPoolObject obj) {
    if (validationDelayMillis > 0) {
      try {
        Thread.sleep(validationDelayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failValidationForBorrow) {
      validationForBorrowFailCount.incrementAndGet();
      return false;
    }
    return obj.isValid();
  }

  @Override
  public boolean isObjectValid(TestPoolObject obj) {
    if (validationDelayMillis > 0) {
      try {
        Thread.sleep(validationDelayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
    if (failValidation) {
      validationFailCount.incrementAndGet();
      return false;
    }

    return obj != null && obj.getEntityId() != null;
  }

  @Override
  public void destroyObject(TestPoolObject obj) {
    if (destroyDelayMillis > 0) {
      try {
        Thread.sleep(destroyDelayMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    if (failDestroy) {
      throw new RuntimeException("Simulated destroy failure");
    } else {
      obj.destroy();
    }
  }

  /**
   * Reset all failure simulation flags and counters
   */
  public void reset() {
    failCreation            = false;
    failValidationForBorrow = false;
    failValidation          = false;
    failDestroy             = false;
    validationForBorrowFailCount.set(0);
    validationFailCount.set(0);
  }
}
