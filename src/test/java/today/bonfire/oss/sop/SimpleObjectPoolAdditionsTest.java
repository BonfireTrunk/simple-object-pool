package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolInterruptedException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static today.bonfire.oss.sop.TestSupport.awaitUntil;

/** Additive API and observability promises for 3.0.0. */
@Timeout(30)
class SimpleObjectPoolAdditionsTest {

  private static final Duration AWAIT = Duration.ofSeconds(5);

  private static SimpleObjectPoolConfig.Builder config() {
    return SimpleObjectPoolConfig.builder().maxPoolSize(1).evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE);
  }

  @Test
  void sharedConfigCanHaveDistinctPoolNames() {
    var config  = config().poolName("config-name").build();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory();
    try (var first = new SimpleObjectPool<>("first", config, factory);
         var second = new SimpleObjectPool<>("second", config, factory);
         var legacy = new SimpleObjectPool<>(config, factory)) {
      assertThat(first.toString()).contains("name=first,");
      assertThat(second.toString()).contains("name=second,");
      assertThat(legacy.toString()).contains("name=config-name,");
      assertThat(first.config()).isSameAs(second.config());
      first.close();
      second.close();
      assertThatThrownBy(first::borrowObject).hasMessageContaining("Pool first is closed");
      assertThatThrownBy(second::borrowObject).hasMessageContaining("Pool second is closed");
    }
  }

  @Test
  void explicitPoolNameMustNotBeNullOrBlank() {
    var config  = config().build();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory();
    for (String name : new String[]{null, "", " \t\n"}) {
      assertThatThrownBy(() -> new SimpleObjectPool<>(name, config, factory)).isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Test
  void successfulParkedBorrowUpdatesWaitStatisticsWhenItFinishes() throws Exception {
    try (var pool = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      var held    = pool.borrowObject();
      var got     = new AtomicReference<TestPoolObject>();
      var failure = new AtomicReference<Throwable>();
      Thread waiter = Thread.ofVirtual().start(() -> {
        try {
          got.set(pool.borrowObject(Duration.ofSeconds(-1)));
        } catch (Throwable t) {
          failure.set(t);
        }
      });
      awaitUntil("borrower parked", AWAIT, () -> pool.waitingCount() == 1 && waiter.getState() == Thread.State.WAITING);
      assertThat(pool.stats().waits()).as("unfinished borrow is not yet counted").isZero();
      pool.returnObject(held);
      waiter.join(5000);
      assertThat(waiter.isAlive()).isFalse();
      assertThat(failure.get()).isNull();
      assertThat(got.get()).isSameAs(held);
      assertThat(pool.stats().waits()).isEqualTo(1);
      assertThat(pool.stats().totalWait()).isPositive();
      assertThat(pool.stats().maxWait()).isEqualTo(pool.stats().totalWait());
      pool.returnObject(got.get());
    }
  }

  @Test
  void timedOutParkedBorrowsAccumulateTotalAndMaximumWait() {
    try (var pool = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      var held = pool.borrowObject();
      assertThatThrownBy(() -> pool.borrowObject(Duration.ofMillis(30))).isInstanceOf(PoolTimeoutException.class);
      var first = pool.stats();
      assertThat(first.waits()).isEqualTo(1);
      assertThat(first.totalWait()).isPositive();
      assertThat(first.maxWait()).isEqualTo(first.totalWait());
      assertThatThrownBy(() -> pool.borrowObject(Duration.ofMillis(50))).isInstanceOf(PoolTimeoutException.class);
      var second = pool.stats();
      assertThat(second.waits()).isEqualTo(2);
      assertThat(second.totalWait()).isGreaterThan(first.totalWait());
      assertThat(second.maxWait()).isEqualTo(first.maxWait().compareTo(second.totalWait().minus(first.totalWait())) >= 0
                                           ? first.maxWait() : second.totalWait().minus(first.totalWait()));
      pool.returnObject(held);
    }
  }

  @Test
  void borrowsWithoutParkingLeaveWaitStatisticsAtZero() {
    try (var pool = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      var obj = pool.borrowObject(Duration.ZERO);
      pool.returnObject(obj);
      assertThat(pool.borrowObject(Duration.ZERO)).isSameAs(obj);
      assertThatThrownBy(() -> pool.borrowObject(Duration.ZERO)).isInstanceOf(PoolTimeoutException.class);
      assertThat(pool.stats().waits()).isZero();
      assertThat(pool.stats().totalWait()).isEqualTo(Duration.ZERO);
      assertThat(pool.stats().maxWait()).isEqualTo(Duration.ZERO);
      pool.returnObject(obj);
    }
  }

  @Test
  void interruptedBorrowThrowsTheSpecificTypeAndRestoresTheFlag() throws Exception {
    try (var pool = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      var held     = pool.borrowObject();
      var failure  = new AtomicReference<Throwable>();
      var restored = new AtomicBoolean();
      Thread waiter = Thread.ofVirtual().start(() -> {
        try {
          pool.borrowObject(Duration.ofSeconds(-1));
        } catch (Throwable t) {
          failure.set(t);
          restored.set(Thread.currentThread().isInterrupted());
        }
      });
      awaitUntil("borrower parked", AWAIT, () -> pool.waitingCount() == 1 && waiter.getState() == Thread.State.WAITING);
      waiter.interrupt();
      waiter.join(5000);
      assertThat(waiter.isAlive()).isFalse();
      assertThat(failure.get()).isInstanceOf(PoolInterruptedException.class).hasCauseInstanceOf(InterruptedException.class);
      assertThat(restored.get()).isTrue();
      assertThat(pool.stats().waits()).isEqualTo(1);
      assertThat(pool.stats().totalWait()).isPositive();
      pool.returnObject(held);
    }
  }

  @Test
  void prepareRethrowsTheFirstCreationFailureAndKeepsEarlierObjects() {
    var boom    = new IllegalStateException("second creation failed");
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory();
    var failing = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public TestPoolObject createObject() {
        if (createCalls.incrementAndGet() == 2) throw boom;
        return factory.createObject();
      }
    };
    try (var pool = new SimpleObjectPool<>(config().maxPoolSize(3).minPoolSize(3).durationBetweenEvictionsRuns(Duration.ofDays(1)).build(), failing)) {
      assertThatThrownBy(pool::prepare).isSameAs(boom);
      assertThat(failing.createCalls.get()).isEqualTo(2);
      assertThat(pool.idleObjectCount()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isEqualTo(1);
    }
  }
}
