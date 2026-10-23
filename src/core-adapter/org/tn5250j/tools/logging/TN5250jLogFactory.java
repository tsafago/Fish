package org.tn5250j.tools.logging;

/** No external logging dependency and no terminal contents or credentials in logs. */
public final class TN5250jLogFactory {
    private TN5250jLogFactory() {}
    public static TN5250jLogger getLogger(Class<?> type) { return getLogger(type.getName()); }
    public static TN5250jLogger getLogger(String name) { return new StrictLogger(name); }

    private static final class StrictLogger implements TN5250jLogger {
        private final String name;
        StrictLogger(String name) { this.name = name; }
        @Override public void initialize(String ignored) {}
        @Override public void debug(Object message) {}
        @Override public void debug(Object message, Throwable t) {}
        @Override public void info(Object message) {
            String value = String.valueOf(message).toLowerCase(java.util.Locale.ROOT);
            if (value.contains("not supported") || value.startsWith("invalid"))
                throw new IllegalStateException("Emulator rejected unsupported behavior in " + name);
        }
        @Override public void info(Object message, Throwable t) { info(message); }
        private void stop(Object message) {
            // Core diagnostics may include screen contents; expose only the bounded reason.
            String reason = String.valueOf(message);
            if (reason.length() > 200) reason = reason.substring(0, 200);
            throw new IllegalStateException(name.substring(name.lastIndexOf('.') + 1) + ": " + reason);
        }
        @Override public void warn(Object message) { stop(message); }
        @Override public void warn(Object message, Throwable t) { stop(message); }
        @Override public void error(Object message) { stop(message); }
        @Override public void error(Object message, Throwable t) { stop(message); }
        @Override public void fatal(Object message) { stop(message); }
        @Override public void fatal(Object message, Throwable t) { stop(message); }
        @Override public boolean isDebugEnabled() { return false; }
        @Override public boolean isInfoEnabled() { return false; }
        @Override public boolean isWarnEnabled() { return true; }
        @Override public boolean isErrorEnabled() { return true; }
        @Override public boolean isFatalEnabled() { return true; }
        @Override public void setLevel(int level) {}
        @Override public int getLevel() { return WARN; }
    }
}
