package today.bonfire.oss.sop;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

public class TestLogger {
  private final ListAppender<ILoggingEvent> listAppender = new ListAppender<>();

  public void startCapturing() {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    listAppender.start();
    rootLogger.addAppender(listAppender);
  }

  public void stopCapturing() {
    Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    rootLogger.detachAppender(listAppender);
    listAppender.stop();
  }

  public String getLog() {
    StringBuilder logMessages = new StringBuilder();
    for (ILoggingEvent event : listAppender.list) {
      logMessages.append(event.getFormattedMessage()).append("\n");
    }
    return logMessages.toString();
  }
}
