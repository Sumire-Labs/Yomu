package com.sumirelabs.yomu.benchmark;

import java.lang.instrument.Instrumentation;

/** Benchmark-only sizing agent. It does not transform or retransform classes. */
public final class HeapAgent {
    private static Instrumentation instrumentation;
    private HeapAgent() { }
    public static void premain(String arguments, Instrumentation value) { instrumentation = value; }
    static long sizeOf(Object value) {
        if (instrumentation == null) throw new IllegalStateException("Start with the lifecycle sizing javaagent");
        return instrumentation.getObjectSize(value);
    }
}
