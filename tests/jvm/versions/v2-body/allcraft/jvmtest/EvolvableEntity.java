package allcraft.jvmtest;

public class EvolvableEntity extends BaseEntity {
    @Override
    public boolean isLocalPlayer() {
        return true;
    }

    public int tickVersion() {
        return 2;
    }
}
