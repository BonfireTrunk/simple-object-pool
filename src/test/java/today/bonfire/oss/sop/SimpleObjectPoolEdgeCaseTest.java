package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import today.bonfire.oss.sop.exceptions.PoolException;

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
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .objEvictionTimeout(Duration.ZERO)
                                                            .durationBetweenEvictionsRuns(Duration.ofMillis(100))
                                                            .build(), factory);

    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed);

    // Wait for multiple eviction runs
    Thread.sleep(200);

    // Borrow again should return the same object eviction is immediate
    TestPoolObject borrowedAgain = pool.borrowObject();
    assertThat(borrowedAgain).isNotEqualTo(borrowed);
    pool.close();
  }

  @Test
  void testBorrowInterruptedException() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .build(), factory);

    // First fill the pool to max capacity
    var borrowed = new TestPoolObject[MAX_POOL_SIZE];
    for (int i = 0; i < MAX_POOL_SIZE; i++) {
      borrowed[i] = pool.borrowObject();
    }

    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch latch = new CountDownLatch(1);

    Future<?> future = executor.submit(() -> {
      try {
        pool.borrowObject();
        fail("Should have been interrupted");
      } catch (PoolException e) {
        fail("Should not throw PoolException");
      }
    });

    Thread.sleep(100);
    future.cancel(true);
    executor.shutdownNow();

    assertThat(latch.await(2, TimeUnit.SECONDS))
        .as("Interrupted exception should have been thrown")
        .isTrue();
    assertThat(executor.awaitTermination(1, TimeUnit.SECONDS))
        .as("Executor should terminate")
        .isTrue();

    // Cleanup
    for (var obj : borrowed) {
      pool.returnObject(obj);
    }
    pool.close();
  }

  @Test
  void testObjectValidationFailureDuringBorrowAfterIdle() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .testWhileIdle(true)
                                                            .build(), factory);

    // Borrow and return an object
    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed);

    // Set validation to fail before next borrow
    factory.setFailValidationForBorrow(true);

    // Next borrow should fail validation and throw exception
    assertThatThrownBy(() -> pool.borrowObject())
        .isInstanceOf(PoolException.class);

    assertThat(factory.getValidationForBorrowFailCount().get())
        .as("Validation should fail exactly once during borrow")
        .isEqualTo(1 + MAX_POOL_SIZE); // will try MAX_POOL_SIZE times before giving up

    pool.close();
  }

  @Test
  void testReturnSameObjectTwice() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .build(), factory);

    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed);

    // Second return of same object should be ignored
    pool.returnObject(borrowed);

    // Should return same object as it's still valid and in the pool
    TestPoolObject borrowedAgain = pool.borrowObject();
    assertThat(borrowedAgain).isSameAs(borrowed);
    pool.close();
  }

}
