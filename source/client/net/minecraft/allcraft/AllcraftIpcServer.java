package net.minecraft.allcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FileUtil;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/** Loopback-only automation API used by Allcraft's development tools. */
public final class AllcraftIpcServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static volatile AllcraftIpcServer instance;
    private final Minecraft minecraft;
    private final ServerSocket serverSocket;
    private final Path endpointFile;

    private AllcraftIpcServer(Minecraft minecraft, ServerSocket serverSocket, Path endpointFile) {
        this.minecraft = minecraft;
        this.serverSocket = serverSocket;
        this.endpointFile = endpointFile;
    }

    public static synchronized void start(Minecraft minecraft) {
        if (instance != null) {
            return;
        }

        try {
            int configuredPort = Integer.getInteger("allcraft.ipcPort", 0);
            ServerSocket socket = new ServerSocket(configuredPort, 16, InetAddress.getByName("127.0.0.1"));
            Path gameDir = Path.of(System.getProperty("allcraft.gameDir", minecraft.gameDirectory.getAbsolutePath())).toAbsolutePath().normalize();
            Path endpoint = gameDir.resolve("allcraft/ipc.json");
            AllcraftIpcServer server = new AllcraftIpcServer(minecraft, socket, endpoint);
            server.writeEndpoint();
            instance = server;
            Runtime.getRuntime().addShutdownHook(new Thread(server::close, "Allcraft IPC Shutdown"));
            Thread listener = new Thread(server::listen, "Allcraft IPC");
            listener.setDaemon(true);
            listener.start();
            LOGGER.info("Allcraft IPC listening on 127.0.0.1:{}", socket.getLocalPort());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to start Allcraft IPC", e);
        }
    }

    private void listen() {
        while (!this.serverSocket.isClosed()) {
            try {
                Socket client = this.serverSocket.accept();
                Thread handler = new Thread(() -> this.handle(client), "Allcraft IPC Client");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                if (!this.serverSocket.isClosed()) {
                    LOGGER.error("Allcraft IPC accept failed", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        try (
            socket;
            BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            BufferedWriter output = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))
        ) {
            String line;
            while ((line = input.readLine()) != null) {
                JsonObject response;
                try {
                    JsonObject request = JsonParser.parseString(line).getAsJsonObject();
                    response = this.dispatch(request);
                    response.addProperty("ok", true);
                    if (request.has("id")) {
                        response.add("id", request.get("id"));
                    }
                } catch (Exception e) {
                    response = new JsonObject();
                    response.addProperty("ok", false);
                    response.addProperty("error", conciseMessage(e));
                }
                output.write(GSON.toJson(response).replace("\n", ""));
                output.newLine();
                output.flush();
            }
        } catch (IOException e) {
            LOGGER.debug("Allcraft IPC client disconnected", e);
        }
    }

    private JsonObject dispatch(JsonObject request) throws Exception {
        String action = requiredString(request, "action");
        return switch (action) {
            case "status" -> onClientThread(this::status);
            case "list-worlds" -> listWorlds();
            case "join-world" -> onClientThread(() -> joinWorld(requiredString(request, "world")));
            case "create-world" -> onClientThread(() -> createWorld(request));
            case "quit-world" -> onClientThread(this::quitWorld);
            case "chat" -> onClientThread(() -> sendChat(requiredString(request, "text"), false));
            case "command" -> onClientThread(() -> sendChat(requiredString(request, "text"), true));
            case "use-block" -> onClientThread(() -> useBlock(request));
            case "close-screen" -> onClientThread(this::closeScreen);
            default -> throw new IOException("Unknown Allcraft IPC action: " + action);
        };
    }

    private JsonObject status() {
        JsonObject response = new JsonObject();
        response.addProperty("pid", ProcessHandle.current().pid());
        response.addProperty("fps", this.minecraft.getFps());
        response.addProperty("screen", this.minecraft.gui.screen() == null ? null : this.minecraft.gui.screen().getClass().getSimpleName());
        response.addProperty("inWorld", this.minecraft.level != null && this.minecraft.player != null);
        response.addProperty("player", this.minecraft.player == null ? null : this.minecraft.player.getName().getString());
        if (this.minecraft.player != null) {
            response.addProperty("x", this.minecraft.player.getX());
            response.addProperty("y", this.minecraft.player.getY());
            response.addProperty("z", this.minecraft.player.getZ());
        }
        response.addProperty(
            "world",
            this.minecraft.getSingleplayerServer() == null ? null : this.minecraft.getSingleplayerServer().getWorldData().getLevelName()
        );
        response.addProperty("agentLoaded", AllcraftAgent.isLoaded());
        return response;
    }

    private JsonObject listWorlds() throws IOException {
        Path saves = gameDir().resolve("saves");
        JsonArray worlds = new JsonArray();
        if (Files.isDirectory(saves)) {
            try (var entries = Files.list(saves)) {
                entries.filter(Files::isDirectory)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(path -> worlds.add(path.getFileName().toString()));
            }
        }
        JsonObject response = new JsonObject();
        response.add("worlds", worlds);
        return response;
    }

    private JsonObject joinWorld(String world) throws IOException {
        if (!this.minecraft.getLevelSource().levelExists(world)) {
            throw new IOException("World does not exist: " + world);
        }
        leaveCurrentWorld();
        this.minecraft.createWorldOpenFlows().openWorld(world, () -> this.minecraft.gui.setScreen(new TitleScreen()));
        return accepted("joining", world);
    }

    private JsonObject createWorld(JsonObject request) throws IOException {
        String name = requiredString(request, "name").trim();
        if (name.isEmpty()) {
            throw new IOException("World name cannot be empty");
        }
        String requestedMode = request.has("mode") ? request.get("mode").getAsString().toLowerCase(Locale.ROOT) : "survival";
        GameType gameType = switch (requestedMode) {
            case "survival" -> GameType.SURVIVAL;
            case "creative" -> GameType.CREATIVE;
            default -> throw new IOException("World mode must be survival or creative");
        };
        long seed = request.has("seed") ? request.get("seed").getAsLong() : new Random().nextLong();
        leaveCurrentWorld();
        String levelId = FileUtil.findAvailableName(this.minecraft.getLevelSource().getBaseDir(), name, "");
        LevelSettings settings = new LevelSettings(
            name,
            gameType,
            new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false),
            true,
            WorldDataConfiguration.DEFAULT
        );
        this.minecraft
            .createWorldOpenFlows()
            .createFreshLevel(levelId, settings, new WorldOptions(seed, true, false), WorldPresets::createNormalWorldDimensions, new TitleScreen());
        JsonObject response = accepted("creating", name);
        response.addProperty("worldId", levelId);
        response.addProperty("mode", requestedMode);
        response.addProperty("seed", seed);
        return response;
    }

    private JsonObject quitWorld() {
        if (this.minecraft.level != null) {
            this.minecraft.level.disconnect(ClientLevel.DEFAULT_QUIT_MESSAGE);
            this.minecraft.disconnectWithSavingScreen();
            this.minecraft.gui.setScreen(new TitleScreen());
        }
        return accepted("quit", "world");
    }

    private JsonObject sendChat(String text, boolean command) throws IOException {
        ClientPacketListener connection = this.minecraft.getConnection();
        if (connection == null) {
            throw new IOException("The client is not connected to a world");
        }
        if (command) {
            connection.sendCommand(text.startsWith("/") ? text.substring(1) : text);
        } else {
            connection.sendChat(text);
        }
        return accepted(command ? "command" : "chat", text);
    }

    private JsonObject useBlock(JsonObject request) throws IOException {
        if (this.minecraft.player == null || this.minecraft.level == null || this.minecraft.gameMode == null) {
            throw new IOException("The client is not ready to interact with a world");
        }
        BlockPos pos = new BlockPos(requiredInt(request, "x"), requiredInt(request, "y"), requiredInt(request, "z"));
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        InteractionResult result = this.minecraft.gameMode.useItemOn(this.minecraft.player, InteractionHand.MAIN_HAND, hit);
        JsonObject response = accepted("use-block", pos.toShortString());
        response.addProperty("result", result.toString());
        return response;
    }

    private JsonObject closeScreen() throws IOException {
        if (this.minecraft.player == null) {
            throw new IOException("The client is not connected to a world");
        }
        if (this.minecraft.player.containerMenu != this.minecraft.player.inventoryMenu) {
            this.minecraft.player.closeContainer();
        } else {
            this.minecraft.gui.setScreen(null);
        }
        return accepted("close-screen", "screen");
    }

    private void leaveCurrentWorld() {
        if (this.minecraft.level != null) {
            this.minecraft.level.disconnect(Component.literal("Allcraft IPC switching worlds"));
            this.minecraft.disconnectWithSavingScreen();
            this.minecraft.gui.setScreen(new TitleScreen());
        }
    }

    private JsonObject onClientThread(IpcAction action) throws Exception {
        CompletableFuture<JsonObject> result = new CompletableFuture<>();
        this.minecraft.execute(() -> {
            try {
                result.complete(action.run());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(90L, TimeUnit.SECONDS);
    }

    private void writeEndpoint() throws IOException {
        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("format", 1);
        endpoint.addProperty("host", "127.0.0.1");
        endpoint.addProperty("port", this.serverSocket.getLocalPort());
        endpoint.addProperty("pid", ProcessHandle.current().pid());
        endpoint.addProperty("startedAt", Instant.now().toString());
        Files.createDirectories(this.endpointFile.getParent());
        Path temporary = this.endpointFile.resolveSibling(this.endpointFile.getFileName() + ".tmp");
        Files.writeString(temporary, GSON.toJson(endpoint) + System.lineSeparator(), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, this.endpointFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, this.endpointFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void close() {
        try {
            this.serverSocket.close();
        } catch (IOException e) {
            LOGGER.debug("Failed to close Allcraft IPC socket", e);
        }
        try {
            if (Files.isRegularFile(this.endpointFile)) {
                JsonObject endpoint = JsonParser.parseString(Files.readString(this.endpointFile, StandardCharsets.UTF_8)).getAsJsonObject();
                if (endpoint.has("pid") && endpoint.get("pid").getAsLong() == ProcessHandle.current().pid()) {
                    Files.deleteIfExists(this.endpointFile);
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Failed to remove Allcraft IPC endpoint", e);
        }
    }

    private Path gameDir() {
        return Path.of(System.getProperty("allcraft.gameDir", this.minecraft.gameDirectory.getAbsolutePath())).toAbsolutePath().normalize();
    }

    private static JsonObject accepted(String action, String value) {
        JsonObject response = new JsonObject();
        response.addProperty("action", action);
        response.addProperty("value", value);
        return response;
    }

    private static String requiredString(JsonObject request, String property) throws IOException {
        if (!request.has(property) || !request.get(property).isJsonPrimitive()) {
            throw new IOException("Missing string property: " + property);
        }
        return request.get(property).getAsString();
    }

    private static int requiredInt(JsonObject request, String property) throws IOException {
        if (!request.has(property) || !request.get(property).isJsonPrimitive()) {
            throw new IOException("Missing integer property: " + property);
        }
        return request.get(property).getAsInt();
    }

    private static String conciseMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.ExecutionException || cause instanceof RuntimeException)) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return message == null ? cause.getClass().getSimpleName() : message.substring(0, Math.min(message.length(), 1000));
    }

    @FunctionalInterface
    private interface IpcAction {
        JsonObject run() throws Exception;
    }

}
