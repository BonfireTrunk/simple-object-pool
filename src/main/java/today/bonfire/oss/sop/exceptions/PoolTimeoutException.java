package today.bonfire.oss.sop.exceptions;

public class PoolTimeoutException extends PoolException {
  public PoolTimeoutException() {
  }

  public PoolTimeoutException(String message) {
    super(message);
  }

  public PoolTimeoutException(Throwable cause) {
    super(cause);
  }

  public PoolTimeoutException(String message, Throwable cause) {
    super(message, cause);
  }
}
