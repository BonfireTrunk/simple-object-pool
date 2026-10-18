# simple-object-pool

A small, fast, thread-safe object pool for Java 21+. One dependency: SLF4J.

Use it for anything expensive to create and safe to reuse: database or Redis connections,
sockets, parsers, buffers, HTTP clients.

```xml
<dependency>
    <groupId>today.bonfire.oss</groupId>
    <artifactId>simple-object-pool</artifactId>
    <version>3.0.0</version>
</dependency>
```

## Quick start

Implement a factory. Only `createObject` and `destroyObject` are required.

```java
public class ConnectionFactory implements PooledObjectFactory<Connection> {
  @Override public Connection createObject()            { return Connection.open(host, port); }
  @Override public void destroyObject(Connection c)     { c.close(); }
  @Override public boolean isObjectValid(Connection c)  { return c.ping(); }   // optional
}
```

Create a pool and borrow from it.

```java
var config = SimpleObjectPoolConfig.builder()
    .poolName("redis-main")
    .maxPoolSize(16)
    .minPoolSize(2)
    .waitingForObjectTimeout(Duration.ofSeconds(5))
    .build();

try (var pool = new SimpleObjectPool<>(config, new ConnectionFactory())) {
  pool.prepare();                         // optional: prefill to minPoolSize now

  // 1. try-with-resources lease
  try (var lease = pool.lease()) {
    lease.get().send("PING");
  }

  // 2. functional helpers
  String reply = pool.apply(c -> c.send("GET key"));
  pool.use(c -> c.send("SET key value"));

  // 3. manual borrow and return
  Connection c = pool.borrowObject();
  try {
    c.send("INCR counter");
  } finally {
    pool.returnObject(c);                 // or pool.returnObject(c, true) if it is broken
  }
}
```

If the action passed to `use` or `apply` throws, the object is returned as broken and destroyed.

## How it behaves

- **Capacity.** Never more than `maxPoolSize` objects exist. Creation runs in parallel, so a
  cold start with many threads opens connections concurrently instead of one at a time.
- **No I/O under locks.** The pool never calls your factory while holding an internal lock. A
  slow or dead endpoint stalls only the threads that touch it.
- **Waiting.** When the pool is exhausted, `borrowObject()` waits up to
  `waitingForObjectTimeout` for a return or a freed slot, then throws `PoolTimeoutException`.
  A negative timeout waits forever, `Duration.ZERO` fails immediately. `borrowObject(Duration)`
  overrides the wait per call. `fairness(true)` serves waiters in arrival order.
- **Idle order.** Last in, first out by default, so a burst leaves cold objects at the tail
  where the evictor finds and removes them. `lifo(false)` switches to round-robin.
- **Validation.** `testOnCreate`, `testOnBorrow`, `testOnReturn` and `testWhileIdle` (default on)
  control when `isObjectValid` and `isObjectValidForBorrow` run. Invalid objects are destroyed.
- **Eviction.** Every `durationBetweenEvictionsRuns`, up to `numValidationsPerEvictionRun` idle
  objects are examined. An object is evicted if the factory's `shouldEvict` hook says so, if it
  has been idle longer than `objEvictionTimeout` and the pool is above `minPoolSize`, or if it
  fails validation. The run then refills to `minPoolSize`. `evictionPolicy` picks which idle
  objects are examined first: `RANDOM` (default), `OLDEST_FIRST`, `LEAST_USED`, `MOST_USED`, or
  `NONE` to turn the scheduled run off. `pool.evict()` examines every idle object immediately.
- **Abandon detection.** Off by default. Set `abandonedTimeout` to destroy objects that have been
  borrowed longer than that. Do not enable it if callers legitimately hold objects for a long
  time, for example a subscription or a blocking read.
- **Close.** `close()` destroys idle objects and wakes waiting borrowers, which throw
  `PoolClosedException`. Borrowed objects stay usable and are destroyed when returned.
  `forceClose()` destroys borrowed objects immediately.
- **Threads.** One shared daemon thread ticks maintenance for every pool in the JVM. Each run
  executes on a virtual thread, so pools never block each other and an unclosed pool never
  keeps the JVM alive.

## Configuration reference

| Builder method | Default | Meaning |
|---|---|---|
| `poolName(String)` | random `SOP-xxxxxxxx` | Name used in logs and thread names |
| `maxPoolSize(int)` | 8 | Hard capacity |
| `minPoolSize(int)` | 0 | Idle objects to maintain after each maintenance run |
| `waitingForObjectTimeout(Duration)` | 10 s | Max wait in `borrowObject()`; negative = forever, zero = fail fast |
| `fairness(boolean)` | false | Serve waiting borrowers in arrival order |
| `lifo(boolean)` | true | Reuse the most recently returned object first |
| `testOnCreate(boolean)` | false | Validate new objects before first use |
| `testOnBorrow(boolean)` | false | Run `isObjectValidForBorrow` on every borrow |
| `testOnReturn(boolean)` | false | Run `isObjectValid` on every return |
| `testWhileIdle(boolean)` | true | Validate idle objects during eviction runs |
| `evictionPolicy(EvictionPolicy)` | `RANDOM` | Which idle objects an eviction run examines; `NONE` disables the scheduled run |
| `durationBetweenEvictionsRuns(Duration)` | 5 min | Maintenance interval |
| `objEvictionTimeout(Duration)` | 5 min | Idle time after which an object above `minPoolSize` is evicted |
| `numValidationsPerEvictionRun(Integer)` | `maxPoolSize` | Idle objects examined per scheduled run |
| `maxRetries(int)` | 0 | Extra creation attempts per borrow when `createObject` throws |
| `retryCreationDelay(Duration)` | 0 | Pause before each retry, capped at the remaining wait |
| `abandonedTimeout(Duration)` | off | Destroy objects borrowed longer than this |
| `durationBetweenAbandonCheckRuns(Duration)` | 60 s | How often abandon detection runs |

`config.toBuilder()` copies every setting for tweaking.

## Factory lifecycle

| Method | Required | Called |
|---|---|---|
| `createObject()` | yes | When a borrower needs an object and capacity is free, or by `prepare` / `addObjects` / maintenance |
| `destroyObject(T)` | yes | Exactly once per object, on eviction, invalidation, abandonment or close |
| `activateObject(T)` | no | Before an idle object is handed to a borrower, and before idle validation |
| `passivateObject(T)` | no | After return, before the object goes idle, and after idle validation |
| `isObjectValidForBorrow(T)` | no | On borrow when `testOnBorrow` is on; keep it cheap |
| `isObjectValid(T)` | no | On create, return and idle tests per the `testOn*` flags |
| `shouldEvict(T, PooledObject<T>)` | no | For each idle object examined by an eviction run; true evicts it |

An exception from `createObject` reaches the borrower unchanged once retries are exhausted.
Exceptions from the other methods mark the object invalid; it is destroyed and the borrower
moves on to the next one.

## Observability

`pool.stats()` returns a `PoolStats` record with total, idle, borrowed, waiting, created,
destroyed and borrow counts. The individual counters are also available as methods
(`idleObjectCount()`, `borrowedObjectsCount()`, `waitingCount()`, and so on).

## Exceptions

All pool exceptions are unchecked and extend `PoolException`.

| Exception | When |
|---|---|
| `PoolTimeoutException` | No object became available within the wait |
| `PoolClosedException` | Borrow on a closed pool, or the pool closed while waiting |
| `PoolObjectException` | Returning null or an object that was never borrowed from this pool |
| `PoolObjectValidationException` | A newly created object failed `testOnCreate` validation |

Factory exceptions from `createObject` are not wrapped.

## Upgrading from 2.x

See [CHANGELOG.md](CHANGELOG.md) for the full list. The short version:

- Pooled classes no longer implement `PoolObject`. Delete `getEntityId` and `setEntityId`.
- `PooledObjectFactory` only requires `createObject` and `destroyObject`.
- Abandon detection is off unless you set `abandonedTimeout`.
- The constructor no longer prefills. Call `prepare()` if you relied on that.
- `maxRetries` defaults to 0. Creation failures surface after one attempt.
- Config time getters return `Duration`.
- `close()` no longer destroys borrowed objects. Use `forceClose()` for the old behaviour.
- Returning an object that is not borrowed throws instead of logging.

## Building

```bash
mvn verify            # unit tests
mvn verify -Pperf     # also runs the performance test
```

Requires JDK 21 or newer.

## License

Apache License 2.0.
