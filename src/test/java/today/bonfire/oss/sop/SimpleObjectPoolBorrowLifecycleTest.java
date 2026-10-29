package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolObjectValidationException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Activation and the borrow test on every borrow, and the {@code isBrokenBy} hook behind {@code use} and {@code apply}. */
@Timeout(30)
class SimpleObjectPoolBorrowLifecycleTest {

  private static SimpleObjectPoolConfig.Builder config() {
    return SimpleObjectPoolConfig.builder()
                                 .maxPoolSize(1)
                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                 .waitingForObjectTimeout(Duration.ofSeconds(5));
  }

  /** Records lifecycle calls in order and fails the ones a test asks it to. */
  static class RecordingFactory extends SimpleObjectPoolRaceAndGuardTest.ControlFactory {
    final List<String> calls = new CopyOnWriteArrayList<>();

    volatile Consumer<TestPoolObject>               onActivate = obj -> {};
    volatile Predicate<TestPoolObject>              borrowTest = obj -> true;
    volatile BiPredicate<TestPoolObject, Throwable> brokenBy; // null: the interface default

    @Override
    public TestPoolObject createObject() {
      TestPoolObject obj = super.createObject();
      calls.add("create " + obj.seq());
      return obj;
    }

    @Override
    public void activateObject(TestPoolObject obj) {
      calls.add("activate " + obj.seq());
      onActivate.accept(obj);
    }

    @Override
    public boolean isObjectValidForBorrow(TestPoolObject obj) {
      calls.add("test " + obj.seq());
      return borrowTest.test(obj);
    }

    @Override
    public void passivateObject(TestPoolObject obj) {
      calls.add("passivate " + obj.seq());
    }

    @Override
    public boolean isBrokenBy(TestPoolObject obj, Throwable error) {
      return brokenBy == null ? super.isBrokenBy(obj, error) : brokenBy.test(obj, error);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Activation and the borrow test
  // ---------------------------------------------------------------------------------------------

  @Test
  void freshObjectIsActivatedThenTestedLikeAnIdleOne() {
    var factory = new RecordingFactory();
    try (var pool = new SimpleObjectPool<>(config().testOnBorrow(true).build(), factory)) {
      TestPoolObject obj = pool.borrowObject();
      pool.returnObject(obj);
      pool.returnObject(pool.borrowObject());
      long s = obj.seq();
      assertThat(factory.calls).containsExactly("create " + s, "activate " + s, "test " + s, "passivate " + s,
                                                "activate " + s, "test " + s, "passivate " + s);
    }
  }

  @Test
  void prefilledObjectIsActivatedOnItsFirstBorrowNotWhenCreated() {
    var factory = new RecordingFactory();
    try (var pool = new SimpleObjectPool<>(config().minPoolSize(1).build(), factory)) {
      pool.prepare();
      assertThat(factory.calls).singleElement().asString().startsWith("create ");
      TestPoolObject obj = pool.borrowObject();
      assertThat(factory.calls).containsExactly("create " + obj.seq(), "activate " + obj.seq());
      pool.returnObject(obj);
    }
  }

  @Test
  void activationFailureOnAFreshObjectIsACreationFailure() {
    var boom    = new IllegalStateException("activation failed");
    var factory = new RecordingFactory();
    factory.onActivate = obj -> {
      throw boom;
    };
    try (var pool = new SimpleObjectPool<>(config().maxRetries(1).build(), factory)) {
      // Every attempt fails: the borrower gets the activation exception unchanged and no slot stays taken
      assertThatThrownBy(pool::borrowObject).isSameAs(boom);
      assertThat(factory.created.get()).isEqualTo(2);
      assertThat(factory.destroyed.get()).isEqualTo(2);
      assertThat(pool.currentPoolSize()).isZero();

      // Only the first fresh object fails: the retry hands out the second
      var failNext = new AtomicBoolean(true);
      factory.onActivate = obj -> {
        if (failNext.getAndSet(false)) throw boom;
      };
      TestPoolObject obj = pool.borrowObject();
      assertThat(obj.isDestroyed()).isFalse();
      assertThat(factory.created.get()).isEqualTo(4);
      assertThat(factory.destroyed.get()).isEqualTo(3);
      assertThat(pool.currentPoolSize()).isEqualTo(1);
      pool.returnObject(obj);
    }
  }

  @Test
  void freshObjectFailingTheBorrowTestRaisesValidationException() {
    var factory = new RecordingFactory();
    factory.borrowTest = obj -> false;
    try (var pool = new SimpleObjectPool<>(config().testOnBorrow(true).build(), factory)) {
      assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolObjectValidationException.class).hasNoCause();
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();

      var boom = new IllegalStateException("borrow test threw");
      factory.borrowTest = obj -> {
        throw boom;
      };
      assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolObjectValidationException.class).hasCause(boom);
      assertThat(factory.destroyed.get()).isEqualTo(2);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void errorFromFreshActivationPropagatesAndFreesTheSlot() {
    var error   = new AssertionError("activate");
    var factory = new RecordingFactory();
    factory.onActivate = obj -> {
      throw error;
    };
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      assertThatThrownBy(pool::borrowObject).isSameAs(error);
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();

      factory.onActivate = obj -> {};
      TestPoolObject obj = pool.borrowObject();
      assertThatThrownBy(() -> pool.borrowObject(Duration.ZERO)).isInstanceOf(PoolTimeoutException.class);
      pool.returnObject(obj);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // isBrokenBy
  // ---------------------------------------------------------------------------------------------

  @Test
  void failedActionDestroysTheObjectByDefault() {
    var boom    = new IllegalStateException("action failed");
    var factory = new RecordingFactory();
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      assertThatThrownBy(() -> pool.use(obj -> {
        throw boom;
      })).isSameAs(boom);
      assertThat(boom.getSuppressed()).isEmpty();
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void factoryCanKeepAnObjectThroughAFailure() {
    var factory = new RecordingFactory();
    factory.brokenBy = (obj, error) -> !(error instanceof IllegalArgumentException);
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      TestPoolObject first = pool.borrowObject();
      pool.returnObject(first);

      var harmless = new IllegalArgumentException("bad input");
      assertThatThrownBy(() -> pool.apply(obj -> {
        throw harmless;
      })).isSameAs(harmless);
      assertThat(first.isDestroyed()).isFalse();
      assertThat(factory.calls).endsWith("passivate " + first.seq()); // a normal return
      assertThat(pool.stats().idle()).isEqualTo(1);

      var fatal = new IllegalStateException("connection lost");
      assertThatThrownBy(() -> pool.use(obj -> {
        throw fatal;
      })).isSameAs(fatal);
      assertThat(first.isDestroyed()).isTrue();
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void keptObjectStillGoesThroughTheReturnTest() {
    var factory = new RecordingFactory() {
      @Override
      public boolean isObjectValid(TestPoolObject obj) {
        return false;
      }
    };
    factory.brokenBy = (obj, error) -> false;
    try (var pool = new SimpleObjectPool<>(config().testOnReturn(true).build(), factory)) {
      assertThatThrownBy(() -> pool.use(obj -> {
        throw new IllegalArgumentException("bad input");
      })).isInstanceOf(IllegalArgumentException.class);
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void hookThatThrowsDestroysTheObjectAndIsSuppressed() {
    var boom       = new IllegalStateException("action failed");
    var hookFailed = new IllegalStateException("hook failed");
    var factory    = new RecordingFactory();
    factory.brokenBy = (obj, error) -> {
      throw hookFailed;
    };
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      assertThatThrownBy(() -> pool.use(obj -> {
        throw boom;
      })).isSameAs(boom);
      assertThat(boom.getSuppressed()).containsExactly(hookFailed);
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  @Test
  void hookRethrowingTheActionFailureIsNotSelfSuppressed() {
    var boom    = new IllegalStateException("action failed");
    var factory = new RecordingFactory();
    factory.brokenBy = (obj, error) -> {
      throw (RuntimeException) error;
    };
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      assertThatThrownBy(() -> pool.use(obj -> {
        throw boom;
      })).isSameAs(boom);
      assertThat(boom.getSuppressed()).isEmpty();
      assertThat(factory.destroyed.get()).isEqualTo(1);
    }
  }

  @Test
  void hookRunsAfterThePoolTakesTheObjectBack() throws Exception {
    var inHook       = new CountDownLatch(1);
    var releaseHook  = new CountDownLatch(1);
    var sawDestroyed = new AtomicBoolean();
    var factory      = new RecordingFactory();
    factory.brokenBy = (obj, error) -> {
      inHook.countDown();
      await(releaseHook);
      sawDestroyed.set(obj.isDestroyed());
      return false;
    };
    var pool   = new SimpleObjectPool<>(config().build(), factory);
    var boom   = new IllegalStateException("action failed");
    var thrown = new AtomicReference<Throwable>();
    Thread user = Thread.ofVirtual().start(() -> {
      try {
        pool.use(obj -> {
          throw boom;
        });
      } catch (Throwable t) {
        thrown.set(t);
      }
    });
    assertThat(inHook.await(5, TimeUnit.SECONDS)).isTrue();

    pool.forceClose(); // the object is no longer borrowed, so this cannot destroy it under the hook
    assertThat(factory.destroyed.get()).isZero();
    releaseHook.countDown();
    user.join(5000);

    assertThat(thrown.get()).isSameAs(boom);
    assertThat(sawDestroyed).isFalse();
    assertThat(factory.destroyed.get()).as("destroyed by the return, because the pool is closed").isEqualTo(1);
    assertThat(pool.currentPoolSize()).isZero();
  }

  @Test
  void hookIsNotCalledForAnObjectThePoolAlreadyReclaimed() {
    var hookCalls = new AtomicInteger();
    var factory   = new RecordingFactory();
    factory.brokenBy = (obj, error) -> {
      hookCalls.incrementAndGet();
      return true;
    };
    var pool = new SimpleObjectPool<>(config().build(), factory);
    var boom = new IllegalStateException("action failed");
    assertThatThrownBy(() -> pool.use(obj -> {
      pool.forceClose();
      throw boom;
    })).isSameAs(boom);

    assertThat(hookCalls.get()).isZero();
    assertThat(boom.getSuppressed()).isEmpty();
    assertThat(factory.destroyed.get()).isEqualTo(1);
    assertThat(pool.currentPoolSize()).isZero();
  }

  @Test
  void nullActionBorrowsNothing() {
    var factory = new RecordingFactory();
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      assertThatThrownBy(() -> pool.use(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> pool.apply(null)).isInstanceOf(NullPointerException.class);
      assertThat(factory.calls).isEmpty();
      assertThat(pool.stats().borrows()).isZero();
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

  @Test
  void fatalErrorFromTheHookIsRethrownAfterTheObjectIsDestroyed() {
    var fatal   = new OutOfMemoryError("test");
    var factory = new RecordingFactory();
    factory.brokenBy = (obj, error) -> {
      throw fatal;
    };
    try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
      assertThatThrownBy(() -> pool.use(obj -> {
        throw new IllegalStateException("action failed");
      })).isSameAs(fatal);
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }
}
