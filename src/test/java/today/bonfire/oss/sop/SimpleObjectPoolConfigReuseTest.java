package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleObjectPoolConfigReuseTest {

  @Test
  void unsetValidationsFollowTheMaximumWhenABuilderIsReused() {
    SimpleObjectPoolConfig.Builder builder = SimpleObjectPoolConfig.builder().maxPoolSize(8);

    assertThat(builder.build().numValidationsPerEvictionRun()).isEqualTo(8);
    assertThat(builder.maxPoolSize(4).build().numValidationsPerEvictionRun()).isEqualTo(4);
    assertThat(builder.maxPoolSize(50).build().numValidationsPerEvictionRun()).isEqualTo(50);
  }

  @Test
  void unsetValidationsStayUnsetThroughToBuilderWhenShrunk() {
    SimpleObjectPoolConfig defaults = SimpleObjectPoolConfig.defaultConfig();

    assertThat(defaults.toBuilder().maxPoolSize(4).build().numValidationsPerEvictionRun()).isEqualTo(4);
  }

  @Test
  void unsetValidationsStayUnsetThroughToBuilderWhenGrown() {
    SimpleObjectPoolConfig defaults = SimpleObjectPoolConfig.defaultConfig();

    assertThat(defaults.toBuilder().maxPoolSize(50).build().numValidationsPerEvictionRun()).isEqualTo(50);
  }

  @Test
  void explicitValidationsRoundTripAndStillValidateAgainstANewMaximum() {
    SimpleObjectPoolConfig explicit = SimpleObjectPoolConfig.builder()
                                                            .maxPoolSize(8)
                                                            .numValidationsPerEvictionRun(6)
                                                            .build();

    assertThat(explicit.toBuilder().maxPoolSize(10).build().numValidationsPerEvictionRun()).isEqualTo(6);
    assertThatThrownBy(() -> explicit.toBuilder().maxPoolSize(5).build())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
  }

  @Test
  void repeatedBuildsKeepGetterValuesAndBuilderInputsUnchanged() {
    SimpleObjectPoolConfig.Builder builder = SimpleObjectPoolConfig.builder()
                                                                    .abandonedTimeout(Duration.ZERO)
                                                                    .retryCreationDelay(Duration.ofMillis(-1));

    SimpleObjectPoolConfig first  = builder.build();
    SimpleObjectPoolConfig second = builder.build();

    assertThat(second.poolName()).isEqualTo(first.poolName());
    assertThat(second.maxPoolSize()).isEqualTo(first.maxPoolSize());
    assertThat(second.minPoolSize()).isEqualTo(first.minPoolSize());
    assertThat(second.fairness()).isEqualTo(first.fairness());
    assertThat(second.lifo()).isEqualTo(first.lifo());
    assertThat(second.testOnCreate()).isEqualTo(first.testOnCreate());
    assertThat(second.testOnBorrow()).isEqualTo(first.testOnBorrow());
    assertThat(second.testOnReturn()).isEqualTo(first.testOnReturn());
    assertThat(second.testWhileIdle()).isEqualTo(first.testWhileIdle());
    assertThat(second.durationBetweenAbandonCheckRuns()).isEqualTo(first.durationBetweenAbandonCheckRuns());
    assertThat(second.abandonedTimeout()).isEqualTo(first.abandonedTimeout());
    assertThat(second.durationBetweenEvictionsRuns()).isEqualTo(first.durationBetweenEvictionsRuns());
    assertThat(second.objEvictionTimeout()).isEqualTo(first.objEvictionTimeout());
    assertThat(second.numValidationsPerEvictionRun()).isEqualTo(first.numValidationsPerEvictionRun());
    assertThat(second.evictionPolicy()).isEqualTo(first.evictionPolicy());
    assertThat(second.maxRetries()).isEqualTo(first.maxRetries());
    assertThat(second.retryCreationDelay()).isEqualTo(first.retryCreationDelay());
    assertThat(second.waitingForObjectTimeout()).isEqualTo(first.waitingForObjectTimeout());
    assertThat(builderField(builder, "numValidationsPerEvictionRun")).isNull();
    assertThat(builderField(builder, "abandonedTimeout")).isEqualTo(Duration.ZERO);
    assertThat(builderField(builder, "retryCreationDelay")).isEqualTo(Duration.ofMillis(-1));
    assertThat(builder.abandonedTimeout(Duration.ofSeconds(1)).build().abandonedTimeout()).isEqualTo(Duration.ofSeconds(1));
  }

  @Test
  void disabledAbandonTimeoutIsNormalisedBeforeItsCheckIntervalIsValidated() {
    assertThat(SimpleObjectPoolConfig.builder()
                                     .abandonedTimeout(Duration.ZERO)
                                     .durationBetweenAbandonCheckRuns(Duration.ZERO)
                                     .build()
                                     .abandonedTimeout()).isNull();
    assertThat(SimpleObjectPoolConfig.builder()
                                     .abandonedTimeout(Duration.ofMillis(-1))
                                     .durationBetweenAbandonCheckRuns(Duration.ZERO)
                                     .build()
                                     .abandonedTimeout()).isNull();
  }

  @Test
  void subclassesCanKeepTheProtectedPrepareAndCheckContract() {
    ExtendedConfig config = new ExtendedBuilder().maxPoolSize(3).build();

    assertThat(config.numValidationsPerEvictionRun()).isEqualTo(3);
  }

  private static final class ExtendedConfig extends SimpleObjectPoolConfig {
    ExtendedConfig(ExtendedBuilder builder) {
      super(builder.prepareAndCheck());
    }
  }

  private static final class ExtendedBuilder extends SimpleObjectPoolConfig.Builder {
    @Override
    protected ExtendedBuilder prepareAndCheck() {
      super.prepareAndCheck();
      return this;
    }

    @Override
    public ExtendedConfig build() {
      return new ExtendedConfig(prepareAndCheck());
    }

    @Override
    public ExtendedBuilder maxPoolSize(int maxPoolSize) {
      super.maxPoolSize(maxPoolSize);
      return this;
    }
  }

  private static Object builderField(SimpleObjectPoolConfig.Builder builder, String fieldName) {
    try {
      Field field = SimpleObjectPoolConfig.Builder.class.getDeclaredField(fieldName);
      field.setAccessible(true);
      return field.get(builder);
    } catch (ReflectiveOperationException e) {
      throw new AssertionError(e);
    }
  }
}
