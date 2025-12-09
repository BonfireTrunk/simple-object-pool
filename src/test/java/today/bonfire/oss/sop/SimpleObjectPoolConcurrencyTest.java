package today.bonfire.oss.sop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleObjectPoolConcurrencyTest {

  private static final int  MAX_POOL_SIZE       = 5;
  private static final int  MIN_POOL_SIZE       = 0;
  private static final long IDLE_TIMEOUT        = 1000L;
  private static final long ABANDONED_TIMEOUT   = 2000L;
  private static final long OBJECT_WAIT_TIMEOUT = 100L;

  private PooledObjectFactory<TestPoolObject> factory;

  private SimpleObjectPool<TestPoolObject> pool;

  @BeforeEach
  void setUp() {
    factory = mock();
    pool    = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                           .maxPoolSize(MAX_POOL_SIZE)
                                                           .minPoolSize(MIN_POOL_SIZE)
                                                           .testWhileIdle(true)
                                                           .testOnCreate(false)
                                                           .waitingForObjectTimeout(Duration.ofMillis(OBJECT_WAIT_TIMEOUT))
                                                           .durationBetweenEvictionsRuns(Duration.ofMillis(IDLE_TIMEOUT))
                                                           .objEvictionTimeout(Duration.ofMillis(IDLE_TIMEOUT))
                                                           .durationBetweenAbandonCheckRuns(Duration.ofMillis(ABANDONED_TIMEOUT))
                                                           .abandonedTimeout(Duration.ofMillis(ABANDONED_TIMEOUT))
                                                           .build(), factory);
  }

  @AfterEach
  void tearDown() {
    if (pool != null) {
      pool.close();
    }
  }

  @Test
  void testConcurrentBorrowAndReturn() throws Exception {
    var config = pool.config().toBuilder()
                     .maxPoolSize(MAX_POOL_SIZE)
                     .abandonedTimeout(Duration.ofSeconds(10))
                     .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    int             numThreads      = MAX_POOL_SIZE * 2;
    ExecutorService executor        = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch  startLatch      = new CountDownLatch(1);
    CountDownLatch  completionLatch = new CountDownLatch(numThreads);

    AtomicInteger successfulBorrows = new AtomicInteger(0);
    when(factory.createObject()).thenAnswer(inv -> new TestPoolObject());

    // Create tasks that borrow and return objects
    for (int i = 0; i < numThreads; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          TestPoolObject obj = localPool.borrowObject();
          assertThat(obj).isNotNull();
          successfulBorrows.incrementAndGet();
          Thread.sleep(10); // Simulate some work
          localPool.returnObject(obj);
        } catch (Exception e) {
          // Expected some threads to fail due to pool exhaustion
          assertThat(e)
              .as("Failed threads should throw PoolTimeoutException")
              .isInstanceOf(PoolTimeoutException.class);
        } finally {
          completionLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertThat(completionLatch.await(10, TimeUnit.SECONDS))
        .as("All threads should complete within timeout")
        .isTrue();
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
        .as("Executor should terminate within timeout")
        .isTrue();

    assertThat(successfulBorrows.get())
        .as("Should have some successful borrows")
        .isGreaterThan(MAX_POOL_SIZE);
    assertThat(localPool.currentPoolSize())
        .as("Pool size should not exceed maximum")
        .isLessThanOrEqualTo(MAX_POOL_SIZE);

    localPool.close();
  }

  @Test
  void testConcurrentReturnObject() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .build(), factory);

    TestPoolObject borrowed = pool.borrowObject();

    // Start two threads trying to return the same object
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch  = new CountDownLatch(2);

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    for (int i = 0; i < 2; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          pool.returnObject(borrowed);
        } catch (Exception e) {
          // Expected for one thread
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertThat(doneLatch.await(5, TimeUnit.SECONDS))
        .as("Both threads should complete")
        .isTrue();

    executor.shutdown();
    assertThat(executor.awaitTermination(1, TimeUnit.SECONDS))
        .as("Executor should terminate")
        .isTrue();

    // Object should still be valid and in the pool
    TestPoolObject borrowedAgain = pool.borrowObject();
    assertThat(borrowedAgain).isSameAs(borrowed);
    pool.close();
  }

  @Test
  void testConcurrentBorrowWithSlowValidation() throws Exception {
    var factory = new TestPooledObjectFactory();
    factory.setValidationDelayMillis(500); // 500ms validation delay

    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .testOnBorrow(true)
                                                            .waitingForObjectTimeout(Duration.ofSeconds(2))
                                                            .build(), factory);

    ExecutorService executor   = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch  startLatch = new CountDownLatch(1);

    Future<TestPoolObject> future1 = executor.submit(() -> {
      startLatch.await();
      return pool.borrowObject();
    });

    Future<TestPoolObject> future2 = executor.submit(() -> {
      startLatch.await();
      return pool.borrowObject();
    });

    startLatch.countDown();

    TestPoolObject borrowed1 = future1.get(2500, TimeUnit.MILLISECONDS);
    TestPoolObject borrowed2 = future2.get(2500, TimeUnit.MILLISECONDS);

    assertThat(borrowed1)
        .as("First entity should not be null")
        .isNotNull();
    assertThat(borrowed2)
        .as("Second entity should not be null")
        .isNotNull();
    assertThat(borrowed1.getEntityId())
        .as("Entities should have different IDs")
        .isNotEqualTo(borrowed2.getEntityId());

    executor.shutdown();
    assertThat(executor.awaitTermination(1, TimeUnit.SECONDS))
        .as("Executor should terminate")
        .isTrue();

    pool.returnObject(borrowed1);
    pool.returnObject(borrowed2);
    pool.close();
  }

  @Test
  void testWaitingThreadWakesOnDestruction() throws Exception {
    var factory = new TestPooledObjectFactory();
    factory.setValidationDelayMillis(10);
    // Max pool size 1, min 0
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(1)
                                                            .minPoolSize(0)
                                                            .waitingForObjectTimeout(Duration.ofSeconds(2))
                                                            .testOnReturn(true)
                                                            .build(), factory);

    // 1. Borrow the only available slot
    TestPoolObject obj1 = pool.borrowObject();
    assertThat(obj1).isNotNull();

    // 2. Start a second thread that tries to borrow. It should wait because pool is
    // full.
    CountDownLatch  startLatch   = new CountDownLatch(1);
    AtomicInteger   successCount = new AtomicInteger(0);
    ExecutorService executor     = Executors.newSingleThreadExecutor();
    Future<?> future = executor.submit(() -> {
      try {
        startLatch.countDown();
        TestPoolObject obj2 = pool.borrowObject();
        if (obj2 != null) {successCount.incrementAndGet();}
      } catch (Exception e) {
        // e.printStackTrace();
      }
    });

    startLatch.await();
    Thread.sleep(100); // Give it time to enter wait

    // 3. Return obj1 as BROKEN. This should destroy it, decrement size to 0.
    // If we signal, the updating thread should wake up, see size < max, and create
    // a new object.
    pool.returnObject(obj1, true);

    // 4. Wait for thread to finish
    try {
      future.get(1000, TimeUnit.MILLISECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      // Expected failure currently
    }

    // Check results
    assertThat(successCount.get()).isEqualTo(1);

    executor.shutdownNow();
    pool.close();
  }
}
