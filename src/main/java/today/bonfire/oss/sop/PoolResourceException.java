package today.bonfire.oss.sop;

/**
 * Exception thrown when operations on pooled resources fail.
 * This includes scenarios such as:
 * - Failed to borrow an object from the pool
 * - Resource validation failures
 * - Timeout while waiting for available resources
 */
public class PoolResourceException extends RuntimeException {
  public PoolResourceException() {this(null, null);}

  public PoolResourceException(final String message) {this(message, null);}

  public PoolResourceException(final Throwable cause) {this(cause != null ? cause.getMessage() : null, cause);}

  public PoolResourceException(final String message, final Throwable cause) {
    super(message);
    if (cause != null) super.initCause(cause);
  }
}
