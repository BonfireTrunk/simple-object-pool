package today.bonfire.oss.sop.exceptions;

public class PoolObjectValidationException extends PoolException {
  public PoolObjectValidationException() {
  }

  public PoolObjectValidationException(String message) {
    super(message);
  }

  public PoolObjectValidationException(Throwable cause) {
    super(cause);
  }

  public PoolObjectValidationException(String message, Throwable cause) {
    super(message, cause);
  }
}
