package today.bonfire.oss.sop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static today.bonfire.oss.sop.TestSupport.assertNeverDuring;
import static today.bonfire.oss.sop.TestSupport.awaitUntil;

/** Runs alone because scheduler shutdown and context class loaders are shared JVM state. */
@Isolated
@Timeout(30)
class SimpleObjectPoolSchedulerTest {

  private static final Duration AWAIT = Duration.ofSeconds(5);

  private static SimpleObjectPoolConfig.Builder config() {
    return SimpleObjectPoolConfig.builder()
                                 .maxPoolSize(1)
                                 .minPoolSize(1)
                                 .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.NONE)
                                 .durationBetweenEvictionsRuns(Duration.ofMillis(10));
  }

  /**
   * Registration counts are JVM-wide. Start every test from zero so a pool another test class
   * dropped without close() cannot be released in the middle of an assertion.
   */
  @BeforeEach
  void noPoolsRegistered() {
    awaitUntil("no pools registered (did another test leave one open?)", AWAIT, () -> {
      System.gc();
      return SimpleObjectPool.schedulerRegistrationCount() == 0;
    });
  }

  @Test
  void lastCloseShutsDownTheSchedulerAndTheNextPoolRestartsIt() throws Exception {
    var pool = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory());
    ScheduledExecutorService first;
    try {
      awaitUntil("first pool filled", AWAIT, () -> pool.idleObjectCount() == 1);
      first = scheduler();
    } finally {
      pool.close();
    }
    assertThat(first.isShutdown()).as("scheduler shut down after the last pool closes").isTrue();
    try (var next = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      awaitUntil("next pool filled", AWAIT, () -> next.idleObjectCount() == 1);
      assertThat(scheduler()).isNotSameAs(first);
      assertThat(scheduler().isShutdown()).isFalse();
    }
  }

  @Test
  void maintenanceUsesEachPoolsCreatingContextClassLoader() {
    var original = Thread.currentThread().getContextClassLoader();
    var creating = new ClassLoader(original) {};
    var observed = new AtomicReference<ClassLoader>();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory() {
      @Override
      public TestPoolObject createObject() {
        observed.set(Thread.currentThread().getContextClassLoader());
        return super.createObject();
      }
    };
    try (var first = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      awaitUntil("scheduler already started", AWAIT, () -> first.idleObjectCount() == 1);
      Thread.currentThread().setContextClassLoader(creating);
      try (var pool = new SimpleObjectPool<>(config().build(), factory)) {
        Thread.currentThread().setContextClassLoader(original);
        awaitUntil("maintenance called factory", AWAIT, () -> observed.get() != null);
        assertThat(observed.get()).as("maintenance factory context class loader").isSameAs(creating);
      } finally {
        Thread.currentThread().setContextClassLoader(original);
      }
    }
  }

  @Test
  void scheduledPoolsRegisterOnceAndReleaseExactlyOnce() {
    int before = SimpleObjectPool.schedulerRegistrationCount();
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory();
    try (var disabled = new SimpleObjectPool<>(config().minPoolSize(0).build(), factory)) {
      assertThat(SimpleObjectPool.schedulerRegistrationCount()).isEqualTo(before);
      var pool = new SimpleObjectPool<>(config().abandonedTimeout(Duration.ofSeconds(1))
                                               .durationBetweenAbandonCheckRuns(Duration.ofMillis(10)).build(), factory);
      assertThat(SimpleObjectPool.schedulerRegistrationCount()).isEqualTo(before + 1);
      pool.close();
      assertThat(SimpleObjectPool.schedulerRegistrationCount()).isEqualTo(before);
      pool.forceClose();
      pool.close();
      assertThat(SimpleObjectPool.schedulerRegistrationCount()).isEqualTo(before);
    }
  }

  @Test
  void constructionFailureAfterRegisteringReleasesTheRegistration() {
    int before = SimpleObjectPool.schedulerRegistrationCount();
    var boom   = new IllegalStateException("abandon schedule failed");
    var config = new SimpleObjectPoolConfig(config().abandonedTimeout(Duration.ofSeconds(1)).prepareAndCheck()) {
      @Override
      public Duration durationBetweenAbandonCheckRuns() {
        throw boom;
      }
    };
    var factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory();
    assertThatThrownBy(() -> new SimpleObjectPool<>(config, factory)).isSameAs(boom);
    assertThat(SimpleObjectPool.schedulerRegistrationCount()).isEqualTo(before);
    assertThat(factory.created.get()).as("failed construction cannot start maintenance").isZero();
  }

  @Test
  void garbageCollectedPoolReleasesItsRegistrationAndWarnsExactlyOnce() {
    var logger = (Logger) LoggerFactory.getLogger(SimpleObjectPool.class);
    var events = new ConcurrentLinkedQueue<ILoggingEvent>();
    var capture = new AppenderBase<ILoggingEvent>() {
      @Override
      protected void append(ILoggingEvent event) {
        events.add(event);
      }
    };
    capture.start();
    logger.addAppender(capture);
    String name    = "gc-registration-" + System.nanoTime();
    int    before  = SimpleObjectPool.schedulerRegistrationCount();
    var    factory = new SimpleObjectPoolRaceAndGuardTest.ControlFactory();
    try {
      var ref = createAndForget(name, factory);
      assertThat(SimpleObjectPool.schedulerRegistrationCount()).isEqualTo(before + 1);
      awaitUntil("unclosed pool collected and registration released", AWAIT, () -> {
        System.gc();
        return ref.get() == null && SimpleObjectPool.schedulerRegistrationCount() == before;
      });
      assertThat(events.stream().filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(name)).toList())
          .singleElement().satisfies(e -> assertThat(e.getFormattedMessage())
              .contains("garbage collected without close()", "idle objects were never destroyed"));
      assertNeverDuring("duplicate GC warning", Duration.ofMillis(100),
                        () -> events.stream().filter(e -> e.getLevel() == Level.WARN && e.getFormattedMessage().contains(name)).count() > 1);
      assertThat(factory.destroyed.get()).isZero();
    } finally {
      logger.detachAppender(capture);
      capture.stop();
    }
  }

  private static WeakReference<SimpleObjectPool<TestPoolObject>> createAndForget(String name, SimpleObjectPoolRaceAndGuardTest.ControlFactory factory) {
    var pool = new SimpleObjectPool<>(name, config().durationBetweenEvictionsRuns(Duration.ofSeconds(1))
                                                  .abandonedTimeout(Duration.ofSeconds(5))
                                                  .durationBetweenAbandonCheckRuns(Duration.ofSeconds(1)).build(), factory);
    pool.prepare();
    return new WeakReference<>(pool);
  }

  @Test
  void schedulerWorkerUsesTheLibraryClassLoader() {
    ClassLoader original = Thread.currentThread().getContextClassLoader();
    ClassLoader creating = new ClassLoader(original) {};
    Thread.currentThread().setContextClassLoader(creating);
    try (var pool = new SimpleObjectPool<>(config().build(), new SimpleObjectPoolRaceAndGuardTest.ControlFactory())) {
      Thread.currentThread().setContextClassLoader(original);
      awaitUntil("maintenance ran", AWAIT, () -> pool.idleObjectCount() == 1);
      // A worker of an executor shut down by an earlier test may not have exited yet.
      assertThat(Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().equals("sop-maintenance")).toList())
          .isNotEmpty().allSatisfy(t -> {
            assertThat(t.isDaemon()).isTrue();
            assertThat(t.getContextClassLoader()).isSameAs(SimpleObjectPool.class.getClassLoader());
          });
    } finally {
      Thread.currentThread().setContextClassLoader(original);
    }
  }

  private static ScheduledExecutorService scheduler() throws Exception {
    return SimpleObjectPool.maintenanceScheduler();
  }
}
