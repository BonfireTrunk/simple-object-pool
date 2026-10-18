package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolClosedException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static today.bonfire.oss.sop.TestSupport.assertNeverDuring;
import static today.bonfire.oss.sop.TestSupport.awaitUntil;

/**
 * Behaviour the v3 pool must have so it can replace commons-pool2 in Jedis. Tests 1 to 10 follow
 * section 6 of jedis-v8-sop-requirements.md; the rest cover new v3 API.
 */
@Timeout(30)
class SimpleObjectPoolV3RequirementsTest {

  private static final Duration SHORT = Duration.ofMillis(200);
  private static final Duration WAIT  = Duration.ofSeconds(5);

  /** A minimal counting factory with hooks tests can override. */
  static class CountingFactory implements PooledObjectFactory<TestPoolObject> {
    final AtomicInteger creates  = new AtomicInteger();
    final AtomicInteger destroys = new AtomicInteger();

    @Override
    public TestPoolObject createObject() {
      creates.incrementAndGet();
      return new TestPoolObject();
    }

    @Override
    public void destroyObject(TestPoolObject obj) {
      destroys.incrementAndGet();
      obj.destroy();
    }
  }

  private static SimpleObjectPoolConfig.Builder cfg(int max) {
    return SimpleObjectPoolConfig.builder()
                                 .maxPoolSize(max)
                                 .waitingForObjectTimeout(WAIT)
                                 .testWhileIdle(false);
  }

  // 1. R1: long holds are never reclaimed
  @Test
  void borrowedObjectIsNeverDestroyedWhileHeldByDefault() {
    var factory = new CountingFactory();
    var pool = new SimpleObjectPool<>(cfg(2)
                                          .minPoolSize(0)
                                          .durationBetweenEvictionsRuns(Duration.ofMillis(20))
                                          .objEvictionTimeout(Duration.ofMillis(10))
                                          .testWhileIdle(true)
                                          .build(), factory);
    assertThat(pool.config().abandonCheckEnabled()).isFalse();
    var obj = pool.borrowObject();
    assertNeverDuring("destroy while borrowed", Duration.ofMillis(300), () -> factory.destroys.get() > 0);
    assertThat(pool.borrowedObjectsCount()).isEqualTo(1);
    pool.returnObject(obj);
    assertThat(obj.isDestroyed()).isFalse();
    pool.close();
  }

  // 2. R2: close wakes parked borrowers, nothing is created after close
  @Test
  void closeWakesParkedBorrowerAndPreventsCreation() throws Exception {
    var factory = new CountingFactory();
    var pool    = new SimpleObjectPool<>(cfg(1).waitingForObjectTimeout(Duration.ofSeconds(20)).build(), factory);
    var held    = pool.borrowObject();

    var failure = new AtomicReference<Throwable>();
    var done    = new CountDownLatch(1);
    Thread waiter = Thread.ofPlatform().start(() -> {
      try {
        pool.borrowObject();
      } catch (Throwable t) {
        failure.set(t);
      } finally {
        done.countDown();
      }
    });
    awaitUntil("waiter parked", WAIT, () -> pool.waitingCount() == 1);

    long t0 = System.nanoTime();
    pool.close();
    assertThat(done.await(2, TimeUnit.SECONDS)).as("waiter woke up promptly").isTrue();
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    assertThat(failure.get()).isInstanceOf(PoolClosedException.class);

    int createsAtClose = factory.creates.get();
    assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolClosedException.class);
    assertThat(factory.creates.get()).isEqualTo(createsAtClose).isEqualTo(1);
    assertThat(pool.idleObjectCount()).isZero();
    pool.returnObject(held);
    assertThat(pool.currentPoolSize()).isZero();
    waiter.join();
  }

  // 3. R3: close is graceful for borrowed objects
  @Test
  void closeDoesNotDestroyBorrowedObjectUntilReturned() {
    var factory = new CountingFactory();
    var pool    = new SimpleObjectPool<>(cfg(2).build(), factory);
    var held    = pool.borrowObject();
    var idle    = pool.borrowObject();
    pool.returnObject(idle);

    pool.close();
    assertThat(pool.isClosed()).isTrue();
    assertThat(idle.isDestroyed()).as("idle object destroyed at close").isTrue();
    assertThat(held.isDestroyed()).as("borrowed object untouched by close").isFalse();
    assertThat(factory.destroys.get()).isEqualTo(1);
    assertThat(pool.borrowedObjectsCount()).isEqualTo(1);

    pool.returnObject(held);
    assertThat(held.isDestroyed()).isTrue();
    assertThat(factory.destroys.get()).isEqualTo(2);
    assertThat(pool.currentPoolSize()).isZero();
    assertThat(pool.borrowedObjectsCount()).isZero();
  }

  // 4. R4: slow creation does not block return or borrow of other objects
  @Test
  void slowCreateDoesNotBlockOtherBorrowersAndReturners() throws Exception {
    var gate      = new CountDownLatch(1);
    var inCreate  = new CountDownLatch(1);
    var factory = new CountingFactory() {
      @Override
      public TestPoolObject createObject() {
        if (creates.incrementAndGet() == 2) {
          inCreate.countDown();
          try {
            gate.await();
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        }
        return new TestPoolObject();
      }
    };
    var pool = new SimpleObjectPool<>(cfg(4).build(), factory);
    var first = pool.borrowObject(); // creation #1, instant

    var aResult = new AtomicReference<TestPoolObject>();
    Thread a = Thread.ofPlatform().start(() -> aResult.set(pool.borrowObject())); // creation #2, blocks
    assertThat(inCreate.await(5, TimeUnit.SECONDS)).isTrue();

    var cResult = new AtomicReference<TestPoolObject>();
    var cDone   = new CountDownLatch(1);
    Thread b = Thread.ofPlatform().start(() -> pool.returnObject(first));
    b.join(5000);
    assertThat(b.isAlive()).as("return completed while A is inside create").isFalse();

    Thread c = Thread.ofPlatform().start(() -> {
      cResult.set(pool.borrowObject());
      cDone.countDown();
    });
    assertThat(cDone.await(5, TimeUnit.SECONDS)).as("C borrowed while A is inside create").isTrue();
    assertThat(cResult.get()).isSameAs(first);
    assertThat(a.isAlive()).as("A still blocked in create").isTrue();
    assertThat(gate.getCount()).isEqualTo(1);

    gate.countDown();
    a.join(5000);
    assertThat(aResult.get()).isNotNull().isNotSameAs(first);
    pool.returnObject(aResult.get());
    pool.returnObject(cResult.get());
    pool.close();
  }

  // 5. R4: an object under idle test is invisible to borrowers
  @Test
  void borrowerNeverReceivesObjectUnderIdleTest() throws Exception {
    var gate      = new CountDownLatch(1);
    var inTest    = new CountDownLatch(1);
    var factory = new CountingFactory() {
      @Override
      public boolean isObjectValid(TestPoolObject obj) {
        inTest.countDown();
        try {
          gate.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return true;
      }
    };
    var pool = new SimpleObjectPool<>(cfg(1)
                                          .testWhileIdle(true)
                                          .objEvictionTimeout(Duration.ofMinutes(10))
                                          .build(), factory);
    var x = pool.borrowObject();
    pool.returnObject(x);
    assertThat(pool.idleObjectCount()).isEqualTo(1);

    Thread evictor = Thread.ofPlatform().start(pool::evict);
    assertThat(inTest.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(pool.idleObjectCount()).as("object removed from idle while under test").isZero();

    assertThatThrownBy(() -> pool.borrowObject(SHORT)).isInstanceOf(PoolTimeoutException.class);
    assertThat(factory.creates.get()).isEqualTo(1);

    gate.countDown();
    evictor.join(5000);
    awaitUntil("X back in idle", WAIT, () -> pool.idleObjectCount() == 1);
    assertThat(pool.borrowObject()).isSameAs(x);
    pool.returnObject(x);
    pool.close();
  }

  // 6. R5: a single create attempt, exception instance propagated unchanged
  @Test
  void createFailurePropagatesSameInstanceAfterOneAttempt() {
    var boom    = new IllegalStateException("boom");
    var factory = new CountingFactory() {
      @Override
      public TestPoolObject createObject() {
        creates.incrementAndGet();
        throw boom;
      }
    };
    var pool = new SimpleObjectPool<>(cfg(2).build(), factory);
    assertThat(pool.config().maxRetries()).isZero();
    assertThatThrownBy(pool::borrowObject).isSameAs(boom);
    assertThat(factory.creates.get()).isEqualTo(1);
    assertThat(pool.currentPoolSize()).as("slot released after failure").isZero();
    pool.close();
  }

  // 7. R6: passivate failure destroys and frees the slot for a waiter
  @Test
  void passivateFailureDestroysObjectAndFreesSlot() throws Exception {
    var factory = new CountingFactory() {
      @Override
      public void passivateObject(TestPoolObject obj) {
        throw new IllegalStateException("passivate failed");
      }
    };
    var pool = new SimpleObjectPool<>(cfg(1).build(), factory);
    var held = pool.borrowObject();

    var got  = new AtomicReference<TestPoolObject>();
    var done = new CountDownLatch(1);
    Thread waiter = Thread.ofPlatform().start(() -> {
      got.set(pool.borrowObject());
      done.countDown();
    });
    awaitUntil("waiter parked", WAIT, () -> pool.waitingCount() == 1);

    pool.returnObject(held); // must not throw, must destroy
    assertThat(held.isDestroyed()).isTrue();
    assertThat(factory.destroys.get()).isEqualTo(1);
    assertThat(done.await(5, TimeUnit.SECONDS)).as("waiter served with a new object").isTrue();
    assertThat(got.get()).isNotNull().isNotSameAs(held);
    assertThat(factory.creates.get()).isEqualTo(2);
    assertThat(pool.currentPoolSize()).isEqualTo(1);
    waiter.join();
    pool.close();
  }

  // 8. R7: evict() validates everything even with policy NONE
  @Test
  void manualEvictValidatesAllIdleObjectsWithPolicyNone() {
    var validated = new AtomicInteger();
    var factory = new CountingFactory() {
      @Override
      public boolean isObjectValid(TestPoolObject obj) {
        validated.incrementAndGet();
        return false;
      }
    };
    var pool = new SimpleObjectPool<>(cfg(3)
                                          .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                          .testWhileIdle(true)
                                          .numValidationsPerEvictionRun(1)
                                          .objEvictionTimeout(Duration.ofMinutes(10))
                                          .build(), factory);
    assertThat(pool.addObjects(3)).isEqualTo(3);
    assertThat(pool.idleObjectCount()).isEqualTo(3);

    pool.evict();
    assertThat(validated.get()).isEqualTo(3);
    assertThat(factory.destroys.get()).isEqualTo(3);
    assertThat(pool.idleObjectCount()).isZero();
    assertThat(pool.currentPoolSize()).isZero();
    pool.close();
  }

  // 9. R8: shared daemon scheduler
  @Test
  void manyPoolsDoNotMultiplyPlatformThreads() {
    long before = platformThreadCount();
    List<SimpleObjectPool<TestPoolObject>> pools = new ArrayList<>();
    for (int i = 0; i < 100; i++) {
      pools.add(new SimpleObjectPool<>(cfg(2)
                                           .poolName("many-" + i)
                                           .minPoolSize(1)
                                           .durationBetweenEvictionsRuns(Duration.ofMillis(10))
                                           .abandonedTimeout(Duration.ofSeconds(30))
                                           .durationBetweenAbandonCheckRuns(Duration.ofMillis(10))
                                           .build(), new CountingFactory()));
    }
    try {
      awaitUntil("maintenance thread running", WAIT, () -> pools.stream().allMatch(p -> p.idleObjectCount() == 1));
      long after = platformThreadCount();
      // Maintenance runs on virtual threads, whose carriers are a fixed ForkJoinPool sized by CPU count.
      // Growth is therefore bounded by a constant independent of the number of pools.
      long allowed = Runtime.getRuntime().availableProcessors() + 2;
      assertThat(after - before).as("platform threads added by 100 pools").isLessThanOrEqualTo(allowed);
      Set<Thread> threads = Thread.getAllStackTraces().keySet();
      List<Thread> schedulerThreads = threads.stream().filter(t -> t.getName().startsWith("sop-")).toList();
      assertThat(schedulerThreads).as("one shared scheduler thread").hasSize(1).allMatch(Thread::isDaemon);
      assertThat(threads.stream().filter(t -> !t.isVirtual() && t.getName().startsWith("many-")).toList())
          .as("no per-pool platform threads").isEmpty();
    } finally {
      pools.forEach(SimpleObjectPool::close);
    }
  }

  private static long platformThreadCount() {
    return Thread.getAllStackTraces().keySet().stream().filter(t -> !t.isVirtual()).count();
  }

  // 10. S1: cross-pool return is rejected
  @Test
  void returningForeignObjectIsRejectedWithoutCorruptingPool() {
    var factoryA = new CountingFactory();
    var factoryB = new CountingFactory();
    var poolA    = new SimpleObjectPool<>(cfg(2).poolName("A").build(), factoryA);
    var poolB    = new SimpleObjectPool<>(cfg(2).poolName("B").build(), factoryB);
    var fromA    = poolA.borrowObject();
    var fromB    = poolB.borrowObject();

    assertThatThrownBy(() -> poolB.returnObject(fromA)).isInstanceOf(PoolObjectException.class);
    assertThat(poolB.borrowedObjectsCount()).isEqualTo(1);
    assertThat(poolB.idleObjectCount()).isZero();
    assertThat(poolB.currentPoolSize()).isEqualTo(1);
    assertThat(factoryB.destroys.get()).isZero();
    assertThat(fromA.isDestroyed()).isFalse();
    assertThat(poolA.borrowedObjectsCount()).isEqualTo(1);

    poolB.returnObject(fromB);
    assertThat(poolB.idleObjectCount()).isEqualTo(1);
    poolA.returnObject(fromA);
    assertThat(poolA.idleObjectCount()).isEqualTo(1);
    poolA.close();
    poolB.close();
  }

  // 11. LIFO by default, FIFO on request
  @Test
  void idleOrderIsLifoByDefaultAndFifoWhenConfigured() {
    var lifo = new SimpleObjectPool<>(cfg(2).build(), new CountingFactory());
    var a    = lifo.borrowObject();
    var b    = lifo.borrowObject();
    lifo.returnObject(a);
    lifo.returnObject(b);
    assertThat(lifo.borrowObject()).isSameAs(b);
    lifo.close();

    var fifo = new SimpleObjectPool<>(cfg(2).lifo(false).build(), new CountingFactory());
    var c    = fifo.borrowObject();
    var d    = fifo.borrowObject();
    fifo.returnObject(c);
    fifo.returnObject(d);
    assertThat(fifo.borrowObject()).isSameAs(c);
    fifo.close();
  }

  // 12. forceClose destroys borrowed objects, close does not
  @Test
  void forceCloseDestroysBorrowedObjectsImmediately() {
    var factory = new CountingFactory();
    var pool    = new SimpleObjectPool<>(cfg(2).build(), factory);
    var held    = pool.borrowObject();
    pool.forceClose();
    assertThat(pool.isClosed()).isTrue();
    assertThat(held.isDestroyed()).isTrue();
    assertThat(pool.borrowedObjectsCount()).isZero();
    assertThat(pool.currentPoolSize()).isZero();

    var factory2 = new CountingFactory();
    var pool2    = new SimpleObjectPool<>(cfg(2).build(), factory2);
    var held2    = pool2.borrowObject();
    pool2.close();
    assertThat(held2.isDestroyed()).isFalse();
    pool2.returnObject(held2);
    assertThat(held2.isDestroyed()).isTrue();
  }

  // 13. waitingCount reflects parked borrowers
  @Test
  void waitingCountTracksParkedBorrowers() throws Exception {
    var pool = new SimpleObjectPool<>(cfg(1).build(), new CountingFactory());
    var held = pool.borrowObject();
    assertThat(pool.waitingCount()).isZero();
    var done = new CountDownLatch(1);
    Thread waiter = Thread.ofPlatform().start(() -> {
      var o = pool.borrowObject();
      pool.returnObject(o);
      done.countDown();
    });
    awaitUntil("waiter counted", WAIT, () -> pool.waitingCount() == 1);
    assertThat(pool.stats().waiting()).isEqualTo(1);
    pool.returnObject(held);
    assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
    awaitUntil("waiter count back to zero", WAIT, () -> pool.waitingCount() == 0);
    waiter.join();
    pool.close();
  }

  // 14. Lease
  @Test
  void leaseReturnsOnCloseAndMarkBrokenDestroys() {
    var factory = new CountingFactory();
    var pool    = new SimpleObjectPool<>(cfg(2).build(), factory);
    TestPoolObject seen;
    try (var lease = pool.lease()) {
      seen = lease.get();
      assertThat(pool.borrowedObjectsCount()).isEqualTo(1);
    }
    assertThat(pool.borrowedObjectsCount()).isZero();
    assertThat(pool.idleObjectCount()).isEqualTo(1);
    assertThat(seen.isDestroyed()).isFalse();

    try (var lease = pool.lease()) {
      assertThat(lease.get()).isSameAs(seen);
      lease.markBroken();
    }
    assertThat(seen.isDestroyed()).isTrue();
    assertThat(factory.destroys.get()).isEqualTo(1);
    assertThat(pool.currentPoolSize()).isZero();
    pool.close();
  }

  // 15. apply() marks broken on exception
  @Test
  void applyReturnsObjectAsBrokenWhenFunctionThrows() {
    var factory = new CountingFactory();
    var pool    = new SimpleObjectPool<>(cfg(2).build(), factory);
    var result  = pool.apply(TestPoolObject::seq);
    assertThat(result).isPositive();
    assertThat(pool.idleObjectCount()).isEqualTo(1);
    assertThat(factory.destroys.get()).isZero();

    var captured = new AtomicReference<TestPoolObject>();
    assertThatThrownBy(() -> pool.apply(o -> {
      captured.set(o);
      throw new IllegalStateException("work failed");
    })).isInstanceOf(IllegalStateException.class).hasMessage("work failed");
    assertThat(captured.get().isDestroyed()).isTrue();
    assertThat(factory.destroys.get()).isEqualTo(1);
    assertThat(pool.borrowedObjectsCount()).isZero();
    assertThat(pool.idleObjectCount()).isZero();
    pool.close();
  }

  // 16. return to a closed pool destroys and size reaches zero
  @Test
  void returnToClosedPoolDestroysObject() {
    var factory = new CountingFactory();
    var pool    = new SimpleObjectPool<>(cfg(3).build(), factory);
    var a       = pool.borrowObject();
    var b       = pool.borrowObject();
    pool.close();
    assertThat(pool.currentPoolSize()).isEqualTo(2);
    pool.returnObject(a);
    pool.returnObject(b, true);
    assertThat(a.isDestroyed()).isTrue();
    assertThat(b.isDestroyed()).isTrue();
    assertThat(factory.destroys.get()).isEqualTo(2);
    assertThat(pool.currentPoolSize()).isZero();
    assertThat(pool.idleObjectCount()).isZero();
  }

  // 17. shouldEvict hook overrides idle time and minPoolSize
  @Test
  void shouldEvictHookEvictsYoungObjectEvenAtMinPoolSize() {
    var retired = new AtomicReference<TestPoolObject>();
    var factory = new CountingFactory() {
      @Override
      public boolean shouldEvict(TestPoolObject obj, PooledObject<TestPoolObject> info) {
        return obj == retired.get();
      }
    };
    var pool = new SimpleObjectPool<>(cfg(2)
                                          .minPoolSize(2)
                                          .objEvictionTimeout(Duration.ofMinutes(10))
                                          .build(), factory);
    assertThat(pool.prepare()).isEqualTo(2);
    var victim = pool.borrowObject();
    pool.returnObject(victim);
    retired.set(victim);

    pool.evict();
    assertThat(victim.isDestroyed()).isTrue();
    assertThat(factory.destroys.get()).isEqualTo(1);
    // evict() refills to minPoolSize afterwards
    assertThat(pool.idleObjectCount()).isEqualTo(2);
    assertThat(factory.creates.get()).isEqualTo(3);
    pool.close();
  }

  // 18. zero wait fails fast
  @Test
  void zeroWaitFailsImmediatelyWhenExhausted() {
    var pool = new SimpleObjectPool<>(cfg(1).build(), new CountingFactory());
    var held = pool.borrowObject();
    long t0 = System.nanoTime();
    assertThatThrownBy(() -> pool.borrowObject(Duration.ZERO)).isInstanceOf(PoolTimeoutException.class);
    assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofMillis(100));
    assertThat(pool.waitingCount()).isZero();
    pool.returnObject(held);
    pool.close();
  }

  // 19. retries
  @Test
  void retriesHonourMaxRetriesAndDefaultIsSingleAttempt() {
    var attempts = new AtomicInteger();
    var flaky = new CountingFactory() {
      @Override
      public TestPoolObject createObject() {
        creates.incrementAndGet();
        if (attempts.incrementAndGet() <= 2) {
          throw new IllegalStateException("transient " + attempts.get());
        }
        return new TestPoolObject();
      }
    };
    var pool = new SimpleObjectPool<>(cfg(2).maxRetries(2).build(), flaky);
    var obj  = pool.borrowObject();
    assertThat(obj).isNotNull();
    assertThat(flaky.creates.get()).isEqualTo(3);
    assertThat(pool.currentPoolSize()).isEqualTo(1);
    pool.returnObject(obj);
    pool.close();

    var boom   = new IllegalStateException("first failure");
    var strict = new CountingFactory() {
      @Override
      public TestPoolObject createObject() {
        creates.incrementAndGet();
        throw boom;
      }
    };
    var pool2 = new SimpleObjectPool<>(cfg(2).build(), strict);
    assertThatThrownBy(pool2::borrowObject).isSameAs(boom);
    assertThat(strict.creates.get()).isEqualTo(1);
    pool2.close();
  }
}
