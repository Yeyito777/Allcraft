package net.minecraft.allcraft;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.logging.LogUtils;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;

/** Transactional, world-scoped key mappings with stable identities and persistent user bindings. */
public final class AllcraftKeyMappings {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<String, KeyMapping> KNOWN = new HashMap<>();
    private static final Map<KeyMapping, Runnable> CALLBACKS = new IdentityHashMap<>();
    private static final Map<Identifier, KeyMapping.Category> CATEGORIES = new HashMap<>();
    private static final Map<KeyMapping.Category, Integer> CATEGORY_REFERENCES = new HashMap<>();

    private AllcraftKeyMappings() {
    }

    public static KeyMapping register(String name, int keysym, Runnable callback) {
        return register(name, InputConstants.Type.KEYSYM, keysym, KeyMapping.Category.GAMEPLAY, callback);
    }

    public static synchronized KeyMapping register(
        String name, InputConstants.Type type, int value, KeyMapping.Category category, Runnable callback
    ) {
        requireTransaction();
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(callback, "callback");
        KeyMapping mapping = KNOWN.get(name);
        if (mapping == null) {
            mapping = KeyMapping.allcraftCreate(name, type, value, category);
            KNOWN.put(name, mapping);
        } else if (!mapping.getDefaultKey().equals(type.getOrCreate(value)) || !mapping.getCategory().equals(category)) {
            throw new IllegalArgumentException("Key mapping '" + name + "' was previously declared with a different default or category");
        }

        Minecraft minecraft = Minecraft.getInstance();
        boolean wasRegistered = KeyMapping.allcraftIsRegistered(mapping);
        Runnable previousCallback = CALLBACKS.put(mapping, callback);
        if (!wasRegistered) {
            retainCategory(category);
            KeyMapping.allcraftRegister(mapping);
            minecraft.options.allcraftAddKeyMapping(mapping);
        }

        KeyMapping registered = mapping;
        AllcraftRegistries.recordUndo("restore key mapping " + name, () -> {
            synchronized (AllcraftKeyMappings.class) {
                if (previousCallback == null) {
                    CALLBACKS.remove(registered);
                } else {
                    CALLBACKS.put(registered, previousCallback);
                }
                if (!wasRegistered) {
                    minecraft.options.allcraftRemoveKeyMapping(registered);
                    KeyMapping.allcraftUnregister(registered);
                    releaseCategory(category);
                }
            }
        });
        return mapping;
    }

    /** Returns a stable custom category that is published while at least one active key uses it. */
    public static synchronized KeyMapping.Category category(Identifier id) {
        return CATEGORIES.computeIfAbsent(Objects.requireNonNull(id, "id"), KeyMapping.Category::new);
    }

    public static void tick() {
        KeyMapping[] mappings;
        Runnable[] callbacks;
        synchronized (AllcraftKeyMappings.class) {
            mappings = CALLBACKS.keySet().toArray(KeyMapping[]::new);
            callbacks = new Runnable[mappings.length];
            for (int i = 0; i < mappings.length; i++) {
                callbacks[i] = CALLBACKS.get(mappings[i]);
            }
        }
        for (int i = 0; i < mappings.length; i++) {
            while (mappings[i].consumeClick()) {
                try {
                    callbacks[i].run();
                } catch (Throwable failure) {
                    LOGGER.error("Dynamic key mapping {} failed", mappings[i].getName(), failure);
                }
            }
        }
    }

    private static void requireTransaction() {
        if (!AllcraftRegistries.mutationAllowed()) {
            throw new IllegalStateException("Dynamic key mappings require an active Allcraft revision transaction");
        }
    }

    private static void retainCategory(KeyMapping.Category category) {
        int references = CATEGORY_REFERENCES.getOrDefault(category, 0);
        if (references == 0 && CATEGORIES.get(category.id()) == category) {
            KeyMapping.Category.allcraftRegister(category);
        }
        CATEGORY_REFERENCES.put(category, references + 1);
    }

    private static void releaseCategory(KeyMapping.Category category) {
        int references = CATEGORY_REFERENCES.getOrDefault(category, 0);
        if (references <= 1) {
            CATEGORY_REFERENCES.remove(category);
            if (CATEGORIES.get(category.id()) == category) {
                KeyMapping.Category.allcraftUnregister(category);
            }
        } else {
            CATEGORY_REFERENCES.put(category, references - 1);
        }
    }
}
