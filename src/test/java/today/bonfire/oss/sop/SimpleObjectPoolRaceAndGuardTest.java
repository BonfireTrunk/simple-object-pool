package today.bonfire.oss.sop;

import jdk.jfr.FlightRecorder;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordedFrame;
import jdk.jfr.consumer.RecordedStackTrace;
import jdk.jfr.consumer.RecordedThread;
import jdk.jfr.consumer.RecordingFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import today.bonfire.oss.sop.exceptions.PoolClosedException;
import today.bonfire.oss.sop.exceptions.PoolException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolObjectValidationException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static today.bonfire.oss.sop.TestSupport.assertNeverDuring;
import static today.bonfire.oss.sop.TestSupport.awaitUntil;

/**
 * Race windows, factory error branches and regression guards that the behavioural tests do not
 * reach. Each test pins down one promise the pool makes under concurrent use.
 */
@Timeout(30)
class SimpleObjectPoolRaceAndGuardTest {

  private static final Duration AWAIT   = Duration.ofSeconds(5);
  private static final Duration FOREVER = Duration.ofSeconds(-1);

  /**
   * Counting factory whose create, validate and destroy calls can each be made to block on a
   * latch, throw, or return null, so a test can freeze the pool at a precise point.
   */
  static class ControlFactory implements PooledObjectFactory<TestPoolObject> {
    final AtomicInteger createCalls   = new AtomicInteger();
    final AtomicInteger created       = new AtomicInteger();
    final AtomicInteger destroyed     = new AtomicInteger();
    final AtomicInteger validateCalls = new AtomicInteger();

    volatile CountDownLatch   createGate;
    volatile CountDownLatch   destroyGate;
    volatile CountDownLatch   validateGate;
    volatile boolean          createReturnsNull;
    volatile RuntimeException createThrows;
    volatile RuntimeException destroyThrows;
    volatile RuntimeException validateThrows;
    volatile boolean          inCreate;
    volatile boolean          inDestroy;

    @Override
    public TestPoolObject createObject() {
      createCalls.incrementAndGet();
      inCreate = true;
      try {
        await(createGate);
        if (createThrows != null) throw createThrows;
        if (createReturnsNull) return null;
        created.incrementAndGet();
        return new TestPoolObject();
      } finally {
        inCreate = false;
      }
    }

    @Override
    public void destroyObject(TestPoolObject obj) {
      inDestroy = true;
      try {
        await(destroyGate);
        destroyed.incrementAndGet();
        obj.destroy();
        if (destroyThrows != null) throw destroyThrows;
      } finally {
        inDestroy = false;
      }
    }

    @Override
    public boolean isObjectValid(TestPoolObject obj) {
      validateCalls.incrementAndGet();
      await(validateGate);
      if (validateThrows != null) throw validateThrows;
      return obj.isValid();
    }

    private static void await(CountDownLatch gate) {
      if (gate == null) return;
      try {
        gate.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static SimpleObjectPoolConfig.Builder config(int max) {
    return SimpleObjectPoolConfig.builder()
                                 .maxPoolSize(max)
                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                 .waitingForObjectTimeout(Duration.ofSeconds(5));
  }

  // ---------------------------------------------------------------------------------------------
  // 1. Pinning guard
  // ---------------------------------------------------------------------------------------------

  /**
   * The pool promises never to pin a virtual thread to its carrier. This records
   * {@code jdk.VirtualThreadPinned} at a zero threshold while two thousand virtual threads
   * borrow, block briefly, and return through a small pool with validation on both sides, and
   * fails if any pinning event has a frame from this library on its stack. A {@code synchronized}
   * block or {@code Object.wait()} added anywhere on the borrow or return path would show up here.
   */
  @Test
  void noVirtualThreadIsPinnedInsideThePool() throws Exception {
    assertThat(FlightRecorder.getFlightRecorder().getEventTypes())
        .as("jdk.VirtualThreadPinned event type must exist on this JDK")
        .anyMatch(type -> PINNED_EVENT.equals(type.getName()));

    var factory = new PooledObjectFactory<TestPoolObject>() {
      @Override public TestPoolObject createObject() { return new TestPoolObject(); }
      @Override public void destroyObject(TestPoolObject obj) { obj.destroy(); }
      @Override public boolean isObjectValidForBorrow(TestPoolObject obj) { pause(); return true; }
      @Override public boolean isObjectValid(TestPoolObject obj) { pause(); return true; }

      /** A short blocking call, like a ping round trip, on every validation. */
      private void pause() {
        try {
          Thread.sleep(Duration.of(100, ChronoUnit.MICROS));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      }
    };
    String threadPrefix = "pin-guard-" + System.nanoTime() + "-";
    try (var pool = new SimpleObjectPool<>(config(16).testOnBorrow(true).testOnReturn(true).waitingForObjectTimeout(FOREVER).build(), factory)) {
      pool.prepare();
      runVirtualLoad(pool, threadPrefix, 500); // warm up: class loading and lambda spinning happen here, not under the recording

      Path dump = Files.createTempFile("sop-pin-guard", ".jfr");
      try (var recording = new Recording()) {
        recording.enable(PINNED_EVENT).withThreshold(Duration.ZERO).withStackTrace();
        recording.start();
        try {
          for (int round = 0; round < 3; round++) {
            runVirtualLoad(pool, threadPrefix, 2000);
          }
        } finally {
          recording.stop();
          recording.dump(dump);
        }
      }

      List<String> offenders = new ArrayList<>();
      for (RecordedEvent event : RecordingFile.readAllEvents(dump)) {
        RecordedThread thread = event.getThread();
        if (thread == null || thread.getJavaName() == null || !thread.getJavaName().startsWith(threadPrefix)) {
          continue; // other test classes run concurrently; only judge our own threads
        }
        List<String> frames = frames(event);
        if (frames.stream().anyMatch(f -> f.startsWith("today.bonfire.oss.sop"))) {
          offenders.add(describe(event, frames));
        }
      }
      Files.deleteIfExists(dump);
      assertThat(offenders).as("pinning events with a frame from this library").isEmpty();
    }
  }

  private static final String PINNED_EVENT = "jdk.VirtualThreadPinned";

  private static List<String> frames(RecordedEvent event) {
    RecordedStackTrace stack = event.getStackTrace();
    if (stack == null) return List.of();
    List<String> out = new ArrayList<>();
    for (RecordedFrame frame : stack.getFrames()) {
      out.add(frame.getMethod().getType().getName() + "." + frame.getMethod().getName() + ":" + frame.getLineNumber());
    }
    return out;
  }

  private static String describe(RecordedEvent event, List<String> frames) {
    var sb = new StringBuilder(PINNED_EVENT);
    if (event.hasField("pinnedReason")) { // present from JDK 24 on
      sb.append(" [").append(event.getString("pinnedReason")).append(']');
    }
    for (String f : frames) sb.append("\n    ").append(f);
    return sb.toString();
  }

  private static void runVirtualLoad(SimpleObjectPool<TestPoolObject> pool, String prefix, int threads) throws InterruptedException {
    var done = new CountDownLatch(threads);
    var failure = new AtomicReference<Throwable>();
    for (int i = 0; i < threads; i++) {
      Thread.ofVirtual().name(prefix + i).start(() -> {
        try {
          TestPoolObject obj = pool.borrowObject();
          try {
            Thread.sleep(1);
          } finally {
            pool.returnObject(obj);
          }
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        } finally {
          done.countDown();
        }
      });
    }
    done.await();
    assertThat(failure.get()).as("load thread failure").isNull();
  }

  // ---------------------------------------------------------------------------------------------
  // 2. Fairness
  // ---------------------------------------------------------------------------------------------

  /**
   * With {@code fairness(true)}, a returned object goes to the waiter that has been parked the
   * longest, and a thread arriving while others are parked queues behind them instead of taking
   * the object off the idle queue first. Without this, a busy returner could re-borrow its own
   * object indefinitely while others starve. The race is run many times because a single round
   * can pass by scheduling luck even when fairness is broken.
   */
  @Test
  void fairnessServesParkedWaitersInOrderAndNewcomersQueueBehindThem() throws Exception {
    var factory = new ControlFactory();
    try (var pool = new SimpleObjectPool<>(config(1).fairness(true).build(), factory)) {
      int newcomerWins = 0;
      for (int round = 0; round < 20; round++) {
        TestPoolObject held    = pool.borrowObject();
        var            order   = new ConcurrentLinkedQueue<String>();
        var            release = new CountDownLatch(1);

        Thread a = waiter(pool, "A", order, release);
        awaitUntil("A parked", AWAIT, () -> pool.waitingCount() == 1);
        Thread b = waiter(pool, "B", order, release);
        awaitUntil("B parked", AWAIT, () -> pool.waitingCount() == 2);

        pool.returnObject(held);
        // The returner is now a newcomer. Two threads were parked before it, so it must not get the
        // object: A does, and holds it until released, so the newcomer has nothing to find.
        try {
          pool.returnObject(pool.borrowObject(Duration.ZERO));
          newcomerWins++;
        } catch (PoolTimeoutException expected) {
          // queued behind the parked waiters, as it should be
        }
        awaitUntil("A was served", AWAIT, () -> order.contains("A"));

        release.countDown();
        a.join();
        b.join();
        assertThat(order).as("round " + round).containsExactly("A", "B");
      }
      assertThat(newcomerWins).as("rounds in which a newcomer jumped the parked waiters").isZero();
      assertThat(pool.waitingCount()).isZero();
      assertThat(pool.idleObjectCount()).isEqualTo(1);
    }
  }

  /** Borrows with an unbounded wait, records its name once served, holds the object until released, then returns it. */
  private static Thread waiter(SimpleObjectPool<TestPoolObject> pool, String name, ConcurrentLinkedQueue<String> order, CountDownLatch release) {
    return Thread.ofVirtual().name("waiter-" + name).start(() -> {
      TestPoolObject obj = pool.borrowObject(FOREVER);
      order.add(name);
      try {
        release.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      pool.returnObject(obj);
    });
  }

  // ---------------------------------------------------------------------------------------------
  // 3. Wake-up handoff
  // ---------------------------------------------------------------------------------------------

  /**
   * Two borrowers are parked. The only object is returned and, in the same instant, the first
   * waiter is interrupted. Whichever way the race goes, the object must end up with the second
   * waiter and nobody may stay parked: a wake-up consumed by a thread that then leaves empty-handed
   * would otherwise strand the remaining waiter with an idle object sitting in the queue.
   */
  @Test
  void returnedObjectIsNotStrandedWhenTheSignalledWaiterIsInterrupted() throws Exception {
    var factory = new ControlFactory();
    try (var pool = new SimpleObjectPool<>(config(1).build(), factory)) {
      TestPoolObject held = pool.borrowObject();
      var aOutcome = new AtomicReference<String>();
      var bGot     = new AtomicReference<TestPoolObject>();
      var release  = new CountDownLatch(1);

      Thread a = Thread.ofVirtual().name("waiter-A").start(() -> {
        try {
          TestPoolObject obj = pool.borrowObject(FOREVER);
          aOutcome.set("served");
          try {
            release.await();
          } catch (InterruptedException ignored) {
            // the interrupt may land here instead of inside the pool; either way we hand the object on
          }
          pool.returnObject(obj);
        } catch (PoolException e) {
          aOutcome.set("interrupted");
        }
      });
      awaitUntil("A parked", AWAIT, () -> pool.waitingCount() == 1);
      Thread b = Thread.ofVirtual().name("waiter-B").start(() -> bGot.set(pool.borrowObject(FOREVER)));
      awaitUntil("B parked", AWAIT, () -> pool.waitingCount() == 2);

      pool.returnObject(held);
      a.interrupt();

      awaitUntil("A resolved one way or the other", AWAIT, () -> aOutcome.get() != null);
      if ("served".equals(aOutcome.get())) {
        // A won the race and was served despite the interrupt; it must still be able to hand the object on.
        release.countDown();
      }
      awaitUntil("B obtained the object", AWAIT, () -> bGot.get() != null);
      a.join();
      assertThat(bGot.get()).isSameAs(held);
      assertThat(pool.waitingCount()).as("nobody left parked").isZero();
      assertThat(pool.borrowedObjectsCount()).isEqualTo(1);
      pool.returnObject(bGot.get());
      b.join();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 4. Return racing the abandon check
  // ---------------------------------------------------------------------------------------------

  /**
   * The abandon check is inside {@code destroyObject} for a borrowed object when its holder
   * returns it. The pool marks the object as reclaimed before it takes ownership away, so the
   * return must be ignored silently rather than rejected as foreign, and the object must be
   * destroyed exactly once.
   */
  @Test
  void returnDuringAbandonReclaimIsIgnoredAndObjectDestroyedOnce() throws Exception {
    var factory = new ControlFactory();
    try (var pool = new SimpleObjectPool<>(config(1)
                                               .waitingForObjectTimeout(Duration.ofMillis(10))
                                               .abandonedTimeout(Duration.ofMillis(30))
                                               .durationBetweenAbandonCheckRuns(Duration.ofMillis(10))
                                               .build(), factory)) {
      var inDestroy = new CountDownLatch(1);
      factory.destroyGate = inDestroy;
      TestPoolObject obj = pool.borrowObject();

      awaitUntil("abandon check entered destroyObject", AWAIT, () -> factory.inDestroy);
      assertThat(pool.borrowedObjectsCount()).as("ownership already taken away").isZero();
      assertThatCode(() -> pool.returnObject(obj)).as("return while the pool is reclaiming must be silent").doesNotThrowAnyException();

      inDestroy.countDown();
      awaitUntil("slot released after destroy", AWAIT, () -> pool.currentPoolSize() == 0);
      assertThat(factory.destroyed.get()).isEqualTo(1);
      // Now that the reclaimed marker has been consumed, the same object is a genuine foreign return.
      assertThatThrownBy(() -> pool.returnObject(obj)).isInstanceOf(PoolObjectException.class);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 5. Close racing a borrow
  // ---------------------------------------------------------------------------------------------

  /**
   * A borrower has reserved a slot and is inside {@code createObject} when the pool closes.
   * The fresh object must not be handed out: the borrower gets {@link PoolClosedException}, the
   * object is destroyed, and the slot is released so the closed pool holds nothing.
   */
  @Test
  void borrowThatCompletesCreationAfterCloseIsRefusedAndCleanedUp() throws Exception {
    var factory = new ControlFactory();
    var pool    = new SimpleObjectPool<>(config(1).build(), factory);
    var gate    = new CountDownLatch(1);
    factory.createGate = gate;

    var outcome = new AtomicReference<Object>();
    Thread borrower = Thread.ofVirtual().start(() -> {
      try {
        outcome.set(pool.borrowObject());
      } catch (RuntimeException e) {
        outcome.set(e);
      }
    });
    awaitUntil("borrower inside createObject", AWAIT, () -> factory.inCreate);
    assertThat(pool.currentPoolSize()).as("slot reserved for the creation in flight").isEqualTo(1);

    pool.close();
    gate.countDown();
    borrower.join();

    assertThat(outcome.get()).isInstanceOf(PoolClosedException.class);
    assertThat(factory.created.get()).isEqualTo(1);
    assertThat(factory.destroyed.get()).as("object created after close must be destroyed").isEqualTo(1);
    assertThat(pool.currentPoolSize()).isZero();
    assertThat(pool.borrowedObjectsCount()).isZero();
  }

  // ---------------------------------------------------------------------------------------------
  // 6. Factory error branches
  // ---------------------------------------------------------------------------------------------

  /** A factory that returns null has broken its contract; the borrower is told and the slot is freed. */
  @Test
  void nullFromCreateObjectIsAnErrorAndFreesTheSlot() {
    var factory = new ControlFactory();
    factory.createReturnsNull = true;
    try (var pool = new SimpleObjectPool<>(config(1).build(), factory)) {
      assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolObjectException.class).hasMessageContaining("null");
      assertThat(pool.currentPoolSize()).isZero();
      factory.createReturnsNull = false;
      assertThat(pool.borrowObject()).as("pool recovers once the factory behaves").isNotNull();
    }
  }

  /** A throwing {@code destroyObject} must not keep the capacity slot occupied, or the pool would slowly shrink to nothing. */
  @Test
  void destroyObjectThrowingStillReleasesTheSlot() {
    var factory = new ControlFactory();
    factory.destroyThrows = new RuntimeException("simulated destroy failure");
    try (var pool = new SimpleObjectPool<>(config(1).build(), factory)) {
      TestPoolObject obj = pool.borrowObject();
      assertThatCode(() -> pool.returnObject(obj, true)).doesNotThrowAnyException();
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).as("slot released despite the destroy failure").isZero();
      assertThat(pool.borrowObject()).as("a new object can take the freed slot").isNotSameAs(obj);
    }
  }

  /** A validation hook that throws during {@code testOnCreate} is reported with its cause, and the half-built object does not leak. */
  @Test
  void validationThrowingOnCreateIsWrappedWithCauseAndFreesTheSlot() {
    var factory = new ControlFactory();
    var cause   = new IllegalStateException("validation blew up");
    factory.validateThrows = cause;
    try (var pool = new SimpleObjectPool<>(config(1).testOnCreate(true).build(), factory)) {
      assertThatThrownBy(pool::borrowObject).isInstanceOf(PoolObjectValidationException.class).hasCause(cause);
      assertThat(factory.destroyed.get()).as("the rejected object is destroyed").isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  /** A validation hook that throws on return destroys the object quietly; the returner has nothing it can do about it. */
  @Test
  void validationThrowingOnReturnDestroysTheObjectWithoutFailingTheReturn() {
    var factory = new ControlFactory();
    try (var pool = new SimpleObjectPool<>(config(1).testOnReturn(true).build(), factory)) {
      TestPoolObject obj = pool.borrowObject();
      factory.validateThrows = new RuntimeException("ping failed");
      assertThatCode(() -> pool.returnObject(obj)).doesNotThrowAnyException();
      assertThat(obj.isDestroyed()).isTrue();
      assertThat(factory.destroyed.get()).isEqualTo(1);
      assertThat(pool.currentPoolSize()).isZero();
      assertThat(pool.idleObjectCount()).isZero();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 7. Maintenance
  // ---------------------------------------------------------------------------------------------

  /**
   * A maintenance run that is still validating when the next tick fires must not be joined by a
   * second run. The first run has taken the only idle object for testing; a second run would see
   * the idle queue below {@code minPoolSize} and create a surplus object.
   */
  @Test
  void overlappingMaintenanceTickIsSkipped() throws Exception {
    var factory = new ControlFactory();
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(2)
                                                                 .minPoolSize(1)
                                                                 .testWhileIdle(true)
                                                                 .durationBetweenEvictionsRuns(Duration.ofMillis(10))
                                                                 .build(), factory)) {
      pool.prepare();
      assertThat(factory.created.get()).isEqualTo(1);
      var inValidation = new CountDownLatch(1);
      factory.validateGate = inValidation;

      awaitUntil("maintenance run entered idle validation", AWAIT, () -> factory.validateCalls.get() == 1);
      assertNeverDuring("a second maintenance run refilled behind the blocked one", Duration.ofMillis(150), () -> factory.created.get() > 1);

      inValidation.countDown();
      awaitUntil("blocked run finished and put the object back", AWAIT, () -> pool.idleObjectCount() == 1);
      assertThat(pool.currentPoolSize()).isEqualTo(1);
    }
  }

  /**
   * When {@code createObject} fails on every maintenance run, the pool must keep trying on the
   * next tick rather than giving up or crashing its scheduler, and must fill to {@code minPoolSize}
   * as soon as the factory recovers.
   */
  @Test
  void refillKeepsRetryingWhileCreateFailsAndFillsOnRecovery() throws Exception {
    var factory = new ControlFactory();
    factory.createThrows = new RuntimeException("endpoint down");
    try (var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                                 .maxPoolSize(4)
                                                                 .minPoolSize(2)
                                                                 .durationBetweenEvictionsRuns(Duration.ofMillis(10))
                                                                 .build(), factory)) {
      awaitUntil("maintenance attempted creation on several ticks", AWAIT, () -> factory.createCalls.get() >= 3);
      assertThat(pool.currentPoolSize()).as("no slot leaked by the failed attempts").isZero();
      assertThat(pool.isClosed()).isFalse();
      assertThatThrownBy(pool::borrowObject).hasMessage("endpoint down");

      factory.createThrows = null;
      awaitUntil("pool filled to minPoolSize after recovery", AWAIT, () -> pool.idleObjectCount() == 2);
      assertThat(pool.currentPoolSize()).isEqualTo(2);
    }
  }

  // ---------------------------------------------------------------------------------------------
  // 8. Reclaimed set hygiene
  // ---------------------------------------------------------------------------------------------

  /**
   * An abandoned object that its holder never returns must still become garbage. The pool keeps a
   * marker for it so a late return can be ignored, and that marker must hold the object weakly.
   */
  @Test
  void abandonedObjectThatIsNeverReturnedIsCollectable() throws Exception {
    var factory = new ControlFactory();
    try (var pool = new SimpleObjectPool<>(config(1)
                                               .waitingForObjectTimeout(Duration.ofMillis(10))
                                               .abandonedTimeout(Duration.ofMillis(30))
                                               .durationBetweenAbandonCheckRuns(Duration.ofMillis(10))
                                               .build(), factory)) {
      WeakReference<TestPoolObject> ref = borrowAndForget(pool);
      awaitUntil("abandon check destroyed the object", AWAIT, () -> factory.destroyed.get() == 1);
      awaitUntil("abandoned object collected", AWAIT, () -> {
        System.gc();
        return ref.get() == null;
      });
      assertThat(pool.currentPoolSize()).isZero();
    }
  }

  /** Kept out of the test body so no local variable on the test's frame keeps the object alive. */
  private static WeakReference<TestPoolObject> borrowAndForget(SimpleObjectPool<TestPoolObject> pool) {
    return new WeakReference<>(pool.borrowObject());
  }

  // ---------------------------------------------------------------------------------------------
  // 9. Randomised soak
  // ---------------------------------------------------------------------------------------------

  /** Pooled object that knows whether it is currently out and how often it has been destroyed. */
  static final class SoakObject {
    final AtomicBoolean inUse        = new AtomicBoolean();
    final AtomicInteger destroyCount = new AtomicInteger();
    volatile boolean    destroyed;
  }

  /**
   * Thirty-two virtual threads mix borrows, broken returns, manual eviction, idle clears and
   * explicit adds for a few seconds while scheduled eviction and abandon detection run. Every
   * object must be borrowed by at most one thread at a time, never be handed out destroyed,
   * be destroyed exactly once, and the live count must never exceed the cap. Afterwards every
   * created object must have been destroyed.
   */
  @Test
  void randomisedSoakPreservesEveryInvariant() throws Exception {
    final int max = 8;
    var violations = new ConcurrentLinkedQueue<String>();
    var created    = new AtomicInteger();
    var destroyed  = new AtomicInteger();
    var factory = new PooledObjectFactory<SoakObject>() {
      @Override
      public SoakObject createObject() {
        created.incrementAndGet();
        return new SoakObject();
      }

      @Override
      public void destroyObject(SoakObject obj) {
        if (obj.destroyCount.incrementAndGet() != 1) violations.add("destroyed twice");
        if (obj.inUse.get()) violations.add("destroyed while a thread was using it");
        obj.destroyed = true;
        destroyed.incrementAndGet();
      }

      @Override
      public boolean isObjectValid(SoakObject obj) {
        return !obj.destroyed;
      }
    };
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(max)
                                       .minPoolSize(2)
                                       .testOnBorrow(true)
                                       .testOnReturn(true)
                                       .testWhileIdle(true)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(10))
                                       .objEvictionTimeout(Duration.ofMillis(5))
                                       .abandonedTimeout(Duration.ofSeconds(30))
                                       .durationBetweenAbandonCheckRuns(Duration.ofMillis(50))
                                       .waitingForObjectTimeout(Duration.ofSeconds(2))
                                       .build();
    var pool      = new SimpleObjectPool<>(config, factory);
    var stop      = new AtomicBoolean();
    var watermark = new AtomicInteger();
    var timeouts  = new AtomicInteger();
    var ops       = new AtomicInteger();
    var failure   = new AtomicReference<Throwable>();
    var threads   = new ArrayList<Thread>();

    for (int i = 0; i < 32; i++) {
      threads.add(Thread.ofVirtual().name("soak-" + i).start(() -> {
        var rnd = ThreadLocalRandom.current();
        try {
          while (!stop.get()) {
            int roll = rnd.nextInt(100);
            watermark.accumulateAndGet(pool.currentPoolSize(), Math::max);
            if (roll < 80) {
              SoakObject obj;
              try {
                obj = pool.borrowObject();
              } catch (PoolTimeoutException e) {
                timeouts.incrementAndGet();
                continue;
              }
              if (obj.destroyed) violations.add("borrowed a destroyed object");
              if (!obj.inUse.compareAndSet(false, true)) violations.add("borrowed an object already in use");
              for (int spin = rnd.nextInt(200); spin > 0; spin--) Thread.onSpinWait();
              obj.inUse.set(false);
              pool.returnObject(obj, roll < 10);
            } else if (roll < 87) {
              pool.evict();
            } else if (roll < 92) {
              pool.destroyAllIdleObjects();
            } else {
              pool.addObject();
            }
            ops.incrementAndGet();
          }
        } catch (Throwable t) {
          failure.compareAndSet(null, t);
        }
      }));
    }

    runFor(Duration.ofSeconds(3), () -> failure.get() != null || !violations.isEmpty());
    stop.set(true);
    for (Thread t : threads) t.join();
    pool.close();

    assertThat(failure.get()).as("unexpected exception during soak").isNull();
    assertThat(violations).isEmpty();
    assertThat(ops.get()).as("the soak actually did work").isGreaterThan(1000);
    assertThat(watermark.get()).as("live objects never exceeded the cap").isLessThanOrEqualTo(max);
    awaitUntil("every created object destroyed after close", AWAIT, () -> created.get() == destroyed.get());
    assertThat(pool.currentPoolSize()).isZero();
    assertThat(pool.borrowedObjectsCount()).isZero();
    assertThat(pool.idleObjectCount()).isZero();
  }

  /** Lets the soak run for its budget, cutting it short as soon as something has already gone wrong. */
  private static void runFor(Duration budget, java.util.function.BooleanSupplier stopEarly) {
    long deadline = System.nanoTime() + budget.toNanos();
    while (System.nanoTime() < deadline && !stopEarly.getAsBoolean()) {
      TestSupport.sleepQuietly(20);
    }
  }
}
