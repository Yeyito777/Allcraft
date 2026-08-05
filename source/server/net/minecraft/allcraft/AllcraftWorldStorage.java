package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.UUID;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import org.slf4j.Logger;

public final class AllcraftWorldStorage {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int FORMAT_VERSION = 1;

    private AllcraftWorldStorage() {
    }

    public static synchronized void initialize(LevelStorageSource.LevelStorageAccess storageSource) {
        Path worldRoot = storageSource.getLevelPath(LevelResource.ROOT).toAbsolutePath().normalize();

        try {
            Path sourceRoot = configuredPath("allcraft.sourceRoot");
            Path gameRoot = configuredGameRoot(worldRoot);
            String serverId = loadOrCreateServerId(gameRoot);
            Path worldSource = worldRoot.resolve("source");
            cloneSourceIfMissing(sourceRoot, worldSource);
            refreshExocortexTools(sourceRoot, worldSource);
            initializePatchStorage(worldRoot.resolve("patches"), serverId);
            JsonObject manifest = readJson(worldRoot.resolve("patches/manifest.json"));
            long revision = manifest.get("currentRevision").getAsLong();
            AllcraftSourceRepository.initialize(worldRoot, revision);
            AllcraftRevisionBuilder.initializeBaseline(worldRoot);
            AllcraftAiJobs.initializeWorld(worldRoot);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to initialize Allcraft storage for world " + worldRoot, e);
        }
    }

    private static Path configuredPath(String property) throws IOException {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing required JVM property -D" + property + "=<path>");
        }

        Path path = Path.of(value).toAbsolutePath().normalize();
        if (!Files.isDirectory(path)) {
            throw new IOException("Configured path is not a directory: " + path);
        }

        return path;
    }

    private static Path configuredGameRoot(Path worldRoot) {
        String configured = System.getProperty("allcraft.gameDir");
        if (configured != null && !configured.isBlank()) {
            return Path.of(configured).toAbsolutePath().normalize();
        }

        Path parent = worldRoot.getParent();
        if (parent != null && parent.getFileName() != null && parent.getFileName().toString().equals("saves")) {
            return parent.getParent();
        }

        return parent == null ? worldRoot : parent;
    }

    private static String loadOrCreateServerId(Path gameRoot) throws IOException {
        Path allcraftRoot = gameRoot.resolve("allcraft");
        Path identityFile = allcraftRoot.resolve("server.json");
        Files.createDirectories(allcraftRoot);

        if (Files.isRegularFile(identityFile)) {
            JsonObject identity = readJson(identityFile);
            return requireUuid(identity, "serverId", identityFile);
        }

        String serverId = UUID.randomUUID().toString();
        JsonObject identity = new JsonObject();
        identity.addProperty("format", FORMAT_VERSION);
        identity.addProperty("serverId", serverId);
        identity.addProperty("createdAt", Instant.now().toString());
        writeJsonAtomically(identityFile, identity);
        LOGGER.info("Created Allcraft server identity {}", serverId);
        return serverId;
    }

    private static void cloneSourceIfMissing(Path sourceRoot, Path worldSource) throws IOException {
        if (Files.exists(worldSource, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(worldSource, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("World source path is not a directory: " + worldSource);
            }

            return;
        }

        Path temporarySource = worldSource.resolveSibling(".allcraft-source-" + UUID.randomUUID() + ".tmp");
        LOGGER.info("Cloning Allcraft source from {} to {}", sourceRoot, worldSource);

        try {
            copyTree(sourceRoot, temporarySource);
            moveAtomically(temporarySource, worldSource);
        } catch (IOException e) {
            deleteTree(temporarySource);
            throw e;
        }

        LOGGER.info("Finished cloning Allcraft source to {}", worldSource);
    }

    private static void refreshExocortexTools(Path sourceRoot, Path worldSource) throws IOException {
        Path relative = Path.of(".allcraft/exocortex/minecraft-tools.ts");
        Path installed = sourceRoot.resolve(relative);
        if (!Files.isRegularFile(installed)) {
            throw new IOException("Installed Allcraft Minecraft tool module is missing: " + installed);
        }

        Path destination = worldSource.resolve(relative);
        Files.createDirectories(destination.getParent());
        Files.copy(installed, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
        Files.deleteIfExists(worldSource.resolve(".allcraft/exocortex/minecraft-tools-impl.ts"));
    }

    private static void initializePatchStorage(Path patchesRoot, String serverId) throws IOException {
        Files.createDirectories(patchesRoot.resolve("source"));
        Files.createDirectories(patchesRoot.resolve("artifacts/client"));
        Files.createDirectories(patchesRoot.resolve("artifacts/server"));

        Path manifestFile = patchesRoot.resolve("manifest.json");
        if (Files.isRegularFile(manifestFile)) {
            JsonObject manifest = readJson(manifestFile);
            requireUuid(manifest, "worldId", manifestFile);
            if (!serverId.equals(manifest.has("serverId") ? manifest.get("serverId").getAsString() : null)) {
                manifest.addProperty("serverId", serverId);
                writeJsonAtomically(manifestFile, manifest);
            }

            return;
        }

        JsonObject manifest = new JsonObject();
        manifest.addProperty("format", FORMAT_VERSION);
        manifest.addProperty("serverId", serverId);
        manifest.addProperty("worldId", UUID.randomUUID().toString());
        manifest.addProperty("baseVersion", System.getProperty("allcraft.baseVersion", "26.2-allcraft"));
        manifest.addProperty("currentRevision", 0);
        manifest.addProperty("createdAt", Instant.now().toString());
        manifest.add("patches", new JsonArray());
        writeJsonAtomically(manifestFile, manifest);
    }

    private static JsonObject readJson(Path path) throws IOException {
        try {
            return JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Invalid Allcraft JSON file: " + path, e);
        }
    }

    private static String requireUuid(JsonObject object, String property, Path path) throws IOException {
        if (!object.has(property)) {
            throw new IOException("Missing " + property + " in " + path);
        }

        String value = object.get(property).getAsString();
        try {
            UUID.fromString(value);
            return value;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid " + property + " in " + path, e);
        }
    }

    private static void writeJsonAtomically(Path path, JsonObject object) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporaryFile = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(
            temporaryFile,
            GSON.toJson(object) + System.lineSeparator(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE
        );
        moveAtomically(temporaryFile, path);
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException {
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.copy(
                    file,
                    destination.resolve(source.relativize(file)),
                    StandardCopyOption.COPY_ATTRIBUTES,
                    LinkOption.NOFOLLOW_LINKS
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void moveAtomically(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }

        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path directory, IOException error) throws IOException {
                    if (error != null) {
                        throw error;
                    }

                    Files.deleteIfExists(directory);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException cleanupError) {
            LOGGER.warn("Failed to clean temporary Allcraft source directory {}", root, cleanupError);
        }
    }
}
