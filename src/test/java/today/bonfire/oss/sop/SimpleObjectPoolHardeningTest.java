package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static today.bonfire.oss.sop.TestSupport.awaitUntil;

/** Edge cases found during the v3 hardening review. */
@Timeout(30)
class SimpleObjectPoolHardeningTest {

  private static final Duration AWAIT = Duration.ofSeconds(5);

  /** Minimal counting factory with an optional gate on validation. */
  static class GatedFactory implements PooledObjectFactory<TestPoolObject> {
    final AtomicInteger  created   = new AtomicInteger();
    final AtomicInteger  destroyed = new AtomicInteger();
    volatile CountDownLatch validationGate;

    @Override
    public TestPoolObject createObject() {
      created.incrementAndGet();
      return new TestPoolObject();
    }

    @Override
    public void destroyObject(TestPoolObject obj) {
      destroyed.incrementAndGet();
      obj.destroy();
    }

    @Override
    public boolean isObjectValid(TestPoolObject obj) {
      CountDownLatch gate = validationGate;
      if (gate != null) {
        try {
          gate.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
      return obj.isValid();
    }
  }

  @Test
  void destroyAllIdleObjectsAlsoRemovesObjectUnderIdleTest() throws Exception {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(2)
                                                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                                                 .testWhileIdle(true)
                                                                 .build(), factory)) {
      pool.addObjects(1);
      var inTest = new CountDownLatch(1);
      factory.validationGate = inTest;

      var evictor = Thread.ofVirtual().start(pool::evict);
      awaitUntil("evictor removed the object from idle for testing", AWAIT, () -> pool.idleObjectCount() == 0 && pool.currentPoolSize() == 1);

      // clear() while the only object is being validated
      pool.destroyAllIdleObjects();
      assertThat(pool.currentPoolSize()).as("object under test still counted until the test finishes").isEqualTo(1);

      inTest.countDown();
      evictor.join();
      assertThat(pool.currentPoolSize()).as("object under test must not survive a clear").isZero();
      assertThat(factory.destroyed.get()).isEqualTo(1);
    }
  }

  @Test
  void hugeWaitDurationMeansWaitNotImmediateTimeout() throws Exception {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(1).build(), factory)) {
      TestPoolObject held = pool.borrowObject();
      var got = new CountDownLatch(1);
      var waiter = Thread.ofVirtual().start(() -> {
        // Duration.ofDays(100_000).toNanos() overflows a long; must behave as "wait", not "timed out".
        pool.borrowObject(Duration.ofDays(100_000));
        got.countDown();
      });
      awaitUntil("waiter parked", AWAIT, () -> pool.waitingCount() == 1);
      TestSupport.assertNeverDuring("waiter must not time out", Duration.ofMillis(200), () -> got.getCount() == 0 || !waiter.isAlive());
      pool.returnObject(held);
      assertThat(got.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
    }
  }

  @Test
  void subMillisecondMaintenanceIntervalIsAccepted() {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(2)
                                                                 .minPoolSize(1)
                                                                 .durationBetweenEvictionsRuns(Duration.ofNanos(500))
                                                                 .build(), factory)) {
      awaitUntil("maintenance fills minPoolSize", AWAIT, () -> pool.idleObjectCount() == 1);
    }
  }

  @Test
  void idleTimeoutEvictionKeepsMinPoolSizeIdleEvenWhenManyAreBorrowed() throws Exception {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(5)
                                                                 .minPoolSize(2)
                                                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                                                 .objEvictionTimeout(Duration.ZERO) // everything idle is "expired"
                                                                 .testWhileIdle(false)
                                                                 .build(), factory)) {
      pool.addObjects(5);
      TestPoolObject a = pool.borrowObject();
      TestPoolObject b = pool.borrowObject();
      TestPoolObject c = pool.borrowObject();
      // 3 borrowed, 2 idle, total 5. Old rule (total > minPoolSize) evicted both idle objects and recreated them.
      pool.evict();
      assertThat(factory.destroyed.get()).as("idle count equals minPoolSize, nothing to evict").isZero();
      assertThat(pool.idleObjectCount()).isEqualTo(2);

      pool.returnObject(a);
      pool.evict();
      assertThat(factory.destroyed.get()).as("one idle above minPoolSize is evicted").isEqualTo(1);
      assertThat(pool.idleObjectCount()).isEqualTo(2);
      pool.returnObject(b);
      pool.returnObject(c);
    }
  }

  @Test
  void zeroWaitOnExhaustedPoolThrowsWithoutParking() throws Exception {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(1).build(), factory)) {
      TestPoolObject held = pool.borrowObject();
      assertThatThrownBy(() -> pool.borrowObject(Duration.ZERO)).isInstanceOf(PoolTimeoutException.class);
      assertThat(pool.waitingCount()).isZero();
      pool.returnObject(held);
    }
  }

  @Test
  void errorFromCreateObjectDoesNotLeakACapacitySlot() {
    var factory = new GatedFactory() {
      @Override
      public TestPoolObject createObject() {
        throw new NoClassDefFoundError("simulated static-init failure");
      }
    };
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(1).waitingForObjectTimeout(Duration.ZERO).build(), factory)) {
      for (int i = 0; i < 3; i++) {
        assertThatThrownBy(pool::borrowObject).isInstanceOf(NoClassDefFoundError.class);
      }
      // With a leaked slot the next borrow would hit the capacity limit and time out instead of reaching the factory.
      assertThat(pool.currentPoolSize()).isZero();
      assertThatThrownBy(pool::borrowObject).isInstanceOf(NoClassDefFoundError.class);
    }
  }

  @Test
  void errorFromActivateOrPassivateDestroysObjectAndFreesSlot() throws Exception {
    var factory = new GatedFactory() {
      volatile boolean        failPassivate;
      volatile TestPoolObject failActivate;

      @Override
      public void activateObject(TestPoolObject obj) {
        if (obj == failActivate) throw new AssertionError("activate");
      }

      @Override
      public void passivateObject(TestPoolObject obj) {
        if (failPassivate) throw new AssertionError("passivate");
      }
    };
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(1).waitingForObjectTimeout(Duration.ZERO).build(), factory)) {
      TestPoolObject first = pool.borrowObject();
      factory.failPassivate = true;
      pool.returnObject(first); // Error inside passivate: object destroyed, slot freed, no exception to the caller
      assertThat(first.isDestroyed()).isTrue();
      assertThat(pool.currentPoolSize()).isZero();
      factory.failPassivate = false;

      TestPoolObject second = pool.borrowObject();
      pool.returnObject(second);
      factory.failActivate = second;
      TestPoolObject third = pool.borrowObject(); // idle object fails activation with an Error, a fresh one is created
      assertThat(third).isNotSameAs(second);
      assertThat(second.isDestroyed()).isTrue();
      assertThat(pool.currentPoolSize()).isEqualTo(1);
      factory.failActivate = null;
      pool.returnObject(third);
    }
  }

  @Test
  void validationBudgetDoesNotCapIdleTimeoutEviction() throws Exception {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(10)
                                                                 .minPoolSize(0)
                                                                 .numValidationsPerEvictionRun(0)
                                                                 .objEvictionTimeout(Duration.ZERO)
                                                                 .durationBetweenEvictionsRuns(Duration.ofMillis(20))
                                                                 .build(), factory)) {
      pool.addObjects(10);
      awaitUntil("all expired idle objects evicted by the scheduled run despite a zero validation budget", AWAIT, () -> pool.currentPoolSize() == 0);
      assertThat(factory.destroyed.get()).isEqualTo(10);
    }
  }

  @Test
  void applyPropagatesTheFunctionsExceptionAndReturnsObjectBroken() {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(1).build(), factory)) {
      assertThatThrownBy(() -> pool.apply(obj -> {
        throw new IllegalStateException("boom");
      })).isInstanceOf(IllegalStateException.class).hasMessage("boom");
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
      assertThat(pool.<Integer>apply(obj -> 42)).isEqualTo(42);
      assertThat(pool.idleObjectCount()).isEqualTo(1);
    }
  }

  @Test
  void evictionPolicySortSurvivesConcurrentBorrowCountChanges() throws Exception {
    var factory = new GatedFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(64)
                                                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.LEAST_USED)
                                                                 .numValidationsPerEvictionRun(8)
                                                                 .durationBetweenEvictionsRuns(Duration.ofMillis(5))
                                                                 .objEvictionTimeout(Duration.ofHours(1))
                                                                 .build(), factory)) {
      pool.addObjects(64);
      var stop = new java.util.concurrent.atomic.AtomicBoolean();
      var errors = new AtomicInteger();
      var workers = new java.util.ArrayList<Thread>();
      for (int i = 0; i < 8; i++) {
        workers.add(Thread.ofVirtual().start(() -> {
          while (!stop.get()) {
            try {
              pool.returnObject(pool.borrowObject());
            } catch (Throwable t) {
              errors.incrementAndGet();
            }
          }
        }));
      }
      Thread.sleep(300); // let many sorted eviction runs overlap with borrow-count mutation
      stop.set(true);
      for (Thread w : workers) w.join();
      assertThat(errors.get()).isZero();
      assertThat(pool.currentPoolSize()).isEqualTo(64);
    }
  }

  @Test
  void unclosedPoolIsNotPinnedByTheScheduler() throws Exception {
    WeakReference<SimpleObjectPool<TestPoolObject>> ref = createAndDropPool();
    for (int i = 0; i < 50 && ref.get() != null; i++) {
      System.gc();
      Thread.sleep(50);
    }
    assertThat(ref.get()).as("a pool that is dropped without close() must be collectable").isNull();
  }

  private static WeakReference<SimpleObjectPool<TestPoolObject>> createAndDropPool() {
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(2)
                                                            .minPoolSize(1)
                                                            .durationBetweenEvictionsRuns(Duration.ofMillis(20))
                                                            .abandonedTimeout(Duration.ofSeconds(1))
                                                            .durationBetweenAbandonCheckRuns(Duration.ofMillis(20))
                                                            .build(), new GatedFactory());
    pool.prepare();
    return new WeakReference<>(pool);
  }
}
