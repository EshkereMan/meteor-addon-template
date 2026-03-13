/*
 * Модуль для спуфа позиции при доставке с плавным перемещением (по шагам).
 * Работает ТОЛЬКО вместе с официальным Freecam из Meteor.
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

    private final Setting<Integer> increment = sgDelivery.add(new IntSetting.Builder()
        .name("step-size")
        .description("Размер шага по горизонтали (блоки)")
        .defaultValue(8)
        .min(4)
        .max(15)
        .sliderRange(4, 15)
        .build()
    );

    private final Setting<Integer> stepDelayTicks = sgDelivery.add(new IntSetting.Builder()
        .name("step-delay")
        .description("Задержка между шагами (в тиках ~50 мс)")
        .defaultValue(3)   // ~150 мс
        .min(1)
        .max(10)
        .sliderRange(1, 10)
        .build()
    );

    private final Setting<Integer> returnDelay = sgDelivery.add(new IntSetting.Builder()
        .name("return-delay")
        .description("Пауза после клика перед возвратом (мс)")
        .defaultValue(250)
        .min(100)
        .max(800)
        .sliderRange(100, 800)
        .build()
    );

    private final Setting<Integer> chunkWaitTicks = sgDelivery.add(new IntSetting.Builder()
        .name("chunk-wait")
        .description("Макс. ожидание загрузки чанка (тики)")
        .defaultValue(8)
        .min(4)
        .max(20)
        .build()
    );

    // Состояние
    private boolean isSpoofing = false;
    private boolean isReturning = false;
    private Vec3d startPos;
    private Vec3d targetPos;
    private Vec3d originalPos;
    private BlockHitResult savedHit;
    private int currentStep;
    private int totalSteps;
    private int tickCounter;
    private int chunkWaitCounter;
    private long returnStartTime;
    private Vec3d lastSentPos;
    private int stuckCounter;

    public DeliverySpoofer() {
        super(AddonTemplate.CATEGORY, "delivery-spoofer", "Плавный спуф позиции для доставки (с Freecam)");
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
        startPos = null;
        targetPos = null;
        originalPos = null;
        savedHit = null;
        currentStep = 0;
        totalSteps = 0;
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

        if (mc.world == null || mc.player == null || mc.getNetworkHandler() == null || mc.crosshairTarget == null) return;
        if (mc.crosshairTarget.getType() != HitResult.Type.BLOCK) return;

        BlockHitResult hit = (BlockHitResult) mc.crosshairTarget;
        BlockPos pos = hit.getBlockPos();

        if (!(mc.world.getBlockEntity(pos) instanceof SignBlockEntity)) return;

        startSmoothSpoof(hit);
        event.cancel();
    }

    private void startSmoothSpoof(BlockHitResult hit) {
        if (isSpoofing) return;

        BlockPos signPos = hit.getBlockPos();
        info("§aСтарт плавного спуфа к табличке " + signPos.toShortString());

        originalPos = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());
        startPos = originalPos;
        targetPos = new Vec3d(signPos.getX() + 0.5, signPos.getY() + 1.05, signPos.getZ() + 0.5);
        savedHit = hit;

        double horizDist = Math.sqrt(Math.pow(targetPos.x - startPos.x, 2) + Math.pow(targetPos.z - startPos.z, 2));
        totalSteps = (int) Math.ceil(horizDist / increment.get()) + 2; // + запас на вертикаль
        currentStep = 0;
        isSpoofing = true;
        isReturning = false;
        tickCounter = 0;
        chunkWaitCounter = 0;
        stuckCounter = 0;
        lastSentPos = startPos;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isSpoofing || mc.player == null || mc.getNetworkHandler() == null) return;

        tickCounter++;

        if (tickCounter < stepDelayTicks.get()) return;
        tickCounter = 0;

        Vec3d current = new Vec3d(mc.player.getX(), mc.player.getY(), mc.player.getZ());

        if (!isReturning) {
            // → К табличке
            if (currentStep >= totalSteps || distanceHorizontal(current, targetPos) < 1.5) {
                info("§aДостигли — клик по табличке");
                simulateClick(savedHit);
                returnStartTime = System.currentTimeMillis() + returnDelay.get();
                isReturning = true;
                startPos = targetPos;
                targetPos = originalPos;
                currentStep = 0;
                totalSteps = (int) Math.ceil(startPos.distanceTo(targetPos) / increment.get()) + 2;
                return;
            }

            Vec3d next = calculateNextPos(current, targetPos);
            if (!isChunkLoadedForPos(next)) {
                chunkWaitCounter++;
                if (chunkWaitCounter > chunkWaitTicks.get()) {
                    info("§eЧанк не загружен слишком долго — форсируем шаг");
                    chunkWaitCounter = 0;
                } else {
                    return; // ждём ещё
                }
            }

            sendPosition(next);
            lastSentPos = next;
            currentStep++;

            if (currentStep % 5 == 0) {
                double prog = (double) currentStep / totalSteps * 100;
                info(String.format("§7Прогресс: ", prog));
            }

            // Проверка застревания
            if (lastSentPos != null && current.distanceTo(lastSentPos) < 0.3) {
                stuckCounter++;
                if (stuckCounter >= 4) {
                    info("§cЗастревание — форсируем телепорт ближе");
                    Vec3d force = lerp(current, targetPos, 0.4);
                    sendPosition(force);
                    stuckCounter = 0;
                }
            } else {
                stuckCounter = 0;
            }

        } else {
            // ← Возврат
            if (System.currentTimeMillis() < returnStartTime) return; // ещё пауза

            if (currentStep >= totalSteps || distanceHorizontal(current, originalPos) < 1.5) {
                info("§aВозврат завершён");
                reset();
                return;
            }

            Vec3d next = calculateNextPos(current, originalPos);
            if (!isChunkLoadedForPos(next)) return;

            sendPosition(next);
            currentStep++;
        }
    }

    private Vec3d calculateNextPos(Vec3d curr, Vec3d targ) {
        Vec3d dir = targ.subtract(curr);
        double len = dir.length();
        if (len < increment.get() * 1.2) return targ;

        Vec3d norm = dir.normalize();
        double stepSize = Math.min(increment.get(), len);

        // Вертикаль отдельно, если большая разница
        double dy = targ.y - curr.y;
        double vertStep = Math.signum(dy) * Math.min(increment.get() * 0.7, Math.abs(dy));

        return new Vec3d(
            curr.x + norm.x * stepSize,
            curr.y + vertStep,
            curr.z + norm.z * stepSize
        );
    }

    private boolean isChunkLoadedForPos(Vec3d pos) {
        ChunkPos chunk = new ChunkPos(BlockPos.ofFloored(pos));
        return mc.world != null && mc.world.isChunkLoaded(chunk.x, chunk.z);
    }

    private double distanceHorizontal(Vec3d a, Vec3d b) {
        return Math.sqrt(Math.pow(a.x - b.x, 2) + Math.pow(a.z - b.z, 2));
    }

    private void sendPosition(Vec3d pos) {
        PlayerMoveC2SPacket.Full packet = new PlayerMoveC2SPacket.Full(
            pos.x, pos.y, pos.z,
            mc.player.getYaw(),
            mc.player.getPitch(),
            mc.player.isOnGround(),
            true
        );
        mc.getNetworkHandler().sendPacket(packet);
    }

    private void simulateClick(BlockHitResult hit) {
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(
            Hand.MAIN_HAND,
            hit,
            0
        );
        mc.getNetworkHandler().sendPacket(packet);
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    private Vec3d lerp(Vec3d a, Vec3d b, double t) {
        return new Vec3d(
            a.x + (b.x - a.x) * t,
            a.y + (b.y - a.y) * t,
            a.z + (b.z - a.z) * t
        );
    }
}
