package today.bonfire.oss.sop;

import org.slf4j.Logger;
import today.bonfire.oss.sop.exceptions.PoolException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolObjectValidationException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A generic object pool that manages the lifecycle of pooled objects.
 * It supports borrowing, returning, and eviction of objects based on configurable parameters.
 *
 * @param <T> The type of object to be pooled, must implement {@link PoolObject}
 */
public class SimpleObjectPool<T extends PoolObject> implements AutoCloseable {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(SimpleObjectPool.class);

  private final ConcurrentLinkedQueue<PooledObject<T>> idleObjects       = new ConcurrentLinkedQueue<>();
  private final Map<Long, PooledObject<T>>             borrowedObjects   = new ConcurrentHashMap<>();
  private final ScheduledExecutorService               scheduler         = Executors.newSingleThreadScheduledExecutor();
  private final PooledObjectFactory<T>                 factory;
  private final SimpleObjectPoolConfig                 config;
  private final ReentrantLock                          lock;
  private final ReentrantLock creationLock;
  private final Condition                              notEmpty;
  private final Condition                              retryCreationWait;
  private final AtomicLong                             objectCreateCount = new AtomicLong(0);
  private final AtomicInteger                          currentPoolSize   = new AtomicInteger(0);
  private final AtomicLong                             timesBorrowed     = new AtomicLong(0);

  public SimpleObjectPool(SimpleObjectPoolConfig config, PooledObjectFactory<T> factory) {
    this.config       = config;
    this.factory      = factory;
    lock              = new ReentrantLock(config.fairness());
    creationLock = new ReentrantLock();
    notEmpty          = lock.newCondition();
    retryCreationWait = lock.newCondition();

    scheduler.scheduleAtFixedRate(() -> evictionRun(false), config.durationBetweenEvictionsRuns(), config.durationBetweenEvictionsRuns(), TimeUnit.MILLISECONDS);
    scheduler.scheduleAtFixedRate(this::abandonCheckRun, config.durationBetweenAbandonCheckRuns(), config.durationBetweenAbandonCheckRuns(), TimeUnit.MILLISECONDS);
    log.info("Pool - {} created with maxPoolSize: {}, minPoolSize: {}", config.poolName(), config.maxPoolSize(), config.minPoolSize());
    if (config.minPoolSize() > 0) {
      for (int i = 0; i < config.minPoolSize(); i++) {
        idleObjects.add(createObject());
        currentPoolSize.incrementAndGet();
      }
    }
  }


  public SimpleObjectPool(PooledObjectFactory<T> factory) {
    this(SimpleObjectPoolConfig.builder().build(), factory);

  }


  /**
   * Returns the configuration used by this object pool.
   *
   * @return the configuration used by this object pool
   */
  public SimpleObjectPoolConfig config() {
    return config;
  }

  /**
   * Internal method to evict idle objects that exceed the minPoolSize and have been idle longer than idleEvictionTimeout.
   * This method is called periodically when pool size is greater than minPoolSize.
   * Evicted objects are destroyed using the objectFactory.
   */

  /**
   * Performs eviction of idle objects from the pool.
   * <p>
   * This method can be called manually to trigger an eviction run outside of the
   * scheduled eviction cycle. It examines idle objects and evicts those that:
   * <ul>
   *   <li>Have been idle longer than {@link SimpleObjectPoolConfig#objEvictionTimeout()}</li>
   *   <li>Fail validation if {@link SimpleObjectPoolConfig#testWhileIdle()} is enabled</li>
   * </ul>
   * </p>
   * <p>
   * The number of objects examined is determined by
   * {@link SimpleObjectPoolConfig#numValidationsPerEvictionRun()}.
   * </p>
   * <p>
   * After eviction, the pool will attempt to maintain the minimum idle pool size
   * by creating new objects if necessary.
   * </p>
   *
   * @throws Exception if an error occurs during eviction
   */
  public void evict() throws Exception {
    evictionRun(true); // Test all idle objects when manually invoked
  }

  /**
   * Internal method to evict idle objects that exceed the minPoolSize and have been idle longer than idleEvictionTimeout.
   * This method is called periodically when pool size is greater than minPoolSize.
   * Evicted objects are destroyed using the objectFactory.
   */
  private void evictionRun(boolean testAll) {
    if (config.evictionPolicy() == SimpleObjectPoolConfig.EvictionPolicy.NONE) {
      return;
    }

    // Capture the current size of idle objects to determine the batch size for this run.
    // This snapshot prevents processing newly returned objects in the same run if they flood in.
    int size = idleObjects.size();
    if (size < 1) {
      // Even if empty, we might need to ensure min idle, BUT standard pool behavior usually
      // does min idle check *after* potential evictions.
      // However, if it's empty, we just skip to the min-idle check at the end.
      ensureMinIdle();
      return;
    }

    // Determine how many objects to test in this run
    // If testAll is true (manual evict()), test all idle objects
    // Otherwise, limit to configured numValidationsPerEvictionRun
    int numToTest = testAll ? size : Math.min(config.numValidationsPerEvictionRun(), size);

    List<PooledObject<T>> objectsToDestroy = new ArrayList<>();

    try {
      lock.lock();
      // Select objects based on eviction policy
      List<PooledObject<T>> objectsForTest = null;
      switch (config.evictionPolicy()) {
        case RANDOM -> {
          objectsForTest = new ArrayList<>(idleObjects);
          if (objectsForTest.size() > numToTest) {
            // This sublist logic is a bit weak for "Random" but consistent with previous "test all".
            // Previous code tested *all* if policy was random (copied list).
            // Let's just take the first N for efficiency if we are limiting.
            objectsForTest = objectsForTest.subList(0, numToTest);
          }
        }
        case OLDEST_FIRST -> {
          objectsForTest = idleObjects.stream()
                                      .sorted(Comparator.comparingLong(PooledObject::creationTime))
                                      .limit(numToTest)
                                      .toList();
        }
        case LEAST_USED -> {
          objectsForTest = idleObjects.stream()
                                      .sorted(Comparator.comparingLong(PooledObject::borrowCount))
                                      .limit(numToTest)
                                      .toList();
        }
        case MOST_USED -> {
          objectsForTest = idleObjects.stream()
                                      .sorted((o1, o2) -> Long.compare(o2.borrowCount(), o1.borrowCount()))
                                      .limit(numToTest)
                                      .toList();
        }
      }

      if (objectsForTest == null) {
        // Fallback or SHOULD NOT HAPPEN.
        objectsForTest = new ArrayList<>();
      }

      for (var pooledObject : objectsForTest) {
        boolean evict = false;

        // 1. Idle Eviction Check
        // If object is idle for too long, mark for eviction.
        // We typically only evict if we have more than minIdle, OR if the object is purely "too old" regardless?
        // GenericObjectPool has "softMinEvictableIdleTime" (evict if > minIdle) and "minEvictableIdleTime" (always evict).
        // Our config just has "objEvictionTimeout". Let's assume consistent "always evict if timeout exceeded".
        if (pooledObject.idlingTime() > config.objEvictionTimeout()) {
          evict = true;
        } else if (config.testWhileIdle()) {
          // 2. Validation (Test While Idle)
          // Only test if not already evicted.
          // Standard lifecycle: activate -> validate -> passivate
          boolean active = false;
          try {
            factory.activateObject(pooledObject.object());
            active = true;
            if (!factory.isObjectValid(pooledObject.object())) {
              evict = true;
            }
          } catch (Exception e) {
            log.warn("Object failed validation/activation during eviction check: id={}, pool={}", pooledObject.id(), config.poolName(), e);
            evict = true;
          } finally {
            if (active) {
              try {
                factory.passivateObject(pooledObject.object());
              } catch (Exception e) {
                log.warn("Object failed passivation during eviction check: id={}, pool={}", pooledObject.id(), config.poolName(), e);
                evict = true;
              }
            }
          }
        }

        if (evict) {
          // Remove from the main idle queue if it's still there
          if (idleObjects.remove(pooledObject)) {
            currentPoolSize.decrementAndGet();
            notEmpty.signal();
            objectsToDestroy.add(pooledObject);
          } else {
            log.error("Possible memory leak: Object not found in idle queue when evicting: id={}, pool={}", pooledObject.id(), config.poolName());
          }
           
        }
      }

    } catch (Exception e) {
      log.error("Error during eviction run", e);
    } finally {
      lock.unlock();
    }

    // Destroy collected objects outside of lock (best practice to avoid holding lock during I/O or long ops)
    for (PooledObject<T> p : objectsToDestroy) {
      try {
        factory.destroyObject(p.object());
        log.debug("Evicted and destroyed object: id={}, idleTime={}ms", p.id(), p.idlingTime());
      } catch (Exception e) {
        log.warn("Failed to destroy object with id: {} after eviction.", p.id(), e);
      }
    }

    // Ensure min pool size
    ensureMinIdle();
  }

  private void ensureMinIdle() {
    if (config.minPoolSize() > 0 && idleObjects.size() < config.minPoolSize() && currentPoolSize.get() < config.maxPoolSize()) {
      int diff = config.minPoolSize() - idleObjects.size();
      // We should verify we don't exceed maxPoolSize while filling
      // It's possible currentPoolSize > idleObjects.size() (borrowed objects exist)
      // We can only add if currentPoolSize < maxPoolSize.
      // Safe looping:
      for (int i = 0; i < diff; i++) {
        if (currentPoolSize.get() >= config.maxPoolSize()) {
          break;
        }
        creationLock.lock();
        try {
          if (currentPoolSize.get() >= config.maxPoolSize()) {
            break;
          }
          // We are in a scheduled thread, not blocking a user request, so standard create is fine?
          // createObject increments currentPoolSize and adds to valid creation counts inside the method or caller?
          // original constructor calls 'idleObjects.add(createObject()); currentPoolSize.incrementAndGet();'
          PooledObject<T> p = createObject();
          idleObjects.add(p);
          currentPoolSize.incrementAndGet();
        } catch (Exception e) {
          log.warn("Failed to create object to maintain minPoolSize", e);
          // If creation fails, waiting a bit or aborting this run is safer than tight loop spinning.
          break;
        } finally {
          creationLock.unlock();
        }
      }
    }
  }

  /**
   * Destroys all idle objects in the pool.
   * This method removes and destroys all objects from the idle queue,
   * regardless of the minimum pool size or idle time.
   */
  public void destroyAllIdleObjects() {
    try {
      lock.lock();
      // Collect all idle objects for destruction
      var objectsToDestroy = new ArrayList<>(idleObjects);
      idleObjects.clear();
      currentPoolSize.addAndGet(Math.negateExact(objectsToDestroy.size()));

      // Destroy collected objects
      for (PooledObject<T> pooledObject : objectsToDestroy) {
        log.debug("Destroying idle object with id {}.", pooledObject.id());
        try {
          factory.destroyObject(pooledObject.object());
        } catch (Exception e) {
          log.warn("Failed to destroy object with id {} in pool - {}", pooledObject.id(), config.poolName(), e);
        }
      }
      log.info("Destroyed {} idle objects. Current pool size: {}",
               objectsToDestroy.size(), currentPoolSize());
    } catch (Exception e) {
      log.error("Error destroying all idle objects", e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Internal method to detect and remove objects that have been borrowed but not returned within abandonedObjectTimeout.
   * This helps prevent resource leaks when clients fail to return objects.
   * Abandoned objects are destroyed using the objectFactory.
   */
  private void abandonCheckRun() {
    List<PooledObject<T>> objectsToRemove = new ArrayList<>();
    var                   now             = System.currentTimeMillis();
    try {
      lock.lock();
      borrowedObjects.forEach((id, pooledObject) -> {
        if ((pooledObject.isAbandoned(now, config.abandonedTimeout()))) {
          objectsToRemove.add(pooledObject);
        }
      });

      for (PooledObject<T> pooledObject : objectsToRemove) {
        log.warn("Removing abandoned object with id {} in pool - {}. It has been borrowed for more than {} ms and destroying it.", pooledObject.id(), config.poolName(), now - pooledObject.lastBorrowedTime());
        removeAndDestroyBorrowedObjects(pooledObject);
      }
    } catch (Exception e) {
      log.warn("Error removing abandoned objects in pool - {}", config.poolName(), e);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Destroys a pooled object using the object factory.
   *
   * @param pooledObject The pooled entity to destroy
   */
  private void removeAndDestroyBorrowedObjects(PooledObject<T> pooledObject) {
    if (borrowedObjects.remove(pooledObject.id()) != null) {
      currentPoolSize.decrementAndGet();
      notEmpty.signal();
    }
    try {
      factory.destroyObject(pooledObject.object());
    } catch (Exception e) {
      log.warn("Failed to destroy object with id {} in pool - {}", pooledObject.id(), config.poolName(), e.getCause());
    }
  }

  private PooledObject<T> createObject() {
    PooledObject<T> pooledObject;
    try {
      pooledObject = new PooledObject<>(factory.createObject(), objectCreateCount.incrementAndGet());
    } catch (Exception e) {
      log.error("Failed to create object for the pool: {}", e.getMessage());
      throw e;
    }
    if (config.testOnCreate()) {
      if (!factory.isObjectValid(pooledObject.object())) {
        throw new PoolObjectValidationException("Object validation failed on create");
      }
    }
    return pooledObject;
  }

  /**
   * Borrows an object from the pool with a specified timeout.
   * If an idle object is available, it is returned immediately.
   * If no idle object is available and pool size is less than config.maxPoolSize(), creates a new object.
   * Otherwise, waits for the specified timeout for an object to become available.
   *
   * @return A pooled object of type T
   *
   * @throws PoolException if object validation fails, unable to borrow within timeout, or thread interrupted
   */
  public T borrowObject() throws PoolException {
    PooledObject<T> pooledObject   = null;
    boolean         acquired       = false;
    final long      startTime      = System.nanoTime();
    final var       waitTimeout    = config.waitingForObjectTimeout();
    long            remainingNanos = waitTimeout;
    int             retriesLeft    = config.maxRetries();

    try {
      acquired = lock.tryLock(remainingNanos, TimeUnit.NANOSECONDS);
      if (!acquired) {
        throw new PoolTimeoutException("Timeout waiting to acquire lock to borrow object");
      }
      remainingNanos = waitTimeout - (System.nanoTime() - startTime);
      boolean createdObject = false;
      do {
        // First try to get from idle objects
        pooledObject  = idleObjects.poll();
        createdObject = false;
        if (pooledObject == null) {
          // Try to create new, if pool is not full
          if (borrowedObjects.size() < config.maxPoolSize()) {
            if (retriesLeft < 0) {
              throw new PoolObjectException("Max retries reached while creating object and failing to borrow");
            }
            // Apply creation retry delay if configured only from second try
            if (retriesLeft < config.maxRetries()) {
              if (config.retryCreationDelay() > 0) {
                remainingNanos -= retryCreationWait.awaitNanos(config.retryCreationDelay());
              } else {
                // calculate remaining time
                remainingNanos = waitTimeout - (System.nanoTime() - startTime);
              }
              // happens from first retry only
              if (remainingNanos <= 0) {
                throw new PoolTimeoutException("Timeout waiting for object creation during borrow");
              }
            }

            try {
              creationLock.lock();
              try {
                if (currentPoolSize.get() < config.maxPoolSize()) {
                  pooledObject  = createObject();
                  createdObject = true;
                  currentPoolSize.incrementAndGet();
                  retriesLeft--;
                }
              } finally {
                creationLock.unlock();
              }
            } catch (Exception e) {
              if (e instanceof PoolObjectValidationException) {
                log.error("", e);
              }
              retriesLeft--;
              if (retriesLeft < 0) {
                // we have exhausted retries and throw the actual exception so that the caller can handle it
                throw e;
              }
              continue;
            }
          }

          // If still null, wait for objects to become available
          if (pooledObject == null) {
            remainingNanos = notEmpty.awaitNanos(remainingNanos);
            if (remainingNanos <= 0) {
              throw new PoolTimeoutException("Timeout waiting for available object");
            }
            continue;
          }
        }

        // Validate object before borrowing if configured
        final var object = pooledObject.object();
        if (config.testOnBorrow() && !factory.isObjectValidForBorrow(object)) {
          // Object is invalid before it has been added to borrowedObjects or
          // returned to the idle queue. We must destroy it and adjust the
          // pool size, regardless of whether it was newly created or taken
          // from idle.
          currentPoolSize.decrementAndGet();
          try {
            factory.destroyObject(object);
          } catch (Exception e) {
            log.warn("Failed to destroy invalid object during borrow validation in pool - {}", config.poolName(), e);
          }
          continue;
        }


        // Object is valid, prepare for borrowing
        pooledObject.borrow();
        factory.activateObject(object);
        borrowedObjects.put(pooledObject.id(), pooledObject);
        timesBorrowed.incrementAndGet();
        notEmpty.signal();
        log.trace("Resource borrowed - id: {}, current pool size: {}",
                  pooledObject.id(), currentPoolSize.get());
        return object;
      } while (remainingNanos > 0);

      throw new PoolTimeoutException("Timeout waiting for an available object to borrow");
    } catch (InterruptedException e) {
      throw new PoolException("Thread interrupted while attempting to borrow object", e);
    } finally {
      if (acquired) {
        lock.unlock();
      }
    }
  }

  /**
   * Returns a borrowed object back to the pool.
   * If the object is marked as broken or invalid, it will be destroyed instead of being returned to the pool.
   *
   * @param obj    The object to return to the pool
   * @param broken Flag indicating if the object is in a broken state
   * @throws PoolObjectException if object validation fails
   */
  public void returnObject(T obj, boolean broken) throws PoolObjectException {
    if (obj == null) {
      log.error("Attempted returning null object. This is an error.");
      throw new PoolException("Cannot return null object to pool");
    }

    try {
      lock.lock();
      var pooledObject = borrowedObjects.get(obj.getEntityId());
      if (pooledObject == null) {
        log.warn("Attempted returning object that is not in borrowed objects list. id: {}, pool - {}", obj.getEntityId(), config.poolName());
        return;
      }
      if (broken) {
        pooledObject.broken(true);
      }
      // First check if object is broken
      boolean isValid = !pooledObject.isBroken();

      // Then perform testOnReturn validation if configured and object isn't already invalid
      if (isValid && config.testOnReturn()) {
        try {
          isValid = factory.isObjectValid(obj);
        } catch (Exception e) {
          log.error("Error validating object on return", e);
          // exception is swallowed since object is already invalid and will be destroyed.
          isValid = false;
        }
      }

      if (!isValid) {
        log.warn("Returned broken or invalid entity with id {} to pool - {} and destroying it.", pooledObject.id(), config.poolName());
        removeAndDestroyBorrowedObjects(pooledObject);
      } else {

        factory.passivateObject(obj);
        borrowedObjects.remove(pooledObject.id());
        pooledObject.markIdle();
        idleObjects.add(pooledObject);
        log.trace("Object returned - id: {}, current pool size: {}",
                  pooledObject.id(), currentPoolSize.get());
        notEmpty.signal();
      }
    } catch (Exception e) {
      throw new PoolObjectException("Unable to properly return object back to pool", e);
    } finally {
      lock.unlock();
    }

  }

  /**
   * Returns a borrowed object back to the pool.
   *
   * @param obj The object to return to the pool
   * @throws PoolObjectException if object validation fails
   */
  public void returnObject(T obj) throws PoolObjectException {
    returnObject(obj, false);
  }

  /**
   * Closes the object pool and releases all resources.
   * This method shuts down the scheduler and destroys all pooled objects.
   * object returned after the pool is closed will think they are not part of
   * the pool and will get destroyed anyway.
   */
  @Override
  public void close() {
    if (!scheduler.isShutdown()) {
      log.info("Closing object pool - {}. Current pool size: {}", config.poolName(), currentPoolSize.get());
      scheduler.shutdown();
    } else {
      log.warn("Trying to close an Object pool - {} that is already closed", config.poolName());
      return;
    }

    try {
      if (!scheduler.awaitTermination(config.waitingForObjectTimeout(), TimeUnit.NANOSECONDS)) {
        log.warn("Scheduler did not terminate gracefully. Shutting down forcefully.");
        scheduler.shutdownNow();
      }
    } catch (InterruptedException e) {
      log.warn("Interrupted while waiting for scheduler to terminate.", e);
      Thread.currentThread().interrupt();
    }
    try {
      lock.lock();
      // Destroy all objects in the borrowed and idle lists
      borrowedObjects.values().forEach(this::removeAndDestroyBorrowedObjects);
      idleObjects.forEach(this::removeAndDestroyBorrowedObjects);
      currentPoolSize.addAndGet(Math.negateExact(idleObjects.size()));
      // Clear collections
      borrowedObjects.clear();
      idleObjects.clear();
      log.info("Closed object pool - {}", config.poolName());
    } finally {
      lock.unlock();
    }
  }


  protected PooledObjectFactory<T> getFactory() {
    return factory;
  }

  /**
   * Checks if the object pool is closed by checking if the scheduler is shut down which
   * would mean the pool is closed.
   */
  public boolean isClosed() {
    return scheduler.isShutdown();
  }

  /**
   * Returns the number of objects currently borrowed from the pool.
   *
   * @return the number of borrowed objects
   */
  public int borrowedObjectsCount() {
    return borrowedObjects.size();
  }

  /**
   * Returns the current total number of objects in the pool
   *
   * @return the current total number of objects in the pool
   */
  public int currentPoolSize() {
    return currentPoolSize.get();
  }

  /**
   * Returns the number of idle objects currently available in the pool.
   *
   * @return the number of idle objects
   */
  public int idleObjectCount() {
    return idleObjects.size();
  }

  /**
   * Returns the total number of objects created by this pool since its creation.
   *
   * @return the total number of objects created
   */
  public long numOfObjectsCreated() {
    return objectCreateCount.get();
  }

  /**
   * Returns the number of threads currently waiting to acquire the lock.
   * This information may be used for debugging or monitoring purposes.
   *
   * @return the number of waiting threads (approximate only)
   */
  public int waitingCount() {
    return lock.getQueueLength();
  }


  /**
   * Returns the total number of times objects have been borrowed from this pool
   * since its creation.
   *
   * @return the total number of times objects have been borrowed
   */
  public long numOfTimesBorrowedFromPool() {
    return timesBorrowed.get();
  }


  /**
   * Returns the number of times a specific object has been borrowed from the pool
   * since its creation.
   * <p>
   * Note that this method will only return statistics for objects that are currently
   * borrowed from the pool. For objects that are idle this will have to iterate and will have a delay.
   * If you need to check, check only for borrowed objects.
   *
   * @param objectId the id of the object to query, may be null
   * @return the number of times the object has been borrowed
   */
  public long numOfTimesBorrowed(Long objectId) {
    if (objectId == null) return 0;
    var pooledObject = borrowedObjects.get(objectId);
    if (pooledObject == null) {
      log.warn("Object with id {} not found in borrowed objects", objectId);
      pooledObject = idleObjects.stream()
                                .filter(pooledObject1 -> pooledObject1.id().equals(objectId))
                                .findAny().orElse(null);
    }
    if (pooledObject == null) {
      log.warn("Object with id {} not found in idle objects", objectId);
      return 0;
    }
    return pooledObject.timesBorrowed();
  }
}
