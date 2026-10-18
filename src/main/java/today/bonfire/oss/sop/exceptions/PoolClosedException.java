package today.bonfire.oss.sop.exceptions;

/**
 * Thrown when an operation is attempted on a pool that has been closed.
 */
public class PoolClosedException extends PoolException {
  public PoolClosedException() {
  }

  public PoolClosedException(String message) {
    super(message);
  }

  public PoolClosedException(Throwable cause) {
    super(cause);
  }

  public PoolClosedException(String message, Throwable cause) {
    super(message, cause);
  }
}
