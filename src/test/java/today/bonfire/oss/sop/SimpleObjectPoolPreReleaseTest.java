package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static today.bonfire.oss.sop.TestSupport.sleepQuietly;

/** Runs alone because the stress workers would affect JVM-wide thread-count tests. */
@Isolated
@Timeout(30)
class SimpleObjectPoolPreReleaseTest {

  private static final Duration AWAIT = Duration.ofSeconds(5);

  private static SimpleObjectPoolConfig.Builder config(int max) {
    return SimpleObjectPoolConfig.builder()
                                 .maxPoolSize(max)
                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE);
  }

  @Test
  void losingForceCloseReclaimDoesNotEraseTheAbandonChecksMarker() throws Exception {
    var firstDestroy  = new CountDownLatch(1);
    var secondDestroy = new CountDownLatch(1);
    var thirdDestroy  = new CountDownLatch(1);
    var releaseFirst  = new CountDownLatch(1);
    var releaseSecond = new CountDownLatch(1);
    var calls         = new AtomicInteger();
    var third         = new AtomicReference<TestPoolObject>();
    var failure       = new AtomicReference<Throwable>();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public void destroyObject(TestPoolObject obj) {
        int call = calls.incrementAndGet();
        if (call == 1) {
          firstDestroy.countDown();
          await(releaseFirst);
        } else if (call == 2) {
          secondDestroy.countDown();
          await(releaseSecond);
        }
        super.destroyObject(obj);
        if (call == 3) {
          third.set(obj);
          thirdDestroy.countDown();
        }
      }
    };
    try (var pool = new SimpleObjectPool<>(config(3).abandonedTimeout(Duration.ofMillis(30))
                                               .durationBetweenAbandonCheckRuns(Duration.ofMillis(10)).build(), factory)) {
      var objects = new TestPoolObject[]{pool.borrowObject(), pool.borrowObject(), pool.borrowObject()};
      Thread closer = null;
      try {
        assertThat(firstDestroy.await(5, TimeUnit.SECONDS)).isTrue();
        closer = Thread.ofVirtual().start(() -> run(pool::forceClose, failure));
        assertThat(secondDestroy.await(5, TimeUnit.SECONDS)).isTrue();
        // Both iterators have advanced ahead of their blocked destroy callbacks.
        releaseFirst.countDown();
        assertThat(thirdDestroy.await(5, TimeUnit.SECONDS)).isTrue();
      } finally {
        releaseFirst.countDown();
        releaseSecond.countDown();
        if (closer != null) closer.join(5000);
      }
      assertThat(failure.get()).isNull();
      assertThatCode(() -> pool.returnObject(third.get())).as("late return after a losing reclaim").doesNotThrowAnyException();
      for (var object : objects) {
        if (object != third.get()) pool.returnObject(object);
      }
      assertThat(calls.get()).isEqualTo(3);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void abandonCheckNeverDestroysANewBorrowBeforeItsTimeout() throws Exception {
    long timeout = Duration.ofMillis(1).toNanos();
    var starts   = new ConcurrentHashMap<TestPoolObject, Long>();
    var early    = new AtomicInteger();
    var failure  = new AtomicReference<Throwable>();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public void destroyObject(TestPoolObject obj) {
        Long start = starts.get(obj);
        if (start != null && System.nanoTime() - start < timeout) early.incrementAndGet();
        super.destroyObject(obj);
      }
    };
    try (var pool = new SimpleObjectPool<>(config(4)
                                               .lifo(true)
                                               .abandonedTimeout(Duration.ofNanos(timeout))
                                               .durationBetweenAbandonCheckRuns(Duration.ofNanos(100_000))
                                               .build(), factory)) {
      var stop    = new AtomicBoolean();
      var workers = new ArrayList<Thread>();
      for (int i = 0; i < 4; i++) {
        workers.add(Thread.ofPlatform().start(() -> {
          try {
            while (!stop.get()) {
              long           start = System.nanoTime();
              TestPoolObject obj   = pool.borrowObject();
              starts.put(obj, start);
              LockSupport.parkNanos(timeout);
              pool.returnObject(obj);
            }
          } catch (Throwable t) {
            failure.compareAndSet(null, t);
          }
        }));
      }
      try {
        sleepQuietly(1900);
      } finally {
        stop.set(true);
        for (Thread worker : workers) worker.join(5000);
      }
      assertThat(failure.get()).as("holder failure").isNull();
      assertThat(early.get()).as("destroyed before the current borrow timeout").isZero();
    }
  }

  @Test
  void clearStartingBeforeEvictionStillDestroysTheTestedObject() throws Exception {
    var destroyingX = new CountDownLatch(1);
    var releaseX    = new CountDownLatch(1);
    var testingB    = new CountDownLatch(1);
    var releaseB    = new CountDownLatch(1);
    var b           = new TestPoolObject();
    var x           = new TestPoolObject();
    var creates     = new AtomicInteger();
    var failure     = new AtomicReference<Throwable>();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public TestPoolObject createObject() {
        return creates.getAndIncrement() == 0 ? b : x;
      }

      @Override
      public void destroyObject(TestPoolObject obj) {
        if (obj == x) {
          destroyingX.countDown();
          await(releaseX);
        }
        super.destroyObject(obj);
      }

      @Override
      public boolean isObjectValid(TestPoolObject obj) {
        if (obj == b) {
          testingB.countDown();
          await(releaseB);
        }
        return true;
      }
    };
    try (var pool = new SimpleObjectPool<>(config(2).testWhileIdle(true).objEvictionTimeout(Duration.ofDays(1)).build(), factory)) {
      pool.addObject();
      pool.addObject();
      Thread clearer = Thread.ofVirtual().start(() -> run(pool::destroyAllIdleObjects, failure));
      Thread evictor = null;
      try {
        assertThat(destroyingX.await(5, TimeUnit.SECONDS)).isTrue();
        evictor = Thread.ofVirtual().start(() -> run(pool::evict, failure));
        assertThat(testingB.await(5, TimeUnit.SECONDS)).isTrue();
        releaseX.countDown();
        clearer.join(5000);
        assertThat(clearer.isAlive()).isFalse();
        assertThat(pool.idleObjectCount()).isZero();
      } finally {
        releaseX.countDown();
        releaseB.countDown();
        clearer.join(5000);
        if (evictor != null) evictor.join(5000);
      }
      assertThat(failure.get()).isNull();
      assertThat(b.isDestroyed()).as("B must not survive the overlapping clear").isTrue();
      assertThat(pool.idleObjectCount()).isZero();
    }
  }

  @Test
  void zeroDelayRetriesStopAtTheBorrowDeadline() {
    assertRetriesStopAtDeadline(Duration.ZERO, 60, 80, false);
  }

  @Test
  void positiveDelayDoesNotMakeAnAttemptAfterTheDeadline() {
    assertRetriesStopAtDeadline(Duration.ofSeconds(1), 0, 40, true);
  }

  private static void assertRetriesStopAtDeadline(Duration delay, long workMillis, long waitMillis, boolean oneAttempt) {
    var boom     = new IllegalStateException("creation failed");
    var attempts = new AtomicInteger();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public TestPoolObject createObject() {
        attempts.incrementAndGet();
        sleepQuietly(workMillis);
        throw boom;
      }
    };
    try (var pool = new SimpleObjectPool<>(config(1).maxRetries(3).retryCreationDelay(delay).build(), factory)) {
      assertThatThrownBy(() -> pool.borrowObject(Duration.ofMillis(waitMillis))).isSameAs(boom);
      if (oneAttempt) {
        assertThat(attempts.get()).as("creation attempts").isEqualTo(1);
      } else {
        assertThat(attempts.get()).as("creation attempts").isLessThan(4);
      }
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void retryAfterAnIdleObjectFailsValidationStillStopsAtTheDeadline() throws Exception {
    var boom     = new IllegalStateException("creation failed");
    var creating = new CountDownLatch(1);
    var returned = new CountDownLatch(1);
    var attempts = new AtomicInteger();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public TestPoolObject createObject() {
        if (attempts.incrementAndGet() == 1) return super.createObject();
        creating.countDown();
        await(returned);
        sleepQuietly(60); // fail only after the borrower's deadline has passed
        throw boom;
      }

      @Override
      public boolean isObjectValidForBorrow(TestPoolObject obj) {
        return false;
      }
    };
    try (var pool = new SimpleObjectPool<>(config(2).maxRetries(1).testOnBorrow(true).build(), factory)) {
      TestPoolObject held     = pool.borrowObject();
      var            thrown   = new AtomicReference<Throwable>();
      Thread         borrower =Thread.ofVirtual().start(() -> run(() -> pool.borrowObject(Duration.ofMillis(30)), thrown));
      assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
      pool.returnObject(held); // idle while the creation fails; the borrower polls it and it fails validation
      returned.countDown();
      borrower.join(5000);
      assertThat(thrown.get()).isSameAs(boom);
      assertThat(attempts.get()).as("no creation attempt after the deadline").isEqualTo(2);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void fatalDestroyAfterBorrowValidationReleasesTheSlotOnce() {
    assertFatalDestroyReleasesOnce(true);
  }

  @Test
  void fatalDestroyAfterReturnValidationReleasesTheSlotOnce() {
    assertFatalDestroyReleasesOnce(false);
  }

  private static void assertFatalDestroyReleasesOnce(boolean onBorrow) {
    var fatal = new OutOfMemoryError("test");
    var fail  = new AtomicBoolean(true);
    var calls = new AtomicInteger();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public boolean isObjectValidForBorrow(TestPoolObject obj) {
        return !fail.get();
      }

      @Override
      public boolean isObjectValid(TestPoolObject obj) {
        return !fail.get();
      }

      @Override
      public void destroyObject(TestPoolObject obj) {
        calls.incrementAndGet();
        if (fail.get()) throw fatal;
        super.destroyObject(obj);
      }
    };
    try (var pool = new SimpleObjectPool<>(config(1).testOnBorrow(onBorrow).testOnReturn(!onBorrow).build(), factory)) {
      if (onBorrow) {
        pool.addObject();
        assertThatThrownBy(pool::borrowObject).isSameAs(fatal);
      } else {
        TestPoolObject obj = pool.borrowObject();
        assertThatThrownBy(() -> pool.returnObject(obj)).isSameAs(fatal);
      }
      assertThat(calls.get()).as("destroy calls for the rejected object").isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
      fail.set(false);
      TestPoolObject live = pool.borrowObject();
      assertThatThrownBy(() -> pool.borrowObject(Duration.ZERO)).isInstanceOf(PoolTimeoutException.class);
      assertThat(pool.currentPoolSize()).isEqualTo(1);
      pool.returnObject(live);
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("gate timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError(e);
    }
  }

  private static void run(Runnable body, AtomicReference<Throwable> failure) {
    try {
      body.run();
    } catch (Throwable t) {
      failure.compareAndSet(null, t);
    }
  }
}
