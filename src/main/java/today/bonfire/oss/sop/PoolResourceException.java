package today.bonfire.oss.sop;

import lombok.experimental.StandardException;

/**
 * Exception thrown when operations on pooled resources fail.
 * This includes scenarios such as:
 * - Failed to borrow an object from the pool
 * - Resource validation failures
 * - Timeout while waiting for available resources
 */
@StandardException
public class PoolResourceException extends RuntimeException {
}
