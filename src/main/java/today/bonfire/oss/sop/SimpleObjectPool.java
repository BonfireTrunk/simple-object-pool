package today.bonfire.oss.sop;

import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
public class SimpleObjectPool<T extends PoolEntity> implements AutoCloseable {
  private final int                                  maxPoolSize;
  private final int                                  minPoolSize;
  private final long                                 idleEvictionTimeoutMillis;
  private final long                                 abandonedObjectTimeoutMillis;
  private final LinkedBlockingDeque<PooledEntity<T>> idleObjects     = new LinkedBlockingDeque<>();
  private final Map<Long, PooledEntity<T>>           borrowedObjects = new ConcurrentHashMap<>();
  private final ReentrantLock                        lock            = new ReentrantLock();
  private final Condition                            notEmpty        = lock.newCondition();
  private final PooledObjectFactory<T>               objectFactory;
  private final AtomicLong                           idCounter       = new AtomicLong(0);
  private final ScheduledExecutorService             scheduler       = Executors.newSingleThreadScheduledExecutor();

  /**
   * Creates a new SimpleObjectPool instance with specified configuration parameters.
   *
   * @param maxPoolSize The maximum number of objects that can exist in the pool at any time
   * @param minPoolSize The minimum number of idle objects to maintain in the pool
   * @param idleEvictionTimeout Duration in milliseconds after which idle objects exceeding minPoolSize will be evicted
   * @param abandonedObjectTimeout Duration in milliseconds after which borrowed objects are considered abandoned
   * @param objectFactory Factory to create, validate and destroy pooled objects
   * @throws IllegalArgumentException if minPoolSize is greater than maxPoolSize or less than 1
   */
  public SimpleObjectPool(int maxPoolSize, int minPoolSize, long idleEvictionTimeout, long abandonedObjectTimeout, PooledObjectFactory<T> objectFactory) {
    this.maxPoolSize                  = maxPoolSize;
    this.minPoolSize                  = minPoolSize;
    this.idleEvictionTimeoutMillis    = idleEvictionTimeout;
    this.abandonedObjectTimeoutMillis = abandonedObjectTimeout;
    this.objectFactory                = objectFactory;
    if (minPoolSize > maxPoolSize || minPoolSize < 1) {
      throw new IllegalArgumentException("minIdleObjects cannot be greater than maxPoolSize");
    }

    if (maxPoolSize != minPoolSize && idleEvictionTimeoutMillis > 0) {
      scheduler.scheduleAtFixedRate(this::evictIdleObjects, idleEvictionTimeoutMillis, idleEvictionTimeoutMillis, TimeUnit.MILLISECONDS);
    }
    scheduler.scheduleAtFixedRate(this::removeAbandonedObjects, abandonedObjectTimeoutMillis, abandonedObjectTimeoutMillis, TimeUnit.MILLISECONDS);
    scheduler.scheduleAtFixedRate(this::removeStaleObjects, abandonedObjectTimeoutMillis, abandonedObjectTimeoutMillis, TimeUnit.MILLISECONDS);
    log.info("Object pool created with maxPoolSize: {}, minPoolSize: {}", maxPoolSize, minPoolSize);
  }

  /**
   * Internal method to remove and destroy objects that are no longer valid according to the objectFactory.
   * This method is called periodically to maintain pool health.
   * Objects are validated using objectFactory.isObjectValid() and destroyed if invalid.
   */
  private void removeStaleObjects() {
    List<PooledEntity<T>> objectsToDestroy = new ArrayList<>();
    try {
      lock.lock();
      // Remove expired idle objects and collect them for destruction
      idleObjects.removeIf(pooledEntity -> {
        if (!objectFactory.isObjectValid(pooledEntity.getObject())) {
          // Collect the object for destruction
          objectsToDestroy.add(pooledEntity);
          return true; // Indicate that this object should be removed
        }
        return false;
      });

      // Destroy collected objects
      for (PooledEntity<T> pooledEntity : objectsToDestroy) {
        log.debug("Evicting stale object with id {} and destroying it.", pooledEntity.getEntityId());
        destroyObject(pooledEntity);
      }
    } catch (Exception e) {
      log.warn("Error evicting stale objects", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Internal method to evict idle objects that exceed the minPoolSize and have been idle longer than idleEvictionTimeout.
   * This method is called periodically when pool size is greater than minPoolSize.
   * Evicted objects are destroyed using the objectFactory.
   */
  private void evictIdleObjects() {
    long                  now              = System.currentTimeMillis();
    List<PooledEntity<T>> objectsToDestroy = new ArrayList<>();
    try {
      lock.lock();
      // Remove expired idle objects and collect them for destruction
      idleObjects.removeIf(pooledEntity -> {
        if (idleObjects.size() > minPoolSize &&
            (now - pooledEntity.getLastUsageTime()) > idleEvictionTimeoutMillis) {

          // Collect the object for destruction
          objectsToDestroy.add(pooledEntity);
          return true; // Indicate that this object should be removed
        }
        return false;
      });

      // Destroy collected objects
      for (PooledEntity<T> pooledEntity : objectsToDestroy) {
        log.debug("Evicting idle object with id {} and destroying it.", pooledEntity.getEntityId());
        destroyObject(pooledEntity);
      }
    } catch (Exception e) {
      log.warn("Error evicting idle objects", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Internal method to detect and remove objects that have been borrowed but not returned within abandonedObjectTimeout.
   * This helps prevent resource leaks when clients fail to return objects.
   * Abandoned objects are destroyed using the objectFactory.
   */
  private void removeAbandonedObjects() {
    long                  now             = System.currentTimeMillis();
    List<PooledEntity<T>> objectsToRemove = new ArrayList<>();
    try {
      lock.lock();
      borrowedObjects.forEach((id, pooledEntity) -> {
        if ((pooledEntity.isAbandoned(now, abandonedObjectTimeoutMillis))) {
          objectsToRemove.add(pooledEntity);
        }
      });

      for (PooledEntity<T> pooledEntity : objectsToRemove) {
        log.info("Removing abandoned object with id {} and destroying it.", pooledEntity.getEntityId());
        destroyObject(pooledEntity);
      }
    } catch (Exception e) {
      log.warn("Error removing abandoned objects", e);
    } finally {
      lock.unlock();
    }
  }

  private void destroyObject(PooledEntity<T> pooledEntity) {
    borrowedObjects.remove(pooledEntity.getEntityId());
    objectFactory.destroyObject(pooledEntity.getObject());
  }

  /**
   * Borrows an object from the pool with a specified timeout.
   * If an idle object is available, it is returned immediately.
   * If no idle object is available and pool size is less than maxPoolSize, creates a new object.
   * Otherwise, waits for the specified timeout for an object to become available.
   *
   * @param timeout Maximum duration to wait for an available object
   * @return A pooled object of type T
   * @throws InterruptedException if the thread is interrupted while waiting
   * @throws PoolResourceException if object validation fails or unable to borrow within timeout
   * @throws PoolResourceException if object validation fails or unable to borrow within timeout
   */
  public T borrowObject(Duration timeout) throws InterruptedException, PoolResourceException {
    PooledEntity<T> pooledEntity = null;
    boolean acquired = false;
    try {
      acquired = lock.tryLock(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!acquired) {
        throw new PoolResourceException("Timeout waiting to acquire lock");
      }
      
      long remainingNanos = timeout.toNanos();
      long startTime = System.nanoTime();
      
      while (idleObjects.isEmpty() && borrowedObjects.size() >= maxPoolSize) {
        remainingNanos = remainingNanos - (System.nanoTime() - startTime);
        if (remainingNanos <= 0) {
          throw new PoolResourceException("Timeout waiting for available object");
        }
        remainingNanos = notEmpty.awaitNanos(remainingNanos);
      }
      
      pooledEntity = idleObjects.pollFirst();
      if (pooledEntity == null) {
        if (borrowedObjects.size() < maxPoolSize) {
          pooledEntity = new PooledEntity<>(objectFactory.createObject(), idCounter.incrementAndGet());
          log.debug("Created new resource in pool with id {}", pooledEntity.getEntityId());
        } else {
          throw new PoolResourceException("Max pool size reached");
        }
      }
      
      if (!objectFactory.isObjectValidForBorrow(pooledEntity.getObject())) {
        destroyObject(pooledEntity);
        throw new PoolResourceException("Resource validation failed");
      }
      
      pooledEntity.borrow();
      borrowedObjects.put(pooledEntity.getEntityId(), pooledEntity);
      log.debug("Resource borrowed - id: {}, current pool size: {}", pooledEntity.getEntityId(), idleObjects.size() + borrowedObjects.size());
      return pooledEntity.getObject();
      
    } finally {
      if (acquired) {
        lock.unlock();
      }
    }
  }

  /**
   * Returns a borrowed object back to the pool.
   * If the object is marked as broken, it will be destroyed instead of being returned to the pool.
   *
   * @param obj The object to return to the pool
   * @param broken Flag indicating if the object is in a broken state
   */
  public void returnObject(T obj, boolean broken) {
    var pooledEntity = borrowedObjects.get(obj.getEntityId());
    if (pooledEntity != null) {
      lock.lock();
      try {
        if (broken) pooledEntity.setBroken(true);
        if (pooledEntity.isBroken()) {
          destroyObject(pooledEntity);
          log.info("Returned broken entity with id {} and destroyed it.", pooledEntity.getEntityId());
        } else {
          pooledEntity.markIdle();
          borrowedObjects.remove(pooledEntity.getEntityId());
          idleObjects.addFirst(pooledEntity);
          log.debug("Object returned - id: {}, current pool size: {}", pooledEntity.getEntityId(), idleObjects.size() + borrowedObjects.size());
        }
        notEmpty.signal();
      } finally {
        lock.unlock();
      }
    } else {
      log.error("Attempted returning object that is not in borrowed objects list. EntityId: {}", obj.getEntityId());
      objectFactory.destroyObject(obj);
    }
  }

  /**
   * Returns a borrowed object back to the pool.
   *
   * @param obj The object to return to the pool
   */
  public void returnObject(T obj) {
    returnObject(obj, false);
  }

  @Override
  public void close() {
    log.info("Closing object pool");
    scheduler.shutdown();
    try {
      if (!scheduler.awaitTermination(abandonedObjectTimeoutMillis, TimeUnit.MILLISECONDS)) {
        log.warn("Scheduler did not terminate gracefully. Shutting down forcefully.");
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      log.warn("Interrupted while waiting for scheduler to terminate.", e);
      Thread.currentThread().interrupt();
    }

    lock.lock();
    try {
      // Destroy all objects in the borrowed and idle lists
      borrowedObjects.values().forEach(this::destroyObject);
      idleObjects.forEach(this::destroyObject);
      
      // Clear collections
      borrowedObjects.clear();
      idleObjects.clear();
    } finally {
      lock.unlock();
    }
  }
}
