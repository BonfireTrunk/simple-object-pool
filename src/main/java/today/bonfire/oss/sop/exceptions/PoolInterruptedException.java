package today.bonfire.oss.sop.exceptions;

/**
 * Thrown when borrowing is interrupted. The thread's interrupt flag is restored.
 */
public class PoolInterruptedException extends PoolException {
  public PoolInterruptedException() {
  }

  public PoolInterruptedException(String message) {
    super(message);
  }

  public PoolInterruptedException(Throwable cause) {
    super(cause);
  }

  public PoolInterruptedException(String message, Throwable cause) {
    super(message, cause);
  }
}
