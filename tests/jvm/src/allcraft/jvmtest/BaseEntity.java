package allcraft.jvmtest;

public class BaseEntity {
    public boolean isLocalPlayer() {
        return false;
    }

    public long move(long value) {
        long result = value;
        for (int index = 0; index < 16; index++) {
            result = (result * 31L + index) ^ (result >>> 7);
            if (this.isLocalPlayer()) {
                result += 17L + index;
            } else {
                result -= 11L - index;
            }
        }
        return result;
    }
}
