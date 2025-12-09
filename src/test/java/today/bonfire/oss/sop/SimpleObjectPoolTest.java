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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;


@ExtendWith(MockitoExtension.class)
class SimpleObjectPoolTest {

  private static final int  MAX_POOL_SIZE     = 5;
  private static final int  MIN_POOL_SIZE     = 0;
  private static final long IDLE_TIMEOUT      = 1000L;
  private static final long ABANDONED_TIMEOUT = 2000L;
  private static final long OBJECT_WAIT_TIMEOUT = 100L;

  private PooledObjectFactory<TestPoolObject> factory;

  private SimpleObjectPool<TestPoolObject> pool;

  @BeforeEach
  void setUp() {
    factory = mock();
    pool = new SimpleObjectPool<>(SimpleObjectPoolConfig.builder()
                                                        .maxPoolSize(MAX_POOL_SIZE)
                                                        .minPoolSize(MIN_POOL_SIZE)
                                                        .testWhileIdle(true)
                                                        .testOnCreate(false)
                                                        .testOnBorrow(true)
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
      if (!pool.isClosed()) {
        pool.close();
      }
    }
  }

  @Test
  void testDestroyAllIdleObjects() throws Exception {
    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1, obj2);
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);

    // Populate pool
    var b1 = pool.borrowObject();
    var b2 = pool.borrowObject();
    pool.returnObject(b1);
    pool.returnObject(b2);

    assertThat(pool.idleObjectCount()).isEqualTo(2);

    pool.destroyAllIdleObjects();

    assertThat(pool.idleObjectCount()).isEqualTo(0);
    assertThat(pool.currentPoolSize()).isEqualTo(0);
    verify(factory, times(1)).destroyObject(obj1);
    verify(factory, times(1)).destroyObject(obj2);
  }

  @Test
  void testStatistics() throws Exception {
    TestPoolObject obj = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj);
    when(factory.isObjectValidForBorrow(obj)).thenReturn(true);

    assertThat(pool.numOfObjectsCreated()).isEqualTo(0);
    assertThat(pool.numOfTimesBorrowedFromPool()).isEqualTo(0);

    var b1 = pool.borrowObject();
    pool.returnObject(b1);

    var b2 = pool.borrowObject();

    assertThat(pool.numOfObjectsCreated()).isEqualTo(1);
    assertThat(pool.numOfTimesBorrowedFromPool()).isEqualTo(2);

    // Check specific object stats
    // Note: implementation might be tricky if it depends on internal wrapper state not updated by mock?
    // The wrapper 'PooledObject' tracks timesBorrowed.
    assertThat(pool.numOfTimesBorrowed(obj.getEntityId())).isEqualTo(2);
  }

  @Test
  void testManualEvictClearsAllIdle() throws Exception {
    // Setup pool that allows many idle objects
    factory = mock(PooledObjectFactory.class);
    // We need a fresh pool with NO scheduled eviction handling interfering or limited batch size
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(10)
                                       .minPoolSize(0)
                                       .objEvictionTimeout(Duration.ofMillis(1)) // Instant expiration
                                       .numValidationsPerEvictionRun(1) // Configured to only check 1 per run (scheduled)
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject o1 = new TestPoolObject();
    TestPoolObject o2 = new TestPoolObject();
    TestPoolObject o3 = new TestPoolObject();
    when(factory.createObject()).thenReturn(o1, o2, o3);

    // Create 3 idle objects
    var b1 = localPool.borrowObject();
    var b2 = localPool.borrowObject();
    var b3 = localPool.borrowObject();
    localPool.returnObject(b1);
    localPool.returnObject(b2);
    localPool.returnObject(b3);

    Thread.sleep(10); // Ensure they are expired

    // Manual evict should clear ALL despite numValidationsPerEvictionRun=1
    localPool.evict();

    assertThat(localPool.idleObjectCount()).isEqualTo(0);
    verify(factory).destroyObject(o1);
    verify(factory).destroyObject(o2);
    verify(factory).destroyObject(o3);

    localPool.close();
  }

  @Test
  void testReturnForeignObject() {
    TestPoolObject foreign = new TestPoolObject();
    foreign.setEntityId(999L);
    // Should verify it logs warning and returns without error
    pool.returnObject(foreign);
    // No exception, no side effect on pool size
    assertThat(pool.currentPoolSize()).isEqualTo(0);
  }

  @Test
  void testReturnNullObject() {
    assertThatThrownBy(() -> pool.returnObject(null))
        .isInstanceOf(PoolException.class)
        .hasMessageContaining("Cannot return null object");
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
                     .testOnBorrow(true)
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
                                       .testOnBorrow(true)
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
                                                            .maxRetries(3)
                                                            .testOnBorrow(true)
                                                            .build(), factory);

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
                                       .testOnBorrow(false)
                                       .testOnReturn(false)
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    TestPoolObject obj3 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1, obj2, obj3);
    when(factory.isObjectValid(obj1)).thenReturn(false);
    lenient().when(factory.isObjectValid(obj2)).thenReturn(true);
    lenient().when(factory.isObjectValid(obj3)).thenReturn(true);

    var borrowed1 = localPool.borrowObject();
    var borrowed2 = localPool.borrowObject();
    var borrowed3 = localPool.borrowObject();

    Thread.sleep(500); // Wait for some time

    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);
    localPool.returnObject(borrowed3);

    Thread.sleep(300); // Wait for eviction

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
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(80))
                                       .testOnBorrow(true)
                                       .testOnReturn(false)
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
                                       .testOnBorrow(true)
                                       .testOnReturn(false)
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
                                       .testOnBorrow(true)
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
                                       .testOnBorrow(true)
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

  @Test
  void testEvictionLifecycle() throws Exception {
    long evictionRunMillis = 50;

    // Setup mock to return valid objects for initial minPoolSize creation
    TestPoolObject initialObj1 = new TestPoolObject();
    TestPoolObject initialObj2 = new TestPoolObject();
    TestPoolObject obj         = new TestPoolObject();

    when(factory.createObject()).thenReturn(initialObj1, initialObj2, obj);
    when(factory.isObjectValid(any())).thenReturn(true);

    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(5)
                                       .minPoolSize(2)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.RANDOM)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(evictionRunMillis))
                                       .objEvictionTimeout(Duration.ofMinutes(10)) // Long timeout so we rely on validation
                                       .testWhileIdle(true)
                                       .numValidationsPerEvictionRun(5)
                                       .build();
    var localPool = new SimpleObjectPool<>(config, factory);

    // Verify initial fill for minPoolSize = 2
    verify(factory, times(2)).createObject();

    // Setup for borrowing
    lenient().when(factory.isObjectValidForBorrow(obj)).thenReturn(true);

    // Borrow and return to make it idle
    TestPoolObject borrowed = localPool.borrowObject();
    localPool.returnObject(borrowed);

    // Reset mocks to track eviction calls
    clearInvocations(factory);

    // Now setup for eviction run - we want to test that it calls activate -> isObjectValid -> passivate
    when(factory.isObjectValid(any())).thenReturn(true);

    // Wait for eviction run
    Thread.sleep(evictionRunMillis * 3);

    // Verify lifecycle calls - it might be called multiple times depending on sleep and run frequency
    verify(factory, atLeastOnce()).activateObject(any());
    verify(factory, atLeastOnce()).isObjectValid(any());
    verify(factory, atLeastOnce()).passivateObject(any());

    // NOT destroyed
    verify(factory, never()).destroyObject(any());

    // -------------------------------------------------------------
    // Test Case: Activation Failure during Eviction
    // -------------------------------------------------------------
    doThrow(new RuntimeException("Activation failed during eviction")).when(factory).activateObject(any());
    
    // Wait for next eviction run
    Thread.sleep(evictionRunMillis * 3);
    
    // Should be evicted and destroyed because activation failed
    verify(factory, atLeastOnce()).destroyObject(any());
    
    // Reset exception for next steps
    doNothing().when(factory).activateObject(any());
    reset(factory); 
    when(factory.isObjectValid(any())).thenReturn(true);
    // Repopulate for remaining tests if needed...
    // Actually the test continued to verify isObjectValid=false.
    // If we destroyed it, we need to create new ones? 
    // The ensureMinIdle will kick in.
    when(factory.createObject()).thenReturn(new TestPoolObject(), new TestPoolObject());
    
    // Wait for stabilization
    Thread.sleep(evictionRunMillis * 2);
    
    // -------------------------------------------------------------
    // Test Case: Validation Failure (Existing)
    // -------------------------------------------------------------
    // Now make isObjectValid return false to test eviction
    when(factory.isObjectValid(any())).thenReturn(false);

    // Wait for eviction run - wait a bit longer to ensure the eviction run picks up the change
    Thread.sleep(evictionRunMillis * 4);

    // Should be destroyed
    verify(factory, atLeastOnce()).destroyObject(any());

    // Should also verify that ensureMinIdle works. 
    // If we destroyed objects and minPoolSize is 2, it should create new ones.
    // We need to setup the mock to return new objects for ensureMinIdle
    TestPoolObject newObj1 = new TestPoolObject();
    TestPoolObject newObj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(newObj1, newObj2);

    // Wait a bit more for ensureMinIdle to kick in
    Thread.sleep(evictionRunMillis * 2);

    // Verify subsequent creation calls to maintain minPoolSize
    verify(factory, atLeast(3)).createObject(); // 2 initial + at least 1 for ensureMinIdle

    localPool.close();
  }

  @Test
  void testObjectCreationFailuresDoNotLeakPoolSize() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(2)
                                       .minPoolSize(0)
                                       .maxRetries(1)
                                       .testOnBorrow(false)
                                       .build();

    // Custom factory to simulate failures
    PooledObjectFactory<TestPoolObject> failingFactory = mock(PooledObjectFactory.class);

    // First attempt throws exception
    when(failingFactory.createObject())
        .thenThrow(new RuntimeException("Simulated creation failure"))
        .thenReturn(new TestPoolObject()); // Second attempt succeeds

    var localPool = new SimpleObjectPool<>(config, failingFactory);

    // Let's try to verify size is 0 after failure
    // We expect it to fail because maxRetries=1 means 1 initial try + 1 retry (both might fail or we force it)
    // Actually we only mocked 1 failure then success.
    // So 1st try fails (retriesLeft=1), catches, loop continues.
    // 2nd try (retriesLeft=0) succeeds.
    // So borrow should succeed!

    TestPoolObject obj = localPool.borrowObject();
    assertThat(obj).isNotNull();
    assertThat(localPool.currentPoolSize())
        .as("Pool size should be 1 after successful creation (despite initial failure)")
        .isEqualTo(1);

    // Now force total failure
    reset(failingFactory);
    when(failingFactory.createObject()).thenThrow(new RuntimeException("Always fail"));

    assertThatThrownBy(() -> localPool.borrowObject())
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Always fail");

    assertThat(localPool.currentPoolSize())
        .as("Pool size should be 1 (from previous successful borrow that was returned/kept?) Wait, we borrowed 'obj'. Is it returned? No.")
        .isEqualTo(1);

    // Wait, if we borrowed 'obj', it's in borrowedObjects or local var. 
    // currentPoolSize includes borrowed.
    // So distinct failure calls shouldn't increment it further.

    localPool.close();
  }

  @Test
  void testConcurrentBorrowCreatesOnlyUpToMaxSize() throws Exception {
    int maxPoolSize = 5;
    int threadCount = 20;
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(maxPoolSize)
                                       .minPoolSize(0)
                                       .waitingForObjectTimeout(Duration.ofMillis(500))
                                       .build();

    // Use a real factory for concurrency to avoid Mockito overhead/issues
    PooledObjectFactory<TestPoolObject> realFactory = new PooledObjectFactory<>() {
      @Override
      public TestPoolObject createObject() {
        try {Thread.sleep(10);} catch (InterruptedException e) {}
        return new TestPoolObject();
      }

      @Override
      public void activateObject(TestPoolObject obj) {}

      @Override
      public void passivateObject(TestPoolObject obj) {}

      @Override
      public boolean isObjectValidForBorrow(TestPoolObject obj) {return true;}

      @Override
      public boolean isObjectValid(TestPoolObject obj) {return true;}

      @Override
      public void destroyObject(TestPoolObject obj) {}
    };

    var             localPool    = new SimpleObjectPool<>(config, realFactory);
    CountDownLatch  startLatch   = new CountDownLatch(1);
    CountDownLatch  doneLatch    = new CountDownLatch(threadCount);
    ExecutorService executor     = Executors.newFixedThreadPool(threadCount);
    AtomicInteger   successCount = new AtomicInteger(0);
    AtomicInteger   timeoutCount = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          startLatch.await();
          TestPoolObject obj = localPool.borrowObject();
          successCount.incrementAndGet();
          Thread.sleep(20);
          localPool.returnObject(obj);
        } catch (PoolTimeoutException pte) {
          timeoutCount.incrementAndGet();
        } catch (Exception e) {
          // ignore
        } finally {
          doneLatch.countDown();
        }
      });
    }

    startLatch.countDown();
    doneLatch.await(10, TimeUnit.SECONDS);
    executor.shutdownNow();

    assertThat(localPool.currentPoolSize())
        .as("Pool size should never exceed maxPoolSize")
        .isLessThanOrEqualTo(maxPoolSize);

    assertThat(localPool.currentPoolSize())
        .as("Pool should have created some objects")
        .isGreaterThan(0);

    localPool.close();
  }

  @Test
  void testHammerThePool() throws Exception {
    int maxPoolSize = 10;
    int threadCount = 50;
    int iterations  = 50;
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(maxPoolSize)
                                       .minPoolSize(0)
                                       // Small wait to ensure high contention
                                       .waitingForObjectTimeout(Duration.ofMillis(100))
                                       .build();

    // Use robust factory
    var localFactory = new TestPooledObjectFactory();
    var localPool    = new SimpleObjectPool<>(config, localFactory);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch  latch    = new CountDownLatch(threadCount);
    AtomicInteger   errors   = new AtomicInteger(0);
    AtomicInteger   timeouts = new AtomicInteger(0);

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          for (int j = 0; j < iterations; j++) {
            try {
              TestPoolObject obj = localPool.borrowObject();
              // fast return
              localPool.returnObject(obj);
            } catch (PoolTimeoutException e) {
              timeouts.incrementAndGet();
            } catch (Exception e) {
              errors.incrementAndGet();
            }
          }
        } finally {
          latch.countDown();
        }
      });
    }

    boolean finished = latch.await(15, TimeUnit.SECONDS);
    executor.shutdownNow();

    assertThat(finished).as("Test did not finish in time").isTrue();
    assertThat(errors.get()).as("Unexpected errors during stress test").isEqualTo(0);

    // Invariants check
    assertThat(localPool.currentPoolSize())
        .as("Pool size should verify: current <= max")
        .isLessThanOrEqualTo(maxPoolSize);

    assertThat(localPool.borrowedObjectsCount())
        .as("All objects should be returned")
        .isEqualTo(0);

    localPool.close();
  }

  @Test
  void testActivationFailureWithRetrySucceeds() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .maxRetries(1) // Allow 1 retry
                                       .build();

    PooledObjectFactory<TestPoolObject> factory = mock(PooledObjectFactory.class);
    // Return distinct objects for each creation call
    when(factory.createObject()).thenReturn(new TestPoolObject(), new TestPoolObject());

    var localPool = new SimpleObjectPool<>(config, factory);

    // 1. First borrow should succeed (newly created, no activation)
    TestPoolObject obj1 = localPool.borrowObject();
    assertThat(obj1).isNotNull();

    // 2. Return it to the pool so it becomes idle
    localPool.returnObject(obj1);

    // 3. Force activation failure for the NEXT borrow (recycling obj1)
    doThrow(new RuntimeException("Activation Failed")).when(factory).activateObject(any());

    // 4. Borrow should succeed because:
    //    - Tries idle (obj1) -> Activate fails -> Destroys obj1 -> Size=0
    //    - Retries (MaxRetries=1) -> Creates NEW (obj2) -> Skips Activate -> Success!
    TestPoolObject obj2 = localPool.borrowObject();

    assertThat(obj2).isNotNull().isNotSameAs(obj1);
    
    // Check invariants
    verify(factory).destroyObject(any());      // obj1 destroyed
    verify(factory).activateObject(any());     // Activation was attempted once
    assertThat(localPool.currentPoolSize()).isEqualTo(1); // Only obj2 is in pool

    localPool.close();
  }

  @Test
  void testActivationFailureExhaustsRetriesAndThrows() throws Exception {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .maxRetries(0) // No retries allowed
                                       .build();

    PooledObjectFactory<TestPoolObject> factory = mock(PooledObjectFactory.class);
    when(factory.createObject()).thenReturn(new TestPoolObject());

    var localPool = new SimpleObjectPool<>(config, factory);

    // 1. Borrow & Return (create idle)
    TestPoolObject obj1 = localPool.borrowObject();
    localPool.returnObject(obj1);

    // 2. Fail activation
    doThrow(new RuntimeException("Fatal Activation Error")).when(factory).activateObject(any());

    // 3. Borrow should throw because retries=0
    //    - Tries idle -> Fail -> Destroy -> Size=0
    //    - Retries check -> 0 left -> Throw Exception/Timeout

    //    Let's mock creation failure too for the potential retry.
    when(factory.createObject()).thenThrow(new RuntimeException("Create also failed"));

    assertThatThrownBy(() -> localPool.borrowObject())
        .isInstanceOf(RuntimeException.class) // Or PoolException depending on where it fails
        .satisfies(e -> {
             // It might be the create exception or timeout
             // If creation fails, it throws immediately if retries exhausted.
        });
        
    // Correct testing strategy:
    // Verify that the pool does NOT leak (size=0) even if it eventually fails to borrow (due to timeout or create failure).
    
    assertThatThrownBy(() -> localPool.borrowObject())
         .isInstanceOf(Exception.class); // Likely create failure or timeout

    assertThat(localPool.currentPoolSize()).isEqualTo(0);
    verify(factory).destroyObject(any());

    localPool.close();
  }
}
