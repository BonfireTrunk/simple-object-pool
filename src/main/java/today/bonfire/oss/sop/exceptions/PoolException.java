package today.bonfire.oss.sop.exceptions;

/**
 * Exception thrown when operations on pooled resources fail.
 */
public class PoolException extends RuntimeException {

  public PoolException() {this(null, null);}

  public PoolException(final String message) {this(message, null);}

  public PoolException(final Throwable cause) {this(cause != null ? cause.getMessage() : null, cause);}

  public PoolException(final String message, final Throwable cause) {
    super(message);
    if (cause != null) super.initCause(cause);
  }
}
