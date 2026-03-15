/*
 * Модуль для плавного спуфа позиции на табличку (стиль ClickTP, но безопасный)
 * Работает ТОЛЬКО с официальным Freecam из Meteor
 * Учитывает замедление автоматически (уменьшает шаг, чтобы не кикало)
 * Гарантирует минимум 1 блок/сек даже при сильном замедлении
 * Работает на ЛЮБОМ расстоянии (просто занимает время)
 */
package com.example.addon.modules;

import com.example.addon.AddonTemplate;
import meteordevelopment.meteorclient.events.meteor.MouseClickEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.render.Freecam;
import meteordevelopment.meteorclient.utils.misc.input.KeyAction;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public class DeliverySpoofer extends Module {

    private final SettingGroup sgDelivery = settings.createGroup("Delivery");

    private final Setting<Boolean> deliveryMode = sgDelivery.add(new BoolSetting.Builder()
        .name("delivery-mode")
        .description("Автоспуф при ПКМ по табличке (только в Freecam)")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxStepPerTick = sgDelivery.add(new DoubleSetting.Builder()
        .name("max-step-per-tick")
        .description("Максимальный шаг за тик. При замедлении авто-уменьшается. 0.22 = ~4.4 бл/сек (без замедления). Для 1 бл/сек минимум = 0.05")
        .defaultValue(0.22)
        .min(0.05)
        .max(0.8)
        .sliderRange(0.05, 0.8)
        .build()
    );

    private final Setting<Integer> stepDelayTicks = sgDelivery.add(new IntSetting.Builder()
        .name("step-delay")
        .description("Задержка между шагами (тики). 1 = каждый тик (самая плавная)")
        .defaultValue(1)
        .min(1)
        .max(5)
        .sliderRange(1, 5)
        .build()
    );

    private final Setting<Integer> returnDelay = sgDelivery.add(new IntSetting.Builder()
        .name("return-delay")
        .description("Пауза после клика перед возвратом (мс)")
        .defaultValue(180)
        .min(50)
        .max(800)
        .sliderRange(50, 800)
        .build()
    );

    private final Setting<Integer> chunkWaitTicks = sgDelivery.add(new IntSetting.Builder()
        .name("chunk-wait")
        .description("Макс. ожидание чанка (тики)")
        .defaultValue(5)
        .min(1)
        .max(15)
        .build()
    );

    // Состояние
    private boolean isSpoofing = false;
    private boolean isReturning = false;
    private Vec3d originalPos;
    private Vec3d targetPos;
    private Vec3d spoofedPos;        // <-- ТРЕКИРУЕМ СПУФНУТУЮ ПОЗИЦИЮ (важно для Freecam!)
    private BlockHitResult savedHit;
    private int tickCounter = 0;
    private int chunkWaitCounter = 0;
    private long returnStartTime = 0;
    private Vec3d lastSentPos;
    private int stuckCounter = 0;

    public DeliverySpoofer() {
        super(AddonTemplate.CATEGORY, "delivery-spoofer", "Плавный безопасный спуф на табличку (учитывает замедление)");
    }

    @Override
    public void onActivate() {
        reset();
    }

    @Override
    public void onDeactivate() {
        reset();
    }

    private void reset() {
        isSpoofing = false;
        isReturning = false;
        originalPos = null;
        targetPos = null;
        spoofedPos = null;
        savedHit = null;
        tickCounter = 0;
        chunkWaitCounter = 0;
        returnStartTime = 0;
        lastSentPos = null;
        stuckCounter = 0;
    }

    @EventHandler
    private void onMouseClick(MouseClickEvent event) {
        if (!isActive() || !deliveryMode.get() || event.action != KeyAction.Press) return;
        if (event.button() != GLFW.GLFW_MOUSE_BUTTON_RIGHT) return;

        Freecam freecam = Modules.get().get(Freecam.class);
        if (freecam == null || !freecam.isActive()) {
            info("§cВключи Freecam!");
            return;
        }

        if (mc.world == null || mc.player == null || mc.crosshairTarget == null) return;
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = hit.getBlockPos();

        if (!(mc.world.getBlockEntity(pos) instanceof SignBlockEntity)) return;

        if (isSpoofing) return;

        event.cancel();
        startSmoothSpoof(hit);
    }

    private void startSmoothSpoof(BlockHitResult hit) {
        BlockPos signPos = hit.getBlockPos();
        info("§aСтарт плавного спуфа к табличке " + signPos.toShortString());

        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        spoofedPos = originalPos;
        targetPos = new Vec3d(signPos.getX() + 0.5, signPos.getY() + 1.08, signPos.getZ() + 0.5);
        savedHit = hit;

        isSpoofing = true;
        isReturning = false;
        tickCounter = 0;
        chunkWaitCounter = 0;
        stuckCounter = 0;
        lastSentPos = spoofedPos;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isSpoofing || mc.player == null || mc.getNetworkHandler() == null || spoofedPos == null) return;

        tickCounter++;
        if (tickCounter < stepDelayTicks.get()) return;
        tickCounter = 0;

        Vec3d current = spoofedPos;

        if (!isReturning) {
            // → К табличке
            if (distanceHorizontal(current, targetPos) < 0.6) {
                info("§aДостигли — клик по табличке");
                simulateClick(savedHit);

                returnStartTime = System.currentTimeMillis() + returnDelay.get();
                isReturning = true;
                spoofedPos = targetPos;           // точная позиция
                targetPos = originalPos;
                lastSentPos = spoofedPos;
                return;
            }

            double effectiveStep = maxStepPerTick.get();

            // АВТО-УЧЁТ ЗАМЕДЛЕНИЯ
            if (mc.player.hasStatusEffect(StatusEffects.SLOWNESS)) {
                int amp = mc.player.getStatusEffect(StatusEffects.SLOWNESS).getAmplifier();
                effectiveStep = Math.max(0.05, effectiveStep * Math.pow(0.82, amp + 1)); // минимум 1 бл/сек
            }

            Vec3d next = calculateNextPos(current, targetPos, effectiveStep);

            if (!isChunkLoadedForPos(next)) {
                chunkWaitCounter++;
                if (chunkWaitCounter > chunkWaitTicks.get()) {
                    info("§eЧанк не грузится — форсируем");
                    chunkWaitCounter = 0;
                } else return;
            }

            sendPosition(next);
            spoofedPos = next;
            lastSentPos = next;

            // прогресс
            if (System.currentTimeMillis() % 800 < 50) { // раз в ~0.8 сек
                double prog = 100 - (distanceHorizontal(spoofedPos, targetPos) / originalPos.distanceTo(targetPos) * 100);
                info(String.format("§7Прогресс: ", Math.max(0, prog)));
            }

            // анти-застревание
            if (lastSentPos != null && current.distanceTo(lastSentPos) < 0.1) {
                stuckCounter++;
                if (stuckCounter > 6) {
                    Vec3d force = lerp(current, targetPos, 0.35);
                    sendPosition(force);
                    spoofedPos = force;
                    stuckCounter = 0;
                }
            } else {
                stuckCounter = 0;
            }
        } else {
            // ← Возврат
            if (System.currentTimeMillis() < returnStartTime) return;

            if (distanceHorizontal(current, targetPos) < 0.6) { // targetPos теперь original
                info("§aВозврат завершён");
                reset();
                return;
            }

            double effectiveStep = maxStepPerTick.get();
            if (mc.player.hasStatusEffect(StatusEffects.SLOWNESS)) {
                int amp = mc.player.getStatusEffect(StatusEffects.SLOWNESS).getAmplifier();
                effectiveStep = Math.max(0.05, effectiveStep * Math.pow(0.82, amp + 1));
            }

            Vec3d next = calculateNextPos(current, targetPos, effectiveStep);

            if (!isChunkLoadedForPos(next)) return;

            sendPosition(next);
            spoofedPos = next;
        }
    }

    private Vec3d calculateNextPos(Vec3d curr, Vec3d targ, double step) {
        Vec3d dir = targ.subtract(curr);
        double len = dir.length();
        if (len < step * 1.1) return targ;

        Vec3d norm = dir.normalize();

        double dy = targ.y - curr.y;
        double vertStep = Math.signum(dy) * Math.min(step * 0.75, Math.abs(dy));

        return new Vec3d(
            curr.x + norm.x * step,
            curr.y + vertStep,
            curr.z + norm.z * step
        );
    }

    private boolean isChunkLoadedForPos(Vec3d pos) {
        ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(pos));
        return mc.world != null && mc.world.isChunkLoaded(chunk.x, chunk.z);
    }

    private double distanceHorizontal(Vec3d a, Vec3d b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.z - b.z, 2));
    }

    private Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }

    private void sendPosition(Vec3d pos) {
        mc.getNetworkHandler().sendPacket(new PlayerMoveC2SPacket.Full(
            pos.x, pos.y, pos.z,
            mc.player.getYaw(), mc.player.getPitch(),
            mc.player.isOnGround(), true
        ));
    }

    private void simulateClick(BlockHitResult hit) {
        mc.getNetworkHandler().sendPacket(new PlayerInteractBlockC2SPacket(
            Hand.MAIN_HAND, hit, 0
        ));
        mc.player.swingHand(Hand.MAIN_HAND);
    }
}
