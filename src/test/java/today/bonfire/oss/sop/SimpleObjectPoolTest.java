package today.bonfire.oss.sop;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import today.bonfire.oss.sop.exceptions.PoolClosedException;
import today.bonfire.oss.sop.exceptions.PoolException;
import today.bonfire.oss.sop.exceptions.PoolObjectException;
import today.bonfire.oss.sop.exceptions.PoolTimeoutException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static today.bonfire.oss.sop.TestSupport.awaitUntil;
import static today.bonfire.oss.sop.TestSupport.sleepQuietly;

@Timeout(30)
@ExtendWith(MockitoExtension.class)
class SimpleObjectPoolTest {

  private static final int      MAX_POOL_SIZE       = 5;
  private static final Duration OBJECT_WAIT_TIMEOUT = Duration.ofMillis(100);
  private static final Duration AWAIT               = Duration.ofSeconds(5);

  private PooledObjectFactory<TestPoolObject> factory;

  private SimpleObjectPool<TestPoolObject> pool;

  /** Every pool created through {@link #newPool} is closed after the test. */
  private final List<SimpleObjectPool<?>> pools = new ArrayList<>();

  private <T> SimpleObjectPool<T> newPool(SimpleObjectPoolConfig config, PooledObjectFactory<T> f) {
    var p = new SimpleObjectPool<>(config, f);
    pools.add(p);
    return p;
  }

  @BeforeEach
  void setUp() {
    factory = mock();
    // Every borrow, including a fresh object's first, runs the borrow test; objects pass unless a test says otherwise.
    lenient().when(factory.isObjectValidForBorrow(any())).thenReturn(true);
    // Default eviction interval (5 min) keeps the scheduled maintenance out of the way of these tests.
    pool = newPool(SimpleObjectPoolConfig.builder()
                                         .maxPoolSize(MAX_POOL_SIZE)
                                         .minPoolSize(0)
                                         .testWhileIdle(true)
                                         .testOnCreate(false)
                                         .testOnBorrow(true)
                                         .waitingForObjectTimeout(OBJECT_WAIT_TIMEOUT)
                                         .build(), factory);
  }

  @AfterEach
  void tearDown() {
    for (var p : pools) {
      p.forceClose();
    }
  }

  @Test
  void testDestroyAllIdleObjects() {
    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1).thenReturn(obj2);

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
  void testStatistics() {
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

    // v3: per-object stats are looked up by the object itself, not by an entity id
    assertThat(pool.numOfTimesBorrowed(b2)).isEqualTo(2);
  }

  @Test
  void testManualEvictClearsAllIdle() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(10)
                                       .minPoolSize(0)
                                       .objEvictionTimeout(Duration.ofMillis(1)) // Instant expiration
                                       .numValidationsPerEvictionRun(1) // Only limits scheduled runs
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject o1 = new TestPoolObject();
    TestPoolObject o2 = new TestPoolObject();
    TestPoolObject o3 = new TestPoolObject();
    when(factory.createObject()).thenReturn(o1).thenReturn(o2).thenReturn(o3);

    // Create 3 idle objects
    var b1 = localPool.borrowObject();
    var b2 = localPool.borrowObject();
    var b3 = localPool.borrowObject();
    localPool.returnObject(b1);
    localPool.returnObject(b2);
    localPool.returnObject(b3);

    long returnedAt = System.currentTimeMillis();
    awaitUntil("objects idle for longer than the eviction timeout", AWAIT, () -> System.currentTimeMillis() > returnedAt + 5);

    // Manual evict examines ALL idle objects despite numValidationsPerEvictionRun=1
    localPool.evict();

    assertThat(localPool.idleObjectCount()).isEqualTo(0);
    verify(factory).destroyObject(o1);
    verify(factory).destroyObject(o2);
    verify(factory).destroyObject(o3);
  }

  @Test
  void testReturnForeignObject() {
    TestPoolObject foreign = new TestPoolObject();
    // v3: returning an object that is not borrowed from the pool throws (it used to log and return)
    assertThatThrownBy(() -> pool.returnObject(foreign)).isInstanceOf(PoolObjectException.class);
    assertThat(pool.currentPoolSize()).isEqualTo(0);
  }

  @Test
  void testReturnNullObject() {
    // v3: null is rejected with PoolObjectException and a reworded message
    assertThatThrownBy(() -> pool.returnObject(null))
        .isInstanceOf(PoolObjectException.class)
        .hasMessageContaining("Cannot return null");
  }

  @Test
  void testBorrowObject() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);

    TestPoolObject borrowed = pool.borrowObject();
    assertThat(borrowed).isSameAs(entity);
    verify(factory, times(1)).createObject();
    // A freshly created object is activated and then tested on borrow, like an idle one.
    var order = inOrder(factory);
    order.verify(factory).activateObject(entity);
    order.verify(factory).isObjectValidForBorrow(entity);
  }

  @Test
  void testBorrowAndReturnObject() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    when(factory.isObjectValidForBorrow(entity)).thenReturn(true);

    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed);

    // The fresh object was tested on its first borrow
    verify(factory, times(1)).isObjectValidForBorrow(entity);

    TestPoolObject borrowedAgain = pool.borrowObject();
    assertThat(borrowedAgain)
        .as("Should get the same object back")
        .isSameAs(entity);
    verify(factory, times(1)).createObject();
    verify(factory, times(2)).activateObject(entity);
    verify(factory, times(2)).isObjectValidForBorrow(entity);
  }

  @Test
  void testPoolExhaustion() {
    when(factory.createObject()).thenAnswer(inv -> new TestPoolObject());

    for (int i = 0; i < MAX_POOL_SIZE; i++) {
      assertThat(pool.borrowObject()).isNotNull();
    }

    // Next borrow should time out (message reworded in v3)
    assertThatThrownBy(() -> pool.borrowObject())
        .isInstanceOf(PoolTimeoutException.class)
        .hasMessageContaining("Timed out");

    assertThat(pool.currentPoolSize())
        .as("Pool size should be at maximum")
        .isEqualTo(MAX_POOL_SIZE);
    assertThat(pool.borrowedObjectsCount())
        .as("All objects should be borrowed")
        .isEqualTo(MAX_POOL_SIZE);
  }

  @Test
  void testObjectValidation() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);

    TestPoolObject borrowed = pool.borrowObject();
    pool.returnObject(borrowed, true);

    verify(factory, times(1)).destroyObject(entity);
    assertThat(pool.currentPoolSize()).isEqualTo(0);
  }

  @Test
  void testIdleObjectEviction() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(MAX_POOL_SIZE)
                                       .minPoolSize(0)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(50))
                                       .objEvictionTimeout(Duration.ofMillis(100))
                                       .testOnBorrow(true)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject entity = new TestPoolObject();
    TestPoolObject second = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity).thenReturn(second);
    // Idle validation passes, so only the idle timeout can evict the object
    lenient().when(factory.isObjectValid(any())).thenReturn(true);

    var borrowed = localPool.borrowObject();
    localPool.returnObject(borrowed);

    awaitUntil("idle object to be evicted", AWAIT, () -> localPool.currentPoolSize() == 0);
    verify(factory).destroyObject(entity);

    // Borrow again must create a new object
    TestPoolObject newBorrowed = localPool.borrowObject();
    assertThat(newBorrowed).isSameAs(second).isNotSameAs(borrowed);
  }

  @Test
  void testClose() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);

    TestPoolObject borrowed = pool.borrowObject();
    pool.close();

    // v3: close() is graceful, a borrowed object is destroyed when it is returned
    verify(factory, never()).destroyObject(any());
    assertThat(pool.isClosed()).isTrue();
    assertThatThrownBy(() -> pool.borrowObject()).isInstanceOf(PoolClosedException.class);

    pool.returnObject(borrowed);
    verify(factory, times(1)).destroyObject(borrowed);
    assertThat(pool.currentPoolSize()).isEqualTo(0);
  }

  @Test
  void testCloseDestroysIdleObjects() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);

    pool.returnObject(pool.borrowObject());
    pool.close();

    verify(factory, times(1)).destroyObject(entity);
  }

  @Test
  void testForceCloseDestroysBorrowedObjects() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);

    pool.borrowObject();
    pool.forceClose();

    verify(factory, times(1)).destroyObject(entity);
    assertThat(pool.currentPoolSize()).isEqualTo(0);
    assertThat(pool.borrowedObjectsCount()).isEqualTo(0);
  }

  @Test
  void testReturnInvalidObject() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(MAX_POOL_SIZE)
                                       .testOnReturn(true)
                                       .testOnBorrow(true)
                                       .waitingForObjectTimeout(OBJECT_WAIT_TIMEOUT)
                                       .build();
    var localPool = newPool(config, factory);
    TestPoolObject entity = new TestPoolObject();
    TestPoolObject newEntity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity).thenReturn(newEntity);

    TestPoolObject borrowed = localPool.borrowObject();

    when(factory.isObjectValid(entity)).thenReturn(false);

    // v3: testOnReturn failure destroys the object immediately
    localPool.returnObject(borrowed);
    verify(factory, times(1)).destroyObject(entity);
    assertThat(localPool.idleObjectCount()).isEqualTo(0);

    // Next borrow should create new object
    TestPoolObject newBorrowed = localPool.borrowObject();
    assertThat(newBorrowed).isSameAs(newEntity).isNotSameAs(borrowed);
  }

  @Test
  void testPassivateFailureDestroysObject() {
    TestPoolObject entity = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity);
    doThrow(new IllegalStateException("passivate failed")).when(factory).passivateObject(entity);

    pool.returnObject(pool.borrowObject());

    verify(factory, times(1)).destroyObject(entity);
    assertThat(pool.idleObjectCount()).isEqualTo(0);
    assertThat(pool.currentPoolSize()).isEqualTo(0);
  }

  // The old testMinimumTimeoutEnforcement was deleted: v3 no longer clamps timeouts to a 10 ms minimum.

  @Test
  void testNullTimeout() {
    var factory = new TestPooledObjectFactory();
    var localPool = newPool(SimpleObjectPoolConfig.builder().maxPoolSize(1).waitingForObjectTimeout(Duration.ZERO)
                                                  .build(), factory);

    assertThat(localPool.borrowObject()).isNotNull();
    // A zero wait fails immediately when the pool is exhausted
    assertThatThrownBy(localPool::borrowObject).isInstanceOf(PoolTimeoutException.class);
  }

  @Test
  void testNegativeTimeout() {
    var factory = new TestPooledObjectFactory();
    var localPool = newPool(SimpleObjectPoolConfig.builder()
                                                  .waitingForObjectTimeout(Duration.ZERO.minusSeconds(1L))
                                                  .build(), factory);

    assertThat(localPool.borrowObject()).isNotNull();
  }

  @Test
  void testValidationFailureOnBorrowReplacesIdleObject() {
    var factory = new TestPooledObjectFactory();
    var localPool = newPool(SimpleObjectPoolConfig.builder()
                                                  .maxPoolSize(2)
                                                  .testOnBorrow(true)
                                                  .build(), factory);

    var idle = localPool.borrowObject();
    localPool.returnObject(idle);
    idle.destroy(); // TestPooledObjectFactory reports destroyed objects as invalid

    var borrowed = localPool.borrowObject();

    assertThat(borrowed).as("the invalid idle object is replaced by a fresh one").isNotSameAs(idle);
    assertThat(borrowed.isDestroyed()).isFalse();
    assertThat(localPool.currentPoolSize()).isEqualTo(1);
    assertThat(localPool.numOfObjectsCreated()).isEqualTo(2);
    assertThat(localPool.stats().destroyed()).isEqualTo(1);
  }

  @Test
  void testMaxCreationAttempts() {
    var config = SimpleObjectPoolConfig.builder().maxPoolSize(2).maxRetries(3).build();
    var localPool = newPool(config, factory);
    when(factory.createObject()).thenThrow(new IllegalStateException("boom"));

    // maxRetries=3 means 1 initial attempt + 3 retries, and the factory exception reaches the caller unchanged
    assertThatThrownBy(localPool::borrowObject)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("boom");

    verify(factory, times(4)).createObject();
    assertThat(localPool.currentPoolSize()).isEqualTo(0);
  }

  @Test
  void testDefaultsAreOneAttemptAndNoAbandonDetection() {
    var config = SimpleObjectPoolConfig.defaultConfig();
    assertThat(config.maxRetries()).isEqualTo(0);
    assertThat(config.abandonedTimeout()).isNull();
    assertThat(config.abandonCheckEnabled()).isFalse();
    assertThat(config.lifo()).isTrue();

    var localPool = newPool(config, factory);
    when(factory.createObject()).thenThrow(new IllegalStateException("boom"));
    assertThatThrownBy(localPool::borrowObject).isInstanceOf(IllegalStateException.class);
    verify(factory, times(1)).createObject();
  }

  @Test
  void testConstructorDoesNotPrefillButPrepareDoes() {
    var config = SimpleObjectPoolConfig.builder().maxPoolSize(5).minPoolSize(2).build();
    var localPool = newPool(config, factory);
    when(factory.createObject()).thenAnswer(inv -> new TestPoolObject());

    // v3: the constructor creates nothing
    verify(factory, never()).createObject();
    assertThat(localPool.currentPoolSize()).isEqualTo(0);

    assertThat(localPool.prepare()).isEqualTo(2);
    assertThat(localPool.idleObjectCount()).isEqualTo(2);
    verify(factory, times(2)).createObject();
  }

  @Test
  void testLifoReusesMostRecentlyReturned() {
    var factory = new TestPooledObjectFactory();
    var localPool = newPool(SimpleObjectPoolConfig.builder().maxPoolSize(3).build(), factory);

    var a = localPool.borrowObject();
    var b = localPool.borrowObject();
    localPool.returnObject(a);
    localPool.returnObject(b);

    assertThat(localPool.borrowObject()).isSameAs(b);
  }

  @Test
  void testFifoReusesLeastRecentlyReturned() {
    var factory = new TestPooledObjectFactory();
    var localPool = newPool(SimpleObjectPoolConfig.builder().maxPoolSize(3).lifo(false).build(), factory);

    var a = localPool.borrowObject();
    var b = localPool.borrowObject();
    localPool.returnObject(a);
    localPool.returnObject(b);

    assertThat(localPool.borrowObject()).isSameAs(a);
  }

  @Test
  void testEvictDoesNotShrinkBelowMinPoolSize() {
    var factory = new TestPooledObjectFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(5)
                                       .minPoolSize(2)
                                       .objEvictionTimeout(Duration.ofMillis(1))
                                       .build();
    var localPool = newPool(config, factory);
    assertThat(localPool.addObjects(4)).isEqualTo(4);

    long start = System.currentTimeMillis();
    awaitUntil("idle timeout to elapse", AWAIT, () -> System.currentTimeMillis() > start + 5);
    localPool.evict();

    assertThat(localPool.currentPoolSize()).isEqualTo(2);
    assertThat(localPool.idleObjectCount()).isEqualTo(2);
  }

  @Test
  void testShouldEvictHookIsConsultedByEvict() {
    TestPoolObject entity = new TestPoolObject();
    TestPoolObject keeper = new TestPoolObject();
    when(factory.createObject()).thenReturn(entity).thenReturn(keeper);
    when(factory.shouldEvict(eq(entity), any())).thenReturn(true);
    when(factory.shouldEvict(eq(keeper), any())).thenReturn(false);
    when(factory.isObjectValid(keeper)).thenReturn(true);

    pool.addObjects(2);
    pool.evict();

    verify(factory).destroyObject(entity);
    verify(factory, never()).destroyObject(keeper);
    assertThat(pool.idleObjectCount()).isEqualTo(1);
  }

  @Test
  void testEvictionPolicyOldestFirst() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(3)
                                       .minPoolSize(0)
                                       .lifo(false)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.OLDEST_FIRST)
                                       .objEvictionTimeout(Duration.ofSeconds(1000))
                                       .numValidationsPerEvictionRun(1)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(200))
                                       .testOnBorrow(false)
                                       .testOnReturn(false)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    TestPoolObject obj3 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1).thenReturn(obj2).thenReturn(obj3);
    when(factory.isObjectValid(obj1)).thenReturn(false);
    lenient().when(factory.isObjectValid(obj2)).thenReturn(true);
    lenient().when(factory.isObjectValid(obj3)).thenReturn(true);

    var borrowed1 = localPool.borrowObject();
    waitForNextMillis();
    var borrowed2 = localPool.borrowObject();
    waitForNextMillis();
    var borrowed3 = localPool.borrowObject();

    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);
    localPool.returnObject(borrowed3);

    awaitUntil("oldest object to be evicted", AWAIT, () -> localPool.currentPoolSize() == 2);
    verify(factory).destroyObject(obj1);

    var borrowedAgain1 = localPool.borrowObject();
    var borrowedAgain2 = localPool.borrowObject();

    assertThat(borrowedAgain1).isNotSameAs(borrowed1);
    assertThat(borrowedAgain2).isNotSameAs(borrowed1);
  }

  @Test
  void testEvictionPolicyLeastUsed() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(2)
                                       .minPoolSize(0)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.LEAST_USED)
                                       .objEvictionTimeout(Duration.ofMinutes(1))
                                       .numValidationsPerEvictionRun(1)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(200))
                                       .testOnBorrow(true)
                                       .testOnReturn(false)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1).thenReturn(obj2);
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);
    // Only the least used object fails idle validation, so later scheduled runs leave the survivor alone
    when(factory.isObjectValid(obj2)).thenReturn(false);
    lenient().when(factory.isObjectValid(obj1)).thenReturn(true);

    var borrowed1 = localPool.borrowObject(); // Will be borrowed twice
    var borrowed2 = localPool.borrowObject(); // Will be borrowed once

    localPool.returnObject(borrowed1);
    // Borrow obj1 again to increase its usage count
    borrowed1 = localPool.borrowObject();
    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);

    awaitUntil("least used object to be evicted", AWAIT, () -> localPool.currentPoolSize() == 1);
    verify(factory).destroyObject(obj2);

    // The survivor must be obj1. Checked through pool state rather than a borrow: later scheduled
    // runs keep taking obj1 out of the idle queue for its idle test, and a borrow that lands in
    // that window creates a third object instead of handing obj1 back.
    verify(factory, never()).destroyObject(obj1);
    assertThat(localPool.numOfObjectsCreated()).isEqualTo(2);
    assertThat(localPool.currentPoolSize()).isEqualTo(1);
  }

  @Test
  void testEvictionPolicyMostUsed() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(2)
                                       .minPoolSize(0)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.MOST_USED)
                                       .objEvictionTimeout(Duration.ofSeconds(1000))
                                       .numValidationsPerEvictionRun(1)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(200))
                                       .testOnBorrow(true)
                                       .testOnReturn(false)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject obj1 = new TestPoolObject();
    TestPoolObject obj2 = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj1).thenReturn(obj2);
    when(factory.isObjectValidForBorrow(any())).thenReturn(true);
    // Only the most used object fails idle validation, so later scheduled runs leave the survivor alone
    when(factory.isObjectValid(obj1)).thenReturn(false);
    lenient().when(factory.isObjectValid(obj2)).thenReturn(true);

    var borrowed1 = localPool.borrowObject(); // Will be borrowed twice
    var borrowed2 = localPool.borrowObject(); // Will be borrowed once

    localPool.returnObject(borrowed1);
    // Borrow obj1 again to increase its usage count
    borrowed1 = localPool.borrowObject();
    localPool.returnObject(borrowed1);
    localPool.returnObject(borrowed2);

    awaitUntil("most used object to be evicted", AWAIT, () -> localPool.currentPoolSize() == 1);
    verify(factory).destroyObject(obj1);

    // Same reasoning as the LEAST_USED test: assert the survivor through state, not a racy borrow.
    verify(factory, never()).destroyObject(obj2);
    assertThat(localPool.numOfObjectsCreated()).isEqualTo(2);
    assertThat(localPool.currentPoolSize()).isEqualTo(1);
  }

  @Test
  void testRetryCreationWithDelay() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .minPoolSize(0)
                                       .maxRetries(2)
                                       .retryCreationDelay(Duration.ofMillis(50))
                                       .testOnBorrow(true)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject validObj = new TestPoolObject();
    when(factory.createObject())
        .thenThrow(new PoolObjectException("Failed first attempt"))
        .thenThrow(new PoolObjectException("Failed second attempt"))
        .thenReturn(validObj);

    var borrowed = localPool.borrowObject();
    assertThat(borrowed)
        .as("Should get valid object after retries")
        .isSameAs(validObj);
    verify(factory, times(3)).createObject();
  }

  @Test
  void testAbandonedObjectDetection() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .minPoolSize(0)
                                       .abandonedTimeout(Duration.ofMillis(100))
                                       .durationBetweenAbandonCheckRuns(Duration.ofMillis(50))
                                       .testOnBorrow(true)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject obj = new TestPoolObject();
    when(factory.createObject()).thenReturn(obj);

    localPool.borrowObject();

    // The pool slot is released only after the factory destroyed the object, so wait on the pool size.
    awaitUntil("abandoned object to be removed", AWAIT, () -> localPool.borrowedObjectsCount() == 0 && localPool.currentPoolSize() == 0);
    verify(factory).destroyObject(obj);
    assertThat(localPool.currentPoolSize()).isEqualTo(0);
  }

  @Test
  void testValidationOnCreate() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .minPoolSize(0)
                                       .testOnCreate(true)
                                       .build();
    var localPool = newPool(config, factory);

    TestPoolObject invalidObj = new TestPoolObject();
    when(factory.createObject()).thenReturn(invalidObj);
    when(factory.isObjectValid(invalidObj)).thenReturn(false);

    assertThatThrownBy(localPool::borrowObject)
        .as("Should throw exception for invalid object on create")
        .isInstanceOf(PoolException.class);

    verify(factory).destroyObject(invalidObj);
    assertThat(localPool.currentPoolSize()).isEqualTo(0);
  }

  /** Thread-safe hand written factory that counts lifecycle calls, for the scheduled eviction test. */
  private static final class LifecycleFactory implements PooledObjectFactory<TestPoolObject> {
    final AtomicInteger created     = new AtomicInteger();
    final AtomicInteger activated   = new AtomicInteger();
    final AtomicInteger validated   = new AtomicInteger();
    final AtomicInteger passivated  = new AtomicInteger();
    final AtomicInteger destroyed   = new AtomicInteger();
    volatile boolean    failActivate = false;
    volatile boolean    valid        = true;

    @Override
    public TestPoolObject createObject() {
      created.incrementAndGet();
      return new TestPoolObject();
    }

    @Override
    public void destroyObject(TestPoolObject obj) {
      destroyed.incrementAndGet();
      obj.destroy();
    }

    @Override
    public void activateObject(TestPoolObject obj) {
      activated.incrementAndGet();
      if (failActivate) {
        throw new IllegalStateException("Activation failed during eviction");
      }
    }

    @Override
    public void passivateObject(TestPoolObject obj) {
      passivated.incrementAndGet();
    }

    @Override
    public boolean isObjectValid(TestPoolObject obj) {
      validated.incrementAndGet();
      return valid;
    }
  }

  @Test
  void testEvictionLifecycle() {
    var lf = new LifecycleFactory();
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(5)
                                       .minPoolSize(2)
                                       .evictionPolicy(SimpleObjectPoolConfig.EvictionPolicy.RANDOM)
                                       .durationBetweenEvictionsRuns(Duration.ofMillis(50))
                                       .objEvictionTimeout(Duration.ofMinutes(10)) // Long timeout so we rely on validation
                                       .testWhileIdle(true)
                                       .numValidationsPerEvictionRun(5)
                                       .build();
    var localPool = newPool(config, lf);

    // v3: no prefill in the constructor, prepare() (or the first maintenance run) fills minPoolSize
    assertThat(lf.created.get()).isEqualTo(0);
    localPool.prepare();
    awaitUntil("minPoolSize filled", AWAIT, () -> localPool.idleObjectCount() >= 2);

    // Borrow and return to make it idle
    localPool.returnObject(localPool.borrowObject());

    // Eviction runs activate -> isObjectValid -> passivate on idle objects
    awaitUntil("eviction lifecycle calls", AWAIT, () -> lf.activated.get() > 0 && lf.validated.get() > 0 && lf.passivated.get() > 0);
    assertThat(lf.destroyed.get()).as("valid objects are not destroyed").isEqualTo(0);

    // Activation failure during eviction destroys the object
    lf.failActivate = true;
    awaitUntil("object destroyed after activation failure", AWAIT, () -> lf.destroyed.get() > 0);
    lf.failActivate = false;

    // Validation failure during eviction destroys the object, and the pool is topped up again
    int destroyedBefore = lf.destroyed.get();
    int createdBefore   = lf.created.get();
    lf.valid = false;
    awaitUntil("object destroyed after validation failure", AWAIT, () -> lf.destroyed.get() > destroyedBefore);
    awaitUntil("minPoolSize refilled", AWAIT, () -> lf.created.get() > createdBefore);

    lf.valid = true;
    awaitUntil("pool stabilises at minPoolSize", AWAIT, () -> localPool.idleObjectCount() >= 2);
  }

  @Test
  void testObjectCreationFailuresDoNotLeakPoolSize() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(2)
                                       .minPoolSize(0)
                                       .maxRetries(1)
                                       .testOnBorrow(false)
                                       .build();

    PooledObjectFactory<TestPoolObject> failingFactory = mock();

    // First attempt throws, the retry succeeds
    when(failingFactory.createObject())
        .thenThrow(new RuntimeException("Simulated creation failure"))
        .thenReturn(new TestPoolObject());

    var localPool = newPool(config, failingFactory);

    TestPoolObject obj = localPool.borrowObject();
    assertThat(obj).isNotNull();
    assertThat(localPool.currentPoolSize())
        .as("Pool size should be 1 after successful creation (despite initial failure)")
        .isEqualTo(1);

    doThrow(new RuntimeException("Always fail")).when(failingFactory).createObject();

    assertThatThrownBy(localPool::borrowObject)
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("Always fail");

    assertThat(localPool.currentPoolSize())
        .as("Only the still borrowed object counts, failed creations must not leak slots")
        .isEqualTo(1);
  }

  /** Waits until the wall clock moves, so objects created next have a strictly later creation time. */
  private static void waitForNextMillis() {
    long start = System.currentTimeMillis();
    awaitUntil("clock to advance", Duration.ofSeconds(2), () -> System.currentTimeMillis() > start);
  }

  private static PooledObjectFactory<TestPoolObject> slowFactory() {
    return new PooledObjectFactory<>() {
      @Override
      public TestPoolObject createObject() {
        sleepQuietly(10); // simulated work
        return new TestPoolObject();
      }

      @Override
      public void destroyObject(TestPoolObject obj) {}
    };
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

    var             localPool    = newPool(config, slowFactory());
    CountDownLatch  startLatch   = new CountDownLatch(1);
    CountDownLatch  doneLatch    = new CountDownLatch(threadCount);
    ExecutorService executor     = Executors.newFixedThreadPool(threadCount);
    AtomicInteger   successCount = new AtomicInteger(0);
    AtomicInteger   timeoutCount = new AtomicInteger(0);
    AtomicInteger   errorCount   = new AtomicInteger(0);

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            startLatch.await();
            TestPoolObject obj = localPool.borrowObject();
            successCount.incrementAndGet();
            sleepQuietly(20); // simulated work
            localPool.returnObject(obj);
          } catch (PoolTimeoutException pte) {
            timeoutCount.incrementAndGet();
          } catch (Exception e) {
            errorCount.incrementAndGet();
          } finally {
            doneLatch.countDown();
          }
        });
      }

      startLatch.countDown();
      assertThat(doneLatch.await(10, TimeUnit.SECONDS)).as("all workers finished").isTrue();
    } finally {
      executor.shutdownNow();
    }

    assertThat(errorCount.get()).isEqualTo(0);
    assertThat(successCount.get() + timeoutCount.get()).isEqualTo(threadCount);
    assertThat(localPool.numOfObjectsCreated()).as("never creates more than max").isLessThanOrEqualTo(maxPoolSize);
    assertThat(localPool.currentPoolSize())
        .as("Pool size should never exceed maxPoolSize")
        .isLessThanOrEqualTo(maxPoolSize);
    assertThat(localPool.currentPoolSize())
        .as("Pool should have created some objects")
        .isGreaterThan(0);
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

    var localFactory = new TestPooledObjectFactory();
    var localPool    = newPool(config, localFactory);

    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch  latch    = new CountDownLatch(threadCount);
    AtomicInteger   errors   = new AtomicInteger(0);
    AtomicInteger   timeouts = new AtomicInteger(0);
    boolean         finished;

    try {
      for (int i = 0; i < threadCount; i++) {
        executor.submit(() -> {
          try {
            for (int j = 0; j < iterations; j++) {
              try {
                TestPoolObject obj = localPool.borrowObject();
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

      finished = latch.await(15, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
    }

    assertThat(finished).as("Test did not finish in time").isTrue();
    assertThat(errors.get()).as("Unexpected errors during stress test").isEqualTo(0);

    assertThat(localPool.currentPoolSize())
        .as("Pool size should verify: current <= max")
        .isLessThanOrEqualTo(maxPoolSize);

    assertThat(localPool.borrowedObjectsCount())
        .as("All objects should be returned")
        .isEqualTo(0);
  }

  @Test
  void testActivationFailureWithRetrySucceeds() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .maxRetries(1)
                                       .build();

    PooledObjectFactory<TestPoolObject> factory = mock();
    when(factory.createObject()).thenReturn(new TestPoolObject()).thenReturn(new TestPoolObject());

    var localPool = newPool(config, factory);

    // 1. First borrow should succeed (newly created and activated)
    TestPoolObject obj1 = localPool.borrowObject();
    assertThat(obj1).isNotNull();

    // 2. Return it to the pool so it becomes idle
    localPool.returnObject(obj1);

    // 3. Force activation failure for the NEXT borrow (recycling obj1)
    doThrow(new RuntimeException("Activation Failed")).when(factory).activateObject(obj1);

    // 4. Borrow should succeed: activation of idle obj1 fails -> obj1 destroyed -> a new object is created
    TestPoolObject obj2 = localPool.borrowObject();

    assertThat(obj2).isNotNull().isNotSameAs(obj1);

    verify(factory).destroyObject(obj1);
    verify(factory, times(2)).activateObject(obj1); // fresh borrow, then the failed recycle
    verify(factory).activateObject(obj2);
    assertThat(localPool.currentPoolSize()).isEqualTo(1);
  }

  @Test
  void testActivationFailureExhaustsRetriesAndThrows() {
    var config = SimpleObjectPoolConfig.builder()
                                       .maxPoolSize(1)
                                       .maxRetries(0) // exactly one creation attempt
                                       .build();

    PooledObjectFactory<TestPoolObject> factory = mock();
    when(factory.createObject()).thenReturn(new TestPoolObject());

    var localPool = newPool(config, factory);

    // 1. Borrow & Return (create idle)
    TestPoolObject obj1 = localPool.borrowObject();
    localPool.returnObject(obj1);

    // 2. Fail activation and creation
    doThrow(new RuntimeException("Fatal Activation Error")).when(factory).activateObject(any());
    doThrow(new IllegalStateException("Create also failed")).when(factory).createObject();

    // 3. Idle obj1 fails activation and is destroyed; the single creation attempt fails and its exception propagates unchanged
    assertThatThrownBy(localPool::borrowObject)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Create also failed");

    assertThatThrownBy(localPool::borrowObject)
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Create also failed");

    assertThat(localPool.currentPoolSize()).isEqualTo(0);
    verify(factory).destroyObject(obj1);
  }
}
