# simple-object-pool

[![Maven Central](https://img.shields.io/maven-central/v/today.bonfire.oss/simple-object-pool.svg)](https://central.sonatype.com/artifact/today.bonfire.oss/simple-object-pool)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/java-21%2B-orange.svg)](#building)

A small, fast, thread-safe object pool for Java 21 and later. It has one dependency, SLF4J,
and it was written with virtual threads in mind from the start.

Use it for anything that is expensive to create and safe to reuse: database and Redis
connections, sockets, parsers, buffers, HTTP clients.

```xml
<dependency>
    <groupId>today.bonfire.oss</groupId>
    <artifactId>simple-object-pool</artifactId>
    <version>3.0.0</version>
</dependency>
```

## Why another pool?

Most Java pools predate virtual threads and still lean on `synchronized` for their state
transitions. That was fine for a few hundred platform threads. It is not fine when ten thousand
virtual threads share thirty connections. On JDK 21 to 23 a virtual thread that blocks inside a
`synchronized` block pins its carrier thread, so the pool quietly turns virtual threads back
into platform threads. JDK 24 fixed the pinning, but a monitor held across a deque operation or
a factory call is still a point where every borrower and returner queues up behind one thread.

This pool takes a different approach:

- The hot path takes no pool-wide lock. Borrowing an idle object and returning a healthy one
  touch a concurrent deque, a concurrent map and a few atomic counters. Nothing else.
- Capacity is a compare-and-set counter rather than a lock, so a cold start with many threads
  opens connections in parallel instead of one at a time.
- Your factory is never called while the pool holds a lock that borrowers or returners wait on.
  Eviction passes are serialised among themselves. A slow endpoint stalls only the threads that
  touch it.
- The only lock on the borrow path is taken when the pool is exhausted, and its single job is
  to park the caller until something frees up.
- There is no `synchronized` and no `Object.wait()` anywhere in the library. Under JDK Flight
  Recorder it produces zero `jdk.VirtualThreadPinned` events on JDK 21, with or without
  factory validation turned on, and on JDK 25 the only events come from the JDK itself.

## Quick start

Implement a factory. Only `createObject` and `destroyObject` are required; the rest have
sensible no-op defaults.

```java
public class ConnectionFactory implements PooledObjectFactory<Connection> {
  @Override public Connection createObject()            { return Connection.open(host, port); }
  @Override public void destroyObject(Connection c)     { c.close(); }
  @Override public boolean isObjectValid(Connection c)  { return c.ping(); }   // optional
}
```

Then build a pool and borrow from it. There are three ways to borrow, pick whichever reads
best in your code.

```java
var config = SimpleObjectPoolConfig.builder()
    .poolName("redis-main")
    .maxPoolSize(16)
    .minPoolSize(2)
    .waitingForObjectTimeout(Duration.ofSeconds(5))
    .build();

try (var pool = new SimpleObjectPool<>(config, new ConnectionFactory())) {
  pool.prepare();                         // optional: open minPoolSize connections now

  // 1. A lease, closed by try-with-resources
  try (var lease = pool.lease()) {
    lease.get().send("PING");
  }

  // 2. Functional helpers
  String reply = pool.apply(c -> c.send("GET key"));
  pool.use(c -> c.send("SET key value"));

  // 3. Manual borrow and return
  Connection c = pool.borrowObject();
  try {
    c.send("INCR counter");
  } finally {
    pool.returnObject(c);                 // or pool.returnObject(c, true) if it is broken
  }
}
```

If the action you pass to `use` or `apply` throws, the object is returned as broken and
destroyed, and your exception propagates unchanged.

When two pools share a config but need distinct names, use
`new SimpleObjectPool<>("pool-name", config, factory)`.

## How it behaves

**Capacity.** There are never more than `maxPoolSize` objects alive, counting ones that are
still being created.

**Waiting.** When the pool is empty and full, `borrowObject()` waits up to
`waitingForObjectTimeout` for a return or a freed slot and then throws `PoolTimeoutException`.
A negative timeout waits forever and `Duration.ZERO` fails immediately. You can override the
wait per call with `borrowObject(Duration)`. Creation retries stop when a bounded borrow
deadline passes and rethrow the last creation failure; the first creation attempt is always
allowed, so a slow factory call can finish after the deadline.

**Fairness.** By default a thread that just returned an object can grab it straight back
before a parked waiter wakes up. That is the fastest option. If you care more about bounded
wait times than peak throughput, `fairness(true)` makes newcomers queue behind parked waiters.
In our tests it costs about two percent of throughput and cuts the worst-case wait by more
than half under heavy oversubscription.

**Idle order.** Last in, first out by default. A burst of load leaves the cold objects at the
tail of the queue, where the evictor finds them and lets them go. `lifo(false)` switches to
round-robin if you would rather keep every object warm.

**Validation.** `testOnCreate`, `testOnBorrow`, `testOnReturn` and `testWhileIdle` control when
`isObjectValid` and `isObjectValidForBorrow` run. Anything that fails is destroyed and the
borrower moves on to the next object. Only `testWhileIdle` is on by default.

**Eviction.** Every `durationBetweenEvictionsRuns` the pool looks at its idle objects. An object
goes if your factory's `shouldEvict` hook says so, if it has sat idle longer than
`objEvictionTimeout` while the pool is above `minPoolSize`, or if it fails validation. After
the pass the pool refills to `minPoolSize`. Factory validation is limited to
`numValidationsPerEvictionRun` objects per pass, chosen by `evictionPolicy`: `RANDOM` is the
default, and `OLDEST_FIRST`, `LEAST_USED` and `MOST_USED` do what they say. `NONE` turns the
scheduled run off. `pool.evict()` examines every idle object right now, on your thread.

**Abandon detection.** Off by default. Set `abandonedTimeout` and the pool will destroy objects
that have been out longer than that. Leave it off if callers legitimately hold objects for a
long time, for example a pub/sub subscription or a blocking read.

**Close.** `close()` destroys idle objects and wakes any waiting borrowers, which throw
`PoolClosedException`. Objects that are currently borrowed stay usable and are destroyed when
they come back. `forceClose()` destroys them immediately instead, which is the right call when
you need everything torn down and do not trust callers to return in time.
A creation or maintenance run already in progress may finish after `close()` returns; any object
it produces is destroyed and is never handed out.

**Threads.** One daemon thread ticks maintenance for every pool that has a scheduled task. Each
run executes on its own virtual thread, so pools never block each other, a slow validation
never delays a refill, and a pool you forget to close will not keep the JVM alive. The
scheduler shuts down after its last pool closes. Maintenance virtual threads use the context
class loader from the thread that created their pool.

**Identity.** Objects are tracked by reference. The pool never calls `equals` or `hashCode` on
them and never writes anything into them, so any class can be pooled without changes.

**Time.** Idle, borrow and abandon timing use the monotonic clock. A wall-clock adjustment
cannot make an object look stale or abandoned.

## Configuration reference

| Builder method | Default | Meaning |
|---|---|---|
| `poolName(String)` | random `SOP-xxxxxxxx` | Name used in logs and thread names |
| `maxPoolSize(int)` | 8 | Hard capacity |
| `minPoolSize(int)` | 0 | Idle objects to keep after each maintenance run |
| `waitingForObjectTimeout(Duration)` | 10 s | Max wait in `borrowObject()`; negative waits forever, zero fails fast |
| `fairness(boolean)` | false | Serve waiting borrowers in arrival order |
| `lifo(boolean)` | true | Reuse the most recently returned object first |
| `testOnCreate(boolean)` | false | Validate new objects before first use |
| `testOnBorrow(boolean)` | false | Run `isObjectValidForBorrow` on every borrow |
| `testOnReturn(boolean)` | false | Run `isObjectValid` on every return |
| `testWhileIdle(boolean)` | true | Validate idle objects during eviction runs |
| `evictionPolicy(EvictionPolicy)` | `RANDOM` | Which idle objects an eviction run validates first; `NONE` disables the scheduled run |
| `durationBetweenEvictionsRuns(Duration)` | 5 min | Maintenance interval |
| `objEvictionTimeout(Duration)` | 5 min | Idle time after which an object above `minPoolSize` is evicted |
| `numValidationsPerEvictionRun(Integer)` | `maxPoolSize` | Idle objects validated per scheduled run |
| `maxRetries(int)` | 0 | Extra creation attempts per borrow when `createObject` throws |
| `retryCreationDelay(Duration)` | 0 | Pause before each retry, capped at the remaining wait |
| `abandonedTimeout(Duration)` | off | Destroy objects borrowed longer than this |
| `durationBetweenAbandonCheckRuns(Duration)` | 60 s | How often abandon detection runs |

`config.toBuilder()` gives you a builder preloaded with every setting, handy for deriving one
pool's config from another.

`prepare()` creates up to `minPoolSize` idle objects. If creation fails, it rethrows that first
failure unchanged and leaves objects created before it in the pool.

## Factory lifecycle

| Method | Required | Called |
|---|---|---|
| `createObject()` | yes | When a borrower needs an object and capacity is free, or by `prepare` / `addObjects` / maintenance |
| `destroyObject(T)` | yes | Exactly once per object, on eviction, invalidation, abandonment or close |
| `activateObject(T)` | no | Before an idle object is handed to a borrower, and before idle validation |
| `passivateObject(T)` | no | After return, before the object goes idle, and after idle validation |
| `isObjectValidForBorrow(T)` | no | On borrow when `testOnBorrow` is on; keep it cheap |
| `isObjectValid(T)` | no | On create, return and idle tests, per the `testOn*` flags |
| `shouldEvict(T, PooledObject<T>)` | no | For each idle object an eviction run examines; return true to evict it |

An exception from `createObject` reaches the borrower unchanged once retries are exhausted.
Exceptions from the other methods mark the object invalid; it is destroyed and the borrower
moves on. If a factory method throws an `Error`, the pool still cleans up its own state before
rethrowing anything fatal, so a `NoClassDefFoundError` cannot leak a capacity slot.

A factory method that can block, for example a network ping, should carry its own timeout.
The pool does not interrupt factory calls.

## Observability

`pool.stats()` returns a `PoolStats` record with the total, idle, borrowed and waiting counts
plus lifetime created, destroyed and borrow counters. `waits`, `totalWait` and `maxWait` cover
borrow calls that had to park: how many there were (counted when the call finishes, whether it
got an object or not), their total parking time and the longest one. A borrow that never parks
leaves them unchanged. Each count is also available on its own (`idleObjectCount()`,
`borrowedObjectsCount()`, `waitingCount()` and so on). The pool logs at `INFO` when it is
created or closed, at `WARN` when something it did not expect happens, and at `DEBUG` for every
object it destroys and why. It also warns once when a pool with scheduled maintenance is
garbage collected without `close()`, because its idle objects were never destroyed.

## Exceptions

All pool exceptions are unchecked and extend `PoolException`.

| Exception | When |
|---|---|
| `PoolTimeoutException` | No object became available within the wait |
| `PoolInterruptedException` | A borrow was interrupted; the interrupt flag is restored |
| `PoolClosedException` | Borrow on a closed pool, or the pool closed while you were waiting |
| `PoolObjectException` | Returning null, or an object that was never borrowed from this pool |
| `PoolObjectValidationException` | A newly created object failed `testOnCreate` validation |

Factory exceptions from `createObject` are not wrapped. You get exactly what the factory threw.

## Upgrading from 2.x

3.0.0 is a rewrite of the pool core and the contract changed in a few places. The full list
is in [CHANGELOG.md](CHANGELOG.md). The short version:

- Pooled classes no longer implement `PoolObject`. Delete `getEntityId` and `setEntityId`.
- `PooledObjectFactory` only requires `createObject` and `destroyObject`.
- Abandon detection is off unless you set `abandonedTimeout`.
- The constructor no longer prefills. Call `prepare()` if you relied on that.
- `maxRetries` defaults to 0, so a creation failure surfaces after one attempt.
- Config time getters return `Duration`.
- `close()` no longer destroys borrowed objects. Use `forceClose()` for the old behaviour.
- Returning an object that is not borrowed throws instead of logging.

## Building

```bash
./mvnw verify            # unit tests
./mvnw verify -Pperf     # also runs the performance test
```

You need JDK 21 or newer. The Maven Wrapper downloads the pinned Maven version, so no local
Maven install is needed. The library itself depends only on SLF4J.

## Contributing

Follow the [contributing guide](https://github.com/BonfireTrunk/.github/blob/prime/CONTRIBUTING.md).
What is specific to this repository:

- **Build.** Needs Java 21 or newer. The Maven Wrapper downloads the pinned Maven version, so no
  local Maven install is needed:
  ```bash
  ./mvnw verify
  ```
- **Tests.** Add a test for any change in behaviour. For concurrency fixes, a test that fails
  without the fix is worth more than the fix itself.
- **Formatting** follows the `eclipse-formatter.xml` in the repository root.
- **Version.** Leave the version in `pom.xml` alone, with one exception: if it is a release
  version (no `-SNAPSHOT` suffix), yours is the first change since that release, so bump it to
  the next snapshot. For a bug fix, `3.0.0` becomes `3.0.1-SNAPSHOT`; for a new feature,
  `3.0.0` becomes `3.1.0-SNAPSHOT`.

## License

Copyright 2024-2026 Bonfire.

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for the full text.
