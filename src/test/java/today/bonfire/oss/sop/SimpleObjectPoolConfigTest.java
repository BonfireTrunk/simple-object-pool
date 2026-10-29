package today.bonfire.oss.sop;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import today.bonfire.oss.sop.SimpleObjectPoolConfig.EvictionPolicy;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Runs alone: it captures the shared {@code SimpleObjectPoolConfig} logger and asserts on what
 * was logged, and other test classes building configs at the same time would pollute that.
 */
@Isolated
@Timeout(30)
class SimpleObjectPoolConfigTest {

  private Logger                      configLogger;
  private Level                       originalLevel;
  private ListAppender<ILoggingEvent> listAppender;

  @BeforeEach
  void setUp() {
    LoggerContext context = logbackContext();
    configLogger = context.getLogger(SimpleObjectPoolConfig.class);
    originalLevel = configLogger.getLevel();
    configLogger.setLevel(Level.DEBUG);
    listAppender = new ListAppender<>();
    listAppender.start();
    configLogger.addAppender(listAppender);
  }

  /**
   * Test classes run in parallel, so the first logger lookup in the JVM may race SLF4J's
   * initialisation and hand back a substitute factory. Wait for the real Logback context.
   */
  private static LoggerContext logbackContext() {
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (true) {
      var factory = LoggerFactory.getILoggerFactory();
      if (factory instanceof LoggerContext ctx) {
        return ctx;
      }
      if (System.nanoTime() > deadline) {
        throw new IllegalStateException("Logback did not initialise in time, got " + factory.getClass());
      }
      Thread.onSpinWait();
    }
  }

  @AfterEach
  void tearDown() {
    configLogger.detachAppender(listAppender);
    listAppender.stop();
    configLogger.setLevel(originalLevel);
  }

  private List<String> messages(Level level) {
    return listAppender.list.stream()
                            .filter(e -> e.getLevel() == level)
                            .map(ILoggingEvent::getFormattedMessage)
                            .collect(Collectors.toList());
  }

  private List<String> allMessages() {
    return listAppender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(Collectors.toList());
  }

  // ---------------------------------------------------------------- defaults

  @Test
  void defaults() {
    SimpleObjectPoolConfig c = SimpleObjectPoolConfig.defaultConfig();
    assertThat(c.poolName()).startsWith("SOP-");
    assertThat(c.maxPoolSize()).isEqualTo(8);
    assertThat(c.minPoolSize()).isZero();
    assertThat(c.fairness()).isFalse();
    assertThat(c.lifo()).isTrue();
    assertThat(c.testOnCreate()).isFalse();
    assertThat(c.testOnBorrow()).isFalse();
    assertThat(c.testOnReturn()).isFalse();
    assertThat(c.testWhileIdle()).isTrue();
    assertThat(c.durationBetweenAbandonCheckRuns()).isEqualTo(Duration.ofSeconds(60));
    assertThat(c.abandonedTimeout()).isNull();
    assertThat(c.abandonCheckEnabled()).isFalse();
    assertThat(c.durationBetweenEvictionsRuns()).isEqualTo(Duration.ofMinutes(5));
    assertThat(c.objEvictionTimeout()).isEqualTo(Duration.ofMinutes(5));
    assertThat(c.numValidationsPerEvictionRun()).isEqualTo(8);
    assertThat(c.evictionPolicy()).isEqualTo(EvictionPolicy.RANDOM);
    assertThat(c.maxRetries()).isZero();
    assertThat(c.retryCreationDelay()).isEqualTo(Duration.ZERO);
    assertThat(c.waitingForObjectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(allMessages()).isEmpty();
  }

  @Test
  void defaultPoolNamesAreUnique() {
    // 4 hex chars, so a collision is possible; just assert format
    assertThat(SimpleObjectPoolConfig.builder().build().poolName()).matches("SOP-[0-9A-F]+");
  }

  @Test
  void numValidationsPerEvictionRun_defaultsToMaxPoolSize() {
    assertThat(SimpleObjectPoolConfig.builder().maxPoolSize(5).build().numValidationsPerEvictionRun()).isEqualTo(5);
  }

  @Test
  void numValidationsPerEvictionRun_explicitValueKept() {
    assertThat(SimpleObjectPoolConfig.builder().maxPoolSize(5).numValidationsPerEvictionRun(2).build()
                                     .numValidationsPerEvictionRun()).isEqualTo(2);
    assertThat(SimpleObjectPoolConfig.builder().maxPoolSize(5).numValidationsPerEvictionRun(0).build()
                                     .numValidationsPerEvictionRun()).isZero();
  }

  // ---------------------------------------------------------------- throws

  @Test
  void validate_poolNameNull() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().poolName(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("poolName must not be null or blank");
  }

  @Test
  void validate_poolNameBlank() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().poolName("   ").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("poolName must not be null or blank");
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().poolName("").build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("poolName must not be null or blank");
  }

  @Test
  void validate_invalidMaxPoolSize() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxPoolSize(0).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxPoolSize must be greater than 0");
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxPoolSize(-3).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxPoolSize must be greater than 0");
  }

  @Test
  void validate_invalidMinPoolSize_negative() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().minPoolSize(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("minPoolSize cannot be negative");
  }

  @Test
  void validate_invalidMinPoolSize_greaterThanMax() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxPoolSize(2).minPoolSize(3).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("minPoolSize cannot be greater than maxPoolSize");
  }

  @Test
  void validate_minPoolSizeEqualToMax_isValid() {
    assertThatCode(() -> SimpleObjectPoolConfig.builder().maxPoolSize(3).minPoolSize(3).build())
        .doesNotThrowAnyException();
  }

  @Test
  void validate_negativeMaxRetries() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxRetries(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("maxRetries cannot be negative");
  }

  @Test
  void validate_numValidationsPerEvictionRun_negative() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().numValidationsPerEvictionRun(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
  }

  @Test
  void validate_numValidationsPerEvictionRun_greaterThanMax() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxPoolSize(4).numValidationsPerEvictionRun(5).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
  }

  @Test
  void validate_numValidationsPerEvictionRun_equalToMax_isValid() {
    assertThatCode(() -> SimpleObjectPoolConfig.builder().maxPoolSize(4).numValidationsPerEvictionRun(4).build())
        .doesNotThrowAnyException();
  }

  @Test
  void validate_evictionPolicyNull() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().evictionPolicy(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("evictionPolicy must not be null");
  }

  @Test
  void validate_durationBetweenEvictionsRunsNull() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must not be null");
    // null is rejected even when eviction is off
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().evictionPolicy(EvictionPolicy.NONE)
                                                   .durationBetweenEvictionsRuns(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must not be null");
  }

  @Test
  void validate_durationBetweenEvictionsRuns_mustBePositiveWhenEvictionEnabled() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must be positive");
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(Duration.ofSeconds(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must be positive");
  }

  @Test
  void validate_durationBetweenEvictionsRuns_mustBePositiveWhenMinPoolSizePositive() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().evictionPolicy(EvictionPolicy.NONE).minPoolSize(1)
                                                   .durationBetweenEvictionsRuns(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must be positive");
  }

  @Test
  void validate_durationBetweenEvictionsRuns_nonPositiveAllowedWhenNoEvictionAndNoMinPool() {
    assertThatCode(() -> SimpleObjectPoolConfig.builder().evictionPolicy(EvictionPolicy.NONE).minPoolSize(0)
                                               .durationBetweenEvictionsRuns(Duration.ZERO).build())
        .doesNotThrowAnyException();
    assertThatCode(() -> SimpleObjectPoolConfig.builder().evictionPolicy(EvictionPolicy.NONE).minPoolSize(0)
                                               .durationBetweenEvictionsRuns(Duration.ofSeconds(-5)).build())
        .doesNotThrowAnyException();
  }

  @Test
  void validate_objEvictionTimeoutNull() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().objEvictionTimeout(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("objEvictionTimeout must not be null");
  }

  @Test
  void validate_waitingForObjectTimeoutNull() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().waitingForObjectTimeout(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("waitingForObjectTimeout must not be null");
  }

  @Test
  void validate_retryCreationDelayNull() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().retryCreationDelay(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("retryCreationDelay must not be null");
  }

  @Test
  void validate_abandonCheckRunsNull_whenAbandonEnabled() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofMinutes(1))
                                                   .durationBetweenAbandonCheckRuns(null).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenAbandonCheckRuns must be positive when abandonedTimeout is set");
  }

  @Test
  void validate_abandonCheckRunsNonPositive_whenAbandonEnabled() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofMinutes(1))
                                                   .durationBetweenAbandonCheckRuns(Duration.ZERO).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenAbandonCheckRuns must be positive when abandonedTimeout is set");
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofMinutes(1))
                                                   .durationBetweenAbandonCheckRuns(Duration.ofSeconds(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenAbandonCheckRuns must be positive when abandonedTimeout is set");
  }

  @Test
  void validate_abandonCheckRuns_ignoredWhenAbandonDisabled() {
    assertThatCode(() -> SimpleObjectPoolConfig.builder().durationBetweenAbandonCheckRuns(null).build())
        .doesNotThrowAnyException();
    assertThatCode(() -> SimpleObjectPoolConfig.builder().durationBetweenAbandonCheckRuns(Duration.ZERO).build())
        .doesNotThrowAnyException();
  }

  // ---------------------------------------------------------------- normalisation

  @Test
  void abandonedTimeout_zeroNormalisesToDisabled() {
    SimpleObjectPoolConfig c = SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ZERO).build();
    assertThat(c.abandonedTimeout()).isNull();
    assertThat(c.abandonCheckEnabled()).isFalse();
  }

  @Test
  void abandonedTimeout_negativeNormalisesToDisabled() {
    SimpleObjectPoolConfig c = SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofSeconds(-1)).build();
    assertThat(c.abandonedTimeout()).isNull();
    assertThat(c.abandonCheckEnabled()).isFalse();
  }

  @Test
  void abandonedTimeout_zeroWithInvalidCheckRuns_doesNotThrow() {
    // normalised to disabled before validation, so the check-runs rule does not apply
    assertThatCode(() -> SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ZERO)
                                               .durationBetweenAbandonCheckRuns(Duration.ZERO).build())
        .doesNotThrowAnyException();
  }

  @Test
  void abandonedTimeout_positiveEnablesCheck() {
    SimpleObjectPoolConfig c = SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofMinutes(2)).build();
    assertThat(c.abandonedTimeout()).isEqualTo(Duration.ofMinutes(2));
    assertThat(c.abandonCheckEnabled()).isTrue();
  }

  @Test
  void retryCreationDelay_negativeNormalisesToZero() {
    SimpleObjectPoolConfig c = SimpleObjectPoolConfig.builder().retryCreationDelay(Duration.ofMillis(-5)).build();
    assertThat(c.retryCreationDelay()).isEqualTo(Duration.ZERO);
    assertThat(messages(Level.INFO)).isEmpty();
  }

  // ---------------------------------------------------------------- log branches

  @Test
  void warn_maxRetriesGreaterThanMaxPoolSize() {
    SimpleObjectPoolConfig.builder().maxPoolSize(2).maxRetries(3).build();
    assertThat(messages(Level.WARN))
        .containsExactly("maxRetries is greater than maxPoolSize. This may result in excessive retries.");
  }

  @Test
  void noWarn_maxRetriesEqualToMaxPoolSize() {
    SimpleObjectPoolConfig.builder().maxPoolSize(2).maxRetries(2).build();
    assertThat(messages(Level.WARN)).isEmpty();
  }

  @Test
  void info_objEvictionTimeoutZero() {
    SimpleObjectPoolConfig.builder().objEvictionTimeout(Duration.ZERO).build();
    assertThat(messages(Level.INFO)).containsExactly(
        "objEvictionTimeout is zero or negative. This means idle objects will be evicted on the first eviction run.");
  }

  @Test
  void info_objEvictionTimeoutNegative() {
    SimpleObjectPoolConfig.builder().objEvictionTimeout(Duration.ofSeconds(-1)).build();
    assertThat(messages(Level.INFO)).hasSize(1);
    assertThat(messages(Level.INFO).get(0)).startsWith("objEvictionTimeout is zero or negative.");
  }

  @Test
  void noInfo_objEvictionTimeoutPositive_andNoDebugLog() {
    SimpleObjectPoolConfig.builder().objEvictionTimeout(Duration.ofSeconds(1)).build();
    assertThat(allMessages()).isEmpty();
  }

  @Test
  void info_positiveRetryCreationDelay() {
    SimpleObjectPoolConfig.builder().retryCreationDelay(Duration.ofMillis(10)).build();
    assertThat(messages(Level.INFO)).hasSize(1);
    assertThat(messages(Level.INFO).get(0)).startsWith("You have set a positive retryCreationDelay.");
  }

  @Test
  void noInfo_zeroRetryCreationDelay() {
    SimpleObjectPoolConfig.builder().retryCreationDelay(Duration.ZERO).build();
    assertThat(messages(Level.INFO)).isEmpty();
  }

  @Test
  void warn_abandonedTimeoutLessThanWaitingForObjectTimeout() {
    SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofSeconds(5))
                          .waitingForObjectTimeout(Duration.ofSeconds(10)).build();
    assertThat(messages(Level.WARN)).containsExactly(
        "abandonedTimeout is less than or equal to waitingForObjectTimeout. This may result in excessive actions on the pool.");
  }

  @Test
  void warn_abandonedTimeoutEqualToWaitingForObjectTimeout() {
    SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofSeconds(10))
                          .waitingForObjectTimeout(Duration.ofSeconds(10)).build();
    assertThat(messages(Level.WARN)).hasSize(1);
  }

  @Test
  void noWarn_abandonedTimeoutGreaterThanWaitingForObjectTimeout() {
    SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofSeconds(11))
                          .waitingForObjectTimeout(Duration.ofSeconds(10)).build();
    assertThat(messages(Level.WARN)).isEmpty();
  }

  @Test
  void noWarn_abandonedTimeoutWithInfiniteWait() {
    SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofSeconds(1))
                          .waitingForObjectTimeout(Duration.ofSeconds(-1)).build();
    assertThat(messages(Level.WARN)).isEmpty();
  }

  @Test
  void noWarn_abandonDisabledEvenIfWaitIsLong() {
    SimpleObjectPoolConfig.builder().waitingForObjectTimeout(Duration.ofHours(1)).build();
    assertThat(messages(Level.WARN)).isEmpty();
  }

  @Test
  void waitingForObjectTimeout_acceptsAnyValueWithoutLogging() {
    for (Duration d : new Duration[]{Duration.ofSeconds(-1), Duration.ZERO, Duration.ofMillis(1), Duration.ofDays(1)}) {
      listAppender.list.clear();
      SimpleObjectPoolConfig c = SimpleObjectPoolConfig.builder().waitingForObjectTimeout(d).build();
      assertThat(c.waitingForObjectTimeout()).isEqualTo(d);
      assertThat(allMessages()).isEmpty();
    }
  }

  @Test
  void noWarn_evictionRunShorterOrLongerThanWaitTimeout() {
    // the old "durationBetweenEvictionsRuns <= waitingForObjectTimeout" warning no longer exists
    SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(Duration.ofSeconds(1))
                          .waitingForObjectTimeout(Duration.ofSeconds(30)).build();
    assertThat(allMessages()).isEmpty();
  }

  // ---------------------------------------------------------------- builder / toBuilder

  @Test
  void builder_setsAllValues() {
    SimpleObjectPoolConfig c = SimpleObjectPoolConfig.builder()
                                                     .poolName("p")
                                                     .maxPoolSize(10)
                                                     .minPoolSize(2)
                                                     .lifo(false)
                                                     .fairness(true)
                                                     .evictionPolicy(EvictionPolicy.OLDEST_FIRST)
                                                     .build();
    assertThat(c.poolName()).isEqualTo("p");
    assertThat(c.maxPoolSize()).isEqualTo(10);
    assertThat(c.minPoolSize()).isEqualTo(2);
    assertThat(c.lifo()).isFalse();
    assertThat(c.fairness()).isTrue();
    assertThat(c.evictionPolicy()).isEqualTo(EvictionPolicy.OLDEST_FIRST);
  }

  @Test
  void evictionPolicy_allValuesAccepted() {
    for (EvictionPolicy p : EvictionPolicy.values()) {
      assertThat(SimpleObjectPoolConfig.builder().evictionPolicy(p).build().evictionPolicy()).isEqualTo(p);
    }
  }

  @Test
  void toBuilder_roundTripsEveryField() {
    SimpleObjectPoolConfig original = SimpleObjectPoolConfig.builder()
                                                            .poolName("custom-pool")
                                                            .maxPoolSize(20)
                                                            .minPoolSize(3)
                                                            .fairness(true)
                                                            .lifo(false)
                                                            .testOnCreate(true)
                                                            .testOnBorrow(true)
                                                            .testOnReturn(true)
                                                            .testWhileIdle(false)
                                                            .durationBetweenAbandonCheckRuns(Duration.ofSeconds(17))
                                                            .abandonedTimeout(Duration.ofMinutes(9))
                                                            .durationBetweenEvictionsRuns(Duration.ofSeconds(33))
                                                            .objEvictionTimeout(Duration.ofSeconds(44))
                                                            .numValidationsPerEvictionRun(7)
                                                            .evictionPolicy(EvictionPolicy.LEAST_USED)
                                                            .maxRetries(4)
                                                            .retryCreationDelay(Duration.ofMillis(250))
                                                            .waitingForObjectTimeout(Duration.ofSeconds(3))
                                                            .build();

    SimpleObjectPoolConfig copy = original.toBuilder().build();

    assertThat(copy).isNotSameAs(original);
    assertThat(copy.poolName()).isEqualTo("custom-pool").isEqualTo(original.poolName());
    assertThat(copy.maxPoolSize()).isEqualTo(original.maxPoolSize());
    assertThat(copy.minPoolSize()).isEqualTo(original.minPoolSize());
    assertThat(copy.fairness()).isEqualTo(original.fairness());
    assertThat(copy.lifo()).isEqualTo(original.lifo());
    assertThat(copy.testOnCreate()).isEqualTo(original.testOnCreate());
    assertThat(copy.testOnBorrow()).isEqualTo(original.testOnBorrow());
    assertThat(copy.testOnReturn()).isEqualTo(original.testOnReturn());
    assertThat(copy.testWhileIdle()).isEqualTo(original.testWhileIdle());
    assertThat(copy.durationBetweenAbandonCheckRuns()).isEqualTo(original.durationBetweenAbandonCheckRuns());
    assertThat(copy.abandonedTimeout()).isEqualTo(original.abandonedTimeout());
    assertThat(copy.abandonCheckEnabled()).isEqualTo(original.abandonCheckEnabled());
    assertThat(copy.durationBetweenEvictionsRuns()).isEqualTo(original.durationBetweenEvictionsRuns());
    assertThat(copy.objEvictionTimeout()).isEqualTo(original.objEvictionTimeout());
    assertThat(copy.numValidationsPerEvictionRun()).isEqualTo(original.numValidationsPerEvictionRun());
    assertThat(copy.evictionPolicy()).isEqualTo(original.evictionPolicy());
    assertThat(copy.maxRetries()).isEqualTo(original.maxRetries());
    assertThat(copy.retryCreationDelay()).isEqualTo(original.retryCreationDelay());
    assertThat(copy.waitingForObjectTimeout()).isEqualTo(original.waitingForObjectTimeout());

    // spot-check the values really are the customised ones, not defaults
    assertThat(copy.lifo()).isFalse();
    assertThat(copy.abandonedTimeout()).isEqualTo(Duration.ofMinutes(9));
    assertThat(copy.numValidationsPerEvictionRun()).isEqualTo(7);
  }

  @Test
  void toBuilder_roundTripsDefaultsWithAbandonDisabled() {
    SimpleObjectPoolConfig original = SimpleObjectPoolConfig.defaultConfig();
    SimpleObjectPoolConfig copy = original.toBuilder().build();
    assertThat(copy.poolName()).isEqualTo(original.poolName());
    assertThat(copy.abandonedTimeout()).isNull();
    assertThat(copy.abandonCheckEnabled()).isFalse();
    assertThat(copy.numValidationsPerEvictionRun()).isEqualTo(original.numValidationsPerEvictionRun());
  }

  @Test
  void toBuilder_modificationDoesNotAffectOriginal() {
    SimpleObjectPoolConfig original = SimpleObjectPoolConfig.builder().poolName("a").maxPoolSize(4).build();
    SimpleObjectPoolConfig changed = original.toBuilder().poolName("b").maxPoolSize(6).build();
    assertThat(original.poolName()).isEqualTo("a");
    assertThat(original.maxPoolSize()).isEqualTo(4);
    assertThat(changed.poolName()).isEqualTo("b");
    assertThat(changed.maxPoolSize()).isEqualTo(6);
  }
}
