package com.example.addon.modules;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import java.util.List;

public class TreePacketMiner extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Какие блоки дерева обрабатывать")
        .defaultValue(List.of(
            Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG, Blocks.JUNGLE_LOG,
            Blocks.ACACIA_LOG, Blocks.DARK_OAK_LOG, Blocks.MANGROVE_LOG, Blocks.CHERRY_LOG,
            Blocks.STRIPPED_OAK_LOG, Blocks.STRIPPED_SPRUCE_LOG // можно добавить свои
        ))
        .filter(b -> b.getHardness() > 0)
        .build());

    public final Setting<Integer> abortCount = sgGeneral.add(new IntSetting.Builder()
        .name("abort-count")
        .description("Сколько раз отправлять ABORT (ты просил 4)")
        .defaultValue(4)
        .min(1)
        .max(12)
        .build());

    private BlockPos lastProcessed = null;

    public TreePacketMiner() {
        super(Categories.World, "tree-packet-miner", "Делает 1 лом → N×ABORT → 1 лом через SpeedMine. Идеально для x100–x1000 modifier.");
    }

    @Override
    public void onDeactivate() {
        lastProcessed = null;
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!(event.packet instanceof PlayerActionC2SPacket packet)) return;
        if (packet.getAction() != PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) return;

        BlockPos pos = packet.getPos();
        if (pos.equals(lastProcessed)) return; // защита от цикла

        Block block = mc.world.getBlockState(pos).getBlock();
        if (!blocks.get().contains(block)) return;

        Direction dir = packet.getDirection();
        var nh = mc.getNetworkHandler();

        // 4× (или сколько ты поставил) ABORT
        for (int i = 0; i < abortCount.get(); i++) {
            nh.sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
                pos,
                dir
            ));
        }

        // Второй лом блока
        nh.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            pos,
            dir
        ));
        nh.sendPacket(new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            pos,
            dir
        ));

        lastProcessed = pos; // второй STOP уже не сработает
    }
}
