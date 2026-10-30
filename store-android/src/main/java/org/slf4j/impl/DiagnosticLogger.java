package org.slf4j.impl;

import java.util.regex.Pattern;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

/** Captures Bt internals while keeping endpoint addresses out of user-shared debug dumps. */
final class DiagnosticLogger extends MarkerIgnoringBase {
    private static final long serialVersionUID = 1L;
    private static final Pattern IPV4 = Pattern.compile("(?<![0-9])(?:[0-9]{1,3}\\.){3}[0-9]{1,3}(?![0-9])");
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-f])(?:[0-9a-f]{0,4}:){2,7}[0-9a-f]{0,4}(?![0-9a-f])");
    private final boolean networkLogger;
    private final boolean cryptoLogger;
    private final boolean metadataLogger;

    DiagnosticLogger(String name) {
        this.name = name;
        this.networkLogger = name.startsWith("bt.net.") || name.startsWith("bt.torrent.messaging.");
        this.metadataLogger = name.startsWith("bt.tracker.") || name.startsWith("bt.peer.") ||
                name.startsWith("bt.magnet.") || name.contains("Metadata") || name.contains("metadata");
        this.cryptoLogger = name.equals("bt.net.crypto.MSEHandshakeProcessor");
    }

    private void write(String level, String pattern, Object... arguments) {
        FormattingTuple tuple = MessageFormatter.arrayFormat(pattern, arguments);
        write(level, tuple.getMessage(), tuple.getThrowable());
    }

    private void write(String level, String message, Throwable error) {
        if (message == null) message = "";
        String safe = IPV4.matcher(message).replaceAll("<peer-ip>");
        safe = IPV6.matcher(safe).replaceAll("<peer-ipv6>");
        if (error != null) {
            // The outer exception hid bind failures and tracker response causes.
            // Keep bounded, sanitized cause stacks instead of dropping nested errors.
            StringBuilder details = new StringBuilder();
            java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
            Throwable cause = error;
            for (int depth = 0; cause != null && depth < 8 && seen.add(cause); depth++, cause = cause.getCause()) {
                details.append(depth == 0 ? " error=" : "\nCaused by: ")
                        .append(cause.getClass().getName()).append(": ").append(cause.getMessage());
                StackTraceElement[] stack = cause.getStackTrace();
                for (int index = 0; index < Math.min(stack.length, 12); index++) {
                    details.append("\n    at ").append(stack[index]);
                }
            }
            String errorText = IPV4.matcher(details.toString()).replaceAll("<peer-ip>");
            safe += IPV6.matcher(errorText).replaceAll("<peer-ipv6>");
        }
        com.infinifolder.storage.DiagnosticLog.INSTANCE.record(
                "bt.slf4j." + level.toLowerCase() + "." + name,
                safe,
                null);
    }

    @Override public boolean isTraceEnabled() { return cryptoLogger || name.equals("bt.net.PeerConnectionFactory"); }
    @Override public void trace(String msg) { if (isTraceEnabled()) write("TRACE", msg); }
    @Override public void trace(String format, Object arg) { if (isTraceEnabled()) write("TRACE", format, arg); }
    @Override public void trace(String format, Object arg1, Object arg2) { if (isTraceEnabled()) write("TRACE", format, arg1, arg2); }
    @Override public void trace(String format, Object... arguments) { if (isTraceEnabled()) write("TRACE", format, arguments); }
    @Override public void trace(String msg, Throwable t) { if (isTraceEnabled()) write("TRACE", msg, t); }

    @Override public boolean isDebugEnabled() { return networkLogger || metadataLogger; }
    @Override public void debug(String msg) { if (isDebugEnabled()) write("DEBUG", msg); }
    @Override public void debug(String format, Object arg) { if (isDebugEnabled()) write("DEBUG", format, arg); }
    @Override public void debug(String format, Object arg1, Object arg2) { if (isDebugEnabled()) write("DEBUG", format, arg1, arg2); }
    @Override public void debug(String format, Object... arguments) { if (isDebugEnabled()) write("DEBUG", format, arguments); }
    @Override public void debug(String msg, Throwable t) { if (isDebugEnabled()) write("DEBUG", msg, t); }

    @Override public boolean isInfoEnabled() { return networkLogger || metadataLogger; }
    @Override public void info(String msg) { if (isInfoEnabled()) write("INFO", msg); }
    @Override public void info(String format, Object arg) { if (isInfoEnabled()) write("INFO", format, arg); }
    @Override public void info(String format, Object arg1, Object arg2) { if (isInfoEnabled()) write("INFO", format, arg1, arg2); }
    @Override public void info(String format, Object... arguments) { if (isInfoEnabled()) write("INFO", format, arguments); }
    @Override public void info(String msg, Throwable t) { if (isInfoEnabled()) write("INFO", msg, t); }

    @Override public boolean isWarnEnabled() { return true; }
    @Override public void warn(String msg) { write("WARN", msg); }
    @Override public void warn(String format, Object arg) { write("WARN", format, arg); }
    @Override public void warn(String format, Object arg1, Object arg2) { write("WARN", format, arg1, arg2); }
    @Override public void warn(String format, Object... arguments) { write("WARN", format, arguments); }
    @Override public void warn(String msg, Throwable t) { write("WARN", msg, t); }

    @Override public boolean isErrorEnabled() { return true; }
    @Override public void error(String msg) { write("ERROR", msg); }
    @Override public void error(String format, Object arg) { write("ERROR", format, arg); }
    @Override public void error(String format, Object arg1, Object arg2) { write("ERROR", format, arg1, arg2); }
    @Override public void error(String format, Object... arguments) { write("ERROR", format, arguments); }
    @Override public void error(String msg, Throwable t) { write("ERROR", msg, t); }
}
