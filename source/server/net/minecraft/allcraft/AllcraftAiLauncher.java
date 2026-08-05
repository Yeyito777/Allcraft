package net.minecraft.allcraft;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

public final class AllcraftAiLauncher {
    static final Path EXO_CLI = Path.of("/home/yeyito/Workspace/exocortex/external-tools/exo-cli/bin/exo");
    static final Path TOOL_MODULE_RELATIVE = Path.of(".allcraft/exocortex/minecraft-tools.ts");
    static final Duration CLI_TIMEOUT = Duration.ofSeconds(30L);
    private static final Pattern CONVERSATION_ID = Pattern.compile("[0-9]+-[a-z0-9]{6}");
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ExecutorService AI_EXECUTOR = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "Allcraft Exocortex Launcher");
        thread.setDaemon(true);
        return thread;
    });

    private AllcraftAiLauncher() {
    }

    public static int start(CommandSourceStack source, String request) {
        String exactRequest = request == null ? "" : request;
        if (exactRequest.isBlank()) {
            source.sendFailure(Component.literal("Usage: /allcraft ai <request>"));
            return 0;
        }

        MinecraftServer server = source.getServer();
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path sourceRoot = worldRoot.resolve("source");
        Path toolModule = sourceRoot.resolve(TOOL_MODULE_RELATIVE);
        String validationError = validate(EXO_CLI, sourceRoot, toolModule);
        if (validationError != null) {
            source.sendFailure(Component.literal(validationError));
            return 0;
        }

        source.sendSuccess(
            () -> Component.literal("Starting an Allcraft AI conversation in Exocortex…").withStyle(ChatFormatting.AQUA), false
        );
        AI_EXECUTOR.execute(() -> {
            try {
                CliResult result = runCli(EXO_CLI, sourceRoot, toolModule, exactRequest, CLI_TIMEOUT);
                server.execute(
                    () -> source.sendSuccess(
                        () -> Component.literal("Allcraft AI conversation started: exo:" + result.conversationId())
                            .withStyle(ChatFormatting.GREEN),
                        false
                    )
                );
            } catch (Exception e) {
                LOGGER.error("Failed to start Allcraft AI conversation", e);
                String message = conciseMessage(e);
                server.execute(
                    () -> source.sendFailure(
                        Component.literal("Failed to start Allcraft AI conversation: " + message).withStyle(ChatFormatting.RED)
                    )
                );
            }
        });
        return 1;
    }

    static String validate(Path executable, Path sourceRoot, Path toolModule) {
        if (!Files.isDirectory(sourceRoot)) return "Allcraft world source is unavailable: " + sourceRoot;
        if (!Files.isRegularFile(toolModule)) return "Allcraft Minecraft tool module is unavailable: " + toolModule;
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
            return "Exocortex CLI is unavailable or not executable: " + executable;
        }
        return null;
    }

    static List<String> command(Path executable, Path toolModule) {
        return List.of(
            executable.toString(),
            "send",
            "--custom-tool",
            toolModule.toString(),
            "--internal-tool",
            "read",
            "--internal-tool",
            "write",
            "--internal-tool",
            "edit",
            "--internal-tool",
            "patch",
            "--internal-tool",
            "minecraft_glob",
            "--internal-tool",
            "minecraft_grep",
            "--folder",
            "allcraft/logs",
            "--auto-title",
            "--detach",
            "--id"
        );
    }

    static CliResult runCli(Path executable, Path sourceRoot, Path toolModule, String request, Duration timeout)
        throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command(executable, toolModule));
        builder.directory(sourceRoot.toFile());
        builder.environment().remove("EXOCORTEX_PARENT_CONV_ID");
        builder.environment().remove("EXOCORTEX_SUBAGENT_MAX_DEPTH");
        Process process = builder.start();
        try (var stdin = process.getOutputStream()) {
            stdin.write(request.getBytes(StandardCharsets.UTF_8));
        }

        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!exited) {
            process.destroyForcibly();
            process.waitFor();
            throw new IOException("Exocortex CLI timed out after " + timeout.toSeconds() + " seconds");
        }

        String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException(
                "Exocortex CLI exited with code " + process.exitValue() + (stderr.isEmpty() ? "" : ": " + stderr)
            );
        }
        if (!CONVERSATION_ID.matcher(stdout).matches()) {
            throw new IOException("Exocortex CLI returned an invalid conversation ID: " + (stdout.isEmpty() ? "<empty>" : stdout));
        }
        return new CliResult(stdout, stderr);
    }

    private static String conciseMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 499) + "…";
    }

    record CliResult(String conversationId, String stderr) {
    }
}
