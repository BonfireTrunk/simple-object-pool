package today.bonfire.oss.sop;

import org.junit.jupiter.api.Test;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleObjectPoolBlockTest {

  @Test
  void testBlockWhenExhaustedFalse() throws Exception {
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .waitingForObjectTimeout(Duration.ZERO)
                                       .build();

    try (var pool = new SimpleObjectPool<>(config, factory)) {
      TestPoolObject obj1 = pool.borrowObject();
      assertThat(obj1).isNotNull();

      // improved check: should fail immediately without waiting
      long start = System.currentTimeMillis();
      assertThatThrownBy(() -> pool.borrowObject())
          .isInstanceOf(PoolTimeoutException.class)
          .hasMessageContaining("Pool exhausted");
      long duration = System.currentTimeMillis() - start;

      // Should result in exception almost immediately, definitely less than default
      // timeout (10s)
      assertThat(duration).isLessThan(100);
    }
  }

  @Test
  void testBlockWhenExhaustedTrue() throws Exception {
    // Functional test for default blocking behavior is arguably covered by existing
    // concurrency tests,
    // but explicit test helps confirm flag is respected.
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .waitingForObjectTimeout(Duration.ofSeconds(1))
                                       .build();

    try (var pool = new SimpleObjectPool<>(config, factory)) {
      TestPoolObject obj1 = pool.borrowObject();

      long start = System.currentTimeMillis();
      assertThatThrownBy(() -> pool.borrowObject())
          .isInstanceOf(PoolTimeoutException.class);
      long duration = System.currentTimeMillis() - start;

      // Should wait for timeout
      assertThat(duration).isGreaterThanOrEqualTo(1000);
    }
  }
}
