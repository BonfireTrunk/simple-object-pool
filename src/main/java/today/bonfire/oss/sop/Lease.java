package today.bonfire.oss.sop;

/**
 * A borrowed object that returns itself to the pool when closed. Obtain one with
 * {@link SimpleObjectPool#lease()} and use it in a try-with-resources block:
 * <pre>{@code
 * try (var lease = pool.lease()) {
 *   lease.get().doWork();
 * }
 * }</pre>
 * If the object turns out to be unusable, call {@link #markBroken()} before the block ends
 * and the pool destroys it instead of reusing it. Closing a lease twice is a no-op.
 *
 * @param <T> the type of the pooled object
 */
public final class Lease<T> implements AutoCloseable {

  private final SimpleObjectPool<T> pool;
  private final T                   object;
  private       boolean             broken;
  private       boolean             closed;

  Lease(SimpleObjectPool<T> pool, T object) {
    this.pool   = pool;
    this.object = object;
  }

  public T get() {
    return object;
  }

  /** Flags the object so that it is destroyed rather than returned to the idle queue. */
  public void markBroken() {
    broken = true;
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    pool.returnObject(object, broken);
  }
}
