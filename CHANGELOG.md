# Changelog

## [3.0.0] - 2026-10-29

This release rewrites the pool core. The 2.x design kept one lock across borrow, return and
eviction, and held it while calling your factory, so a single slow connection could stall
every other thread in the pool. 3.0.0 replaces that with a hot path that takes no pool-wide
lock, a compare-and-set capacity counter and a small lock whose only job is parking exhausted
borrowers. The public API looks familiar, but the contract changed in enough places that this
is a major version. Read the breaking changes before upgrading; most users will only need to
delete `PoolObject` from their classes.

### Breaking changes

- **`PoolObject` is gone.** `SimpleObjectPool<T>` now accepts any type. Objects are tracked by
  reference, the pool never calls `equals` or `hashCode` on them, and nothing is written into
  them. Remove `getEntityId` and `setEntityId` from your classes.
- **`numOfTimesBorrowed(Long id)` is now `numOfTimesBorrowed(T obj)`.**
- **Abandon detection is off by default.** Previously anything borrowed for more than 60 s was
  destroyed. Set `abandonedTimeout(Duration)` to turn it on. Zero or negative disables it.
- **The constructor no longer creates objects.** Call `prepare()` to fill to `minPoolSize`, or
  `addObject()` / `addObjects(int)`. The first scheduled maintenance run also fills it.
- **`maxRetries` defaults to 0.** A failing `createObject` reaches the borrower after one
  attempt, with the factory's exception unchanged. The old default allowed three attempts
  back to back.
- **Every borrow activates, then tests.** An object created for a borrower now goes through
  `activateObject` before the borrower gets it, and `activateObject` runs before
  `isObjectValidForBorrow` instead of after. A new object that fails either step counts as a
  failed creation and uses up a retry: an activation exception reaches the borrower unchanged,
  a failed borrow test surfaces as `PoolObjectValidationException`.
- **`close()` is graceful.** Idle objects are destroyed, threads waiting in `borrowObject()`
  wake up and throw `PoolClosedException`, and borrowed objects are destroyed when they come
  back. The old behaviour of destroying borrowed objects on the spot is now `forceClose()`.
- **Borrowing from a closed pool throws `PoolClosedException`.** It used to create objects
  inside a closed pool and leak them.
- **Returning an object that was never borrowed from the pool throws `PoolObjectException`.**
  It used to log a warning and carry on. Returning an object the pool itself already reclaimed
  (through abandon detection or `forceClose`) is silently ignored.
- **Idle order is last in, first out.** Set `lifo(false)` for the old round-robin behaviour.
  With FIFO under steady light load every idle object stayed warm and the pool never shrank.
- **Config time getters return `Duration`** instead of `long` millis or nanos.
- **`evict()` no longer declares `throws Exception`.** It validates every idle object whatever
  the `evictionPolicy`, and is safe to call from any thread while borrows and returns are in
  flight. `EvictionPolicy.NONE` now only disables the scheduled run.
- **`waitingForObjectTimeout` semantics:** negative waits forever, zero fails immediately,
  positive is a bounded wait. Negative used to behave like zero.
- **Validation warnings removed** for `durationBetweenEvictionsRuns` versus
  `waitingForObjectTimeout`. `abandonedTimeout` no longer rejects zero.
- **Lombok is no longer on the classpath** via the parent POM. Nothing in this library used it.

### Fixes

Lock scope and throughput:

- No factory method runs while a lock that borrowers or returners wait on is held. Eviction
  passes are serialised among themselves. One slow `createObject`, `activateObject`,
  `passivateObject` or validation no longer stalls every other borrower and returner.
- Creation runs in parallel up to `maxPoolSize`.
- An object the evictor is validating is removed from the idle queue first, so a borrower can
  never be handed an object another thread is testing.
- `waitingCount()` counts threads actually waiting for an object. It used to count threads
  waiting for an internal lock and missed parked waiters.
- A waiter that times out or is interrupted after being signalled passes the signal on, so a
  wake-up is never lost.
- Eviction-policy sorting snapshots borrow counts so concurrent borrows cannot break the sort.

Lifecycle and capacity:

- A failing `passivateObject` on return destroys the object and frees its slot immediately.
  The slot used to stay occupied until abandon detection ran.
- Idle-timeout eviction no longer drops the pool below `minPoolSize` only to recreate the same
  objects in the same run.
- `numValidationsPerEvictionRun` now limits only factory validation. Idle-timeout and
  `shouldEvict` checks always cover every idle object, so `0` no longer disables eviction.
- `destroyAllIdleObjects()` also removes an object the evictor is validating at that moment.
- Returning an object that the abandon check is reclaiming at that instant no longer throws.
- Min-idle fill signals waiting borrowers.
- An `Error` thrown by any factory method, for example `NoClassDefFoundError`, no longer leaks
  a capacity slot or an object. Cleanup always runs, then `VirtualMachineError`s are rethrown.

Timing and configuration:

- Idle timeout, abandon detection and eviction timing use the monotonic clock. A wall-clock
  step cannot make objects look abandoned or expired.
- Creation retries stop at a bounded borrow deadline and rethrow the last creation failure
  without another attempt. The retry delay is capped at the remaining wait and is no longer
  served while holding a capacity slot. A creation call already in progress can finish after
  the deadline.
- The retry counter was off by one and allowed one more creation attempt than configured.
- Durations too large for a `long` of nanoseconds are treated as unbounded, and
  sub-millisecond maintenance intervals are accepted.
- The interrupt flag is restored before `PoolInterruptedException` is thrown on interruption.
- `toBuilder()` now copies `poolName`.
- `build()` no longer writes resolved defaults back into the builder. An unset
  `numValidationsPerEvictionRun` stays unset, so reusing a builder or deriving a config with
  `toBuilder()` resolves it against the new `maxPoolSize` instead of keeping the old value or
  failing validation.
- `EvictionPolicy.RANDOM` now actually samples at random. It used to take the first N.
- `apply()` no longer masks the function's exception when the return also fails; the return
  failure is attached as suppressed.
- Pools dropped without `close()` are no longer pinned in memory by the maintenance scheduler.
  The scheduler logs one warning that their idle objects were never destroyed.

### New

- `borrowObject(Duration maxWait)`.
- `Lease<T>` with `pool.lease()` for try-with-resources borrowing, plus `pool.use(Consumer)`
  and `pool.apply(Function)`.
- `prepare()`, `addObject()`, `addObjects(int)`.
- `forceClose()`.
- `PoolClosedException`.
- `PoolInterruptedException`, thrown when a borrow is interrupted after restoring the interrupt
  flag.
- `SimpleObjectPool(String name, SimpleObjectPoolConfig config, PooledObjectFactory<T> factory)`
  for pools that share a config but need distinct names.
- `PoolStats.waits`, `PoolStats.totalWait` and `PoolStats.maxWait` for borrow calls that parked.
- `prepare()` rethrows its first creation failure unchanged while retaining objects it created
  before the failure.
- `PooledObjectFactory.shouldEvict(T, PooledObject<T>)`, a per-object eviction hook. Use it to
  retire connections on a server-initiated handoff, for example.
- `PooledObjectFactory.isBrokenBy(T, Throwable)` decides whether `use` and `apply` destroy the
  object after the action throws. The default destroys it.
- Default no-op implementations for `activateObject`, `passivateObject`,
  `isObjectValidForBorrow` and `isObjectValid`. A minimal factory is two methods.
- `PoolStats stats()` snapshot record.
- `PooledObject` exposes `state()`, `idleTime()`, `borrowedTime()`, `lastReturnedTime()`.
- `lifo(boolean)` and `abandonCheckEnabled()` on the config.
- A `module-info.java` (`bonfire.oss.sop`).

### Internals

- The library contains no `synchronized` block and no `Object.wait()`. All blocking goes
  through `java.util.concurrent` locks and conditions, so virtual threads never pin a carrier
  on JDK 21 to 23, and on later JDKs no borrower ever queues behind a monitor.
- One shared daemon thread ticks maintenance for every pool in the JVM. Each run executes on
  a virtual thread named after the pool. Pools no longer own a non-daemon thread each, and
  nothing is scheduled for features that are disabled.
- The shared scheduler shuts down when its last registered pool closes and is recreated when a
  later pool needs maintenance. Its worker uses the library class loader; maintenance virtual
  threads use the context class loader captured when their pool was created.
- Parent POM moved to `bonfire-oss-parent` 2.0.0 (JUnit 6, Mockito 5.24, Logback 1.6).
- The performance test runs only under `./mvnw verify -Pperf`.
