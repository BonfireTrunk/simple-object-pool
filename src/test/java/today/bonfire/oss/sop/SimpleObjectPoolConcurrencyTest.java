package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Timeout(30)
class SimpleObjectPoolConcurrencyTest {

  private static final int      MAX_POOL_SIZE = 5;
  private static final Duration AWAIT         = Duration.ofSeconds(10);

  @Test
  void testConcurrentBorrowAndReturn() throws Exception {
    var factory = new TestPooledObjectFactory();
    // Generous wait: every thread must eventually get an object.
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(MAX_POOL_SIZE)
                                       .waitingForObjectTimeout(Duration.ofSeconds(20))
                                       .build();

    int           numThreads = MAX_POOL_SIZE * 4;
    AtomicInteger successful = new AtomicInteger();
    AtomicInteger inUse      = new AtomicInteger();
    AtomicInteger maxInUse   = new AtomicInteger();
    CountDownLatch start     = new CountDownLatch(1);

    try (var pool = new SimpleObjectPool<>(config, factory);
         ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      List<Future<?>> futures = new ArrayList<>();
      for (int i = 0; i < numThreads; i++) {
        futures.add(executor.submit(() -> {
          start.await();
          TestPoolObject obj = pool.borrowObject();
          assertThat(obj).isNotNull();
          maxInUse.accumulateAndGet(inUse.incrementAndGet(), Math::max);
          successful.incrementAndGet();
          TestSupport.sleepQuietly(10); // simulated work
          inUse.decrementAndGet();
          pool.returnObject(obj);
          return null;
        }));
      }
      start.countDown();
      for (var f : futures) {
        f.get(20, TimeUnit.SECONDS);
      }

      assertThat(successful.get()).isEqualTo(numThreads);
      assertThat(maxInUse.get()).as("never more objects out than maxPoolSize").isLessThanOrEqualTo(MAX_POOL_SIZE);
      assertThat(pool.currentPoolSize()).isLessThanOrEqualTo(MAX_POOL_SIZE);
      assertThat(factory.getIdCounter().get()).isLessThanOrEqualTo(MAX_POOL_SIZE);
      assertThat(pool.borrowedObjectsCount()).isZero();
      assertThat(pool.waitingCount()).isZero();
    }
  }

  @Test
  void testConcurrentReturnObject() throws Exception {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(MAX_POOL_SIZE).build(), factory);
         ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      TestPoolObject borrowed = pool.borrowObject();

      CountDownLatch startLatch = new CountDownLatch(1);
      AtomicInteger  succeeded  = new AtomicInteger();
      AtomicInteger  rejected   = new AtomicInteger();
      List<Future<?>> futures   = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        futures.add(executor.submit(() -> {
          startLatch.await();
          try {
            pool.returnObject(borrowed);
            succeeded.incrementAndGet();
          } catch (today.bonfire.oss.sop.exceptions.PoolObjectException e) {
            rejected.incrementAndGet();
          }
          return null;
        }));
      }
      startLatch.countDown();
      for (var f : futures) {
        f.get(10, TimeUnit.SECONDS);
      }

      assertThat(succeeded.get()).isEqualTo(1);
      assertThat(rejected.get()).isEqualTo(1);
      assertThat(pool.idleObjectCount()).isEqualTo(1);
      assertThat(pool.borrowObject()).isSameAs(borrowed);
    }
  }

  @Test
  void testConcurrentBorrowGetsDistinctObjects() throws Exception {
    var factory = new TestPooledObjectFactory();
    factory.setCreationDelayMillis(100);
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(MAX_POOL_SIZE)
                                                                 .testOnBorrow(true)
                                                                 .waitingForObjectTimeout(Duration.ofSeconds(5))
                                                                 .build(), factory);
         ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      CountDownLatch startLatch = new CountDownLatch(1);
      List<Future<TestPoolObject>> futures = new ArrayList<>();
      for (int i = 0; i < 3; i++) {
        futures.add(executor.submit(() -> {
          startLatch.await();
          return pool.borrowObject();
        }));
      }
      startLatch.countDown();

      Set<Long> seqs = new HashSet<>();
      List<TestPoolObject> objs = new ArrayList<>();
      for (var f : futures) {
        TestPoolObject o = f.get(10, TimeUnit.SECONDS);
        assertThat(o).isNotNull();
        seqs.add(o.seq());
        objs.add(o);
      }
      assertThat(seqs).as("distinct instances").hasSize(3);
      for (var o : objs) {
        pool.returnObject(o);
      }
    }
  }

  @Test
  void testCreationsRunInParallel() throws Exception {
    // Each createObject() blocks until two creations are in flight at once. If the pool serialised
    // factory calls, the second would never arrive and the latch would time out.
    CountDownLatch bothInFlight = new CountDownLatch(2);
    AtomicInteger  timedOut     = new AtomicInteger();
    var factory = new PooledObjectFactory<TestPoolObject>() {
      @Override
      public TestPoolObject createObject() {
        bothInFlight.countDown();
        try {
          if (!bothInFlight.await(5, TimeUnit.SECONDS)) timedOut.incrementAndGet();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return new TestPoolObject();
      }

      @Override
      public void destroyObject(TestPoolObject obj) {
        obj.destroy();
      }
    };

    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(2).build(), factory);
         ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<TestPoolObject> a = executor.submit(() -> pool.borrowObject());
      Future<TestPoolObject> b = executor.submit(() -> pool.borrowObject());
      TestPoolObject oa = a.get(10, TimeUnit.SECONDS);
      TestPoolObject ob = b.get(10, TimeUnit.SECONDS);
      assertThat(timedOut.get()).as("creations overlapped").isZero();
      assertThat(oa).isNotSameAs(ob);
      pool.returnObject(oa);
      pool.returnObject(ob);
    }
  }

  @Test
  void testWaitingThreadWakesOnDestruction() throws Exception {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(1)
                                                                 .waitingForObjectTimeout(Duration.ofSeconds(10))
                                                                 .testOnReturn(true)
                                                                 .build(), factory);
         ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      TestPoolObject obj1 = pool.borrowObject();

      Future<TestPoolObject> waiter = executor.submit(() -> pool.borrowObject());
      TestSupport.awaitUntil("borrower parked", AWAIT, () -> pool.waitingCount() == 1);

      // Returning as broken destroys the object and frees the slot; the waiter must wake and create a new one.
      pool.returnObject(obj1, true);

      TestPoolObject obj2 = waiter.get(10, TimeUnit.SECONDS);
      assertThat(obj2).isNotNull().isNotSameAs(obj1);
      assertThat(obj1.isDestroyed()).isTrue();
      assertThat(pool.currentPoolSize()).isEqualTo(1);
      pool.returnObject(obj2);
    }
  }

  @Test
  void testCloseWakesAllParkedBorrowers() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(1)
                                                            .waitingForObjectTimeout(Duration.ofSeconds(-1))
                                                            .build(), factory);
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      TestPoolObject held = pool.borrowObject();
      List<Future<TestPoolObject>> waiters = new ArrayList<>();
      for (int i = 0; i < 4; i++) {
        waiters.add(executor.submit(() -> pool.borrowObject()));
      }
      TestSupport.awaitUntil("all borrowers parked", AWAIT, () -> pool.waitingCount() == 4);

      pool.close();

      List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
      for (var w : waiters) {
        try {
          w.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
          failures.add(e.getCause());
        }
      }
      assertThat(failures).hasSize(4).allMatch(t -> t instanceof today.bonfire.oss.sop.exceptions.PoolClosedException);
      TestSupport.awaitUntil("waiters drained", AWAIT, () -> pool.waitingCount() == 0);
      pool.returnObject(held);
      assertThat(held.isDestroyed()).isTrue();
    } finally {
      pool.forceClose();
    }
  }
}
