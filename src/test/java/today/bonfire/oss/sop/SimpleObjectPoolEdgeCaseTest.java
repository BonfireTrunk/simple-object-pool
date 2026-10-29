package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolClosedException;
import today.bonfire.oss.sop.exceptions.PoolException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolObjectValidationException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class SimpleObjectPoolEdgeCaseTest {

  private static final int      MAX_POOL_SIZE = 5;
  private static final Duration AWAIT         = Duration.ofSeconds(10);

  @Test
  void testZeroIdleEvictionTimeout() {
    var factory = new TestPooledObjectFactory();
    // minPoolSize is 0: v3 never evicts below minPoolSize, so a zero idle timeout needs min 0 to take effect.
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(MAX_POOL_SIZE)
                                                                 .minPoolSize(0)
                                                                 .objEvictionTimeout(Duration.ZERO)
                                                                 .durationBetweenEvictionsRuns(Duration.ofMillis(50))
                                                                 .build(), factory)) {
      TestPoolObject borrowed = pool.borrowObject();
      pool.returnObject(borrowed);

      TestSupport.awaitUntil("idle object evicted", AWAIT, () -> pool.idleObjectCount() == 0 && pool.currentPoolSize() == 0);
      assertThat(borrowed.isDestroyed()).isTrue();

      TestPoolObject borrowedAgain = pool.borrowObject();
      assertThat(borrowedAgain).isNotSameAs(borrowed);
      assertThat(borrowedAgain.seq()).isNotEqualTo(borrowed.seq());
    }
  }

  @Test
  void testBorrowInterruptedException() throws Exception {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(MAX_POOL_SIZE)
                                                                 .waitingForObjectTimeout(Duration.ofSeconds(20))
                                                                 .build(), factory)) {
      var borrowed = new TestPoolObject[MAX_POOL_SIZE];
      for (int i = 0; i < MAX_POOL_SIZE; i++) {
        borrowed[i] = pool.borrowObject();
      }

      AtomicReference<Throwable> thrown            = new AtomicReference<>();
      AtomicReference<Boolean>   interruptRestored = new AtomicReference<>();
      Thread t = Thread.ofVirtual().start(() -> {
        try {
          pool.borrowObject();
        } catch (Throwable e) {
          thrown.set(e);
          interruptRestored.set(Thread.currentThread().isInterrupted());
        }
      });

      TestSupport.awaitUntil("borrower parked", AWAIT, () -> pool.waitingCount() == 1);
      t.interrupt();
      t.join(10_000);

      assertThat(t.isAlive()).isFalse();
      assertThat(thrown.get()).isInstanceOf(PoolException.class)
                              .hasCauseInstanceOf(InterruptedException.class);
      assertThat(thrown.get().getMessage()).contains("Interrupted");
      assertThat(interruptRestored.get()).as("interrupt flag restored").isTrue();
      assertThat(pool.waitingCount()).isZero();

      for (var obj : borrowed) {
        pool.returnObject(obj);
      }
    }
  }

  @Test
  void testObjectValidationFailureDuringBorrowAfterIdle() throws Exception {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(MAX_POOL_SIZE)
                                                                 .maxRetries(2)
                                                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                                                 .testOnBorrow(true)
                                                                 .build(), factory)) {
      TestPoolObject borrowed = pool.borrowObject();
      pool.returnObject(borrowed);

      factory.setFailValidationForBorrow(true);

      // The invalid idle object is destroyed, and every fresh replacement fails the same borrow
      // test, which counts as a failed creation until retries are exhausted.
      assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolObjectValidationException.class);

      assertThat(factory.getValidationForBorrowFailCount().get())
          .as("The idle object plus one fresh object per creation attempt")
          .isEqualTo(1 + 3);
      assertThat(borrowed.isDestroyed()).isTrue();
      assertThat(pool.currentPoolSize()).isZero();

      factory.setFailValidationForBorrow(false);
      pool.returnObject(pool.borrowObject());
    }
  }

  @Test
  void testReturnSameObjectTwice() {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(2)
                                                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                                                 .build(), factory)) {
      TestPoolObject borrowed = pool.borrowObject();
      pool.returnObject(borrowed);

      // v3: a second return is rejected instead of silently ignored.
      assertThatThrownBy(() -> pool.returnObject(borrowed)).isInstanceOf(PoolObjectException.class);

      assertThat(pool.idleObjectCount()).isEqualTo(1);
      TestPoolObject borrowedAgain = pool.borrowObject();
      assertThat(borrowedAgain).isSameAs(borrowed);
    }
  }

  @Test
  void testReturnForeignObjectRejected() {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(2).build(), factory)) {
      assertThatThrownBy(() -> pool.returnObject(new TestPoolObject())).isInstanceOf(PoolObjectException.class);
    }
  }

  @Test
  void testBorrowAfterCloseThrows() {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(2).build(), factory);
    pool.close();
    assertThat(pool.isClosed()).isTrue();
    assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolClosedException.class);
  }

  @Test
  void testCloseDestroysIdleAndBorrowedOnReturn() {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(3).build(), factory);

    TestPoolObject kept = pool.borrowObject();
    TestPoolObject idle = pool.borrowObject();
    pool.returnObject(idle);

    pool.close();
    assertThat(idle.isDestroyed()).as("idle destroyed on close").isTrue();
    assertThat(kept.isDestroyed()).as("borrowed survives close").isFalse();

    pool.returnObject(kept);
    assertThat(kept.isDestroyed()).as("borrowed destroyed on return").isTrue();
    assertThat(pool.currentPoolSize()).isZero();
  }

  @Test
  void testForceCloseDestroysBorrowed() {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(3).build(), factory);

    TestPoolObject kept = pool.borrowObject();
    pool.forceClose();

    assertThat(kept.isDestroyed()).isTrue();
    assertThat(pool.currentPoolSize()).isZero();
  }

  @Test
  void testConstructorCreatesNothingAndPrepareFillsMinPoolSize() {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(4).minPoolSize(2).build(), factory)) {
      assertThat(factory.getIdCounter().get()).isZero();
      assertThat(pool.prepare()).isEqualTo(2);
      assertThat(pool.idleObjectCount()).isEqualTo(2);
      assertThat(pool.addObjects(10)).as("capped at maxPoolSize").isEqualTo(2);
      assertThat(pool.currentPoolSize()).isEqualTo(4);
    }
  }

  @Test
  void testAbandonedObjectDestroyedWhenDetectionEnabled() throws Exception {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(2)
                                                                 .waitingForObjectTimeout(Duration.ofMillis(50))
                                                                 .abandonedTimeout(Duration.ofMillis(100))
                                                                 .durationBetweenAbandonCheckRuns(Duration.ofMillis(50))
                                                                 .build(), factory)) {
      TestPoolObject leaked = pool.borrowObject();
      TestSupport.awaitUntil("abandoned object destroyed", AWAIT, leaked::isDestroyed);
      assertThat(pool.borrowedObjectsCount()).isZero();
      // The holder returning a reclaimed object is tolerated silently, like commons-pool2.
      assertThatCode(() -> pool.returnObject(leaked)).doesNotThrowAnyException();
      assertThat(pool.currentPoolSize()).isZero();
      // But a second return of the same object is a genuine foreign return.
      assertThatThrownBy(() -> pool.returnObject(leaked)).isInstanceOf(PoolObjectException.class);
    }
  }

  @Test
  void testAbandonDetectionOffByDefault() {
    var factory = new TestPooledObjectFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().maxPoolSize(2).build(), factory)) {
      assertThat(pool.config().abandonedTimeout()).isNull();
      TestPoolObject held = pool.borrowObject();
      TestSupport.assertNeverDuring("held object destroyed", Duration.ofMillis(300), held::isDestroyed);
      pool.returnObject(held);
    }
  }
}
