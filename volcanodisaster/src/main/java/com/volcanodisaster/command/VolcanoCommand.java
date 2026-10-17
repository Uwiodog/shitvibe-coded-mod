package com.volcanodisaster.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.volcanodisaster.volcano.Volcano;
import com.volcanodisaster.volcano.VolcanoData;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/**
 * /volcano spawn [pos] [intensity 1-20] [days]
 * /volcano list | intensity <1-20> [id] | skip [id] | flow [id] | stop [id|all] | remove [id|all] | ashzones clear
 * plus /disaster spawn volcano [pos] and /disaster skip volcano [pos] like the old mod.
 */
public final class VolcanoCommand {
    private VolcanoCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("volcano")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn")
                        .executes(ctx -> spawn(ctx.getSource(), here(ctx.getSource()), -1, -1))
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(ctx -> spawn(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), -1, -1))
                                .then(Commands.argument("intensity", IntegerArgumentType.integer(1, 20))
                                        .executes(ctx -> spawn(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                IntegerArgumentType.getInteger(ctx, "intensity"), -1))
                                        .then(Commands.argument("days", FloatArgumentType.floatArg(0.0F, 100.0F))
                                                .executes(ctx -> spawn(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"),
                                                        IntegerArgumentType.getInteger(ctx, "intensity"),
                                                        FloatArgumentType.getFloat(ctx, "days")))))))
                .then(Commands.literal("intensity")
                        .then(Commands.argument("value", IntegerArgumentType.integer(1, 20))
                                .executes(ctx -> setIntensity(ctx.getSource(), nearest(ctx.getSource(), here(ctx.getSource())),
                                        IntegerArgumentType.getInteger(ctx, "value")))
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .executes(ctx -> setIntensity(ctx.getSource(), byId(ctx),
                                                IntegerArgumentType.getInteger(ctx, "value"))))))
                .then(Commands.literal("list")
                        .executes(ctx -> list(ctx.getSource())))
                .then(Commands.literal("skip")
                        .executes(ctx -> skip(ctx.getSource(), nearest(ctx.getSource(), here(ctx.getSource()))))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> skip(ctx.getSource(), byId(ctx)))))
                .then(Commands.literal("flow")
                        .executes(ctx -> flow(ctx.getSource(), nearest(ctx.getSource(), here(ctx.getSource()))))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> flow(ctx.getSource(), byId(ctx)))))
                .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx.getSource(), single(nearest(ctx.getSource(), here(ctx.getSource())))))
                        .then(Commands.literal("all")
                                .executes(ctx -> stop(ctx.getSource(), new ArrayList<>(data(ctx.getSource()).all()))))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> stop(ctx.getSource(), single(byId(ctx))))))
                .then(Commands.literal("remove")
                        .executes(ctx -> remove(ctx.getSource(), single(nearest(ctx.getSource(), here(ctx.getSource())))))
                        .then(Commands.literal("all")
                                .executes(ctx -> remove(ctx.getSource(), new ArrayList<>(data(ctx.getSource()).all()))))
                        .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                .executes(ctx -> remove(ctx.getSource(), single(byId(ctx))))))
                .then(Commands.literal("ashzones")
                        .then(Commands.literal("clear")
                                .executes(ctx -> clearAshZones(ctx.getSource())))));

        // Same syntax as the old General Disasters command.
        dispatcher.register(Commands.literal("disaster")
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("spawn")
                        .then(Commands.literal("volcano")
                                .executes(ctx -> spawn(ctx.getSource(), here(ctx.getSource()), -1, -1))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> spawn(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"), -1, -1)))))
                .then(Commands.literal("skip")
                        .then(Commands.literal("volcano")
                                .executes(ctx -> skip(ctx.getSource(), nearest(ctx.getSource(), here(ctx.getSource()))))
                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                        .executes(ctx -> skip(ctx.getSource(),
                                                nearest(ctx.getSource(), BlockPosArgument.getLoadedBlockPos(ctx, "pos"))))))));
    }

    /** @param intensity 1-20, or -1 for the configured default */
    private static int spawn(CommandSourceStack source, BlockPos pos, int intensity, float days) {
        ServerLevel level = source.getLevel();
        Volcano v = VolcanoData.get(level).spawn(level, pos, intensity, days);
        String length = v.eruptionLength();
        int strength = Math.round(v.getStrength());
        source.sendSuccess(() -> Component.literal("Volcano #" + v.getId() + " is opening at "
                + pos.getX() + " " + pos.getY() + " " + pos.getZ() + " (intensity " + strength + "/20, erupts "
                + (length.startsWith("until") ? length : "for " + length)
                + "). Change it with /volcano intensity <1-20>, force a pyroclastic flow with /volcano flow, end it with /volcano stop "
                + v.getId()), true);
        return v.getId();
    }

    private static int setIntensity(CommandSourceStack source, Volcano v, int value) {
        if (v == null) {
            source.sendFailure(Component.literal("No volcano found."));
            return 0;
        }
        v.setStrength(value);
        data(source).setDirty();
        source.sendSuccess(() -> Component.literal("Volcano #" + v.getId() + " is heading to intensity " + value
                + "/20 (currently " + String.format("%.1f", v.getIntensity()) + ")."), true);
        return value;
    }

    private static int list(CommandSourceStack source) {
        List<Volcano> all = data(source).all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No active volcanoes in this dimension."), false);
            return 0;
        }
        for (Volcano v : all) {
            BlockPos c = v.getCenter();
            String stage = v.getStage().name().toLowerCase();
            if (v.getStage() == Volcano.Stage.ERUPTING) {
                stage += ", intensity " + String.format("%.1f", v.getIntensity()) + "/20 " + v.trend()
                        + " (" + v.getActivity().name().toLowerCase() + ", usual " + Math.round(v.getStrength()) + ")";
            }
            String text = "#" + v.getId() + "  " + c.getX() + " " + c.getY() + " " + c.getZ()
                    + "  " + stage + " (" + v.timeLeft() + " left)";
            source.sendSuccess(() -> Component.literal(text), false);
        }
        return all.size();
    }

    private static int skip(CommandSourceStack source, Volcano v) {
        if (v == null) {
            source.sendFailure(Component.literal("No volcano found."));
            return 0;
        }
        Volcano.Stage before = v.getStage();
        v.skipStage(source.getLevel());
        data(source).setDirty();
        source.sendSuccess(() -> Component.literal("Volcano #" + v.getId() + ": " + before.name().toLowerCase()
                + " -> " + v.getStage().name().toLowerCase()), true);
        return 1;
    }

    private static int flow(CommandSourceStack source, Volcano v) {
        if (v == null) {
            source.sendFailure(Component.literal("No volcano found."));
            return 0;
        }
        if (!v.triggerFlow(source.getLevel(), data(source))) {
            source.sendFailure(Component.literal("Volcano #" + v.getId()
                    + " can't make a pyroclastic flow right now (it has to be erupting, and one at a time)."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Volcano #" + v.getId() + " is building up to a pyroclastic flow..."), true);
        return 1;
    }

    private static int stop(CommandSourceStack source, List<Volcano> targets) {
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("No volcano found."));
            return 0;
        }
        for (Volcano v : targets) {
            v.stop();
        }
        data(source).setDirty();
        int n = targets.size();
        source.sendSuccess(() -> Component.literal("Stopped " + n + " volcano(es). The lava will harden over the next minute."), true);
        return n;
    }

    private static int remove(CommandSourceStack source, List<Volcano> targets) {
        if (targets.isEmpty()) {
            source.sendFailure(Component.literal("No volcano found."));
            return 0;
        }
        VolcanoData data = data(source);
        for (Volcano v : targets) {
            data.remove(source.getLevel(), v);
        }
        int n = targets.size();
        source.sendSuccess(() -> Component.literal("Removed " + n + " volcano(es). Any lava already placed stays as it is."), true);
        return n;
    }

    private static int clearAshZones(CommandSourceStack source) {
        int n = data(source).clearAshZones();
        source.sendSuccess(() -> Component.literal("Stopped " + n + " ash zone(s) from spreading the Ashfall biome. "
                + "Land that already turned into Ashfall stays that way."), true);
        return n;
    }

    private static VolcanoData data(CommandSourceStack source) {
        return VolcanoData.get(source.getLevel());
    }

    private static BlockPos here(CommandSourceStack source) {
        return BlockPos.containing(source.getPosition());
    }

    private static Volcano nearest(CommandSourceStack source, BlockPos pos) {
        return data(source).nearest(pos);
    }

    private static Volcano byId(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        return data(ctx.getSource()).byId(IntegerArgumentType.getInteger(ctx, "id"));
    }

    private static List<Volcano> single(Volcano v) {
        List<Volcano> list = new ArrayList<>();
        if (v != null) {
            list.add(v);
        }
        return list;
    }
}
