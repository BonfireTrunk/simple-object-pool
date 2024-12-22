package today.bonfire.oss.sop;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleObjectPoolConfigTest {

  private static final Logger                      log = LoggerFactory.getLogger(SimpleObjectPoolConfig.class);
  private              ListAppender<ILoggingEvent> listAppender;

  @BeforeEach
  void setUp() {
    listAppender = new ListAppender<>();
    listAppender.start();
    ((ch.qos.logback.classic.Logger) log).addAppender(listAppender);
  }

  @AfterEach
  void tearDown() {
    ((ch.qos.logback.classic.Logger) log).detachAppender(listAppender);
  }

  @Test
  void validate_validConfig() {
    assertThatCode(() -> SimpleObjectPoolConfig.builder().build()).doesNotThrowAnyException();
  }

  @Test
  void validate_invalidMaxPoolSize() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxPoolSize(0).build())
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
  void validate_invalidMinPoolSize_greaterThanMaxPoolSize() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().minPoolSize(2).maxPoolSize(1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("minPoolSize cannot be greater than maxPoolSize");
  }

  @Test
  void validate_invalidDurationBetweenEvictionsRuns_negative() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(Duration.ofMillis(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must be positive");
  }

  @Test
  void validate_invalidDurationBetweenEvictionsRuns_zero() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(Duration.ofMillis(0)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenEvictionsRuns must be positive");
  }

  @Test
  void validate_invalidNumValidationsPerEvictionRun_negative() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().numValidationsPerEvictionRun(-1).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
  }

  @Test
  void validate_invalidNumValidationsPerEvictionRun_greaterThanMaxPoolSize() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().maxPoolSize(1).numValidationsPerEvictionRun(2).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
  }

  @Test
  void validate_invalidAbandonedTimeout_negative() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofMillis(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("abandonedTimeout must be positive. It is a good idea to set this value based on your applications needs.");
  }

  @Test
  void validate_invalidAbandonedTimeout_zero() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofMillis(0)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("abandonedTimeout must be positive. It is a good idea to set this value based on your applications needs.");
  }

  @Test
  void validate_invalidDurationBetweenAbandonCheckRuns_negative() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenAbandonCheckRuns(Duration.ofMillis(-1)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenAbandonCheckRuns must be positive");
  }

  @Test
  void validate_invalidDurationBetweenAbandonCheckRuns_zero() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder().durationBetweenAbandonCheckRuns(Duration.ofMillis(0)).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("durationBetweenAbandonCheckRuns must be positive");
  }

  @Test
  void validate_warningForMaxRetriesGreaterThanMaxPoolSize() {
    SimpleObjectPoolConfig.builder()
                          .maxPoolSize(5)
                          .maxRetries(10)
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("maxRetries is greater than maxPoolSize. This may result in excessive retries.");
  }

  @Test
  void validate_warnMaxRetries_negative() {
    SimpleObjectPoolConfig.builder().maxRetries(-1).build();
    // Check logs for info
    // log.info("maxRetries is negative. This means that there will be no retries.");
  }

  @Test
  void validate_warningForNegativeMaxRetries() {
    SimpleObjectPoolConfig.builder()
                          .maxRetries(-1)
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("maxRetries is negative. This means that there will be no retries.");
  }

  @Test
  void validate_warnWaitingForObjectTimeout_negative() {
    SimpleObjectPoolConfig.builder().waitingForObjectTimeout(Duration.ofMillis(-1)).build();
    // Check logs for warning
    // log.warn("waitingForObjectTimeout is negative. This assumes that the there will be no waiting timeout.");
  }

  @Test
  void validate_warningForNegativeWaitingTimeout() {
    SimpleObjectPoolConfig.builder()
                          .waitingForObjectTimeout(Duration.ofMillis(-1))
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("waitingForObjectTimeout is negative. This assumes that the there will be no waiting timeout.");
  }

  @Test
  void validate_warnRetryCreationDelay_negative() {
    SimpleObjectPoolConfig.builder().retryCreationDelay(Duration.ofMillis(-1)).build();
    // Check logs for warning
    // log.warn("retryCreationDelay is negative. object creation retries will be immediate.");
  }

  @Test
  void validate_warningForNegativeRetryCreationDelay() {
    SimpleObjectPoolConfig.builder()
                          .retryCreationDelay(Duration.ofMillis(-1))
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("retryCreationDelay is negative. object creation retries will be immediate.");
  }

  @Test
  void validate_infoRetryCreationDelay_positive() {
    SimpleObjectPoolConfig.builder().retryCreationDelay(Duration.ofMillis(1)).build();
    // Check logs for info
    // log.info("You have set a positive retryCreationDelay. This may result is a case where object borrow wait time may be as high as waitingForObjectTimeout + retryCreationDelay in case of creation failure scenario.");
  }

  @Test
  void validate_warningForPositiveRetryCreationDelay() {
    SimpleObjectPoolConfig.builder()
                          .retryCreationDelay(Duration.ofMillis(100))
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains(
            "You have set a positive retryCreationDelay. This may result is a case where object borrow wait time may be as high as waitingForObjectTimeout + retryCreationDelay in case of creation failure scenario.");
  }

  @Test
  void validate_warnObjEvictionTimeout_negative() {
    SimpleObjectPoolConfig.builder().objEvictionTimeout(Duration.ofMillis(-1)).build();
    // Check logs for warning
    // log.warn("objIdleTimeout is negative. This means idle objects will not be destroyed.");
  }

  @Test
  void validate_warningForNegativeObjEvictionTimeout() {
    SimpleObjectPoolConfig.builder()
                          .objEvictionTimeout(Duration.ofMillis(-1))
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("objIdleTimeout is negative. This means idle objects will not be destroyed.");
  }

  @Test
  void validate_infoObjEvictionTimeout_zero() {
    SimpleObjectPoolConfig.builder().objEvictionTimeout(Duration.ofMillis(0)).build();
    // Check logs for info
    // log.info("objIdleTimeout is zero. This means idle objects will be destroyed immediately.");
  }

  @Test
  void validate_warningForZeroObjEvictionTimeout() {
    SimpleObjectPoolConfig.builder()
                          .objEvictionTimeout(Duration.ZERO)
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("objIdleTimeout is zero. This means idle objects will be destroyed immediately.");
  }

  @Test
  void validate_warnDurationBetweenEvictionsRuns_lessThanOrEqualToWaitingForObjectTimeout() {
    SimpleObjectPoolConfig.builder().durationBetweenEvictionsRuns(Duration.ofSeconds(1)).waitingForObjectTimeout(Duration.ofSeconds(1)).build();
    // Check logs for warning
    // log.warn("durationBetweenEvictionsRuns is less than or equal to waitingForObjectTimeout. This may result in unnecessary actions on the pool.");
  }

  @Test
  void validate_warningForEvictionRunsLessThanWaitingTimeout() {
    SimpleObjectPoolConfig.builder()
                          .durationBetweenEvictionsRuns(Duration.ofSeconds(5))
                          .waitingForObjectTimeout(Duration.ofSeconds(10))
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("durationBetweenEvictionsRuns is less than or equal to waitingForObjectTimeout. This may result in unnecessary actions on the pool.");
  }

  @Test
  void validate_warnAbandonedTimeout_lessThanOrEqualToWaitingForObjectTimeout() {
    SimpleObjectPoolConfig.builder().abandonedTimeout(Duration.ofSeconds(1)).waitingForObjectTimeout(Duration.ofSeconds(1)).build();
    // Check logs for warning
    // log.warn("abandonedTimeout is less than or equal to waitingForObjectTimeout. This may result in excessive actions on the pool.");
  }

  @Test
  void validate_warningForAbandonedTimeoutLessThanWaitingTimeout() {
    SimpleObjectPoolConfig.builder()
                          .abandonedTimeout(Duration.ofSeconds(5))
                          .waitingForObjectTimeout(Duration.ofSeconds(10))
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("abandonedTimeout is less than or equal to waitingForObjectTimeout. This may result in excessive actions on the pool.");
  }

  @Test
  void toBuilder_copiesAllValues() {
    SimpleObjectPoolConfig original = SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(10)
                                                            .minPoolSize(2)
                                                            .fairness(false)
                                                            .testOnCreate(true)
                                                            .testOnBorrow(false)
                                                            .testOnReturn(true)
                                                            .testWhileIdle(false)
                                                            .durationBetweenAbandonCheckRuns(Duration.ofSeconds(30))
                                                            .abandonedTimeout(Duration.ofSeconds(45))
                                                            .durationBetweenEvictionsRuns(Duration.ofMinutes(3))
                                                            .objEvictionTimeout(Duration.ofMinutes(2))
                                                            .numValidationsPerEvictionRun(5)
                                                            .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.OLDEST_FIRST)
                                                            .maxRetries(3)
                                                            .retryCreationDelay(Duration.ofMillis(100))
                                                            .waitingForObjectTimeout(Duration.ofSeconds(5))
                                                            .build();

    SimpleObjectPoolConfig copy = original.toBuilder().build();

    // Verify all fields are copied correctly
    assertThat(copy.maxPoolSize()).isEqualTo(original.maxPoolSize());
    assertThat(copy.minPoolSize()).isEqualTo(original.minPoolSize());
    assertThat(copy.fairness()).isEqualTo(original.fairness());
    assertThat(copy.testOnCreate()).isEqualTo(original.testOnCreate());
    assertThat(copy.testOnBorrow()).isEqualTo(original.testOnBorrow());
    assertThat(copy.testOnReturn()).isEqualTo(original.testOnReturn());
    assertThat(copy.testWhileIdle()).isEqualTo(original.testWhileIdle());
    assertThat(copy.durationBetweenAbandonCheckRuns()).isEqualTo(original.durationBetweenAbandonCheckRuns());
    assertThat(copy.abandonedTimeout()).isEqualTo(original.abandonedTimeout());
    assertThat(copy.durationBetweenEvictionsRuns()).isEqualTo(original.durationBetweenEvictionsRuns());
    assertThat(copy.objEvictionTimeout()).isEqualTo(original.objEvictionTimeout());
    assertThat(copy.numValidationsPerEvictionRun()).isEqualTo(original.numValidationsPerEvictionRun());
    assertThat(copy.evictionPolicy()).isEqualTo(original.evictionPolicy());
    assertThat(copy.maxRetries()).isEqualTo(original.maxRetries());
    assertThat(copy.retryCreationDelay()).isEqualTo(original.retryCreationDelay());
    assertThat(copy.waitingForObjectTimeout()).isEqualTo(original.waitingForObjectTimeout());
  }

  @Test
  void validate_evictionPolicies() {
    // Test each eviction policy can be set
    assertThatCode(() -> SimpleObjectPoolConfig.builder()
                                               .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.OLDEST_FIRST)
                                               .build()).doesNotThrowAnyException();

    assertThatCode(() -> SimpleObjectPoolConfig.builder()
                                               .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.LEAST_USED)
                                               .build()).doesNotThrowAnyException();

    assertThatCode(() -> SimpleObjectPoolConfig.builder()
                                               .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.RANDOM)
                                               .build()).doesNotThrowAnyException();

    // Test default eviction policy
    SimpleObjectPoolConfig config = SimpleObjectPoolConfig.builder().build();
    assertThat(config.evictionPolicy()).isEqualTo(SimpleObjectPoolConfig.EvictionPolicy.RANDOM);
  }

  @Test
  void validate_timeoutComparisons() {
    // Test warning condition: durationBetweenEvictionsRuns <= waitingForObjectTimeout
    SimpleObjectPoolConfig config1 = SimpleObjectPoolConfig.builder()
                                                           .durationBetweenEvictionsRuns(Duration.ofSeconds(5))
                                                           .waitingForObjectTimeout(Duration.ofSeconds(10))
                                                           .build();

    // Test warning condition: abandonedTimeout <= waitingForObjectTimeout
    SimpleObjectPoolConfig config2 = SimpleObjectPoolConfig.builder()
                                                           .abandonedTimeout(Duration.ofSeconds(5))
                                                           .waitingForObjectTimeout(Duration.ofSeconds(10))
                                                           .build();
  }

  @Test
  void validate_edgeCases() {
    // Test with maximum possible pool size
    assertThatCode(() -> SimpleObjectPoolConfig.builder()
                                               .maxPoolSize(Integer.MAX_VALUE)
                                               .build()).doesNotThrowAnyException();

    // Test with minimum valid pool size
    assertThatCode(() -> SimpleObjectPoolConfig.builder()
                                               .maxPoolSize(1)
                                               .build()).doesNotThrowAnyException();

    // Test with maximum duration values
    assertThatCode(() -> SimpleObjectPoolConfig.builder()
                                               .durationBetweenEvictionsRuns(Duration.ofMillis(Long.MAX_VALUE))
                                               .abandonedTimeout(Duration.ofMillis(Long.MAX_VALUE))
                                               .waitingForObjectTimeout(Duration.ofNanos(Long.MAX_VALUE))
                                               .build()).doesNotThrowAnyException();
  }

  @Test
  void validate_nullDurations() {
    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder()
                                                   .abandonedTimeout(null)
                                                   .build())
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder()
                                                   .durationBetweenEvictionsRuns(null)
                                                   .build())
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder()
                                                   .objEvictionTimeout(null)
                                                   .build())
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder()
                                                   .retryCreationDelay(null)
                                                   .build())
        .isInstanceOf(NullPointerException.class);

    assertThatThrownBy(() -> SimpleObjectPoolConfig.builder()
                                                   .waitingForObjectTimeout(null)
                                                   .build())
        .isInstanceOf(NullPointerException.class);
  }

  @Test
  void validate_builderChaining() {
    // Test that all builder methods properly chain and maintain state
    SimpleObjectPoolConfig config = SimpleObjectPoolConfig.builder()
                                                          .maxPoolSize(10)
                                                          .minPoolSize(2)
                                                          .fairness(false)
                                                          .testOnCreate(true)
                                                          .testOnBorrow(false)
                                                          .testOnReturn(true)
                                                          .testWhileIdle(false)
                                                          .durationBetweenAbandonCheckRuns(Duration.ofSeconds(30))
                                                          .abandonedTimeout(Duration.ofSeconds(45))
                                                          .durationBetweenEvictionsRuns(Duration.ofMinutes(3))
                                                          .objEvictionTimeout(Duration.ofMinutes(2))
                                                          .numValidationsPerEvictionRun(5)
                                                          .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.OLDEST_FIRST)
                                                          .maxRetries(3)
                                                          .retryCreationDelay(Duration.ofMillis(100))
                                                          .waitingForObjectTimeout(Duration.ofSeconds(5))
                                                          .build();

    assertThat(config.maxPoolSize()).isEqualTo(10);
    assertThat(config.minPoolSize()).isEqualTo(2);
    assertThat(config.fairness()).isFalse();
    assertThat(config.testOnCreate()).isTrue();
    assertThat(config.testOnBorrow()).isFalse();
    assertThat(config.testOnReturn()).isTrue();
    assertThat(config.testWhileIdle()).isFalse();
    assertThat(config.durationBetweenAbandonCheckRuns()).isEqualTo(30000); // 30 seconds in millis
    assertThat(config.abandonedTimeout()).isEqualTo(45000); // 45 seconds in millis
    assertThat(config.durationBetweenEvictionsRuns()).isEqualTo(180000); // 3 minutes in millis
    assertThat(config.objEvictionTimeout()).isEqualTo(120000); // 2 minutes in millis
    assertThat(config.numValidationsPerEvictionRun()).isEqualTo(5);
    assertThat(config.evictionPolicy()).isEqualTo(SimpleObjectPoolConfig.EvictionPolicy.OLDEST_FIRST);
    assertThat(config.maxRetries()).isEqualTo(3);
    assertThat(config.retryCreationDelay()).isEqualTo(100000000); // 100 millis in nanos
    assertThat(config.waitingForObjectTimeout()).isEqualTo(5000000000L); // 5 seconds in nanos
  }

  @Test
  void validate_warnMaxRetries_greaterThanMaxPoolSize() {
    SimpleObjectPoolConfig.builder()
                          .maxPoolSize(5)
                          .maxRetries(10)
                          .build();

    assertThat(listAppender.list)
        .extracting(ILoggingEvent::getMessage)
        .contains("maxRetries is greater than maxPoolSize. This may result in excessive retries.");
  }
}
