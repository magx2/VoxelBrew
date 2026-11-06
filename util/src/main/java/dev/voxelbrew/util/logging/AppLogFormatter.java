package dev.voxelbrew.util.logging;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;

final class AppLogFormatter extends Formatter {
    private final String identity;

    AppLogFormatter(String component) {
        identity = component + ":" + ProcessHandle.current().pid();
    }

    @Override
    public String format(LogRecord record) {
        String severity =
                record.getLevel().intValue() >= Level.SEVERE.intValue()
                        ? "ERROR"
                        : record.getLevel().intValue() >= Level.WARNING.intValue()
                                ? "WARN"
                                : record.getLevel().intValue() >= Level.INFO.intValue()
                                        ? "INFO"
                                        : record.getLevel().intValue() >= Level.FINE.intValue()
                                                ? "DEBUG"
                                                : "TRACE";
        var text =
                new StringBuilder()
                        .append(record.getInstant())
                        .append(' ')
                        .append(severity)
                        .append(" [")
                        .append(identity)
                        .append("/thread-")
                        .append(record.getLongThreadID())
                        .append("] ")
                        .append(record.getLoggerName())
                        .append(" - ")
                        .append(formatMessage(record))
                        .append('\n');
        if (record.getThrown() != null) {
            var stack = new StringWriter();
            record.getThrown().printStackTrace(new PrintWriter(stack));
            text.append(stack);
        }
        return text.toString();
    }
}
