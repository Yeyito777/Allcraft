package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

/** Applies cumulative client-resource overlays without displaying Minecraft's loading overlay. */
public final class AllcraftClientResources {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String INDEX_FILE = "active-resources.json";

    private AllcraftClientResources() {
    }

    /** Validates the complete client resource declaration before READY is reported. */
    public static PreflightResult preflight(Path artifact) throws IOException {
        Map<String, byte[]> resources = readResources(artifact, "assets/");
        List<String> deleted = deletedResources(artifact, "assets/");
        for (Map.Entry<String, byte[]> entry : resources.entrySet()) {
            String name = entry.getKey();
            byte[] bytes = entry.getValue();
            if (name.endsWith(".json") || name.endsWith(".mcmeta")) {
                try {
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    throw new IOException("Invalid staged JSON resource " + name, e);
                }
            } else if (name.endsWith(".png")) {
                try (java.io.ByteArrayInputStream input = new java.io.ByteArrayInputStream(bytes)) {
                    if (javax.imageio.ImageIO.read(input) == null) {
                        throw new IOException("Invalid staged PNG resource " + name);
                    }
                }
            }
        }
        return new PreflightResult(resources.size(), deleted.size());
    }

    public static CompletableFuture<ApplyResult> apply(
        Minecraft minecraft,
        Path artifact,
        String serverId,
        String worldId,
        long revision,
        String expectedSha256
    ) throws IOException {
        Map<String, byte[]> expectedResources = readResources(artifact, "assets/");
        List<String> deletedResources = deletedResources(artifact, "assets/");
        if (expectedResources.isEmpty() && deletedResources.isEmpty()) {
            return CompletableFuture.completedFuture(ApplyResult.empty());
        }

        List<Path> paths = new ArrayList<>(minecraft.allcraftCaptureResourceState().artifactOverlays());
        paths.removeIf(path -> !Files.isRegularFile(path));
        paths.removeIf(path -> path.getFileName().equals(artifact.getFileName()));
        paths.add(artifact);
        long startedAt = System.nanoTime();
        boolean startedWithLoadingOverlay = minecraft.gui.overlay() instanceof LoadingOverlay;

        Set<Identifier> changed = resourceIdentifiers(expectedResources.keySet(), "assets/");
        Set<Identifier> deleted = resourceIdentifiers(deletedResources, "assets/");
        return reloadOnGameThread(minecraft, paths, changed, deleted).thenApply(unused -> {
            try {
                if (startedWithLoadingOverlay || minecraft.gui.overlay() instanceof LoadingOverlay) {
                    throw new IOException("Allcraft resource activation displayed a loading overlay");
                }
                verifyResources(minecraft, expectedResources);
                verifyDeletedResources(minecraft, deletedResources);
                ApplyResult result = new ApplyResult(
                    true, expectedResources.size(), deletedResources.size(), elapsedMillis(startedAt), true
                );
                LOGGER.info(
                    "Applied {} Allcraft client resource(s) and {} deletion(s) from {} in {} ms without a loading screen",
                    result.changedResources(),
                    result.deletedResources(),
                    artifact.getFileName(),
                    result.reloadMillis()
                );
                return result;
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
    }

    /** Persists the overlay index only after the server has made the revision authoritative. */
    public static void commit(
        String serverId, String worldId, long revision, Path artifact, String sha256, ApplyResult result
    ) throws IOException {
        if (!result.reloaded()) {
            return;
        }
        Path root = cacheRoot(serverId, worldId);
        List<Overlay> overlays = readOverlays(root);
        overlays.removeIf(overlay -> !Files.isRegularFile(root.resolve("revisions").resolve(overlay.artifact())));
        overlays.removeIf(overlay -> overlay.revision() == revision);
        overlays.add(new Overlay(revision, artifact.getFileName().toString(), sha256));
        overlays.sort(Comparator.comparingLong(Overlay::revision));
        writeOverlays(root, serverId, worldId, overlays);
    }

    public static CompletableFuture<ApplyResult> restoreIntegrated(Minecraft minecraft, List<Path> artifacts) {
        List<Path> overlays = artifacts.stream().filter(Files::isRegularFile).filter(AllcraftClientResources::hasClientContentUnchecked).toList();
        if (overlays.isEmpty()) {
            return reloadOnGameThread(minecraft, List.of(), Set.of(), Set.of()).thenApply(unused -> ApplyResult.empty());
        }

        long startedAt = System.nanoTime();
        Set<Identifier> changed = new HashSet<>();
        Set<Identifier> deleted = new HashSet<>();
        try {
            for (Path artifact : overlays) {
                changed.addAll(resourceIdentifiers(readResources(artifact, "assets/").keySet(), "assets/"));
                deleted.addAll(resourceIdentifiers(deletedResources(artifact, "assets/"), "assets/"));
            }
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        return reloadOnGameThread(minecraft, overlays, changed, deleted)
            .thenApply(unused -> new ApplyResult(true, countResourcesUnchecked(overlays, "assets/"), 0, elapsedMillis(startedAt), true));
    }

    private static CompletableFuture<Void> reloadOnGameThread(
        Minecraft minecraft, List<Path> overlays, Set<Identifier> changed, Set<Identifier> deleted
    ) {
        if (minecraft.isSameThread()) {
            return minecraft.allcraftReloadResources(overlays, changed, deleted);
        }

        return minecraft.<CompletableFuture<Void>>submit(() -> minecraft.allcraftReloadResources(overlays, changed, deleted)).thenCompose(future -> future);
    }

    private static void verifyResources(Minecraft minecraft, Map<String, byte[]> expected) throws IOException {
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            Identifier id = identifierFromEntry(entry.getKey(), "assets/");
            Resource resource = minecraft.getResourceManager()
                .getResource(id)
                .orElseThrow(() -> new IOException("Activated client resource is missing: " + id));
            try (InputStream input = resource.open()) {
                byte[] actual = input.readAllBytes();
                if (!Arrays.equals(entry.getValue(), actual)) {
                    throw new IOException("Activated client resource has wrong bytes: " + id);
                }
            }
        }
    }

    private static void verifyDeletedResources(Minecraft minecraft, List<String> deleted) throws IOException {
        for (String entry : deleted) {
            Identifier id = identifierFromEntry(entry, "assets/");
            if (minecraft.getResourceManager().getResource(id).isPresent()) {
                throw new IOException("Deleted client resource is still visible: " + id);
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

    private static Set<Identifier> resourceIdentifiers(Iterable<String> entries, String prefix) throws IOException {
        Set<Identifier> result = new HashSet<>();
        for (String entry : entries) {
            result.add(identifierFromEntry(entry, prefix));
        }
        return Set.copyOf(result);
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

    private static boolean hasDeletedResources(Path artifact, String prefix) throws IOException {
        return !deletedResources(artifact, prefix).isEmpty();
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

    private static boolean hasClientContentUnchecked(Path artifact) {
        try {
            return !readResources(artifact, "assets/").isEmpty() || hasDeletedResources(artifact, "assets/");
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static int countResourcesUnchecked(List<Path> artifacts, String prefix) {
        try {
            int count = 0;
            for (Path artifact : artifacts) {
                count += readResources(artifact, prefix).size();
            }
            return count;
        } catch (IOException e) {
            throw new CompletionException(e);
        }
    }

    private static Path cacheRoot(String serverId, String worldId) throws IOException {
        String gameDir = System.getProperty("allcraft.gameDir");
        if (gameDir == null || gameDir.isBlank()) {
            throw new IOException("Missing JVM property allcraft.gameDir");
        }
        return Path.of(gameDir).toAbsolutePath().normalize().resolve("patches").resolve(serverId).resolve(worldId);
    }

    private static List<Overlay> readOverlays(Path root) throws IOException {
        Path index = root.resolve(INDEX_FILE);
        if (!Files.isRegularFile(index)) {
            return new ArrayList<>();
        }
        try {
            JsonObject json = JsonParser.parseString(Files.readString(index, StandardCharsets.UTF_8)).getAsJsonObject();
            JsonArray values = json.has("overlays") ? json.getAsJsonArray("overlays") : new JsonArray();
            List<Overlay> overlays = new ArrayList<>();
            for (var value : values) {
                JsonObject overlay = value.getAsJsonObject();
                String artifact = Path.of(overlay.get("artifact").getAsString()).getFileName().toString();
                overlays.add(new Overlay(overlay.get("revision").getAsLong(), artifact, overlay.get("sha256").getAsString()));
            }
            return overlays;
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft resource index " + index, e);
        }
    }

    private static void writeOverlays(Path root, String serverId, String worldId, List<Overlay> overlays) throws IOException {
        JsonObject json = new JsonObject();
        json.addProperty("format", 1);
        json.addProperty("serverId", serverId);
        json.addProperty("worldId", worldId);
        json.addProperty("updatedAt", Instant.now().toString());
        JsonArray values = new JsonArray();
        for (Overlay overlay : overlays) {
            JsonObject value = new JsonObject();
            value.addProperty("revision", overlay.revision());
            value.addProperty("artifact", overlay.artifact());
            value.addProperty("sha256", overlay.sha256());
            values.add(value);
        }
        json.add("overlays", values);
        writeAtomically(root.resolve(INDEX_FILE), (GSON.toJson(json) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8));
    }

    private static void writeAtomically(Path path, byte[] bytes) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temporary, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    public record ApplyResult(boolean reloaded, int changedResources, int deletedResources, long reloadMillis, boolean noLoadingScreen) {
        private static ApplyResult empty() {
            return new ApplyResult(false, 0, 0, 0L, true);
        }

        public String summary() {
            return this.reloaded
                ? this.changedResources + " resources and " + this.deletedResources + " deletions reloaded in " + this.reloadMillis + " ms"
                : "no client resources";
        }
    }

    private record Overlay(long revision, String artifact, String sha256) {
    }

    public record PreflightResult(int changedResources, int deletedResources) {
    }
}
