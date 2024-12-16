package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

@ExtendWith(MockitoExtension.class)
class SimpleObjectPoolEdgeCaseTest {

  private static final int  MAX_POOL_SIZE     = 5;
  private static final int  MIN_POOL_SIZE     = 2;
  private static final long IDLE_TIMEOUT      = 1000L;
  private static final long ABANDONED_TIMEOUT = 2000L;

  @Test
  void testZeroIdleEvictionTimeout() throws Exception {
    var            factory  = new TestPooledObjectFactory();
    var            pool     = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE, 0, ABANDONED_TIMEOUT, factory);
    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    pool.returnObject(borrowed);

    // Wait for a short time, should not evict
    Thread.sleep(IDLE_TIMEOUT * 3);

    // Borrow again should return the same object
    TestPoolEntity borrowedAgain = pool.borrowObject(Duration.ofSeconds(1));
    assertThat(borrowedAgain).isSameAs(borrowed);
  }

  @Test
  void testBorrowInterruptedException() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    // First fill the pool to max capacity
    var borrowed = new TestPoolEntity[MAX_POOL_SIZE];
    for (int i = 0; i < MAX_POOL_SIZE; i++) {
      borrowed[i] = pool.borrowObject(Duration.ofSeconds(1));
    }

    ExecutorService executor = Executors.newSingleThreadExecutor();
    CountDownLatch  latch    = new CountDownLatch(1);

    executor.submit(() -> {
      try {
        pool.borrowObject(Duration.ofSeconds(10));
        fail("Should have been interrupted");
      } catch (InterruptedException e) {
        latch.countDown();
      } catch (PoolResourceException e) {
        fail("Should not throw PoolResourceException");
      }
    });

    Thread.sleep(100);
    executor.shutdownNow();
    assertThat(latch.await(2, TimeUnit.SECONDS))
        .as("Interrupted exception should have been thrown")
        .isTrue();
    executor.awaitTermination(1, TimeUnit.SECONDS);

    // Cleanup
    for (var obj : borrowed) {
      pool.returnObject(obj);
    }
  }

  @Test
  void testObjectValidationFailureDuringBorrowAfterIdle() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    // Borrow and return an object
    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    pool.returnObject(borrowed);

    // Wait for idle timeout
    Thread.sleep(IDLE_TIMEOUT * 2);

    // Set validation to fail before next borrow
    factory.setFailValidationForBorrow(true);

    // Next borrow should fail validation and throw exception
    assertThatThrownBy(() -> pool.borrowObject(Duration.ofSeconds(1)))
        .isInstanceOf(PoolResourceException.class);

    assertThat(factory.getValidationForBorrowFailCount().get())
        .as("Validation should fail exactly once during borrow")
        .isEqualTo(1 + 5);// since it would try 5 times( pool size) before giving up
  }

  @Test
  void testReturnSameObjectTwice() throws Exception {
    var            factory  = new TestPooledObjectFactory();
    var            pool     = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);
    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    pool.returnObject(borrowed);

    Thread.sleep(1000);
    // Second return should be logged but not throw but here
    // it is assumed the object would be destroyed and any connections it would hold will be closed
    pool.returnObject(borrowed);

    // Should return new object as old object is no longer valid
    TestPoolEntity borrowedAgain = pool.borrowObject(Duration.ofSeconds(1));
    assertThat(borrowedAgain).isNotSameAs(borrowed); // issue
  }

  @Test
  void testConcurrentReturn() throws Exception {
    var            factory  = new TestPooledObjectFactory();
    var            pool     = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);
    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));

    // Start two threads trying to return the same object
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch  = new CountDownLatch(2);

    for (int i = 0; i < 2; i++) {
      new Thread(() -> {
        try {
          startLatch.await();
          pool.returnObject(borrowed);
        } catch (Exception e) {
          // Expected for one thread
        } finally {
          doneLatch.countDown();
        }
      }).start();
    }

    startLatch.countDown();
    assertThat(doneLatch.await(5, TimeUnit.SECONDS))
        .as("Both threads should complete")
        .isTrue();

    // Object should be destroyed and a new one created when borrowed again
    TestPoolEntity borrowedAgain = pool.borrowObject(Duration.ofSeconds(1));
    assertThat(borrowedAgain).isNotSameAs(borrowed);
  }

  @Test
  void testConcurrentBorrowWithSlowValidation() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(2, 0, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    // Make validation slow
    factory.setValidationDelayMillis(300); // 300ms validation delay

    // Start two concurrent borrows
    ExecutorService executor   = Executors.newFixedThreadPool(2);
    CountDownLatch  startLatch = new CountDownLatch(1);

    Future<TestPoolEntity> future1 = executor.submit(() -> {
      startLatch.await();
      return pool.borrowObject(Duration.ofSeconds(2));
    });

    Future<TestPoolEntity> future2 = executor.submit(() -> {
      startLatch.await();
      return pool.borrowObject(Duration.ofSeconds(2));
    });

    // Start both threads simultaneously
    startLatch.countDown();

    // Both should succeed despite slow validation
    TestPoolEntity entity1 = future1.get(3, TimeUnit.SECONDS);
    TestPoolEntity entity2 = future2.get(3, TimeUnit.SECONDS);

    assertThat(entity1)
        .as("First entity should not be null")
        .isNotNull();
    assertThat(entity2)
        .as("Second entity should not be null")
        .isNotNull();
    assertThat(entity1.getEntityId())
        .as("Entities should have different IDs")
        .isNotEqualTo(entity2.getEntityId());

    // Cleanup
    executor.shutdown();
    factory.setValidationDelayMillis(0);
    pool.returnObject(entity1);
    pool.returnObject(entity2);
  }
}
