package allcraft.jvmtest;

import java.util.concurrent.CountDownLatch;

public class EvolutionTarget {
    private int state = 3;

    public EvolutionTarget() {
    }

    public int value() {
        return 3;
    }

    public int activeFrame(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return 3;
    }
}
