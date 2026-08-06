package net.minecraft.allcraft;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class AllcraftAiLauncherRegression {
    private AllcraftAiLauncherRegression() {
    }

    public static void main(String[] args) throws Exception {
        testCommandShape();
        testExactRequestAndConversationId();
        testContinuationAndInfo();
        testFailureAndTimeout();
        testValidation();
        testGreedyRequestParsing();
        testCommittedJournalRecoveryDecision();
        System.out.println("Allcraft AI launcher regression passed");
    }

    private static void testCommandShape() {
        Path executable = Path.of("/tmp/exo");
        Path module = Path.of("/tmp/minecraft-tools.ts");
        List<String> command = AllcraftAiLauncher.command(executable, module);
        require(command.getFirst().equals(executable.toString()), "executable is argv[0]");
        require(command.containsAll(List.of("send", "--model", "openai/gpt-5.6-luna", "--custom-tool", module.toString(), "--folder", "allcraft/logs", "--auto-title", "--detach", "--id")), "required send arguments");
        require(command.get(command.indexOf("--model") + 1).equals(AllcraftAiLauncher.MODEL), "Luna model is explicit");
        require(count(command, "--internal-tool") == 6L, "six exact internal tools");
        require(command.containsAll(List.of("read", "write", "edit", "patch", "minecraft_glob", "minecraft_grep")), "intended tool names");
        require(!command.contains("--external-tool"), "no external tools selected");
    }

    private static void testExactRequestAndConversationId() throws Exception {
        Path root = Files.createTempDirectory("allcraft-ai-success-");
        try {
            Path stdinCapture = root.resolve("stdin.bin");
            Path argsCapture = root.resolve("args.txt");
            Path envCapture = root.resolve("env.txt");
            Path executable = script(
                root,
                "exo-success",
                "#!/bin/sh\nprintf '%s\\n' \"$@\" > " + quote(argsCapture) + "\n"
                    + "printf '%s' \"${EXOCORTEX_PARENT_CONV_ID-unset}\" > " + quote(envCapture) + "\n"
                    + "cat > " + quote(stdinCapture) + "\n"
                    + "printf '%s\\n' '1785000000000-abc123'\n"
            );
            Path module = root.resolve("minecraft-tools.ts");
            Files.writeString(module, "export default [];\n");
            String request = "preserve spaces  and unicode π\\nsecond line\\n".replace("\\n", "\n");

            AllcraftAiLauncher.CliResult result = AllcraftAiLauncher.runCli(executable, root, module, request, Duration.ofSeconds(5L));
            require(result.conversationId().equals("1785000000000-abc123"), "conversation id returned");
            require(Files.readString(stdinCapture).equals(request), "stdin is byte-for-byte request text");
            require(Files.readString(envCapture).equals("unset"), "parent conversation environment removed");
            List<String> arguments = Files.readAllLines(argsCapture);
            require(arguments.getFirst().equals("send"), "fake CLI received send");
            require(arguments.contains(module.toString()), "fake CLI received exact module path");

            Path durableId = root.resolve("conversation-id.pending");
            AllcraftAiLauncher.CliResult durable = AllcraftAiLauncher.runCli(
                executable, root, module, request, "1785000000000-abc123", durableId, Duration.ofSeconds(5L)
            );
            require(durable.conversationId().equals("1785000000000-abc123"), "durable launch returns conversation ID");
            require(Files.readString(durableId).trim().equals(durable.conversationId()), "CLI output durably captures conversation ID");
            require(
                Files.readAllLines(argsCapture).containsAll(List.of("--new-conversation-id", "1785000000000-abc123")),
                "durable launch reserves the persisted conversation ID"
            );
        } finally {
            deleteTree(root);
        }
    }

    private static void testFailureAndTimeout() throws Exception {
        Path root = Files.createTempDirectory("allcraft-ai-failure-");
        try {
            Path module = root.resolve("minecraft-tools.ts");
            Files.writeString(module, "export default [];\n");
            Path failure = script(root, "exo-failure", "#!/bin/sh\ncat >/dev/null\nprintf '%s' 'module rejected' >&2\nexit 7\n");
            expectIOException(
                () -> AllcraftAiLauncher.runCli(failure, root, module, "request", Duration.ofSeconds(5L)),
                "exited with code 7: module rejected"
            );

            Path invalid = script(root, "exo-invalid", "#!/bin/sh\ncat >/dev/null\nprintf '%s' 'not-an-id'\n");
            expectIOException(
                () -> AllcraftAiLauncher.runCli(invalid, root, module, "request", Duration.ofSeconds(5L)),
                "invalid conversation ID"
            );

            Path slow = script(root, "exo-slow", "#!/bin/sh\ncat >/dev/null\nsleep 2\n");
            expectIOException(
                () -> AllcraftAiLauncher.runCli(slow, root, module, "request", Duration.ofMillis(100L)),
                "timed out"
            );
        } finally {
            deleteTree(root);
        }
    }

    private static void testContinuationAndInfo() throws Exception {
        Path root = Files.createTempDirectory("allcraft-ai-followup-");
        try {
            Path stdinCapture = root.resolve("followup.txt");
            Path executable = script(
                root,
                "exo-followup",
                "#!/bin/sh\n"
                    + "if [ \"$1\" = info ]; then printf '%s\\n' '{\"streaming\":false,\"messageCount\":4,\"title\":\"Ruby block\"}'; exit 0; fi\n"
                    + "cat > " + quote(stdinCapture) + "\nprintf '%s\\n' '1785000000000-abc123'\n"
            );
            AllcraftAiLauncher.CliResult continued = AllcraftAiLauncher.continueCli(
                executable, root, "1785000000000-abc123", "repair exactly", Duration.ofSeconds(5L)
            );
            require(continued.conversationId().equals("1785000000000-abc123"), "follow-up conversation retained");
            require(Files.readString(stdinCapture).equals("repair exactly"), "follow-up diagnostics preserved");
            AllcraftAiLauncher.CliInfo info = AllcraftAiLauncher.info(
                executable, root, "1785000000000-abc123", Duration.ofSeconds(5L)
            );
            require(!info.streaming() && info.messageCount() == 4 && info.title().equals("Ruby block"), "conversation info parsed");
        } finally {
            deleteTree(root);
        }
    }

    private static void testValidation() throws Exception {
        Path root = Files.createTempDirectory("allcraft-ai-validation-");
        try {
            Path executable = script(root, "exo", "#!/bin/sh\nexit 0\n");
            Path module = root.resolve(".allcraft/exocortex/minecraft-tools.ts");
            Files.createDirectories(module.getParent());
            Files.writeString(module, "export default [];\n");
            require(AllcraftAiLauncher.validate(executable, root, module) == null, "valid installation");
            require(AllcraftAiLauncher.validate(executable, root.resolve("missing"), module).contains("world source"), "missing source error");
            require(AllcraftAiLauncher.validate(executable, root, root.resolve("missing.ts")).contains("tool module"), "missing module error");
            require(AllcraftAiLauncher.validate(root.resolve("missing-exo"), root, module).contains("CLI"), "missing CLI error");
        } finally {
            deleteTree(root);
        }
    }

    private static void testGreedyRequestParsing() throws Exception {
        String request = "make a ruby block with spaces and symbols !?";
        String parsed = StringArgumentType.greedyString().parse(new StringReader(request));
        require(parsed.equals(request), "greedy command request is preserved");
    }

    private static void testCommittedJournalRecoveryDecision() {
        JsonObject transaction = new JsonObject();
        transaction.addProperty("phase", "committing");
        transaction.addProperty("revision", 7L);
        transaction.addProperty("patchId", "patch-seven");
        JsonObject manifest = new JsonObject();
        manifest.add("patches", new JsonArray());
        require(AllcraftPatchServer.shouldRecoverRollback(manifest, transaction), "uncommitted journal requires rollback recovery");

        JsonObject committed = new JsonObject();
        committed.addProperty("revision", 7L);
        committed.addProperty("patchId", "patch-seven");
        manifest.getAsJsonArray("patches").add(committed);
        require(
            !AllcraftPatchServer.shouldRecoverRollback(manifest, transaction),
            "manifest-selected revision must not run rollback hooks during recovery"
        );
    }

    private static Path script(Path root, String name, String contents) throws IOException {
        Path path = root.resolve(name);
        Files.writeString(path, contents, StandardCharsets.UTF_8);
        path.toFile().setExecutable(true, true);
        return path;
    }

    private static String quote(Path path) {
        return "'" + path.toString().replace("'", "'\\''") + "'";
    }

    private static long count(List<String> values, String expected) {
        return values.stream().filter(expected::equals).count();
    }

    private static void expectIOException(CheckedRunnable runnable, String expected) throws Exception {
        try {
            runnable.run();
            throw new AssertionError("Expected IOException containing: " + expected);
        } catch (IOException error) {
            require(error.getMessage().contains(expected), "IOException message contains " + expected + ": " + error.getMessage());
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((left, right) -> right.getNameCount() - left.getNameCount()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
