package allcraft.jvmtest;

import java.util.concurrent.CountDownLatch;

public class EvolutionTarget implements EvolutionContract {
    private int state = 2;
    private long addedState;

    public EvolutionTarget() {
    }

    public EvolutionTarget(int state) {
        this.state = state;
    }

    public int value() {
        return 2;
    }

    public String addedMethod() {
        return "added-" + this.addedState;
    }

    @Override
    public String allcraftContract() {
        return "contract";
    }

    public int activeFrame(CountDownLatch entered, CountDownLatch release) {
        entered.countDown();
        try {
            release.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
        return 2;
    }
}
