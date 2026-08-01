package allcraft.jvmtest;

import java.lang.instrument.Instrumentation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;

public final class Agent {
    private static volatile Instrumentation instrumentation;
    private static final List<JarFile> appended = new ArrayList<>();

    private Agent() {
    }

    public static void premain(String arguments, Instrumentation value) {
        instrumentation = value;
    }

    public static Instrumentation instrumentation() {
        if (instrumentation == null) {
            throw new IllegalStateException("Allcraft JVM test agent was not loaded");
        }
        return instrumentation;
    }

    public static synchronized void append(Path artifact) throws IOException {
        JarFile jar = new JarFile(artifact.toFile());
        instrumentation().appendToSystemClassLoaderSearch(jar);
        appended.add(jar);
    }
}
