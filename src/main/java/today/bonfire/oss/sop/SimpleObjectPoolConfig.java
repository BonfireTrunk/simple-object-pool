package today.bonfire.oss.sop;

import lombok.Getter;
import lombok.experimental.Accessors;
import org.slf4j.Logger;

import java.time.Duration;

/**
 * {@code SimpleObjectPoolConfig} provides the configuration settings for a {@link SimpleObjectPool}.
 * It encapsulates various parameters that control the behavior of the object pool, such as pool size,
 * testing options, time settings, eviction policies, and retry mechanisms.
 *
 * <p>This class uses a builder pattern to construct instances, allowing for a flexible and readable
 * configuration setup. The builder also includes validation logic to ensure that the configuration
 * settings are consistent and valid.
 */
@Getter @Accessors(fluent = true)
public class SimpleObjectPoolConfig {

  private static final Logger log = org.slf4j.LoggerFactory.getLogger(SimpleObjectPoolConfig.class);

  /**
   * Returns the maximum number of objects that the pool can hold.
   *
   * @return The maximum pool size.
   */
  private final int maxPoolSize;

  /**
   * Returns the minimum number of objects that the pool should maintain.
   *
   * @return The minimum pool size.
   */
  private final int minPoolSize;

  /**
   * Returns whether the pool should use a fair queuing policy.
   *
   * @return {@code true} if the pool should use a fair queuing policy, {@code false} otherwise.
   */
  private final boolean fairness;

  /**
   * Returns whether objects should be tested upon creation.
   *
   * @return {@code true} if objects should be tested upon creation, {@code false} otherwise.
   */
  private final boolean testOnCreate;

  /**
   * Returns whether objects should be tested before being borrowed from the pool.
   *
   * @return {@code true} if objects should be tested before being borrowed, {@code false} otherwise.
   */
  private final boolean testOnBorrow;

  /**
   * Returns whether objects should be tested when returned to the pool.
   *
   * @return {@code true} if objects should be tested when returned, {@code false} otherwise.
   */
  private final boolean testOnReturn;

  /**
   * Returns whether objects should be tested while they are idle in the pool.
   *
   * @return {@code true} if objects should be tested while idle, {@code false} otherwise.
   */
  private final boolean testWhileIdle;

  /**
   * Returns the duration between runs of the abandoned object check.
   *
   * @return The duration in milliseconds between abandoned object check runs.
   */
  private final long durationBetweenAbandonCheckRuns;

  /**
   * Returns the timeout duration for an object to be considered abandoned.
   *
   * @return The abandoned timeout duration in milliseconds.
   */
  private final long abandonedTimeout;

  /**
   * Returns the duration between runs of the eviction process.
   *
   * @return The duration between eviction runs in milliseconds.
   */
  private final long durationBetweenEvictionsRuns;

  /**
   * Returns the timeout duration for an object to be considered idle.
   *
   * @return The object idle timeout duration in milliseconds.
   */
  private final long objEvictionTimeout;

  /**
   * Returns the number of objects to test per eviction run.
   *
   * @return The number of objects to test per eviction run.
   */
  private final int numValidationsPerEvictionRun;

  /**
   * Returns the eviction policy to use when evicting objects from the pool.
   *
   * @return The eviction policy.
   */
  private final EvictionPolicy evictionPolicy;

  /**
   * Returns the maximum number of retries when borrowing an object from the pool.
   *
   * @return The maximum number of retries.
   */
  private final int maxRetries;

  /**
   * Returns the delay between retries when creating objects for the pool.
   * this applicable during the object creation failure scenario
   *
   * @return The retry delay in nanoseconds.
   */
  private final long retryCreationDelay;

  /**
   * Returns the timeout duration for waiting for an object to become available in the pool.
   *
   * @return The waiting for object timeout in nanoseconds.
   */
  private final long waitingForObjectTimeout;

  private SimpleObjectPoolConfig(Builder builder) {
    this.maxPoolSize                     = builder.maxPoolSize;
    this.minPoolSize                     = builder.minPoolSize;
    this.fairness                        = builder.fairness;
    this.testOnCreate                    = builder.testOnCreate;
    this.testOnBorrow                    = builder.testOnBorrow;
    this.testOnReturn                    = builder.testOnReturn;
    this.testWhileIdle                   = builder.testWhileIdle;
    this.durationBetweenAbandonCheckRuns = builder.durationBetweenAbandonCheckRuns.toMillis();
    this.abandonedTimeout                = builder.abandonedTimeout.toMillis();
    this.durationBetweenEvictionsRuns    = builder.durationBetweenEvictionsRuns.toMillis();
    this.objEvictionTimeout           = builder.objEvictionTimeout.toMillis();
    this.numValidationsPerEvictionRun = builder.numValidationsPerEvictionRun;
    this.evictionPolicy                  = builder.evictionPolicy;
    this.maxRetries                      = builder.maxRetries;
    this.retryCreationDelay           = builder.retryCreationDelay.toNanos();
    this.waitingForObjectTimeout      = builder.waitingForObjectTimeout.toNanos();
  }

  public static Builder builder() {
    return new Builder();
  }

  public static SimpleObjectPoolConfig defaultConfig() {
    return new Builder().build();
  }

  public Builder toBuilder() {
    return new Builder()
        .maxPoolSize(this.maxPoolSize)
        .minPoolSize(this.minPoolSize)
        .fairness(this.fairness)
        .testOnCreate(this.testOnCreate)
        .testOnBorrow(this.testOnBorrow)
        .testOnReturn(this.testOnReturn)
        .testWhileIdle(this.testWhileIdle)
        .durationBetweenAbandonCheckRuns(Duration.ofMillis(this.durationBetweenAbandonCheckRuns))
        .abandonedTimeout(Duration.ofMillis(this.abandonedTimeout))
        .durationBetweenEvictionsRuns(Duration.ofMillis(this.durationBetweenEvictionsRuns))
        .objEvictionTimeout(Duration.ofMillis(this.objEvictionTimeout))
        .numValidationsPerEvictionRun(this.numValidationsPerEvictionRun)
        .evictionPolicy(this.evictionPolicy)
        .maxRetries(this.maxRetries)
        .retryCreationDelay(Duration.ofNanos(this.retryCreationDelay))
        .waitingForObjectTimeout(Duration.ofNanos(this.waitingForObjectTimeout));
  }

  public enum EvictionPolicy {
    OLDEST_FIRST,
    LEAST_USED,
    RANDOM // Default
  }

  /**
   * {@code Builder} is a builder for {@link SimpleObjectPoolConfig}.
   * It allows for a flexible and readable configuration setup.
   */
  public static class Builder {
    private int            maxPoolSize                     = 8;
    private int            minPoolSize                     = 0;
    private boolean        fairness                        = true;
    private boolean        testOnCreate                    = false;
    private boolean        testOnBorrow                 = true;
    private boolean        testOnReturn                    = false;
    private boolean        testWhileIdle                = true;
    private Duration       durationBetweenAbandonCheckRuns = Duration.ofSeconds(60);
    private Duration       abandonedTimeout                = Duration.ofSeconds(60);
    private Duration       durationBetweenEvictionsRuns    = Duration.ofMinutes(5);
    private Duration       objEvictionTimeout           = Duration.ofMinutes(5);
    private Integer        numValidationsPerEvictionRun = null;
    private EvictionPolicy evictionPolicy               = EvictionPolicy.RANDOM;
    private Integer        maxRetries                      = null;
    private Duration       retryCreationDelay           = Duration.ZERO;
    private Duration       waitingForObjectTimeout         = Duration.ofSeconds(10);

    /**
     * Sets the maximum number of objects that the pool can hold.
     *
     * @param maxPoolSize The maximum pool size.
     * @return This {@code Builder} instance.
     */
    public Builder maxPoolSize(int maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
      return this;
    }

    /**
     * Sets the minimum number of objects that the pool should maintain.
     *
     * @param minPoolSize The minimum pool size.
     * @return This {@code Builder} instance.
     */
    public Builder minPoolSize(int minPoolSize) {
      this.minPoolSize = minPoolSize;
      return this;
    }

    /**
     * Sets whether the pool should use a fair queuing policy.
     *
     * @param fairness {@code true} if the pool should use a fair queuing policy, {@code false} otherwise.
     * @return This {@code Builder} instance.
     */
    public Builder fairness(boolean fairness) {
      this.fairness = fairness;
      return this;
    }

    /**
     * Sets whether objects should be tested upon creation.
     *
     * @param testOnCreate {@code true} if objects should be tested upon creation, {@code false} otherwise.
     * @return This {@code Builder} instance.
     */
    public Builder testOnCreate(boolean testOnCreate) {
      this.testOnCreate = testOnCreate;
      return this;
    }

    /**
     * Sets whether objects should be tested before being borrowed from the pool.
     *
     * @param testOnBorrow {@code true} if objects should be tested before being borrowed, {@code false} otherwise.
     * @return This {@code Builder} instance.
     */
    public Builder testOnBorrow(boolean testOnBorrow) {
      this.testOnBorrow = testOnBorrow;
      return this;
    }

    /**
     * Sets whether objects should be tested when returned to the pool.
     *
     * @param testOnReturn {@code true} if objects should be tested when returned, {@code false} otherwise.
     * @return This {@code Builder} instance.
     */
    public Builder testOnReturn(boolean testOnReturn) {
      this.testOnReturn = testOnReturn;
      return this;
    }

    /**
     * Sets whether objects should be tested while they are idle in the pool.
     *
     * @param testWhileIdle {@code true} if objects should be tested while idle, {@code false} otherwise.
     * @return This {@code Builder} instance.
     */
    public Builder testWhileIdle(boolean testWhileIdle) {
      this.testWhileIdle = testWhileIdle;
      return this;
    }

    /**
     * Sets the duration between runs of the abandoned object check.
     *
     * @param durationBetweenAbandonCheckRuns The duration between abandoned object check runs.
     * @return This {@code Builder} instance.
     */
    public Builder durationBetweenAbandonCheckRuns(Duration durationBetweenAbandonCheckRuns) {
      this.durationBetweenAbandonCheckRuns = durationBetweenAbandonCheckRuns;
      return this;
    }

    /**
     * Sets the timeout duration for an object to be considered abandoned.
     *
     * @param abandonedTimeout The abandoned timeout duration.
     * @return This {@code Builder} instance.
     */
    public Builder abandonedTimeout(Duration abandonedTimeout) {
      this.abandonedTimeout = abandonedTimeout;
      return this;
    }

    /**
     * Sets the duration between runs of the eviction process.
     *
     * @param durationBetweenEvictionsRuns The duration between eviction runs.
     * @return This {@code Builder} instance.
     */
    public Builder durationBetweenEvictionsRuns(Duration durationBetweenEvictionsRuns) {
      this.durationBetweenEvictionsRuns = durationBetweenEvictionsRuns;
      return this;
    }

    /**
     * Sets the timeout duration for an object from its creation time to be considered for
     * eviction.
     *
     * @param objEvictionTimeout The object idle timeout duration.
     * @return This {@code Builder} instance.
     */
    public Builder objEvictionTimeout(Duration objEvictionTimeout) {
      this.objEvictionTimeout = objEvictionTimeout;
      return this;
    }

    /**
     * Sets the number of objects to validate in an eviction run.
     * Note that all objects in pool are tested  based on {@link #objEvictionTimeout},
     * This param is used for validating using the {@link PooledObjectFactory#isObjectValid(PoolObject)} which in
     * many cases take some time compared to simple tests.
     * <br>
     * If set to 0 then no factory validation will be done. <br>
     * If set to null or not set then the factory validation will be limited to {@link #maxPoolSize()}
     *
     * @param numValidationsPerEvictionRun The number of objects to test per eviction run.
     * @return This {@code Builder} instance.
     */
    public Builder numValidationsPerEvictionRun(Integer numValidationsPerEvictionRun) {
      this.numValidationsPerEvictionRun = numValidationsPerEvictionRun;
      return this;
    }

    /**
     * Sets the eviction policy to use when evicting objects from the pool.
     * Defaults to {@link EvictionPolicy#RANDOM} if not set.
     *
     * @param evictionPolicy The eviction policy.
     * @return This {@code Builder} instance.
     */
    public Builder evictionPolicy(EvictionPolicy evictionPolicy) {
      this.evictionPolicy = evictionPolicy;
      return this;
    }

    /**
     * Sets the maximum number of retries when borrowing an object from the pool.
     *
     * @param maxRetries The maximum number of retries.
     * @return This {@code Builder} instance.
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Sets the delay between retries when creating an object for the pool.
     * This is applicable during the object creation failure scenario case only.
     *
     * @param retryCreationDelay The retry delay.
     * @return This {@code Builder} instance.
     */
    public Builder retryCreationDelay(Duration retryCreationDelay) {
      this.retryCreationDelay = retryCreationDelay;
      return this;
    }

    /**
     * Sets the timeout duration for waiting for an object to become available in the pool.
     *
     * @param waitingForObjectTimeout The waiting for object timeout duration.
     * @return This {@code Builder} instance.
     */
    public Builder waitingForObjectTimeout(Duration waitingForObjectTimeout) {
      this.waitingForObjectTimeout = waitingForObjectTimeout;
      return this;
    }


    /**
     * Validates the configuration settings to ensure that they are consistent and valid.
     * This method will throw an exception if any of the configuration settings are invalid.
     * It will also log warnings if any of the settings may result in unexpected behavior.
     */
    public void validate() {
      if (maxPoolSize < 1) {
        throw new IllegalArgumentException("maxPoolSize must be greater than 0");
      }
      if (minPoolSize < 0) {
        throw new IllegalArgumentException("minPoolSize cannot be negative");
      }
      if (minPoolSize > maxPoolSize) {
        throw new IllegalArgumentException("minPoolSize cannot be greater than maxPoolSize");
      }

      if (maxRetries > maxPoolSize) {
        log.warn("maxRetries is greater than maxPoolSize. This may result in excessive retries.");
      }

      if (maxRetries < 0) {
        log.info("maxRetries is negative. This means that there will be no retries.");
      }

      if (waitingForObjectTimeout.isNegative()) {
        log.warn("waitingForObjectTimeout is negative. This assumes that the there will be no waiting timeout.");
      }

      if (retryCreationDelay.isNegative()) {
        log.warn("retryCreationDelay is negative. object creation retries will be immediate.");
      }

      if (retryCreationDelay.isPositive()) {
        log.info(
            "You have set a positive retryCreationDelay. This may result is a case where object borrow wait time may be as high as waitingForObjectTimeout + retryCreationDelay in case of creation failure scenario.");
      }

      if (objEvictionTimeout.isNegative()) {
        log.warn("objIdleTimeout is negative. This means idle objects will not be destroyed.");
      }

      if (objEvictionTimeout.isZero()) {
        log.info("objIdleTimeout is zero. This means idle objects will be destroyed immediately.");
      }

      if (durationBetweenEvictionsRuns.isNegative() || durationBetweenEvictionsRuns.isZero()) {
        throw new IllegalArgumentException("durationBetweenEvictionsRuns must be positive");
      }

      if (durationBetweenEvictionsRuns.compareTo(waitingForObjectTimeout) <= 0) {
        log.warn("durationBetweenEvictionsRuns is less than or equal to waitingForObjectTimeout. This may result in unnecessary actions on the pool.");
      }

      if (numValidationsPerEvictionRun < 0 || numValidationsPerEvictionRun > maxPoolSize) {
        throw new IllegalArgumentException("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
      }

      if (abandonedTimeout.isNegative() || abandonedTimeout.isZero()) {
        throw new IllegalArgumentException("abandonedTimeout must be positive. It is a good idea to set this value based on your applications needs.");
      }

      if (abandonedTimeout.compareTo(waitingForObjectTimeout) <= 0) {
        log.warn("abandonedTimeout is less than or equal to waitingForObjectTimeout. This may result in excessive actions on the pool.");
      }

      if (durationBetweenAbandonCheckRuns.isNegative() || durationBetweenAbandonCheckRuns.isZero()) {
        throw new IllegalArgumentException("durationBetweenAbandonCheckRuns must be positive");
      }

    }

    public SimpleObjectPoolConfig build() {
      if (numValidationsPerEvictionRun == null) {
        numValidationsPerEvictionRun = maxPoolSize;
      }
      if (maxRetries == null) {
        maxRetries = Math.floorDiv(maxPoolSize, 4);
      }
      SimpleObjectPoolConfig config = new SimpleObjectPoolConfig(this);
      validate();
      return config;
    }
  }
}
