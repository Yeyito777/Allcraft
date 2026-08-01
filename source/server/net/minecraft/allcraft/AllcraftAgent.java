package net.minecraft.allcraft;

import java.lang.instrument.Instrumentation;

/** JVM entry point used by Allcraft's runtime patch engine. */
public final class AllcraftAgent {
    private static volatile Instrumentation instrumentation;

    private AllcraftAgent() {
    }

    public static void premain(String arguments, Instrumentation value) {
        initialize(value);
    }

    public static void agentmain(String arguments, Instrumentation value) {
        initialize(value);
    }

    private static void initialize(Instrumentation value) {
        instrumentation = value;
        System.setProperty("allcraft.agent.loaded", "true");
        System.out.println("[Allcraft] Runtime instrumentation agent loaded");
    }

    public static Instrumentation instrumentation() {
        Instrumentation value = instrumentation;
        if (value == null) {
            throw new IllegalStateException("Allcraft runtime agent is not loaded; launch with -javaagent:build/allcraft-agent.jar");
        }

        return value;
    }

    public static boolean isLoaded() {
        return instrumentation != null;
    }
}
