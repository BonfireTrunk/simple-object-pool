package today.bonfire.oss.sop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleObjectPoolTest {

  private static final int  MAX_POOL_SIZE     = 5;
  private static final int  MIN_POOL_SIZE     = 2;
  private static final long IDLE_TIMEOUT      = 1000L;
  private static final long ABANDONED_TIMEOUT = 2000L;

  @Mock
  private PooledObjectFactory<TestPoolEntity> factory;

  private SimpleObjectPool<TestPoolEntity> pool;

  @BeforeEach
  void setUp() {
    pool = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);
  }

  @AfterEach
  void tearDown() {
    if (pool != null) {
      pool.close();
    }
  }

  @Test
  void testConstructorWithInvalidParameters() {
    // Test when minPoolSize > maxPoolSize
    assertThatThrownBy(() -> {try (var testPoolEntitySimpleObjectPool = new SimpleObjectPool<>(2, 3, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory)) {}})
        .isInstanceOf(IllegalArgumentException.class);

    // Test when minPoolSize < 1
    assertThatThrownBy(() -> {try (var testPoolEntitySimpleObjectPool = new SimpleObjectPool<>(2, -1, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory)) {}})
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void testBorrowObject() throws Exception {
    TestPoolEntity entity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    assertThat(borrowed).isNotNull();
    assertThat(borrowed).isEqualTo(entity);
    verify(factory, times(1)).createObject();
  }

  @Test
  void testBorrowAndReturnObject() throws Exception {
    TestPoolEntity entity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);
    when(factory.isObjectValid(entity)).thenReturn(true);

    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    pool.returnObject(borrowed);

    TestPoolEntity borrowedAgain = pool.borrowObject(Duration.ofSeconds(1));
    assertThat(borrowedAgain).as("Should get the same object back").isEqualTo(entity);
    verify(factory, times(1)).createObject();
  }

  @Test
  void testPoolExhaustion() throws Exception {
    AtomicInteger counter = new AtomicInteger();
    when(factory.createObject()).thenAnswer(inv -> {
      TestPoolEntity entity = new TestPoolEntity();
      when(factory.isObjectValidForBorrow(entity)).thenReturn(true);
      return entity;
    });

    // Borrow up to max pool size
    for (int i = 0; i < MAX_POOL_SIZE; i++) {
      assertThat(pool.borrowObject(Duration.ofSeconds(1))).isNotNull();
    }

    // Next borrow should timeout
    assertThatThrownBy(() -> pool.borrowObject(Duration.ofMillis(100)))
        .isInstanceOf(PoolResourceException.class);
  }

  @Test
  void testConcurrentBorrowAndReturn() throws Exception {
    int             numThreads      = MAX_POOL_SIZE * 2;
    ExecutorService executor        = Executors.newVirtualThreadPerTaskExecutor();
    CountDownLatch  startLatch      = new CountDownLatch(1);
    CountDownLatch  completionLatch = new CountDownLatch(numThreads);

    AtomicInteger counter = new AtomicInteger();
    when(factory.createObject()).thenAnswer(inv -> {
      TestPoolEntity entity = new TestPoolEntity();
      when(factory.isObjectValidForBorrow(entity)).thenReturn(true);
      when(factory.isObjectValid(entity)).thenReturn(true);
      return entity;
    });

    // Create tasks that borrow and return objects
    for (int i = 0; i < numThreads; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          TestPoolEntity obj = pool.borrowObject(Duration.ofSeconds(2));
          assertThat(obj).isNotNull();
          Thread.sleep(100); // Simulate some work
          pool.returnObject(obj);
        } catch (Exception e) {
          throw new RuntimeException(e);
        } finally {
          completionLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    assertThat(completionLatch.await(10, TimeUnit.SECONDS))
        .as("All threads should complete within timeout")
        .isTrue();
    executor.shutdown();
    assertThat(executor.awaitTermination(5, TimeUnit.SECONDS))
        .as("Executor should terminate within timeout")
        .isTrue();
  }

  @Test
  void testObjectValidation() throws Exception {
    TestPoolEntity entity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    pool.returnObject(borrowed, false);

    verify(factory, times(1)).destroyObject(entity);
  }

  @Test
  void testIdleObjectEviction() throws Exception {
    var factory   = new TestPooledObjectFactory();
    var localPool = new SimpleObjectPool<>(5, 0, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    var borrowed = localPool.borrowObject(Duration.ofSeconds(1));
    localPool.returnObject(borrowed);
    // Wait for idle timeout
    Thread.sleep(IDLE_TIMEOUT * 2);

    // Borrow again should create new object
    var newBorrowed = localPool.borrowObject(Duration.ofSeconds(1));
    assertThat(newBorrowed).isNotEqualTo(borrowed);
  }

  @Test
  void testClose() throws Exception {
    TestPoolEntity entity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));
    pool.close();

    verify(factory, times(1)).destroyObject(borrowed);
  }

  @Test
  void testReturnInvalidObject() throws Exception {
    TestPoolEntity entity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolEntity borrowed = pool.borrowObject(Duration.ofSeconds(1));

    when(factory.isObjectValid(entity)).thenReturn(false);

    pool.returnObject(borrowed);
    verify(factory, times(1)).destroyObject(entity);

    // Next borrow should create new object
    TestPoolEntity newEntity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(newEntity);
    when(factory.isObjectValidForBorrow(newEntity)).thenReturn(true);

    TestPoolEntity newBorrowed = pool.borrowObject(Duration.ofSeconds(1));
    assertThat(newBorrowed).isNotEqualTo(borrowed);
  }

  @Test
  void testMinimumTimeoutEnforcement() throws Exception {
    // Create a pool with timeouts less than MIN_TIMEOUT_MS (10ms)
    SimpleObjectPool<TestPoolEntity> minTimeoutPool = new SimpleObjectPool<>(
        MAX_POOL_SIZE, MIN_POOL_SIZE, 5L, 5L, factory);

    var entity = new TestPoolEntity();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    // Borrow and return an object
    TestPoolEntity borrowed = minTimeoutPool.borrowObject(Duration.ofSeconds(1));
    minTimeoutPool.returnObject(borrowed);

    // Sleep for 7ms (> 5ms original timeout, but < 10ms minimum timeout)
    Thread.sleep(7);

    // Object should still be in pool since actual timeout is 10ms
    TestPoolEntity borrowedAgain = minTimeoutPool.borrowObject(Duration.ofSeconds(1));
    assertThat(borrowedAgain).as("Object should not be evicted before minimum timeout").isEqualTo(entity);

    minTimeoutPool.close();
  }

  @Test
  void testNullTimeout() {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE,
                                      IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    assertThatThrownBy(() -> pool.borrowObject(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Timeout cannot be null");
  }

  @Test
  void testNegativeTimeout() {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(MAX_POOL_SIZE, MIN_POOL_SIZE,
                                      IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    assertThatThrownBy(() -> pool.borrowObject(Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Timeout cannot be negative");
  }

  @Test
  void testMaxCreationAttemptsWithValidationFailure() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool    = new SimpleObjectPool<>(3, 0, IDLE_TIMEOUT, ABANDONED_TIMEOUT, factory);

    // Make all validations fail
    factory.setFailValidationForBorrow(true);

    // Should fail after max attempts (pool size = 3)
    assertThatThrownBy(() -> pool.borrowObject(Duration.ofSeconds(1)))
        .isInstanceOf(PoolResourceException.class)
        .hasMessageContaining("Timeout waiting for available object");

    assertThat(factory.getValidationForBorrowFailCount().get())
        .as("Should attempt validation exactly pool size times")
        .isEqualTo(3);

    // Reset factory for cleanup
    factory.reset();
  }
}
