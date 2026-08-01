package allcraft.jvmtest;

import java.util.concurrent.CountDownLatch;
import java.util.function.IntSupplier;

public class EvolutionTarget {
    private int state = 1;

    public EvolutionTarget() {
    }

    public int value() {
        return this.state;
    }

    public IntSupplier liveLambda() {
        return () -> this.state;
    }

    public int activeFrame(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return 1;
    }
}
