package traben.flowing_fluids.forge.nether;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import traben.flowing_fluids.FlowingFluids;

public final class ForgeNetherLavaCommands {
    private ForgeNetherLavaCommands() {
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("flowing_fluids")
                .then(Commands.literal("nether_lava")
                        .executes(context -> showInfo(context.getSource()))
                        .then(Commands.literal("info")
                                .executes(context -> showInfo(context.getSource())))
                        .then(Commands.literal("status")
                                .executes(context -> status(context.getSource())))
                        .then(Commands.literal("stop")
                                .requires(source -> source.hasPermission(2))
                                .executes(context -> stop(context.getSource())))
                        .then(Commands.literal("start_here")
                                .requires(source -> source.hasPermission(2))
                                .then(eventBranch(NetherLavaEventSystem.NetherLavaEventType.LAVA_TIDE))
                                .then(eventBranch(NetherLavaEventSystem.NetherLavaEventType.BASALT_WAVE))
                                .then(eventBranch(NetherLavaEventSystem.NetherLavaEventType.EMBER_STORM))
                                .then(eventBranch(NetherLavaEventSystem.NetherLavaEventType.LAVA_PATHS)))));
    }

    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> eventBranch(NetherLavaEventSystem.NetherLavaEventType type) {
        return Commands.literal(type.id)
                .executes(context -> start(context.getSource(), type,
                        FlowingFluids.config.netherLavaEventDefaultRadius,
                        Math.max(5, FlowingFluids.config.netherLavaEventMinDurationTicks / 20)))
                .then(Commands.argument("radius", IntegerArgumentType.integer(12, 192))
                        .executes(context -> start(context.getSource(), type,
                                IntegerArgumentType.getInteger(context, "radius"),
                                Math.max(5, FlowingFluids.config.netherLavaEventMinDurationTicks / 20)))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(5, 900))
                                .executes(context -> start(context.getSource(), type,
                                        IntegerArgumentType.getInteger(context, "radius"),
                                        IntegerArgumentType.getInteger(context, "seconds")))));
    }

    private static int showInfo(CommandSourceStack source) {
        source.sendSystemMessage(Component.literal(
                "ネザー溶岩イベントコマンド"
                        + "\n`/flowing_fluids nether_lava info`"
                        + "\n  各イベントが何をするかと、起動方法を表示します。"
                        + "\n`/flowing_fluids nether_lava status`"
                        + "\n  この灼熱次元で動いているイベントと、いまいる場所からの距離を表示します。"
                        + "\n`/flowing_fluids nether_lava start_here <event> [radius] [seconds]`"
                        + "\n  いま立っている場所を中心にイベントを1つ起動します。"
                        + "\n  指定できるイベント: lava_tide, basalt_wave, ember_storm, lava_paths"
                        + "\n`/flowing_fluids nether_lava stop`"
                        + "\n  この次元で動いているネザー溶岩イベントをすべて停止します。"
                        + "\nイベント内容:"
                        + "\n- lava_tide: 溶岩がせり上がり、近くの溶岩スプリングも強く噴きます。"
                        + "\n- basalt_wave: 近くのネザーブロックが、じわじわ玄武岩やブラックストーンに変わります。"
                        + "\n- ember_storm: 灰と火の粉が舞い、周囲に小さな火種が散ります。"
                        + "\n- lava_paths: 溶岩が一方向へ伸びやすくなり、熱い流路が育ちます。"
        ));
        return 1;
    }

    private static int status(CommandSourceStack source) {
        BlockPos pos = BlockPos.containing(source.getPosition());
        source.sendSystemMessage(Component.literal(
                NetherLavaEventSystem.describeStatus(source.getLevel(), pos)
                        + "\nコマンド一覧は `/flowing_fluids nether_lava info` で見られます。"
        ));
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        boolean stopped = NetherLavaEventSystem.stopAll(source.getLevel());
        source.sendSystemMessage(Component.literal(
                stopped ? "この次元で動いていたネザー溶岩イベントを停止しました。" : "この次元では、いま動いているネザー溶岩イベントはありません。"
        ));
        return stopped ? 1 : 0;
    }

    private static int start(CommandSourceStack source, NetherLavaEventSystem.NetherLavaEventType type, int radius, int durationSeconds) {
        BlockPos center = BlockPos.containing(source.getPosition());
        boolean started = NetherLavaEventSystem.startEvent(source.getLevel(), type, center, radius, durationSeconds * 20);
        source.sendSystemMessage(Component.literal(
                started
                        ? center + " 周辺で「" + type.displayName + "」を開始しました。"
                        + "\n半径=" + radius + "、継続時間=" + durationSeconds + "秒"
                        + "\n様子を見るなら `/flowing_fluids nether_lava status`、止めるなら `/flowing_fluids nether_lava stop` を使ってください。"
                        : "ここではネザー溶岩イベントを開始できませんでした。灼熱次元にいるか、この機能が有効かを確認してください。"
        ));
        return started ? 1 : 0;
    }
}
