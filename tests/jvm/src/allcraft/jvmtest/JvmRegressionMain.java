package allcraft.jvmtest;

import com.sun.management.HotSpotDiagnosticMXBean;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import jdk.jfr.Recording;

public final class JvmRegressionMain {
    private static final Path VERSIONS = Path.of(System.getProperty("allcraft.jvmtest.versions")).toAbsolutePath().normalize();
    private static volatile long blackhole;

    private JvmRegressionMain() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Expected one scenario name");
        }
        switch (arguments[0]) {
            case "configuration" -> configuration();
            case "c2-old-method" -> c2OldMethod();
            case "structural" -> structural();
            case "multi-structural" -> multiStructural();
            case "repeat" -> repeat();
            case "jfr" -> jfr();
            case "jfr-structural" -> jfrStructural();
            case "jfr-wait" -> jfrWait();
            default -> throw new IllegalArgumentException("Unknown scenario " + arguments[0]);
        }
        System.out.println("PASS " + arguments[0] + " blackhole=" + blackhole);
    }

    private static void configuration() {
        HotSpotDiagnosticMXBean options = ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean.class);
        require(Boolean.parseBoolean(options.getVMOption("AllowEnhancedClassRedefinition").getValue()), "enhanced redefinition enabled");
        require(Boolean.parseBoolean(options.getVMOption("OptimizeCodeFlush").getValue()), "selective code flushing enabled by default");
        require(Boolean.parseBoolean(options.getVMOption("TieredCompilation").getValue()), "tiered compilation enabled");
        require(Integer.parseInt(options.getVMOption("TieredStopAtLevel").getValue()) == 4, "C2 globally enabled");
    }

    private static void c2OldMethod() throws Exception {
        EvolvableEntity entity = new EvolvableEntity();
        warmMove(entity, Duration.ofSeconds(3));
        redefine(EvolvableEntity.class, "v2-body");
        warmMove(entity, Duration.ofSeconds(20));
    }

    private static void structural() throws Exception {
        EvolutionTarget target = new EvolutionTarget();
        require(target.value() == 1, "version one behavior");
        IntSupplier oldLambda = target.liveLambda();
        require(oldLambda.getAsInt() == 1, "old-generation lambda executes before redefinition");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger activeResult = new AtomicInteger();
        Thread activeFrame = new Thread(() -> activeResult.set(target.activeFrame(entered, release)), "active old frame");
        activeFrame.start();
        require(entered.await(10L, TimeUnit.SECONDS), "old frame entered before redefinition");
        redefine(EvolutionTarget.class, "v2-structural");
        release.countDown();
        activeFrame.join(10_000L);
        require(!activeFrame.isAlive(), "old frame completed after redefinition");
        require(activeResult.get() == 1, "active frame retained old bytecodes");
        require(oldLambda.getAsInt() == 1, "live lambda retains its deleted old-generation target");
        require(target.value() == 2, "live instance uses version two behavior");
        Field field = EvolutionTarget.class.getDeclaredField("addedState");
        field.setAccessible(true);
        field.setLong(target, 41L);
        require((long)field.get(target) == 41L, "added field is writable on a live instance");
        Method method = EvolutionTarget.class.getDeclaredMethod("addedMethod");
        require(method.invoke(target).equals("added-41"), "added method executes");
        require(EvolutionTarget.class.getDeclaredConstructor(int.class).newInstance(9).value() == 2, "added constructor executes");
        require(EvolutionContract.class.isAssignableFrom(EvolutionTarget.class), "added interface is visible");
        Agent.append(VERSIONS.resolve("v2-structural.jar"));
        Class<?> addedType = Class.forName("allcraft.jvmtest.AddedType");
        Object added = addedType.getDeclaredConstructor().newInstance();
        require(addedType.getDeclaredMethod("value").invoke(added).equals("added"), "added class executes");
        redefine(EvolutionTarget.class, "v3-structural");
        redefine(addedType, "v3-structural");
        require(target.value() == 3, "live instance uses version three behavior");
        requireNoField("addedState");
        requireNoMethod("addedMethod");
        requireNoConstructor(int.class);
        require(!EvolutionContract.class.isAssignableFrom(EvolutionTarget.class), "removed interface is absent");
        try {
            addedType.getDeclaredMethod("value").invoke(added);
            throw new AssertionError("retired class should reject execution");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof NoClassDefFoundError, "retired class uses a tombstone definition");
        }
        redefine(EvolutionTarget.class, "v1");
        require(target.value() == 1, "reset behavior");
    }

    private static void multiStructural() throws Exception {
        EvolutionTarget first = new EvolutionTarget();
        SecondEvolutionTarget second = new SecondEvolutionTarget();
        ClassDefinition[] definitions = {
            definition(EvolutionTarget.class, "v2-structural"),
            definition(SecondEvolutionTarget.class, "v2-structural")
        };
        Agent.instrumentation().redefineClasses(definitions);
        require(first.value() == 2, "first class in a structural batch evolved");
        require(second.value() == 2, "second class in a structural batch evolved");
        require(SecondEvolutionTarget.class.getDeclaredField("addedState") != null, "batch added a field");
        require(SecondEvolutionTarget.class.getDeclaredMethod("addedMethod") != null, "batch added a method");
        try {
            SecondEvolutionTarget.class.getDeclaredMethod("removedMethod");
            throw new AssertionError("batch should remove a method");
        } catch (NoSuchMethodException expected) {
        }
    }

    private static void repeat() throws Exception {
        EvolutionTarget target = new EvolutionTarget();
        for (int iteration = 0; iteration < 100; iteration++) {
            redefine(EvolutionTarget.class, "v2-structural");
            blackhole += target.value();
            redefine(EvolutionTarget.class, "v3-structural");
            blackhole += target.value();
            redefine(EvolutionTarget.class, "v1");
            blackhole += target.value();
        }
        require(target.value() == 1, "repeat ended at base version");
    }

    private static void jfr() throws Exception {
        EvolvableEntity entity = new EvolvableEntity();
        warmMove(entity, Duration.ofSeconds(2));
        redefine(EvolvableEntity.class, "v2-body");
        Path output = Path.of(System.getProperty("allcraft.jvmtest.jfr", "allcraft-jvm-test.jfr")).toAbsolutePath();
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.start();
            warmMove(entity, Duration.ofSeconds(8));
            recording.stop();
            recording.dump(output);
        }
        require(Files.size(output) > 0L, "JFR recording is non-empty");
    }

    private static void jfrStructural() throws Exception {
        EvolutionTarget target = new EvolutionTarget();
        EvolvableEntity entity = new EvolvableEntity();
        Path output = Path.of(System.getProperty("allcraft.jvmtest.jfr", "allcraft-jvm-structural.jfr")).toAbsolutePath();
        try (Recording recording = new Recording()) {
            recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(10));
            recording.start();
            redefine(EvolutionTarget.class, "v2-structural");
            require(target.value() == 2, "structural evolution remains enabled while JFR is recording");
            configuration();
            warmMove(entity, Duration.ofSeconds(8));
            recording.stop();
            recording.dump(output);
        }
        require(Files.size(output) > 0L, "structural JFR recording is non-empty");
    }

    private static void jfrWait() throws Exception {
        EvolvableEntity entity = new EvolvableEntity();
        warmMove(entity, Duration.ofSeconds(2));
        redefine(EvolvableEntity.class, "v2-body");
        System.out.println("READY pid=" + ProcessHandle.current().pid());
        System.out.flush();
        warmMove(entity, Duration.ofSeconds(3));
        EvolutionTarget target = new EvolutionTarget();
        redefine(EvolutionTarget.class, "v2-structural");
        require(target.value() == 2, "dynamic JFR attach preserves structural evolution");
        configuration();
        warmMove(entity, Duration.ofSeconds(27));
    }

    private static void warmMove(BaseEntity entity, Duration duration) {
        long deadline = System.nanoTime() + duration.toNanos();
        long value = blackhole;
        while (System.nanoTime() < deadline) {
            for (int iteration = 0; iteration < 10_000; iteration++) {
                value += entity.move(value + iteration);
            }
        }
        blackhole = value;
    }

    private static void redefine(Class<?> type, String version) throws Exception {
        Agent.instrumentation().redefineClasses(definition(type, version));
    }

    private static ClassDefinition definition(Class<?> type, String version) throws IOException {
        Path classFile = VERSIONS.resolve(version).resolve(type.getName().replace('.', '/') + ".class");
        return new ClassDefinition(type, Files.readAllBytes(classFile));
    }

    private static void requireNoField(String name) throws Exception {
        try {
            EvolutionTarget.class.getDeclaredField(name);
            throw new AssertionError("Field should have been removed: " + name);
        } catch (NoSuchFieldException expected) {
        }
    }

    private static void requireNoMethod(String name) throws Exception {
        try {
            EvolutionTarget.class.getDeclaredMethod(name);
            throw new AssertionError("Method should have been removed: " + name);
        } catch (NoSuchMethodException expected) {
        }
    }

    private static void requireNoConstructor(Class<?>... parameters) throws Exception {
        try {
            EvolutionTarget.class.getDeclaredConstructor(parameters);
            throw new AssertionError("Constructor should have been removed");
        } catch (NoSuchMethodException expected) {
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
