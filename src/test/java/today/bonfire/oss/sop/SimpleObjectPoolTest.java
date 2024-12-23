package today.bonfire.oss.sop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import today.bonfire.oss.sop.exceptions.PoolException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SimpleObjectPoolTest {

  private static final int  MAX_POOL_SIZE       = 5;
  private static final int  MIN_POOL_SIZE       = 0;
  private static final long IDLE_TIMEOUT        = 1000L;
  private static final long ABANDONED_TIMEOUT   = 2000L;
  private static final long OBJECT_WAIT_TIMEOUT = 100L;

  private PooledObjectFactory<TestPoolObject> factory;

  private SimpleObjectPool<TestPoolObject> pool;

  @BeforeEach
  void setUp() {
    factory = mock();
    pool    = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                           .maxPoolSize(MAX_POOL_SIZE)
                                                           .minPoolSize(MIN_POOL_SIZE)
                                                           .testWhileIdle(true)
                                                           .testOnCreate(false)
                                                           .waitingForObjectTimeout(Duration.ofMillis(OBJECT_WAIT_TIMEOUT))
                                                           .durationBetweenEvictionsRuns(Duration.ofMillis(IDLE_TIMEOUT))
                                                           .objEvictionTimeout(Duration.ofMillis(IDLE_TIMEOUT))
                                                           .durationBetweenAbandonCheckRuns(Duration.ofMillis(ABANDONED_TIMEOUT))
                                                           .abandonedTimeout(Duration.ofMillis(ABANDONED_TIMEOUT))
                                                           .build(), factory);
  }

  @AfterEach
  void tearDown() {
    if (pool != null) {
      pool.close();
    }
  }

  @Test
  void testBorrowObject() throws Exception {

    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolObject borrowed = pool.borrowObject();
    assertThat(borrowed).isNotNull();
    assertThat(borrowed).isEqualTo(entity);
    verify(factory, times(1)).createObject();
    verify(factory, times(1)).isObjectValidForBorrow(entity);
  }

  @Test
  void testBorrowAndReturnObject() throws Exception {

    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed);

    verify(factory, times(1)).isObjectValidForBorrow(entity);

    TestPoolObject borrowedAgain = pool.borrowObject();
    assertThat(borrowedAgain)
        .as("Should get the same object back")
        .isEqualTo(entity);
    verify(factory, times(1)).createObject();
    verify(factory, times(2)).isObjectValidForBorrow(entity);

    pool.close();
  }

  @Test
  void testPoolExhaustion() throws Exception {
    var config = pool.config().toBuilder()
                     .abandonedTimeout(Duration.ofSeconds(10))
                     .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    when(factory.createObject()).thenAnswer(inv -> {
      TestPoolObject entity = new TestPoolObject();
      when(factory.isObjectValidForBorrow(entity)).thenReturn(true);
      return entity;
    });

    // Borrow up to max pool size
    for (int i = 0; i < MAX_POOL_SIZE; i++) {
      assertThat(localPool.borrowObject()).isNotNull();
    }

    // Next borrow should time out
    assertThatThrownBy(() -> localPool.borrowObject())
        .isInstanceOf(PoolTimeoutException.class)
        .hasMessageContaining("Timeout waiting for available object");

    assertThat(localPool.currentPoolSize())
        .as("Pool size should be at maximum")
        .isEqualTo(MAX_POOL_SIZE);
    assertThat(localPool.borrowedObjectsCount())
        .as("All objects should be borrowed")
        .isEqualTo(MAX_POOL_SIZE);

    localPool.close();
  }


  @Test
  void testObjectValidation() throws Exception {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed, true);

    verify(factory, times(1)).destroyObject(entity);
  }

  @Test
  void testIdleObjectEviction() throws Exception {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);
    var borrowed = pool.borrowObject();
    pool.returnObject(borrowed);
    // Wait for idle timeout
    Thread.sleep(IDLE_TIMEOUT * 2);

    TestPoolObject newBorrowed = new TestPoolObject();
    when(factory.createObject()).thenReturn(newBorrowed);
    when(factory.isObjectValidForBorrow(newBorrowed)).thenReturn(true);
    // Borrow again should create new object
    pool.borrowObject();
    assertThat(newBorrowed).isNotEqualTo(borrowed);
  }

  @Test
  void testClose() throws Exception {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolObject borrowed = pool.borrowObject();
    pool.close();

    verify(factory, times(1)).destroyObject(borrowed);
  }

  @Test
  void testReturnInvalidObject() throws Exception {
    var config = pool.config().toBuilder()
                     .testOnReturn(true)
                     .build();
    pool = new SimpleObjectPool<>(config, factory);
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolObject borrowed = pool.borrowObject();

    when(factory.isObjectValid(entity)).thenReturn(false);

    pool.returnObject(borrowed);
    verify(factory, times(1)).destroyObject(entity);

    // Next borrow should create new object
    TestPoolObject newEntity = new TestPoolObject();
    when(factory.createObject()).thenReturn(newEntity);
    when(factory.isObjectValidForBorrow(newEntity)).thenReturn(true);

    TestPoolObject newBorrowed = pool.borrowObject();
    assertThat(newBorrowed).isNotEqualTo(borrowed);
  }

  @Test
  void testMinimumTimeoutEnforcement() throws Exception {
    // Create a pool with timeouts less than MIN_TIMEOUT_MS (10ms)
    var config = SimpleObjectPoolConfig.builder()
                                       .testOnReturn(false)
                                       .testWhileIdle(false)
                                       .waitingForObjectTimeout(Duration.ofMillis(10))
                                       .abandonedTimeout(Duration.ofMillis(200))
                                       .durationBetweenAbandonCheckRuns(Duration.ofMillis(5))
                                       .objEvictionTimeout(Duration.ofMillis(20))
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(10))
                                       .build();
    pool = new SimpleObjectPool<>(config, factory);
    when(factory.createObject()).then(invocation -> new TestPoolObject());
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);

    // Borrow and return an object
    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed);

    // Sleep for 7ms (> 5ms original timeout, but < 10ms minimum timeout)
    Thread.sleep(7);
    // Object should still be in pool since actual timeout is 10ms
    TestPoolObject borrowedAgain = pool.borrowObject();
    assertThat(borrowedAgain).as("Object should not be evicted before minimum timeout").isEqualTo(borrowed);
  }

  @Test
  void testNullTimeout() {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder().waitingForObjectTimeout(Duration.ZERO)
                                                            .build(), factory);

    assertThat(pool.borrowObject()).isNotNull();
  }

  @Test
  void testNegativeTimeout() {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .waitingForObjectTimeout(Duration.ZERO.minusSeconds(1L))
                                                            .build(), factory);

    assertThat(pool.borrowObject()).isNotNull();
  }

  @Test
  void testMaxCreationAttemptsWithValidationFailure() throws Exception {
    var factory = new TestPooledObjectFactory();
    var pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                            .maxRetries(3).build(), factory);

    // Make all validations fail
    factory.setFailValidationForBorrow(true);

    // Should fail after max attempts (pool size = 3)
    assertThatThrownBy(() -> pool.borrowObject())
        .isInstanceOf(PoolException.class)
        .hasMessageContaining("Max retries reached while creating object and failing to borrow");

    assertThat(factory.getValidationForBorrowFailCount().get())
        .as("Reties should be 3 times and once for initial creation")
        .isEqualTo(1 + 3);

    // Reset factory for cleanup
    factory.reset();
  }

  @Test
  void testEvictionPolicyOldestFirst() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(3)
                                       .minPoolSize(0)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.OLDEST_FIRST)
                                       .objEvictionTimeout(Duration.ofSeconds(1000))
                                       .numValidationsPerEvictionRun(1)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(80))
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    TestPoolObject obj3 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1, obj2, obj3);
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);

    var borrowed1 = localPool.borrowObject();
    var borrowed2 = localPool.borrowObject();
    var borrowed3 = localPool.borrowObject();

    Thread.sleep(100); // Wait for some time

    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);
    localPool.returnObject(borrowed3);

    Thread.sleep(100); // Wait for eviction

    assertThat(localPool.currentPoolSize())
        .as("Oldest object should be evicted")
        .isEqualTo(2);

    // borrow again to test
    var borrowedAgain1 = localPool.borrowObject();
    var borrowedAgain2 = localPool.borrowObject();

    assertThat(borrowedAgain1).isNotEqualTo(borrowed1);
    assertThat(borrowedAgain2).isNotEqualTo(borrowed1);

    localPool.close();
  }

  @Test
  void testEvictionPolicyLeastUsed() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(2)
                                       .minPoolSize(0)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.LEAST_USED)
                                       .objEvictionTimeout(Duration.ofMinutes(1))
                                       .numValidationsPerEvictionRun(1)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(100))
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1, obj2);
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);
    when(factory.isObjectValid(any())).thenReturn(false);

    var borrowed1 = localPool.borrowObject(); // Will be borrowed twice

    var borrowed2 = localPool.borrowObject(); // Will be borrowed once

    localPool.returnObject(borrowed1);
    // Borrow obj1 again to increase its usage count
    borrowed1 = localPool.borrowObject();
    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);

    Thread.sleep(100); // Wait longer than eviction timeout

    assertThat(localPool.currentPoolSize())
        .as("Least used object should be evicted")
        .isEqualTo(1);

    var borrowedAgain = localPool.borrowObject();
    assertThat(borrowedAgain).isEqualTo(borrowed1);

    localPool.close();
  }

  @Test
  void testEvictionPolicyMostUsed() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(2)
                                       .minPoolSize(0)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.MOST_USED)
                                       .objEvictionTimeout(Duration.ofSeconds(1000))
                                       .numValidationsPerEvictionRun(1)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(80))
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1, obj2);
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);
    when(factory.isObjectValid(any())).thenReturn(false);

    var borrowed1 = localPool.borrowObject(); // Will be borrowed twice

    var borrowed2 = localPool.borrowObject(); // Will be borrowed once

    localPool.returnObject(borrowed1);
    // Borrow obj1 again to increase its usage count
    borrowed1 = localPool.borrowObject();
    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);

    Thread.sleep(100); // Wait longer than eviction timeout

    assertThat(localPool.currentPoolSize())
        .as("Most used object should be evicted")
        .isEqualTo(1);

    var borrowedAgain = localPool.borrowObject();
    assertThat(borrowedAgain).isEqualTo(borrowed2);

    localPool.close();
  }

  @Test
  void testRetryCreationWithDelay() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .minPoolSize(0)
                                       .maxRetries(2)
                                       .retryCreationDelay(Duration.ofMillis(50))
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject validObj = new TestPoolObject();
    when(factory.createObject())
        .thenThrow(new PoolObjectException("Failed first attempt"))
        .thenThrow(new PoolObjectException("Failed second attempt"))
        .thenReturn(validObj);
    when(factory.isObjectValidForBorrow(validObj)).thenReturn(true);

    var borrowed = localPool.borrowObject();
    assertThat(borrowed)
        .as("Should get valid object after retries")
        .isEqualTo(validObj);

    localPool.close();
  }

  @Test
  void testAbandonedObjectDetection() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .minPoolSize(0)
                                       .abandonedTimeout(Duration.ofMillis(100))
                                       .durationBetweenAbandonCheckRuns(Duration.ofMillis(50))
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject obj = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj);
    when(factory.isObjectValidForBorrow(obj)).thenReturn(true);

    var borrowed = localPool.borrowObject();
    Thread.sleep(200); // Wait longer than abandoned timeout

    assertThat(localPool.borrowedObjectsCount())
        .as("Abandoned object should be removed")
        .isEqualTo(0);

    localPool.close();
  }

  @Test
  void testValidationOnCreate() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .minPoolSize(0)
                                       .testOnCreate(true)
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject invalidObj = new TestPoolObject();
    when(factory.createObject()).thenReturn(invalidObj);
    when(factory.isObjectValid(invalidObj)).thenReturn(false);

    assertThatThrownBy(() -> localPool.borrowObject())
        .as("Should throw exception for invalid object on create")
        .isInstanceOf(PoolException.class);

    localPool.close();
  }
}
