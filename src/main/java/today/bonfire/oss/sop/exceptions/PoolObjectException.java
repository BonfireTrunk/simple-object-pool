package today.bonfire.oss.sop.exceptions;

public class PoolObjectException extends PoolException {

  public PoolObjectException() {
    super();
  }

  public PoolObjectException(String message) {
    super(message);
  }

  public PoolObjectException(Throwable cause) {
    super(cause);
  }

  public PoolObjectException(String message, Throwable cause) {
    super(message, cause);
  }
}
