package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

public class FastSignInteract extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Integer> clicksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("clicks-per-tick")
        .description("Сколько right-click пакетов отправлять за один тик.")
        .defaultValue(4)
        .min(1)
        .max(12)
        .sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> onlyWhenLooking = sgGeneral.add(new BoolSetting.Builder()
        .name("only-when-looking")
        .description("Работает только если прицел на табличке (sign / hanging sign).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> swingHand = sgGeneral.add(new BoolSetting.Builder()
        .name("swing-hand")
        .description("Отправлять анимацию взмаха руки (выглядит легитимнее).")
        .defaultValue(true)
        .build()
    );

    private int sequence = 0;

    public FastSignInteract() {
        super(AddonTemplate.CATEGORY, "fast-sign-interact", "Обходит задержку между взаимодействиями с табличками (спам right-click).");
    }

    @EventHandler
    private void onTickPost(TickEvent.Post event) {
        if (mc.player == null || mc.world == null || mc.crosshairTarget == null) return;

        HitResult hit = mc.crosshairTarget;
        if (!(hit instanceof BlockHitResult blockHit)) return;

        // Если включена проверка — смотрим, табличка ли под прицелом
        if (onlyWhenLooking.get()) {
            if (!(mc.world.getBlockState(blockHit.getBlockPos()).getBlock() instanceof AbstractSignBlock)) {
                return;
            }
        }

        int clicks = clicksPerTick.get();

        for (int i = 0; i < clicks; i++) {
            // Главный пакет взаимодействия
            PlayerInteractBlockC2SPacket interact = new PlayerInteractBlockC2SPacket(
                Hand.MAIN_HAND,          // можно потом сделать настройку руки
                blockHit,
                sequence++
            );

            mc.getNetworkHandler().sendPacket(interact);

            // Взмах руки (делает поведение более естественным)
            if (swingHand.get()) {
                mc.getNetworkHandler().sendPacket(new HandSwingC2SPacket(Hand.MAIN_HAND));
            }
        }
    }

    @Override
    public void onActivate() {
        sequence = 0;           // сбрасываем sequence при включении модуля
        // Можно добавить sequence = mc.player.age * 31 + (int)(Math.random() * 100); для большей рандомизации
    }

    @Override
    public void onDeactivate() {
        // опционально: можно здесь сбросить что-то ещё, если нужно
    }
}
