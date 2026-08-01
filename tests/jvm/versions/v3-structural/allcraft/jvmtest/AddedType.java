package allcraft.jvmtest;

/** Tombstone definition used because loaded JVM classes cannot be unloaded individually. */
public class AddedType {
    public String value() {
        throw new NoClassDefFoundError("allcraft.jvmtest.AddedType was removed by this revision");
    }
}
