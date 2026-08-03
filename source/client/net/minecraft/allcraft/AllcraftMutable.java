package net.minecraft.allcraft;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** Small migration utility for changing private game state without adding subsystem interfaces. */
public final class AllcraftMutable {
    private AllcraftMutable() {
    }

    public static void set(Object target, String fieldName, Object value) {
        if (target == null) {
            throw new IllegalArgumentException("Target cannot be null; use setStatic for static fields");
        }
        Field field = find(target.getClass(), fieldName);
        setField(field, target, value);
    }

    public static void setStatic(Class<?> owner, String fieldName, Object value) {
        Field field = find(owner, fieldName);
        if (!Modifier.isStatic(field.getModifiers())) {
            throw new IllegalArgumentException(owner.getName() + "." + fieldName + " is not static");
        }
        setField(field, null, value);
    }

    public static Object get(Object target, String fieldName) {
        if (target == null) {
            throw new IllegalArgumentException("Target cannot be null");
        }
        return get(find(target.getClass(), fieldName), target);
    }

    private static void setField(Field field, Object target, Object value) {
        if (Modifier.isFinal(field.getModifiers())) {
            throw new IllegalArgumentException(
                "Field " + field.getDeclaringClass().getName() + "." + field.getName() + " is still final and must be explicitly unlocked"
            );
        }
        Object previous = get(field, target);
        write(field, target, value);
        AllcraftRegistries.recordUndo(
            "restore field " + field.getDeclaringClass().getName() + "." + field.getName(),
            () -> write(field, target, previous)
        );
    }

    private static Object get(Field field, Object target) {
        try {
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot read " + field, e);
        }
    }

    private static void write(Field field, Object target, Object value) {
        try {
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot write " + field, e);
        }
    }

    private static Field find(Class<?> owner, String name) {
        for (Class<?> type = owner; type != null; type = type.getSuperclass()) {
            try {
                return type.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new IllegalArgumentException("No field " + name + " exists on " + owner.getName());
    }
}
