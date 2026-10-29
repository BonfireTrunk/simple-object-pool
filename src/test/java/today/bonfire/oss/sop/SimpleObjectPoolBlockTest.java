package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolClosedException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class SimpleObjectPoolBlockTest {

  private static final Duration AWAIT = Duration.ofSeconds(10);

  @Test
  void testBlockWhenExhaustedFalse() {
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .waitingForObjectTimeout(Duration.ZERO)
                                       .build();

    try (var pool = new SimpleObjectPool<>(config, factory)) {
      TestPoolObject obj1 = pool.borrowObject();
      assertThat(obj1).isNotNull();

      // ZERO means fail fast: no parking, so no waiter is ever registered.
      long start = System.nanoTime();
      assertThatThrownBy(pool::borrowObject)
          .isInstanceOf(PoolTimeoutException.class)
          .hasMessageContaining("Timed out");
      long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

      // Generous bound (default wait is 10s); the point is that it did not wait.
      assertThat(durationMs).isLessThan(2000);
      assertThat(pool.waitingCount()).isZero();
    }
  }

  @Test
  void testBlockWhenExhaustedTrue() {
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .waitingForObjectTimeout(Duration.ofMillis(500))
                                       .build();

    try (var pool = new SimpleObjectPool<>(config, factory)) {
      pool.borrowObject();

      long start = System.nanoTime();
      assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolTimeoutException.class);
      long durationMs = Duration.ofNanos(System.nanoTime() - start).toMillis();

      assertThat(durationMs).isGreaterThanOrEqualTo(500);
      assertThat(pool.waitingCount()).isZero();
    }
  }

  @Test
  void testNegativeTimeoutWaitsUntilObjectReturned() throws Exception {
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .waitingForObjectTimeout(Duration.ofSeconds(-1))
                                       .build();

    try (var pool = new SimpleObjectPool<>(config, factory)) {
      TestPoolObject obj1 = pool.borrowObject();

      CompletableFuture<TestPoolObject> waiter = CompletableFuture.supplyAsync(pool::borrowObject);
      TestSupport.awaitUntil("borrower parked", AWAIT, () -> pool.waitingCount() == 1);
      assertThat(waiter).isNotDone();

      pool.returnObject(obj1);
      assertThat(waiter.get(10, java.util.concurrent.TimeUnit.SECONDS)).isSameAs(obj1);
      TestSupport.awaitUntil("waiter count back to 0", AWAIT, () -> pool.waitingCount() == 0);
    }
  }

  @Test
  void testCloseWakesInfiniteWaiter() {
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .waitingForObjectTimeout(Duration.ofSeconds(-1))
                                       .build();

    var pool = new SimpleObjectPool<>(config, factory);
    try {
      pool.borrowObject();
      CompletableFuture<TestPoolObject> waiter = CompletableFuture.supplyAsync(pool::borrowObject);
      TestSupport.awaitUntil("borrower parked", AWAIT, () -> pool.waitingCount() == 1);

      pool.close();

      assertThatThrownBy(() -> waiter.get(10, java.util.concurrent.TimeUnit.SECONDS))
          .hasRootCauseInstanceOf(PoolClosedException.class);
    } finally {
      pool.forceClose();
    }
  }
}
