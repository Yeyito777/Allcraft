package net.minecraft.allcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.world.item.crafting.Recipe;
import org.slf4j.Logger;

/** Applies cumulative server-data overlays through Minecraft's normal asynchronous reload pipeline. */
public final class AllcraftServerResources {
    private static final Logger LOGGER = LogUtils.getLogger();

    private AllcraftServerResources() {
    }

    public static CompletableFuture<ApplyResult> apply(
        MinecraftServer server, Path patchesRoot, Path artifact, String testName
    ) throws IOException {
        Map<String, byte[]> expectedResources = readResources(artifact, "data/");
        List<String> deletions = deletedResources(artifact, "data/");
        if (expectedResources.isEmpty() && deletions.isEmpty()) {
            return CompletableFuture.completedFuture(ApplyResult.empty());
        }

        List<Path> overlays = previousOverlays(patchesRoot);
        if (!overlays.contains(artifact)) {
            overlays.add(artifact);
        }
        return reload(server, overlays, expectedResources, deletions, testName);
    }

    public static CompletableFuture<ApplyResult> restore(MinecraftServer server, List<Path> artifacts) {
        List<Path> overlays = artifacts.stream().filter(Files::isRegularFile).filter(AllcraftServerResources::hasServerContentUnchecked).toList();
        if (overlays.isEmpty()) {
            return CompletableFuture.completedFuture(ApplyResult.empty());
        }
        return reload(server, overlays, Map.of(), List.of(), "restore");
    }

    public static boolean hasServerContent(Path artifact) throws IOException {
        return !readResources(artifact, "data/").isEmpty() || !deletedResources(artifact, "data/").isEmpty();
    }

    private static CompletableFuture<ApplyResult> reload(
        MinecraftServer server,
        List<Path> overlays,
        Map<String, byte[]> expectedResources,
        List<String> deletions,
        String testName
    ) {
        long startedAt = System.nanoTime();
        return server.allcraftReloadResources(overlays).thenApply(unused -> {
            try {
                verifyResources(server, expectedResources);
                verifyDeletedResources(server, deletions);
                if (testName.equals("live-recipe")) {
                    ResourceKey<Recipe<?>> recipe = ResourceKey.create(
                        Registries.RECIPE, Identifier.fromNamespaceAndPath("allcraft", "dirt_to_diamond")
                    );
                    if (server.getRecipeManager().byKey(recipe).isEmpty()) {
                        throw new IOException("Live recipe was not installed in RecipeManager");
                    }
                }
                ApplyResult result = new ApplyResult(true, expectedResources.size(), deletions.size(), elapsedMillis(startedAt));
                LOGGER.info(
                    "Applied {} Allcraft server resource(s) and {} deletion(s) in {} ms",
                    result.changedResources(),
                    result.deletedResources(),
                    result.reloadMillis()
                );
                return result;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static List<Path> previousOverlays(Path patchesRoot) throws IOException {
        Path manifestPath = patchesRoot.resolve("manifest.json");
        JsonObject manifest = JsonParser.parseString(Files.readString(manifestPath, StandardCharsets.UTF_8)).getAsJsonObject();
        JsonArray patches = manifest.has("patches") ? manifest.getAsJsonArray("patches") : new JsonArray();
        List<Path> overlays = new ArrayList<>();
        for (var value : patches) {
            JsonObject patch = value.getAsJsonObject();
            String stem = String.format("%08d-%s.jar", patch.get("revision").getAsLong(), patch.get("patchId").getAsString());
            Path artifact = patchesRoot.resolve("artifacts/server").resolve(stem);
            if (Files.isRegularFile(artifact) && hasServerContent(artifact)) {
                overlays.add(artifact);
            }
        }
        return overlays;
    }

    private static void verifyResources(MinecraftServer server, Map<String, byte[]> expected) throws IOException {
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            Identifier id = identifierFromEntry(entry.getKey(), "data/");
            Resource resource = server.getResourceManager()
                .getResource(id)
                .orElseThrow(() -> new IOException("Activated server resource is missing: " + id));
            try (InputStream input = resource.open()) {
                if (!Arrays.equals(entry.getValue(), input.readAllBytes())) {
                    throw new IOException("Activated server resource has wrong bytes: " + id);
                }
            }
        }
    }

    private static void verifyDeletedResources(MinecraftServer server, List<String> deleted) throws IOException {
        for (String entry : deleted) {
            Identifier id = identifierFromEntry(entry, "data/");
            if (server.getResourceManager().getResource(id).isPresent()) {
                throw new IOException("Deleted server resource is still visible: " + id);
            }
        }
    }

    private static Identifier identifierFromEntry(String entry, String prefix) throws IOException {
        String relative = entry.substring(prefix.length());
        int separator = relative.indexOf('/');
        if (separator <= 0 || separator + 1 >= relative.length()) {
            throw new IOException("Invalid Allcraft resource entry " + entry);
        }
        Identifier id = Identifier.tryBuild(relative.substring(0, separator), relative.substring(separator + 1));
        if (id == null) {
            throw new IOException("Invalid Allcraft resource identifier " + entry);
        }
        return id;
    }

    private static Map<String, byte[]> readResources(Path artifact, String prefix) throws IOException {
        Map<String, byte[]> resources = new LinkedHashMap<>();
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            for (JarEntry entry : jar.stream().filter(value -> !value.isDirectory() && value.getName().startsWith(prefix)).sorted(java.util.Comparator.comparing(JarEntry::getName)).toList()) {
                try (InputStream input = jar.getInputStream(entry)) {
                    resources.put(entry.getName(), input.readAllBytes());
                }
            }
        }
        return resources;
    }

    private static List<String> deletedResources(Path artifact, String prefix) throws IOException {
        try (JarFile jar = new JarFile(artifact.toFile(), false)) {
            JarEntry descriptor = jar.getJarEntry("META-INF/allcraft-patch.json");
            if (descriptor == null) {
                return List.of();
            }
            try (InputStream input = jar.getInputStream(descriptor)) {
                JsonObject json = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                if (!json.has("deletedResources")) {
                    return List.of();
                }
                List<String> deleted = new ArrayList<>();
                for (var value : json.getAsJsonArray("deletedResources")) {
                    if (value.getAsString().startsWith(prefix)) {
                        deleted.add(value.getAsString());
                    }
                }
                return List.copyOf(deleted);
            }
        }
    }

    private static boolean hasServerContentUnchecked(Path artifact) {
        try {
            return hasServerContent(artifact);
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public record ApplyResult(boolean reloaded, int changedResources, int deletedResources, long reloadMillis) {
        private static ApplyResult empty() {
            return new ApplyResult(false, 0, 0, 0L);
        }

        public String summary() {
            return this.reloaded
                ? this.changedResources + " resources and " + this.deletedResources + " deletions reloaded in " + this.reloadMillis + " ms"
                : "no server resources";
        }
    }
}
