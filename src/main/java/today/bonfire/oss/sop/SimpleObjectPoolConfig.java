package today.bonfire.oss.sop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.UUID;

/**
 * {@code SimpleObjectPoolConfig} provides the configuration settings for a
 * {@link SimpleObjectPool}.
 *
 * <p>
 * This class uses a builder pattern to construct instances, allowing for a flexible and readable
 * configuration setup. The builder also includes validation logic to ensure that the configuration
 * settings are consistent and valid. Instances are immutable.
 *
 * <p>
 * Summary of the settings:
 * <ul>
 *   <li><b>Capacity</b>: {@code maxPoolSize} is the upper bound of objects (idle plus borrowed).
 *   {@code minPoolSize} is the number of idle objects the scheduled maintenance task tries to keep.</li>
 *   <li><b>Idle ordering</b>: {@code lifo} (default {@code true}) hands out the most recently returned idle
 *   object first, otherwise the least recently returned one. {@code fairness} controls whether waiting
 *   borrowers are served in arrival order.</li>
 *   <li><b>Validation</b>: {@code testOnCreate}, {@code testOnBorrow}, {@code testOnReturn} and
 *   {@code testWhileIdle} select when the factory validation is invoked.</li>
 *   <li><b>Eviction</b>: a scheduled run every {@code durationBetweenEvictionsRuns} removes idle objects older than
 *   {@code objEvictionTimeout}, validates up to {@code numValidationsPerEvictionRun} idle objects and tops the pool
 *   up to {@code minPoolSize}. {@code evictionPolicy} decides which objects are examined first.</li>
 *   <li><b>Abandon detection</b>: off by default. When {@code abandonedTimeout} is set, objects that have been
 *   borrowed for longer than the timeout are destroyed. Do not enable it if callers legitimately hold objects for
 *   long periods.</li>
 *   <li><b>Retries</b>: {@code maxRetries} is the number of additional creation attempts per borrow after the
 *   first one fails (0 means exactly one attempt), separated by {@code retryCreationDelay}.</li>
 *   <li><b>Wait semantics</b>: {@code waitingForObjectTimeout} negative waits indefinitely, zero fails immediately
 *   when the pool is exhausted, positive waits at most that long.</li>
 * </ul>
 */
public class SimpleObjectPoolConfig {

  private static final Logger log = LoggerFactory.getLogger(SimpleObjectPoolConfig.class);

  private final String         poolName;
  private final int            maxPoolSize;
  private final int            minPoolSize;
  private final boolean        fairness;
  private final boolean        lifo;
  private final boolean        testOnCreate;
  private final boolean        testOnBorrow;
  private final boolean        testOnReturn;
  private final boolean        testWhileIdle;
  private final Duration       durationBetweenAbandonCheckRuns;
  private final Duration       abandonedTimeout;
  private final Duration       durationBetweenEvictionsRuns;
  private final Duration       objEvictionTimeout;
  private final int            numValidationsPerEvictionRun;
  private final boolean        numValidationsPerEvictionRunSet;
  private final EvictionPolicy evictionPolicy;
  private final int            maxRetries;
  private final Duration       retryCreationDelay;
  private final Duration       waitingForObjectTimeout;

  protected SimpleObjectPoolConfig(Builder builder) {
    this.poolName                        = builder.poolName;
    this.maxPoolSize                     = builder.maxPoolSize;
    this.minPoolSize                     = builder.minPoolSize;
    this.fairness                        = builder.fairness;
    this.lifo                            = builder.lifo;
    this.testOnCreate                    = builder.testOnCreate;
    this.testOnBorrow                    = builder.testOnBorrow;
    this.testOnReturn                    = builder.testOnReturn;
    this.testWhileIdle                   = builder.testWhileIdle;
    this.durationBetweenAbandonCheckRuns = builder.durationBetweenAbandonCheckRuns;
    this.abandonedTimeout                = normalisedAbandonedTimeout(builder.abandonedTimeout);
    this.durationBetweenEvictionsRuns    = builder.durationBetweenEvictionsRuns;
    this.objEvictionTimeout              = builder.objEvictionTimeout;
    this.numValidationsPerEvictionRun    = builder.numValidationsPerEvictionRun == null ? builder.maxPoolSize : builder.numValidationsPerEvictionRun;
    this.numValidationsPerEvictionRunSet = builder.numValidationsPerEvictionRun != null;
    this.evictionPolicy                  = builder.evictionPolicy;
    this.maxRetries                      = builder.maxRetries;
    this.retryCreationDelay              = normalisedRetryCreationDelay(builder.retryCreationDelay);
    this.waitingForObjectTimeout         = builder.waitingForObjectTimeout;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static SimpleObjectPoolConfig defaultConfig() {
    return new Builder().build();
  }

  /** @return the name used for logging and debugging */
  public String poolName() {
    return poolName;
  }

  /**
   * Returns the maximum number of objects that the pool can hold.
   */
  public int maxPoolSize() {
    return maxPoolSize;
  }

  /**
   * Returns the minimum number of idle objects that the pool should maintain.
   */
  public int minPoolSize() {
    return minPoolSize;
  }

  /**
   * Returns whether the pool should use a fair queuing policy.
   *
   * @return {@code true} if the pool should use a fair queuing policy,
   *     {@code false} otherwise.
   */
  public boolean fairness() {
    return fairness;
  }

  /**
   * Returns whether idle objects are handed out last-in-first-out.
   *
   * @return {@code true} if the most recently returned idle object is borrowed first,
   *     {@code false} if the least recently returned one is borrowed first.
   */
  public boolean lifo() {
    return lifo;
  }

  /**
   * Returns whether objects should be tested upon creation.
   *
   * @return {@code true} if objects should be tested upon creation, {@code false}
   *     otherwise.
   */
  public boolean testOnCreate() {
    return testOnCreate;
  }

  /**
   * Returns whether objects should be tested before being borrowed from the pool.
   *
   * @return {@code true} if objects should be tested before being borrowed,
   *     {@code false} otherwise.
   */
  public boolean testOnBorrow() {
    return testOnBorrow;
  }

  /**
   * Returns whether objects should be tested when returned to the pool.
   *
   * @return {@code true} if objects should be tested when returned, {@code false}
   *     otherwise.
   */
  public boolean testOnReturn() {
    return testOnReturn;
  }

  /**
   * Returns whether objects should be tested while they are idle in the pool.
   *
   * @return {@code true} if objects should be tested while idle, {@code false}
   *     otherwise.
   */
  public boolean testWhileIdle() {
    return testWhileIdle;
  }

  /**
   * Returns the duration between runs of the abandoned object check.
   */
  public Duration durationBetweenAbandonCheckRuns() {
    return durationBetweenAbandonCheckRuns;
  }

  /**
   * Returns the timeout after which a borrowed object is considered abandoned.
   *
   * @return The abandoned timeout, or {@code null} if abandon detection is disabled.
   */
  public Duration abandonedTimeout() {
    return abandonedTimeout;
  }

  /**
   * Returns whether abandon detection is enabled.
   *
   * @return {@code true} if and only if {@link #abandonedTimeout()} is not {@code null}.
   */
  public boolean abandonCheckEnabled() {
    return abandonedTimeout != null;
  }

  /**
   * Returns the duration between runs of the eviction process.
   */
  public Duration durationBetweenEvictionsRuns() {
    return durationBetweenEvictionsRuns;
  }

  /**
   * Returns the timeout duration for an idle object to be considered for eviction.
   */
  public Duration objEvictionTimeout() {
    return objEvictionTimeout;
  }

  /**
   * Returns the number of objects to test per eviction run.
   *
   * @return The explicit number of objects to test per eviction run, or {@link #maxPoolSize()}
   *         when it was not set.
   */
  public int numValidationsPerEvictionRun() {
    return numValidationsPerEvictionRun;
  }

  /**
   * Returns the eviction policy to use when evicting objects from the pool.
   */
  public EvictionPolicy evictionPolicy() {
    return evictionPolicy;
  }

  /**
   * Returns the maximum number of creation retries when borrowing an object from the pool.
   * Zero means exactly one creation attempt per borrow.
   */
  public int maxRetries() {
    return maxRetries;
  }

  /**
   * Returns the delay between retries when creating objects for the pool.
   * This is applicable during the object creation failure scenario.
   */
  public Duration retryCreationDelay() {
    return retryCreationDelay;
  }

  /**
   * Returns the timeout duration for waiting for an object to become available in the pool.
   */
  public Duration waitingForObjectTimeout() {
    return waitingForObjectTimeout;
  }

  public Builder toBuilder() {
    Builder builder = new Builder()
        .poolName(this.poolName)
        .maxPoolSize(this.maxPoolSize)
        .minPoolSize(this.minPoolSize)
        .fairness(this.fairness)
        .lifo(this.lifo)
        .testOnCreate(this.testOnCreate)
        .testOnBorrow(this.testOnBorrow)
        .testOnReturn(this.testOnReturn)
        .testWhileIdle(this.testWhileIdle)
        .durationBetweenAbandonCheckRuns(this.durationBetweenAbandonCheckRuns)
        .abandonedTimeout(this.abandonedTimeout)
        .durationBetweenEvictionsRuns(this.durationBetweenEvictionsRuns)
        .objEvictionTimeout(this.objEvictionTimeout)
        .evictionPolicy(this.evictionPolicy)
        .maxRetries(this.maxRetries)
        .retryCreationDelay(this.retryCreationDelay)
        .waitingForObjectTimeout(this.waitingForObjectTimeout);
    if (numValidationsPerEvictionRunSet) {
      builder.numValidationsPerEvictionRun(this.numValidationsPerEvictionRun);
    }
    return builder;
  }

  private static Duration normalisedAbandonedTimeout(Duration abandonedTimeout) {
    return abandonedTimeout != null && (abandonedTimeout.isZero() || abandonedTimeout.isNegative()) ? null : abandonedTimeout;
  }

  private static Duration normalisedRetryCreationDelay(Duration retryCreationDelay) {
    return retryCreationDelay != null && retryCreationDelay.isNegative() ? Duration.ZERO : retryCreationDelay;
  }

  /**
   * The order in which idle objects are examined by the scheduled eviction run.
   * <p>
   * {@link #NONE} disables the scheduled eviction run only. A manual {@code evict()} call still validates
   * all idle objects, and {@code minPoolSize} is still maintained by the scheduled maintenance task.
   */
  public enum EvictionPolicy {
    OLDEST_FIRST,
    LEAST_USED,
    MOST_USED,
    RANDOM, // Default fastest
    NONE
  }

  public static class Builder {
    private String         poolName                        = "SOP-" + UUID.randomUUID().toString().substring(28).toUpperCase();
    private int            maxPoolSize                     = 8;
    private int            minPoolSize                     = 0;
    private boolean        fairness                        = false;
    private boolean        lifo                            = true;
    private boolean        testOnCreate                    = false;
    private boolean        testOnBorrow                    = false;
    private boolean        testOnReturn                    = false;
    private boolean        testWhileIdle                   = true;
    private Duration       durationBetweenAbandonCheckRuns = Duration.ofSeconds(60);
    private Duration       abandonedTimeout                = null;
    private Duration       durationBetweenEvictionsRuns    = Duration.ofMinutes(5);
    private Duration       objEvictionTimeout              = Duration.ofMinutes(5);
    private Integer        numValidationsPerEvictionRun    = null;
    private EvictionPolicy evictionPolicy                  = EvictionPolicy.RANDOM;
    private int            maxRetries                      = 0;
    private Duration       retryCreationDelay              = Duration.ZERO;
    private Duration       waitingForObjectTimeout         = Duration.ofSeconds(10);

    /**
     * Sets the name of the pool.
     * <p>
     * The name is used for logging and debugging purposes. It must not be null or blank.
     *
     * @return This {@code Builder} instance.
     */
    public Builder poolName(String poolName) {
      this.poolName = poolName;
      return this;
    }

    /**
     * Sets the maximum number of objects that the pool can hold.
     *
     * @return This {@code Builder} instance.
     */
    public Builder maxPoolSize(int maxPoolSize) {
      this.maxPoolSize = maxPoolSize;
      return this;
    }

    /**
     * Sets the minimum number of idle objects that the pool should maintain.
     * Note setting a positive value does not guarantee that the pool will have at least the specified number
     * of idle objects rather it suggests that the pool should attempt to maintain the specified number of idle objects.
     * The scheduled maintenance task tops the pool up to this number, so a positive value requires a positive
     * {@code durationBetweenEvictionsRuns} even when the eviction policy is {@link EvictionPolicy#NONE}.
     *
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
     *
     * @return This {@code Builder} instance.
     */
    public Builder fairness(boolean fairness) {
      this.fairness = fairness;
      return this;
    }

    /**
     * Sets the order in which idle objects are handed out.
     * <p>
     * If {@code true} (default) the most recently returned idle object is borrowed first, which keeps a hot
     * subset of objects in use and lets the others age out. If {@code false} the least recently returned idle
     * object is borrowed first, which spreads the load across all objects.
     *
     * @param lifo {@code true} for last-in-first-out, {@code false} for first-in-first-out.
     *
     * @return This {@code Builder} instance.
     */
    public Builder lifo(boolean lifo) {
      this.lifo = lifo;
      return this;
    }

    /**
     * Sets whether objects should be tested upon creation.
     *
     * @param testOnCreate {@code true} if objects should be tested upon creation,
     *                     {@code false} otherwise.
     *
     * @return This {@code Builder} instance.
     */
    public Builder testOnCreate(boolean testOnCreate) {
      this.testOnCreate = testOnCreate;
      return this;
    }

    /**
     * Sets whether objects should be tested before being borrowed from the pool.
     *
     * @param testOnBorrow {@code true} if objects should be tested before being
     *                     borrowed, {@code false} otherwise.
     *
     * @return This {@code Builder} instance.
     */
    public Builder testOnBorrow(boolean testOnBorrow) {
      this.testOnBorrow = testOnBorrow;
      return this;
    }

    /**
     * Sets whether objects should be tested when returned to the pool.
     *
     * @param testOnReturn {@code true} if objects should be tested when returned,
     *                     {@code false} otherwise.
     *
     * @return This {@code Builder} instance.
     */
    public Builder testOnReturn(boolean testOnReturn) {
      this.testOnReturn = testOnReturn;
      return this;
    }

    /**
     * Sets whether objects should be tested for validity while they are idle in the
     * pool.
     *
     * @param testWhileIdle {@code true} if idle objects should be tested,
     *                      {@code false} otherwise.
     *
     * @return This {@code Builder} instance.
     */
    public Builder testWhileIdle(boolean testWhileIdle) {
      this.testWhileIdle = testWhileIdle;
      return this;
    }

    /**
     * Sets the duration between runs of the abandoned object check.
     * Must be positive when abandon detection is enabled; ignored otherwise.
     *
     * @return This {@code Builder} instance.
     */
    public Builder durationBetweenAbandonCheckRuns(Duration durationBetweenAbandonCheckRuns) {
      this.durationBetweenAbandonCheckRuns = durationBetweenAbandonCheckRuns;
      return this;
    }

    /**
     * Sets the timeout after which a borrowed object is considered abandoned and is destroyed.
     * <p>
     * {@code null}, zero and negative values all mean that abandon detection is disabled (the default).
     * Do not enable it if callers legitimately hold objects for long periods, since such objects would be
     * destroyed while still in use.
     *
     * @param abandonedTimeout The abandoned timeout duration, or {@code null} to disable.
     *
     * @return This {@code Builder} instance.
     */
    public Builder abandonedTimeout(Duration abandonedTimeout) {
      this.abandonedTimeout = abandonedTimeout;
      return this;
    }

    /**
     * Sets the duration between runs of the eviction process.
     * <p>
     * Must be positive unless the eviction policy is {@link EvictionPolicy#NONE} and {@code minPoolSize} is 0,
     * because the same scheduled task also maintains {@code minPoolSize}.
     *
     * @return This {@code Builder} instance.
     */
    public Builder durationBetweenEvictionsRuns(Duration durationBetweenEvictionsRuns) {
      this.durationBetweenEvictionsRuns = durationBetweenEvictionsRuns;
      return this;
    }

    /**
     * Sets the timeout duration after which an idle object is considered for eviction.
     * <br>
     * If set to zero or a negative value then idle objects are evicted on the first eviction run.
     * <p>
     * Note: if you want eviction only based on object validation then set
     * this to a very high value in the range of hours or days.
     * </p>
     *
     * @return This {@code Builder} instance.
     */
    public Builder objEvictionTimeout(Duration objEvictionTimeout) {
      this.objEvictionTimeout = objEvictionTimeout;
      return this;
    }

    /**
     * Sets the number of objects to validate in an eviction run.
     * Note that all objects in pool are tested based on
     * {@link #objEvictionTimeout},
     * This param is used for validating using the
     * {@link PooledObjectFactory} validation which in
     * many cases take some time compared to simple tests.
     * <br>
     * If set to 0 then no factory validation will be done. <br>
     * If set to null or not set then the factory validation will be limited to
     * {@code maxPoolSize}.
     *
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
     * @return This {@code Builder} instance.
     */
    public Builder evictionPolicy(EvictionPolicy evictionPolicy) {
      this.evictionPolicy = evictionPolicy;
      return this;
    }

    /**
     * Sets the maximum number of creation retries when borrowing an object from the pool.
     * <p>
     * The default is 0, which means exactly one creation attempt per borrow. Each retry is one additional
     * attempt after a failed creation.
     *
     * @param maxRetries The maximum number of retries, must not be negative.
     *
     * @return This {@code Builder} instance.
     */
    public Builder maxRetries(int maxRetries) {
      this.maxRetries = maxRetries;
      return this;
    }

    /**
     * Sets the delay between retries when creating an object for the pool.
     * This is applicable during the object creation failure scenario only. Negative values are treated as zero.
     * A bounded borrow sleeps no longer than its remaining wait and does not make another creation attempt
     * after that wait has elapsed.
     *
     * @return This {@code Builder} instance.
     */
    public Builder retryCreationDelay(Duration retryCreationDelay) {
      this.retryCreationDelay = retryCreationDelay;
      return this;
    }

    /**
     * Sets the timeout duration for waiting for an object to become available in
     * the pool. The default value is 10 seconds if not set.
     * <ul>
     *   <li>Negative: wait indefinitely.</li>
     *   <li>Zero: do not wait, fail immediately when the pool is exhausted.</li>
     *   <li>Positive: wait at most the given duration.</li>
     * </ul>
     * Waiting indefinitely is not recommended, rather handle the cases with shorter timeouts.
     *
     * @return This {@code Builder} instance.
     */
    public Builder waitingForObjectTimeout(Duration waitingForObjectTimeout) {
      this.waitingForObjectTimeout = waitingForObjectTimeout;
      return this;
    }

    /**
     * Validates the configuration settings to ensure that they are consistent and
     * valid.
     * This method will throw an exception if any of the configuration settings are
     * invalid.
     * It will also log warnings if any of the settings may result in unexpected
     * behavior.
     */
    protected void validate() {
      Duration normalisedAbandonedTimeout   = normalisedAbandonedTimeout(abandonedTimeout);
      Duration normalisedRetryCreationDelay = normalisedRetryCreationDelay(retryCreationDelay);
      if (poolName == null || poolName.isBlank()) {
        throw new IllegalArgumentException("poolName must not be null or blank");
      }
      if (maxPoolSize < 1) {
        throw new IllegalArgumentException("maxPoolSize must be greater than 0");
      }
      if (minPoolSize < 0) {
        throw new IllegalArgumentException("minPoolSize cannot be negative");
      }
      if (minPoolSize > maxPoolSize) {
        throw new IllegalArgumentException("minPoolSize cannot be greater than maxPoolSize");
      }
      if (maxRetries < 0) {
        throw new IllegalArgumentException("maxRetries cannot be negative");
      }
      if (maxRetries > maxPoolSize) {
        log.warn("maxRetries is greater than maxPoolSize. This may result in excessive retries.");
      }
      if (numValidationsPerEvictionRun != null && (numValidationsPerEvictionRun < 0 || numValidationsPerEvictionRun > maxPoolSize)) {
        throw new IllegalArgumentException("numValidationsPerEvictionRun must be between 0 and maxPoolSize");
      }
      if (evictionPolicy == null) {
        throw new IllegalArgumentException("evictionPolicy must not be null");
      }
      if (durationBetweenEvictionsRuns == null) {
        throw new IllegalArgumentException("durationBetweenEvictionsRuns must not be null");
      }
      if ((evictionPolicy != EvictionPolicy.NONE || minPoolSize > 0)
          && (durationBetweenEvictionsRuns.isNegative() || durationBetweenEvictionsRuns.isZero())) {
        throw new IllegalArgumentException("durationBetweenEvictionsRuns must be positive");
      }
      if (objEvictionTimeout == null) {
        throw new IllegalArgumentException("objEvictionTimeout must not be null");
      }
      if (objEvictionTimeout.isZero() || objEvictionTimeout.isNegative()) {
        log.info("objEvictionTimeout is zero or negative. This means idle objects will be evicted on the first eviction run.");
      }
      if (waitingForObjectTimeout == null) {
        throw new IllegalArgumentException("waitingForObjectTimeout must not be null");
      }
      if (normalisedRetryCreationDelay == null) {
        throw new IllegalArgumentException("retryCreationDelay must not be null");
      }
      if (normalisedRetryCreationDelay.isPositive()) {
        log.info("You have set a positive retryCreationDelay. The pool caps the delay at the remaining waitingForObjectTimeout "
                 + "and does not make another creation attempt after that wait has elapsed.");
      }
      if (normalisedAbandonedTimeout != null) {
        if (durationBetweenAbandonCheckRuns == null || durationBetweenAbandonCheckRuns.isNegative()
            || durationBetweenAbandonCheckRuns.isZero()) {
          throw new IllegalArgumentException("durationBetweenAbandonCheckRuns must be positive when abandonedTimeout is set");
        }
        if (normalisedAbandonedTimeout.compareTo(waitingForObjectTimeout) <= 0) {
          log.warn("abandonedTimeout is less than or equal to waitingForObjectTimeout. This may result in excessive actions on the pool.");
        }
      }
    }

    protected Builder prepareAndCheck() {
      validate();
      return this;
    }

    public SimpleObjectPoolConfig build() {
      prepareAndCheck();
      return new SimpleObjectPoolConfig(this);
    }
  }
}
