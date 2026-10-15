package ch.tecost.profiler;

final class Log {
    static volatile boolean debug;

    static void info(String msg) {
        System.err.println("[entity-profiler] " + msg);
    }

    static void warn(String msg) {
        System.err.println("[entity-profiler] WARN " + msg);
    }

    static void debug(String msg) {
        if (debug)
            System.err.println("[entity-profiler] DEBUG " + msg);
    }
}
