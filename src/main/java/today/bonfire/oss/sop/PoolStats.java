package today.bonfire.oss.sop;

import java.time.Duration;

/**
 * A consistent-enough snapshot of a pool's counters, taken without locking.
 *
 * @param total          objects currently alive (idle plus borrowed plus being created)
 * @param idle           objects waiting in the idle queue
 * @param borrowed       objects currently handed out
 * @param waiting        threads blocked in {@code borrowObject()} waiting for capacity
 * @param created        objects created since the pool was constructed
 * @param destroyed      objects destroyed since the pool was constructed
 * @param borrows        successful borrows since the pool was constructed
 * @param maxPoolSize    the configured capacity
 * @param waits          completed borrow calls that parked at least once, whether successful or not
 * @param totalWait      total time those borrow calls spent parked
 * @param maxWait        longest accumulated parking time of one borrow call
 */
public record PoolStats(int total, int idle, int borrowed, int waiting, long created, long destroyed, long borrows, int maxPoolSize,
                        long waits, Duration totalWait, Duration maxWait) {
}
