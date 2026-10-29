package today.bonfire.oss.sop;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("performance")
@Timeout(60)
class SimpleObjectPoolPerformanceTest {

  private static final Logger log = LoggerFactory.getLogger(SimpleObjectPoolPerformanceTest.class);

  private static final int NUM_BORROW_REQUESTS = 10000;
  private static final int MAX_POOL_SIZE       = 10;
  private static final int MIN_POOL_SIZE       = 5;

  @Test
  void testHighConcurrencyBorrowAndReturn() throws Exception {
    var factory = new TestPooledObjectFactory();
    factory.setValidationDelayMillis(5);
    var pool = new SimpleObjectPool<>(
        SimpleObjectPoolConfig.builder()
                              .maxPoolSize(MAX_POOL_SIZE)
                              .minPoolSize(MIN_POOL_SIZE)
                              .testWhileIdle(false)
                              .testOnBorrow(true)
                              .fairness(true)
                              .waitingForObjectTimeout(Duration.ofSeconds(60))
                              .objEvictionTimeout(Duration.ofSeconds(10))
                              .abandonedTimeout(Duration.ofSeconds(20))
                              .build(), factory);

    ExecutorService               executor      = Executors.newVirtualThreadPerTaskExecutor();
    List<CompletableFuture<Void>> futures       = new ArrayList<>();
    AtomicInteger                 borrowCounter = new AtomicInteger(0);

    long startTime = System.currentTimeMillis();
    for (int i = 0; i < NUM_BORROW_REQUESTS; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          var entity = pool.borrowObject();
          assertThat(entity).isNotNull();
          TestSupport.sleepQuietly(10); // simulated work
          borrowCounter.incrementAndGet();
          pool.returnObject(entity);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor));
    }
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(50, TimeUnit.SECONDS);
    long duration = System.currentTimeMillis() - startTime;
    log.info("Total execution time: {} ms", duration);

    // Creation is parallel but capped by the CAS-reserved slot counter, so exactly maxPoolSize
    // objects exist when 10000 borrowers compete for 10 slots and nothing is destroyed.
    assertThat(factory.getIdCounter().get()).isEqualTo(MAX_POOL_SIZE);
    assertThat(pool.numOfObjectsCreated()).isEqualTo(MAX_POOL_SIZE);
    assertThat(pool.numOfTimesBorrowedFromPool()).isEqualTo(NUM_BORROW_REQUESTS);
    assertThat(borrowCounter.get()).isEqualTo(NUM_BORROW_REQUESTS);
    assertThat(pool.currentPoolSize()).isEqualTo(MAX_POOL_SIZE);
    assertThat(pool.borrowedObjectsCount()).isZero();
    assertThat(pool.waitingCount()).isZero();
    log.info("pool size: {}, times borrowed: {}", pool.currentPoolSize(), pool.numOfTimesBorrowedFromPool());

    // Borrow counts across the pool's objects add up to the total borrows.
    List<TestPoolObject> held  = new ArrayList<>();
    long                 total = 0;
    for (int i = 0; i < MAX_POOL_SIZE; i++) {
      var entity = pool.borrowObject();
      held.add(entity);
      long timesBorrowed = pool.numOfTimesBorrowed(entity);
      assertThat(timesBorrowed).isPositive();
      total += timesBorrowed;
    }
    // +MAX_POOL_SIZE for the borrows made just above
    assertThat(total).isEqualTo(NUM_BORROW_REQUESTS + MAX_POOL_SIZE);
    assertThat(held).extracting(TestPoolObject::seq).doesNotHaveDuplicates();
    for (var e : held) {
      pool.returnObject(e);
    }

    pool.close();
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }
}
