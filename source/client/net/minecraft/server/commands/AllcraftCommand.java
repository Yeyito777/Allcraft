package net.minecraft.server.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.allcraft.AllcraftAiLauncher;
import net.minecraft.allcraft.AllcraftPatchServer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

public final class AllcraftCommand {
    private AllcraftCommand() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("allcraft")
                .then(
                    Commands.literal("test")
                        .executes(context -> listTests(context.getSource()))
                        .then(
                            Commands.argument("test-name", StringArgumentType.word())
                                .suggests(
                                    (context, builder) -> SharedSuggestionProvider.suggest(AllcraftPatchServer.TEST_NAMES, builder)
                                )
                                .executes(
                                    context -> AllcraftPatchServer.startTest(
                                        context.getSource(), StringArgumentType.getString(context, "test-name")
                                    )
                                )
                        )
                )
                .then(
                    Commands.literal("ai")
                        .executes(context -> AllcraftAiLauncher.start(context.getSource(), ""))
                        .then(
                            Commands.argument("request", StringArgumentType.greedyString())
                                .executes(
                                    context -> AllcraftAiLauncher.start(
                                        context.getSource(), StringArgumentType.getString(context, "request")
                                    )
                                )
                        )
                )
                .then(
                    Commands.literal("apply")
                        .executes(context -> AllcraftPatchServer.startApply(context.getSource(), "source"))
                        .then(
                            Commands.argument("label", StringArgumentType.word())
                                .executes(
                                    context -> AllcraftPatchServer.startApply(
                                        context.getSource(), StringArgumentType.getString(context, "label")
                                    )
                                )
                        )
                )
        );
    }

    private static int listTests(CommandSourceStack source) {
        source.sendSuccess(
            () -> Component.literal("Allcraft patch tests: " + String.join(", ", AllcraftPatchServer.TEST_NAMES)), false
        );
        return AllcraftPatchServer.TEST_NAMES.size();
    }
}
