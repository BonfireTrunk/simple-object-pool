package today.bonfire.oss.sop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import today.bonfire.oss.sop.exceptions.PoolClosedException;
import today.bonfire.oss.sop.exceptions.PoolException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolObjectValidationException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A bounded, thread-safe object pool.
 * <p>
 * Design notes:
 * <ul>
 *   <li>Capacity is a counter reserved with a compare-and-set before an object is created, so
 *       creations run in parallel up to {@code maxPoolSize}.</li>
 *   <li>Idle objects live in a lock-free deque. LIFO by default, so a burst leaves cold objects
 *       at the tail where the evictor finds them.</li>
 *   <li>Borrowed objects are tracked by identity. The pooled type needs no interface and the pool
 *       never calls {@code equals} or {@code hashCode} on it.</li>
 *   <li>No factory method is ever called while a lock that borrowers or returners contend is
 *       held. Eviction runs are serialised among themselves only. An object being validated by
 *       the evictor is removed from the idle deque first, so no borrower can see it.</li>
 *   <li>Borrowers that find no idle object and no free capacity park on a condition. Returns,
 *       destroys and {@link #close()} signal it.</li>
 *   <li>Maintenance (eviction, min-idle fill, abandon detection) is ticked by one shared daemon
 *       thread and runs on a virtual thread per pool, so a slow factory in one pool never delays
 *       another.</li>
 *   <li>Every factory call is guarded against {@link Throwable}: the slot or object is cleaned up
 *       first, then {@link VirtualMachineError}s are rethrown and everything else is treated as
 *       a failure of that object.</li>
 * </ul>
 * The constructor does not create objects. Call {@link #prepare()} or {@link #addObjects(int)}
 * to prefill, otherwise the first maintenance run fills to {@code minPoolSize}.
 * <p>
 * {@code fairness(true)} serves parked waiters in arrival order through a fair lock. A newcomer
 * that arrives while others are parked goes behind them, but one that wins the race for a freed
 * capacity slot can still create an object first. Ordering is best effort, never a guarantee.
 *
 * @param <T> the type of object to be pooled
 */
public class SimpleObjectPool<T> implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(SimpleObjectPool.class);

  /** One daemon thread ticks every pool's maintenance. The work itself runs on virtual threads. */
  private static final ScheduledExecutorService SCHEDULER;

  static {
    ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, r -> {
      Thread t = new Thread(r, "sop-maintenance");
      t.setDaemon(true);
      return t;
    });
    executor.setRemoveOnCancelPolicy(true);
    SCHEDULER = executor;
  }

  private final SimpleObjectPoolConfig config;
  private final PooledObjectFactory<T> factory;
  private final String                 name;
  private final int                    maxPoolSize;
  private final boolean                lifo;
  private final long                   evictionTimeoutNanos;

  private final ConcurrentLinkedDeque<PooledObject<T>>          idle     = new ConcurrentLinkedDeque<>();
  private final ConcurrentHashMap<IdentityKey, PooledObject<T>> borrowed = new ConcurrentHashMap<>();

  /**
   * Objects the pool destroyed while they were still borrowed (abandon check, forceClose). Their
   * holders may still return them; those returns are silently ignored. Entries are weak identity
   * references so an object that is never returned does not leak.
   */
  private final Set<WeakIdentityKey>   reclaimed      = ConcurrentHashMap.newKeySet();
  private final ReferenceQueue<Object> reclaimedQueue = new ReferenceQueue<>();

  /** Objects alive or being created. Never exceeds maxPoolSize. */
  private final AtomicInteger total = new AtomicInteger();

  private final ReentrantLock waitLock;
  private final Condition     available;
  private final AtomicInteger waiters = new AtomicInteger();

  private final ReentrantLock evictionLock        = new ReentrantLock();
  private final AtomicBoolean maintenanceRunning  = new AtomicBoolean();
  private final AtomicBoolean abandonCheckRunning = new AtomicBoolean();
  /** Bumped by destroyAllIdleObjects so an object under idle test at that moment is destroyed instead of restored. */
  private final AtomicLong    clearEpoch          = new AtomicLong();

  private final AtomicLong idSequence     = new AtomicLong();
  private final AtomicLong destroyedCount = new AtomicLong();
  private final AtomicLong borrowCount    = new AtomicLong();

  private volatile boolean            closed;
  private final    ScheduledFuture<?> maintenanceTask;
  private final    ScheduledFuture<?> abandonTask;

  public SimpleObjectPool(SimpleObjectPoolConfig config, PooledObjectFactory<T> factory) {
    this.config               = Objects.requireNonNull(config, "config");
    this.factory              = Objects.requireNonNull(factory, "factory");
    this.name                 = config.poolName();
    this.maxPoolSize          = config.maxPoolSize();
    this.lifo                 = config.lifo();
    this.evictionTimeoutNanos = Math.max(0, saturatedNanos(config.objEvictionTimeout()));
    this.waitLock             = new ReentrantLock(config.fairness());
    this.available            = waitLock.newCondition();

    boolean needsMaintenance = config.evictionPolicy() != SimpleObjectPoolConfig.EvictionPolicy.NONE || config.minPoolSize() > 0;
    maintenanceTask = needsMaintenance
                      ? schedule(this, config.durationBetweenEvictionsRuns(), "maintenance", pool -> pool.runOnVirtualThread("maintenance", pool.maintenanceRunning, pool::scheduledMaintenance))
                      : null;
    abandonTask = config.abandonCheckEnabled()
                  ? schedule(this, config.durationBetweenAbandonCheckRuns(), "abandon-check", pool -> pool.runOnVirtualThread("abandon-check", pool.abandonCheckRunning, pool::abandonCheckRun))
                  : null;
    log.info("Pool {} created: maxPoolSize={}, minPoolSize={}, lifo={}, abandonCheck={}", name, maxPoolSize, config.minPoolSize(), lifo, config.abandonCheckEnabled());
  }

  public SimpleObjectPool(PooledObjectFactory<T> factory) {
    this(SimpleObjectPoolConfig.defaultConfig(), factory);
  }

  /**
   * Schedules a periodic task that holds the pool only weakly, so a pool that is dropped without
   * being closed can still be garbage collected; its task then cancels itself on the next tick.
   */
  private static <T> ScheduledFuture<?> schedule(SimpleObjectPool<T> pool, Duration period, String task, Consumer<SimpleObjectPool<T>> body) {
    long nanos = Math.max(1, saturatedNanos(period));
    var  ref   = new WeakReference<>(pool);
    var  self  = new AtomicReference<ScheduledFuture<?>>();
    ScheduledFuture<?> future = SCHEDULER.scheduleWithFixedDelay(() -> {
      SimpleObjectPool<T> p = ref.get();
      if (p == null || p.closed) {
        ScheduledFuture<?> f = self.get();
        if (f != null) f.cancel(false);
        return;
      }
      try {
        body.accept(p);
      } catch (Throwable t) {
        log.error("Pool {}: failed to start {} run", p.name, task, t);
      }
    }, nanos, nanos, TimeUnit.NANOSECONDS);
    self.set(future);
    return future;
  }

  // ---------------------------------------------------------------------------------------------
  // Borrow
  // ---------------------------------------------------------------------------------------------

  /**
   * Borrows an object, waiting up to {@link SimpleObjectPoolConfig#waitingForObjectTimeout()}.
   *
   * @return a pooled object, never null
   *
   * @throws PoolTimeoutException if no object became available in time; if creation failed during
   *                              the wait, the last creation exception is the cause
   * @throws PoolClosedException  if the pool is closed, or is closed while waiting
   * @throws PoolException        if the thread is interrupted; the interrupt flag is restored
   * @throws RuntimeException     whatever the factory threw from {@code createObject()} once retries are exhausted
   */
  public T borrowObject() throws PoolException {
    return borrowObject(config.waitingForObjectTimeout());
  }

  /**
   * Borrows an object with an explicit wait. A negative duration waits indefinitely and
   * {@link Duration#ZERO} fails immediately when the pool is exhausted.
   *
   * @param maxWait how long to wait for capacity or an idle object
   * @return a pooled object, never null
   * @see #borrowObject()
   */
  public T borrowObject(Duration maxWait) throws PoolException {
    Objects.requireNonNull(maxWait, "maxWait");
    final long    waitNanos = saturatedNanos(maxWait);
    final boolean infinite  = waitNanos < 0 || waitNanos == Long.MAX_VALUE;
    final long    deadline  = infinite ? 0 : System.nanoTime() + waitNanos;

    int              createAttemptsLeft = config.maxRetries() + 1;
    RuntimeException lastCreateFailure  = null;
    boolean          retryPending       = false;

    try {
      while (true) {
        if (closed) {
          throw new PoolClosedException("Pool " + name + " is closed");
        }

        PooledObject<T> p     = null;
        boolean         fresh = false;

        // Fast path: skip the lock entirely when nobody is queued. With fairness on and
        // waiters present, newcomers go through the (fair) lock behind them.
        if (!config.fairness() || waiters.get() == 0) {
          p = idle.pollFirst();
        }

        if (p == null) {
          if (retryPending) {
            // Directly after a failed creation: honour the retry delay, capped at the remaining
            // wait, without holding a slot while we sleep.
            retryPending = false;
            long delay = saturatedNanos(config.retryCreationDelay());
            if (delay > 0) {
              long remaining = infinite ? delay : deadline - System.nanoTime();
              if (remaining <= 0) {
                throw new PoolTimeoutException("Timed out in pool " + name + " before retrying object creation", lastCreateFailure);
              }
              TimeUnit.NANOSECONDS.sleep(Math.min(delay, remaining));
            }
          }
          if (reserveSlot()) {
            boolean created = false;
            try {
              p       = create();
              created = true;
              fresh   = true;
            } catch (RuntimeException e) {
              lastCreateFailure = e;
              createAttemptsLeft--;
              if (createAttemptsLeft <= 0) {
                throw e;
              }
              retryPending = true;
              log.warn("Pool {}: object creation failed, {} attempt(s) left: {}", name, createAttemptsLeft, e.toString());
            } finally {
              if (!created) {
                releaseSlot(); // also covers Errors escaping the factory
              }
            }
            if (p == null) {
              continue;
            }
          } else {
            p = awaitAvailable(infinite, deadline, lastCreateFailure);
            if (p == null) {
              continue; // capacity may have freed up, go round and try to reserve it
            }
          }
        }

        T object = p.object();
        if (!fresh && !prepareForBorrow(p)) {
          continue;
        }

        p.markBorrowed();
        borrowed.put(new IdentityKey(object), p);
        borrowCount.incrementAndGet();
        if (closed) {
          // Lost the race with close(): behave as if we had returned it to a closed pool.
          if (borrowed.remove(new IdentityKey(object)) != null) {
            destroy(p, "pool closed during borrow");
          }
          throw new PoolClosedException("Pool " + name + " is closed");
        }
        if (log.isTraceEnabled()) {
          log.trace("Pool {}: borrowed object {} (total={}, idle={})", name, p.id(), total.get(), idle.size());
        }
        return object;
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new PoolException("Interrupted while borrowing from pool " + name, e);
    }
  }

  /**
   * Validates (if configured) and activates an idle object the caller now owns. On any failure
   * the object is destroyed and false is returned; fatal errors are rethrown after cleanup.
   */
  private boolean prepareForBorrow(PooledObject<T> p) {
    T object = p.object();
    try {
      if (config.testOnBorrow() && !factory.isObjectValidForBorrow(object)) {
        destroy(p, "failed validation on borrow");
        return false;
      }
      factory.activateObject(object);
      return true;
    } catch (Throwable t) {
      destroy(p, "validation or activation on borrow threw");
      rethrowIfFatal(t);
      log.warn("Pool {}: validation or activation of object {} threw, destroyed it", name, p.id(), t);
      return false;
    }
  }

  /**
   * Parks until an idle object is pushed, capacity is released or the pool is closed.
   *
   * @return an idle object, or null if capacity may now be available and the caller should retry
   */
  private PooledObject<T> awaitAvailable(boolean infinite, long deadline, RuntimeException lastCreateFailure) throws InterruptedException {
    waitLock.lockInterruptibly();
    waiters.incrementAndGet();
    boolean consumed = false;
    try {
      while (true) {
        if (closed) {
          throw new PoolClosedException("Pool " + name + " closed while waiting for an object");
        }
        PooledObject<T> p = idle.pollFirst();
        if (p != null) {
          consumed = true;
          return p;
        }
        if (total.get() < maxPoolSize) {
          consumed = true;
          return null;
        }
        if (infinite) {
          available.await();
        } else {
          long remaining = deadline - System.nanoTime();
          if (remaining <= 0) {
            throw new PoolTimeoutException("Timed out waiting for an object from pool " + name + " (idle=0, total=" + total.get() + "/" + maxPoolSize + ")", lastCreateFailure);
          }
          available.awaitNanos(remaining);
        }
      }
    } finally {
      waiters.decrementAndGet();
      if (!consumed) {
        // Leaving on timeout, interrupt or close without taking anything: a signal meant for us
        // must not be lost, so pass it to the next waiter.
        available.signal();
      }
      waitLock.unlock();
    }
  }

  private void signalOne() {
    if (waiters.get() == 0) return;
    waitLock.lock();
    try {
      available.signal();
    } finally {
      waitLock.unlock();
    }
  }

  private void signalAll() {
    waitLock.lock();
    try {
      available.signalAll();
    } finally {
      waitLock.unlock();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Return
  // ---------------------------------------------------------------------------------------------

  /**
   * Returns a borrowed object. Broken or invalid objects are destroyed instead of being reused.
   * Returning to a closed pool destroys the object. Returning an object the pool already
   * reclaimed itself (abandon detection or {@link #forceClose()}) is silently ignored.
   *
   * @param obj    the object to return
   * @param broken true if the caller knows the object is unusable
   *
   * @throws PoolObjectException if the object was not borrowed from this pool
   */
  public void returnObject(T obj, boolean broken) throws PoolObjectException {
    if (obj == null) {
      throw new PoolObjectException("Cannot return null to pool " + name);
    }
    PooledObject<T> p = borrowed.remove(new IdentityKey(obj));
    if (p == null) {
      if (reclaimed.remove(new WeakIdentityKey(obj, null))) {
        log.debug("Pool {}: ignoring return of an object the pool had already reclaimed and destroyed", name);
        return;
      }
      throw new PoolObjectException("Object is not currently borrowed from pool " + name);
    }
    if (broken) {
      p.markBroken();
    }

    if (closed) {
      destroy(p, "returned to a closed pool");
      return;
    }
    if (p.isBroken()) {
      destroy(p, "returned as broken");
      return;
    }
    try {
      if (config.testOnReturn() && !factory.isObjectValid(obj)) {
        destroy(p, "failed validation on return");
        return;
      }
      factory.passivateObject(obj);
    } catch (Throwable t) {
      destroy(p, "validation or passivation on return threw");
      rethrowIfFatal(t);
      log.warn("Pool {}: validation or passivation of object {} threw, destroyed it", name, p.id(), t);
      return;
    }

    p.markIdle();
    pushIdle(p);
    if (closed) {
      // close() may have drained the deque before our push; make sure nothing is left behind.
      if (idle.remove(p)) {
        destroy(p, "returned to a closed pool");
      }
      return;
    }
    if (log.isTraceEnabled()) {
      log.trace("Pool {}: returned object {} (total={}, idle={})", name, p.id(), total.get(), idle.size());
    }
    signalOne();
  }

  /**
   * Returns a borrowed object that is still usable.
   *
   * @see #returnObject(Object, boolean)
   */
  public void returnObject(T obj) throws PoolObjectException {
    returnObject(obj, false);
  }

  private void pushIdle(PooledObject<T> p) {
    if (lifo) {
      idle.addFirst(p);
    } else {
      idle.addLast(p);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Lease helpers
  // ---------------------------------------------------------------------------------------------

  /**
   * Borrows an object wrapped in an {@link AutoCloseable} lease for use in try-with-resources.
   */
  public Lease<T> lease() throws PoolException {
    return new Lease<>(this, borrowObject());
  }

  /**
   * Borrows an object, runs the action, and returns the object. If the action throws, the object
   * is returned as broken and the exception propagates.
   */
  public void use(Consumer<? super T> action) throws PoolException {
    apply(obj -> {
      action.accept(obj);
      return null;
    });
  }

  /**
   * Borrows an object, applies the function, returns the object, and yields the result. If the
   * function throws, the object is returned as broken and the function's exception propagates;
   * any exception from the return is attached to it as suppressed.
   */
  public <R> R apply(Function<? super T, ? extends R> function) throws PoolException {
    T obj = borrowObject();
    R result;
    try {
      result = function.apply(obj);
    } catch (Throwable t) {
      try {
        returnObject(obj, true);
      } catch (RuntimeException returnFailure) {
        t.addSuppressed(returnFailure);
      }
      throw t;
    }
    returnObject(obj, false);
    return result;
  }

  // ---------------------------------------------------------------------------------------------
  // Creation and destruction
  // ---------------------------------------------------------------------------------------------

  private boolean reserveSlot() {
    while (true) {
      int current = total.get();
      if (current >= maxPoolSize) {
        return false;
      }
      if (total.compareAndSet(current, current + 1)) {
        return true;
      }
    }
  }

  private void releaseSlot() {
    total.decrementAndGet();
    signalOne();
  }

  /**
   * Creates an object for a slot the caller has already reserved. Throws the factory's exception
   * unchanged. The caller must release the slot if this throws.
   */
  private PooledObject<T> create() {
    T object = factory.createObject();
    if (object == null) {
      throw new PoolObjectException("Factory for pool " + name + " returned null from createObject()");
    }
    PooledObject<T> p = new PooledObject<>(object, idSequence.incrementAndGet());
    if (config.testOnCreate()) {
      boolean valid;
      try {
        valid = factory.isObjectValid(object);
      } catch (Throwable t) {
        destroyQuietly(p);
        rethrowIfFatal(t);
        throw new PoolObjectValidationException("Validation of a newly created object threw in pool " + name, t);
      }
      if (!valid) {
        destroyQuietly(p);
        throw new PoolObjectValidationException("Newly created object failed validation in pool " + name);
      }
    }
    if (log.isTraceEnabled()) {
      log.trace("Pool {}: created object {} (total={})", name, p.id(), total.get());
    }
    return p;
  }

  /** Destroys an object that owns a slot and releases that slot, whatever the factory does. */
  private void destroy(PooledObject<T> p, String reason) {
    log.debug("Pool {}: destroying object {}: {}", name, p.id(), reason);
    try {
      destroyQuietly(p);
    } finally {
      releaseSlot();
    }
  }

  private void destroyQuietly(PooledObject<T> p) {
    p.markInvalid();
    destroyedCount.incrementAndGet();
    try {
      factory.destroyObject(p.object());
    } catch (Throwable t) {
      rethrowIfFatal(t);
      log.warn("Pool {}: factory failed to destroy object {}", name, p.id(), t);
    }
  }

  /** Rethrows errors the pool must never swallow. Everything else is treated as a factory failure. */
  private static void rethrowIfFatal(Throwable t) {
    if (t instanceof VirtualMachineError e) {
      throw e;
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Prefill
  // ---------------------------------------------------------------------------------------------

  /**
   * Creates idle objects until the pool holds {@code minPoolSize} idle objects or is full.
   *
   * @return the number of objects created
   */
  public int prepare() {
    return ensureMinIdle();
  }

  /**
   * Creates up to {@code count} idle objects, stopping early when the pool is full or creation
   * fails. The first creation failure is rethrown unchanged.
   *
   * @return the number of objects created
   */
  public int addObjects(int count) {
    int created = 0;
    for (int i = 0; i < count; i++) {
      if (!addObject()) break;
      created++;
    }
    return created;
  }

  /**
   * Creates one idle object.
   *
   * @return true if an object was created, false if the pool is full or closed
   */
  public boolean addObject() {
    if (closed || !reserveSlot()) {
      return false;
    }
    PooledObject<T> p       = null;
    boolean         created = false;
    try {
      p       = create();
      created = true;
    } finally {
      if (!created) {
        releaseSlot();
      }
    }
    pushIdle(p);
    if (closed) {
      if (idle.remove(p)) destroy(p, "pool closed during add");
      return false;
    }
    signalOne();
    return true;
  }

  private int ensureMinIdle() {
    int created = 0;
    while (!closed && idle.size() < config.minPoolSize() && total.get() < maxPoolSize) {
      try {
        if (!addObject()) break;
        created++;
      } catch (RuntimeException e) {
        log.warn("Pool {}: could not create object to maintain minPoolSize", name, e);
        break;
      }
    }
    return created;
  }

  // ---------------------------------------------------------------------------------------------
  // Maintenance: eviction, min idle, abandon detection
  // ---------------------------------------------------------------------------------------------

  private void runOnVirtualThread(String task, AtomicBoolean busy, Runnable body) {
    if (closed) return;
    if (!busy.compareAndSet(false, true)) {
      log.debug("Pool {}: previous {} run still in progress, skipping this tick", name, task);
      return;
    }
    boolean started = false;
    try {
      Thread.ofVirtual().name(name + "-" + task).start(() -> {
        try {
          body.run();
        } catch (Throwable t) {
          log.error("Pool {}: {} run failed", name, task, t);
        } finally {
          busy.set(false);
        }
      });
      started = true;
    } finally {
      if (!started) {
        busy.set(false);
      }
    }
  }

  private void scheduledMaintenance() {
    // Refill first so a slow eviction pass (factory validation) never delays minimum capacity.
    ensureMinIdle();
    if (config.evictionPolicy() != SimpleObjectPoolConfig.EvictionPolicy.NONE) {
      evictionRun(false);
      ensureMinIdle();
    }
  }

  /**
   * Examines every idle object now, on the calling thread, regardless of the configured
   * eviction policy. Objects that fail the factory's {@code shouldEvict} hook or validation
   * are destroyed, as are objects idle longer than {@code objEvictionTimeout} while more than
   * {@code minPoolSize} objects are idle. Afterwards the pool is refilled to {@code minPoolSize}.
   * Safe to call concurrently with borrows and returns.
   */
  public void evict() {
    if (closed) return;
    evictionRun(true);
    ensureMinIdle();
  }

  /**
   * One eviction pass. Every idle object gets the cheap checks (shouldEvict hook, idle timeout).
   * Factory validation is limited to {@code numValidationsPerEvictionRun} objects on a scheduled
   * run, chosen by the eviction policy, and unlimited on a manual {@link #evict()}.
   */
  private void evictionRun(boolean testAll) {
    evictionLock.lock();
    try {
      List<PooledObject<T>> candidates       = orderByPolicy(new ArrayList<>(idle));
      int                   validationBudget = testAll ? Integer.MAX_VALUE : config.numValidationsPerEvictionRun();
      // Idle-timeout eviction must not take the idle count below minPoolSize. Borrowed objects do
      // not count. Returns during the run only make this estimate conservative.
      int idleRemaining = candidates.size();

      for (PooledObject<T> p : candidates) {
        if (closed) return;
        // Take exclusive ownership: once removed, no borrower can reach it.
        if (!idle.remove(p)) {
          idleRemaining--;
          continue;
        }
        long   epoch  = clearEpoch.get();
        String reason = null;
        try {
          p.markTesting();
          boolean validate = validationBudget > 0;
          reason = evictionReason(p, idleRemaining > config.minPoolSize(), validate);
          if (validate) validationBudget--;
        } catch (Throwable t) {
          destroy(p, "eviction check threw");
          idleRemaining--;
          rethrowIfFatal(t);
          log.error("Pool {}: eviction check of object {} threw, destroyed it", name, p.id(), t);
          continue;
        }
        if (reason == null && clearEpoch.get() != epoch) {
          reason = "idle queue cleared during idle test";
        }
        if (reason != null) {
          idleRemaining--;
          destroy(p, reason);
        } else {
          p.markIdleAfterTest(); // state only; the idle timestamp is preserved
          // Put it back at the cold end so it keeps its place in the reuse order.
          if (lifo) {
            idle.addLast(p);
          } else {
            idle.addFirst(p);
          }
          if ((closed || clearEpoch.get() != epoch) && idle.remove(p)) {
            destroy(p, closed ? "pool closed during eviction run" : "idle queue cleared during idle test");
          } else {
            signalOne(); // a borrower may have parked while we held the only idle object
          }
        }
      }
    } finally {
      evictionLock.unlock();
    }
  }

  /**
   * @return why the object should be evicted, or null to keep it. May throw whatever the factory
   * throws; the caller destroys the object in that case.
   */
  private String evictionReason(PooledObject<T> p, boolean aboveMinIdle, boolean validate) {
    T object = p.object();
    if (factory.shouldEvict(object, p)) {
      return "factory requested eviction";
    }
    if (aboveMinIdle && p.idleNanos() >= evictionTimeoutNanos) {
      return "idle for " + p.idleTime() + " ms";
    }
    if (!validate || !config.testWhileIdle()) {
      return null;
    }
    factory.activateObject(object);
    boolean valid = factory.isObjectValid(object);
    factory.passivateObject(object);
    return valid ? null : "failed idle validation";
  }

  /** Orders candidates so that the ones the policy prefers to validate come first. Counters are snapshotted so concurrent borrows cannot break the sort. */
  private List<PooledObject<T>> orderByPolicy(List<PooledObject<T>> snapshot) {
    switch (config.evictionPolicy()) {
      case OLDEST_FIRST -> snapshot.sort(Comparator.comparingLong(PooledObject::creationTime));
      case LEAST_USED, MOST_USED -> {
        record Ranked<T>(PooledObject<T> p, long borrows) {}
        List<Ranked<T>> ranked = new ArrayList<>(snapshot.size());
        for (PooledObject<T> p : snapshot) ranked.add(new Ranked<>(p, p.borrowCount()));
        Comparator<Ranked<T>> byBorrows = Comparator.comparingLong(Ranked::borrows);
        ranked.sort(config.evictionPolicy() == SimpleObjectPoolConfig.EvictionPolicy.LEAST_USED ? byBorrows : byBorrows.reversed());
        snapshot.clear();
        for (Ranked<T> r : ranked) snapshot.add(r.p());
      }
      case RANDOM -> Collections.shuffle(snapshot, ThreadLocalRandom.current());
      case NONE -> {
        // Only reached via a manual evict(); order is irrelevant because everything is validated.
      }
    }
    return snapshot;
  }

  private void markReclaimed(T object) {
    Reference<?> dead;
    while ((dead = reclaimedQueue.poll()) != null) {
      reclaimed.remove(dead);
    }
    reclaimed.add(new WeakIdentityKey(object, reclaimedQueue));
  }

  /**
   * Takes a borrowed object away from its holder. The reclaimed marker is placed before the
   * ownership CAS so a concurrent return by the holder never sees a gap in which the object is
   * neither borrowed nor reclaimed.
   *
   * @return true if this call took ownership and destroyed the object
   */
  private boolean reclaimBorrowed(IdentityKey key, PooledObject<T> p, String reason) {
    markReclaimed(p.object());
    if (!borrowed.remove(key, p)) {
      reclaimed.remove(new WeakIdentityKey(p.object(), null));
      return false;
    }
    destroy(p, reason);
    return true;
  }

  private void abandonCheckRun() {
    long timeoutNanos = Math.max(0, saturatedNanos(config.abandonedTimeout()));
    for (var entry : borrowed.entrySet()) {
      PooledObject<T> p = entry.getValue();
      if (p.state() != PooledObject.State.BORROWED) continue;
      long heldNanos = p.borrowedNanos();
      if (heldNanos >= timeoutNanos && reclaimBorrowed(entry.getKey(), p, "abandoned")) {
        log.warn("Pool {}: object {} had been borrowed for {} ms, treated it as abandoned and destroyed it", name, p.id(), heldNanos / 1_000_000L);
      }
    }
  }

  /**
   * Destroys every idle object now, regardless of {@code minPoolSize} or idle time, including an
   * object that is being validated by the evictor at this moment. Borrowed objects are unaffected.
   */
  public void destroyAllIdleObjects() {
    clearEpoch.incrementAndGet();
    int n = drainIdle("destroyAllIdleObjects");
    log.info("Pool {}: destroyed {} idle objects, total now {}", name, n, total.get());
  }

  private int drainIdle(String reason) {
    int             n = 0;
    PooledObject<T> p;
    while ((p = idle.pollFirst()) != null) {
      destroy(p, reason);
      n++;
    }
    return n;
  }

  // ---------------------------------------------------------------------------------------------
  // Close
  // ---------------------------------------------------------------------------------------------

  /**
   * Closes the pool. Idle objects are destroyed now. Borrowed objects stay usable and are
   * destroyed when returned. Threads waiting in {@link #borrowObject()} are woken and throw
   * {@link PoolClosedException}. Calling this on a closed pool does nothing.
   */
  @Override
  public void close() {
    if (closed) return;
    synchronized (this) {
      if (closed) return;
      closed = true;
    }
    log.info("Pool {}: closing (total={}, idle={}, borrowed={})", name, total.get(), idle.size(), borrowed.size());
    if (maintenanceTask != null) maintenanceTask.cancel(false);
    if (abandonTask != null) abandonTask.cancel(false);
    signalAll();
    drainIdle("pool closed");
    log.info("Pool {}: closed, {} object(s) still borrowed and will be destroyed on return", name, borrowed.size());
  }

  /**
   * Closes the pool and also destroys every borrowed object immediately. Callers still holding
   * those objects will find them unusable. Use {@link #close()} unless you need this.
   */
  public void forceClose() {
    close();
    for (var entry : borrowed.entrySet()) {
      reclaimBorrowed(entry.getKey(), entry.getValue(), "pool force-closed");
    }
  }

  /** @return true once {@link #close()} or {@link #forceClose()} has been called */
  public boolean isClosed() {
    return closed;
  }

  // ---------------------------------------------------------------------------------------------
  // Introspection
  // ---------------------------------------------------------------------------------------------

  public SimpleObjectPoolConfig config() {
    return config;
  }

  protected PooledObjectFactory<T> getFactory() {
    return factory;
  }

  /** @return objects currently handed out */
  public int borrowedObjectsCount() {
    return borrowed.size();
  }

  /** @return objects alive or being created */
  public int currentPoolSize() {
    return total.get();
  }

  /** @return objects in the idle queue; walks the queue, so avoid calling it in a hot loop */
  public int idleObjectCount() {
    return idle.size();
  }

  /** @return objects created since construction */
  public long numOfObjectsCreated() {
    return idSequence.get();
  }

  /** @return threads currently blocked in {@link #borrowObject()} */
  public int waitingCount() {
    return waiters.get();
  }

  /** @return successful borrows since construction */
  public long numOfTimesBorrowedFromPool() {
    return borrowCount.get();
  }

  /**
   * @return how many times the given object has been borrowed, or 0 if it is not part of this
   * pool. Cheap for borrowed objects, a scan for idle ones.
   */
  public long numOfTimesBorrowed(T obj) {
    if (obj == null) return 0;
    PooledObject<T> p = borrowed.get(new IdentityKey(obj));
    if (p == null) {
      for (PooledObject<T> i : idle) {
        if (i.object() == obj) {
          p = i;
          break;
        }
      }
    }
    return p == null ? 0 : p.borrowCount();
  }

  /** @return a snapshot of the pool's counters */
  public PoolStats stats() {
    return new PoolStats(total.get(), idle.size(), borrowed.size(), waiters.get(), idSequence.get(), destroyedCount.get(), borrowCount.get(), maxPoolSize);
  }

  @Override
  public String toString() {
    return "SimpleObjectPool{name=" + name + ", " + stats() + '}';
  }

  /** Duration to nanoseconds without throwing on huge values. Negative stays negative; overflow saturates. */
  private static long saturatedNanos(Duration d) {
    try {
      return d.toNanos();
    } catch (ArithmeticException overflow) {
      return d.isNegative() ? Long.MIN_VALUE : Long.MAX_VALUE;
    }
  }

  /**
   * Weak reference that compares referents by identity. Used for the reclaimed set. A key built
   * with a null queue is a probe for lookups only.
   */
  private static final class WeakIdentityKey extends WeakReference<Object> {
    private final int hash;

    WeakIdentityKey(Object ref, ReferenceQueue<Object> queue) {
      super(ref, queue);
      this.hash = System.identityHashCode(ref);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (!(o instanceof WeakIdentityKey k)) return false;
      Object mine = get();
      return mine != null && mine == k.get();
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }

  /** Map key that compares the wrapped object by reference only. */
  private static final class IdentityKey {
    private final Object ref;

    IdentityKey(Object ref) {
      this.ref = ref;
    }

    @Override
    public boolean equals(Object o) {
      return o instanceof IdentityKey k && k.ref == ref;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(ref);
    }
  }
}
