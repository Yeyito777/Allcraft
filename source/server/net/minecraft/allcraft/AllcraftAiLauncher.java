package net.minecraft.allcraft;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import net.minecraft.commands.CommandSourceStack;

public final class AllcraftAiLauncher {
    static final Path EXO_CLI = Path.of("/home/yeyito/Workspace/exocortex/external-tools/exo-cli/bin/exo");
    static final Path TOOL_MODULE_RELATIVE = Path.of(".allcraft/exocortex/minecraft-tools.ts");
    private static final Pattern CONVERSATION_ID = Pattern.compile("[0-9]+-[a-z0-9]{6}");

    private AllcraftAiLauncher() {
    }

    public static int start(CommandSourceStack source, String request) {
        return AllcraftAiJobs.start(source, request);
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
        return command(executable, toolModule, null);
    }

    static List<String> command(Path executable, Path toolModule, String newConversationId) {
        List<String> command = new ArrayList<>(List.of(
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
        ));
        if (newConversationId != null) {
            command.add("--new-conversation-id");
            command.add(newConversationId);
        }
        return List.copyOf(command);
    }

    static CliResult runCli(Path executable, Path sourceRoot, Path toolModule, String request, Duration timeout)
        throws IOException, InterruptedException {
        return runIdCommand(command(executable, toolModule), sourceRoot, request, timeout);
    }

    /** Lets the CLI durably publish its detached conversation ID even if Minecraft stops before waitFor returns. */
    static CliResult runCli(
        Path executable,
        Path sourceRoot,
        Path toolModule,
        String request,
        String newConversationId,
        Path idOutput,
        Duration timeout
    ) throws IOException, InterruptedException {
        Files.createDirectories(idOutput.getParent());
        Files.deleteIfExists(idOutput);
        ProcessBuilder builder = new ProcessBuilder(command(executable, toolModule, newConversationId));
        builder.directory(sourceRoot.toFile());
        builder.environment().remove("EXOCORTEX_PARENT_CONV_ID");
        builder.environment().remove("EXOCORTEX_SUBAGENT_MAX_DEPTH");
        builder.redirectOutput(idOutput.toFile());
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
        String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (process.exitValue() != 0) {
            throw new IOException(
                "Exocortex CLI exited with code " + process.exitValue() + (stderr.isEmpty() ? "" : ": " + stderr)
            );
        }
        String stdout = Files.readString(idOutput, StandardCharsets.UTF_8).trim();
        if (!CONVERSATION_ID.matcher(stdout).matches()) {
            throw new IOException("Exocortex CLI returned an invalid conversation ID: " + (stdout.isEmpty() ? "<empty>" : stdout));
        }
        return new CliResult(stdout, stderr);
    }

    static CliResult continueCli(Path executable, Path sourceRoot, String conversationId, String request, Duration timeout)
        throws IOException, InterruptedException {
        return runIdCommand(
            List.of(executable.toString(), "send", "--conv", conversationId, "--detach", "--id"),
            sourceRoot,
            request,
            timeout
        );
    }

    static CliInfo info(Path executable, Path sourceRoot, String conversationId, Duration timeout)
        throws IOException, InterruptedException {
        ProcessResult result = runCommand(
            List.of(executable.toString(), "info", conversationId, "--json"), sourceRoot, null, timeout
        );
        JsonObject object;
        try {
            object = JsonParser.parseString(result.stdout()).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Exocortex CLI returned invalid info JSON", e);
        }
        return new CliInfo(
            object.has("streaming") && object.get("streaming").getAsBoolean(),
            object.has("messageCount") ? object.get("messageCount").getAsInt() : 0,
            object.has("title") ? object.get("title").getAsString() : ""
        );
    }

    static void abort(Path executable, Path sourceRoot, String conversationId, Duration timeout)
        throws IOException, InterruptedException {
        runCommand(List.of(executable.toString(), "abort", conversationId), sourceRoot, null, timeout);
    }

    private static CliResult runIdCommand(List<String> command, Path sourceRoot, String request, Duration timeout)
        throws IOException, InterruptedException {
        ProcessResult result = runCommand(command, sourceRoot, request, timeout);
        String stdout = result.stdout().trim();
        if (!CONVERSATION_ID.matcher(stdout).matches()) {
            throw new IOException("Exocortex CLI returned an invalid conversation ID: " + (stdout.isEmpty() ? "<empty>" : stdout));
        }
        return new CliResult(stdout, result.stderr());
    }

    private static ProcessResult runCommand(
        List<String> command, Path sourceRoot, String stdinText, Duration timeout
    ) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(sourceRoot.toFile());
        builder.environment().remove("EXOCORTEX_PARENT_CONV_ID");
        builder.environment().remove("EXOCORTEX_SUBAGENT_MAX_DEPTH");
        Process process = builder.start();
        try (var stdin = process.getOutputStream()) {
            if (stdinText != null) stdin.write(stdinText.getBytes(StandardCharsets.UTF_8));
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
        return new ProcessResult(stdout, stderr);
    }

    private static String conciseMessage(Exception error) {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 500 ? message : message.substring(0, 499) + "…";
    }

    record CliResult(String conversationId, String stderr) {
    }

    record CliInfo(boolean streaming, int messageCount, String title) {
    }

    private record ProcessResult(String stdout, String stderr) {
    }
}
