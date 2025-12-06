package today.bonfire.oss.sop;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A concrete implementation of PooledObjectFactory for testing purposes.
 * This factory provides real behavior instead of mocks, making tests more realistic.
 */
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
  public TestPooledObjectFactory() {
  }

  public AtomicLong getIdCounter() {
    return idCounter;
  }

  public long getCreationDelayMillis() {
    return creationDelayMillis;
  }

  public void setCreationDelayMillis(long creationDelayMillis) {
    this.creationDelayMillis = creationDelayMillis;
  }

  public long getDestroyDelayMillis() {
    return destroyDelayMillis;
  }

  public void setDestroyDelayMillis(long destroyDelayMillis) {
    this.destroyDelayMillis = destroyDelayMillis;
  }

  public long getValidationDelayMillis() {
    return validationDelayMillis;
  }

  public void setValidationDelayMillis(long validationDelayMillis) {
    this.validationDelayMillis = validationDelayMillis;
  }

  public boolean isFailCreation() {
    return failCreation;
  }

  public void setFailCreation(boolean failCreation) {
    this.failCreation = failCreation;
  }

  public boolean isFailValidationForBorrow() {
    return failValidationForBorrow;
  }

  public void setFailValidationForBorrow(boolean failValidationForBorrow) {
    this.failValidationForBorrow = failValidationForBorrow;
  }

  public boolean isFailValidation() {
    return failValidation;
  }

  public void setFailValidation(boolean failValidation) {
    this.failValidation = failValidation;
  }

  public boolean isFailDestroy() {
    return failDestroy;
  }

  public void setFailDestroy(boolean failDestroy) {
    this.failDestroy = failDestroy;
  }

  public AtomicInteger getValidationForBorrowFailCount() {
    return validationForBorrowFailCount;
  }

  public void setValidationForBorrowFailCount(AtomicInteger validationForBorrowFailCount) {
    this.validationForBorrowFailCount = validationForBorrowFailCount;
  }

  public AtomicInteger getValidationFailCount() {
    return validationFailCount;
  }

  public void setValidationFailCount(AtomicInteger validationFailCount) {
    this.validationFailCount = validationFailCount;
  }

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

  @Override
  public void activateObject(TestPoolObject obj) {
    // Do nothing
  }

  @Override
  public void passivateObject(TestPoolObject obj) {
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
