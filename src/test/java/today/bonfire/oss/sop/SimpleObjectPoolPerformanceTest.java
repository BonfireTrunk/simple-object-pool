package today.bonfire.oss.sop;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j @ExtendWith(MockitoExtension.class)
class SimpleObjectPoolPerformanceTest {

  private static final int  MAX_POOL_SIZE       = 10;
  private static final int  MIN_POOL_SIZE       = 5;
  private static final long IDLE_TIMEOUT        = 10000L;
  private static final long ABANDONED_TIMEOUT   = 20000L;
  private static final int  NUM_BORROW_REQUESTS = 10000;


  @Test
  void testHighConcurrencyBorrowAndReturn() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(MAX_POOL_SIZE)
                                                            .minPoolSize(MIN_POOL_SIZE)
                                                            .testWhileIdle(false)
                                                            .waitingForObjectTimeout(Duration.ofSeconds(20))
                                                            .objEvictionTimeout(Duration.ofMillis(IDLE_TIMEOUT))
                                                            .abandonedTimeout(Duration.ofMillis(ABANDONED_TIMEOUT))
                                                            .build(), factory);

    ExecutorService               executor      = Executors.newVirtualThreadPerTaskExecutor();
    List<CompletableFuture<Void>> futures       = new ArrayList<>();
    AtomicInteger                 borrowCounter = new AtomicInteger(0);

    for (int i = 0; i < NUM_BORROW_REQUESTS; i++) {
      futures.add(CompletableFuture.runAsync(() -> {
        try {
          var entity = pool.borrowObject();
          assertThat(entity).isNotNull();
          Thread.sleep(10);
          borrowCounter.incrementAndGet();
          // log.debug("Borrowed object with id {}", borrowCounter.incrementAndGet());
          pool.returnObject(entity);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }, executor));
    }
    assertThat(factory.getIdCounter().get()).isEqualTo(MAX_POOL_SIZE);
    CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(30, TimeUnit.SECONDS);
    assertThat(pool.numOfTimesBorrowedFromPool()).isEqualTo(NUM_BORROW_REQUESTS);
    log.info("pool size: {}, times borrowed: {}", pool.currentPoolSize(), pool.numOfTimesBorrowedFromPool());
    for (var i = 0; i < MAX_POOL_SIZE; i++) {
      var       entity        = pool.borrowObject();
      final var timesBorrowed = pool.numOfTimesBorrowed(entity.getEntityId());
      log.debug("Borrowed object's count {} with id {}", timesBorrowed, entity.getEntityId());
    }
    assertThat(borrowCounter.get()).isEqualTo(NUM_BORROW_REQUESTS);
    pool.close();
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
  }
}
