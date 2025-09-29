package com.stash.hunt.modules;

import com.stash.hunt.Addon;
import com.stash.hunt.utils.FlightManager;
import com.stash.hunt.utils.KeyHold;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.AutoEXP;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

public class AutoElytraRepair extends Module {
    private final SettingGroup sgGeneral = this.settings.getDefaultGroup();

    private final Setting<Integer> repairThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("repair-threshold")
        .description("Durability threshold to trigger repair (remaining durability).")
        .defaultValue(50)
        .min(1)
        .sliderMax(100)
        .build()
    );

    private final Setting<Integer> repairTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("repair-timeout")
        .description("Maximum time to spend repairing in seconds.")
        .defaultValue(300)
        .min(60)
        .sliderMax(600)
        .build()
    );

    private final Setting<Boolean> debugMode = sgGeneral.add(new BoolSetting.Builder()
        .name("debug-mode")
        .description("Enable debug logging for troubleshooting.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> stationaryTicksThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("stationary-ticks-threshold")
        .description("How many ticks the player must be stationary before starting the repair.")
        .defaultValue(20)
        .min(0)
        .sliderMax(100)
        .build()
    );

    private RepairState currentState = RepairState.MONITORING;
    private long repairStartTime = 0;
    private int timer = 0;
    private boolean justFinishedRepairing = false;

    private Vec3d lastPlayerPosition = null;
    private int stationaryTicks = 0;

    private enum RepairState {
        MONITORING,
        WAITING_FOR_STOP,
        SCAFFOLDING,
        REPAIRING,
        RESUMING_FLIGHT,
        EMERGENCY_DISCONNECT
    }

    public AutoElytraRepair() {
        super(Addon.CATEGORY, "auto-elytra-repair", "Automatically repairs elytras when they get low on durability.");
    }

    @Override
    public void onActivate() {
        currentState = RepairState.MONITORING;
        resetRepairState();
        debugLog("AutoElytraRepair activated");
    }

    @Override
    public void onDeactivate() {
        if (currentState != RepairState.MONITORING) {
            resumeNormalOperation();
        }
        debugLog("AutoElytraRepair deactivated");
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) {
            return;
        }

        switch (currentState) {
            case MONITORING:
                handleMonitoring();
                break;
            case WAITING_FOR_STOP:
                handleWaitingForStop();
                break;
            case SCAFFOLDING:
                handleScaffolding();
                break;
            case REPAIRING:
                handleRepairing();
                break;
            case RESUMING_FLIGHT:
                handleResumingFlight();
                break;
            case EMERGENCY_DISCONNECT:
                handleEmergencyDisconnect();
                break;
        }
    }

    private void handleMonitoring() {
        if (!FlightManager.isFlightActive()) return;
        ItemStack chestSlot = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestSlot.getItem() != Items.ELYTRA) return;

        if (needsRepair(chestSlot)) {
            initiateRepairSequence();
        }
    }

    private void handleWaitingForStop() {
        Vec3d currentPos = mc.player.getPos();
        if (lastPlayerPosition != null) {
            double distance = currentPos.distanceTo(lastPlayerPosition);
            if (distance > 0.1) {
                stationaryTicks = 0;
                lastPlayerPosition = currentPos;
            } else {
                stationaryTicks++;
            }
        } else {
            lastPlayerPosition = currentPos;
        }

        if (stationaryTicks >= stationaryTicksThreshold.get()) {
            info("Player has stopped moving. Proceeding with repair sequence.");
            currentState = RepairState.SCAFFOLDING;
        }
    }

    private void handleScaffolding() {
        BlockPos targetBlock = mc.player.getBlockPos().down();
        if (mc.world.getBlockState(targetBlock).isAir()) {
            info("No block below player, using Baritone to place one.");
            String command = String.format("build stone %d %d %d", targetBlock.getX(), targetBlock.getY(), targetBlock.getZ());
            baritone.api.BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(command);
        }

        // Give Baritone a moment to place the block
        if (timer++ > 40 && !mc.world.getBlockState(targetBlock).isAir()) {
            info("Block is present. Starting repair.");
            currentState = RepairState.REPAIRING;
            repairStartTime = System.currentTimeMillis();
            timer = 0;
        } else if (timer > 100) { // Timeout after 5 seconds
            error("Baritone failed to place block in time. Aborting repair.");
            currentState = RepairState.EMERGENCY_DISCONNECT;
            timer = 0;
        }
    }

    private void handleRepairing() {
        if (System.currentTimeMillis() - repairStartTime > repairTimeout.get() * 1000L) {
            warning("Repair timeout reached. Resuming flight.");
            currentState = RepairState.RESUMING_FLIGHT;
            return;
        }

        ItemStack chestElytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (chestElytra.getItem() != Items.ELYTRA) {
            warning("No elytra equipped during repair.");
            currentState = RepairState.RESUMING_FLIGHT;
            return;
        }

        if (chestElytra.getDamage() == 0) {
            info("Equipped elytra repaired. Resuming flight.");
            currentState = RepairState.RESUMING_FLIGHT;
        }
    }

    private void handleResumingFlight() {
        resumeNormalOperation();
        KeyHold.hold(mc.options.jumpKey, 10, null); // Hold jump for 10 ticks to take off
        FlightManager.resume();
        info("Resuming flight.");
        justFinishedRepairing = true;
        resetRepairState();
        currentState = RepairState.MONITORING;
    }

    private void handleEmergencyDisconnect() {
        resumeNormalOperation();
        error("Emergency disconnect initiated...");
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().getConnection().disconnect(net.minecraft.text.Text.of("Emergency disconnect: Elytra repair failed"));
        }
        this.toggle();
    }

    private void initiateRepairSequence() {
        FlightManager.pause();
        currentState = RepairState.WAITING_FOR_STOP;
        info("Elytra durability low. Pausing flight for repair sequence.");
        lastPlayerPosition = mc.player.getPos();
        stationaryTicks = 0;
    }

    private void resetRepairState() {
        repairStartTime = 0;
        timer = 0;
        justFinishedRepairing = false;
        lastPlayerPosition = null;
        stationaryTicks = 0;
    }

    private void resumeNormalOperation() {
        AutoEXP autoExp = Modules.get().get(AutoEXP.class);
        if (autoExp != null && !autoExp.isActive()) {
            autoExp.toggle();
        }
        debugLog("AutoElytraRepair cleanup finished.");
    }

    public boolean isRepairing() {
        return currentState != RepairState.MONITORING;
    }

    public boolean justFinishedRepair() {
        if (justFinishedRepairing) {
            justFinishedRepairing = false;
            return true;
        }
        return false;
    }

    private boolean needsRepair(ItemStack elytra) {
        if (elytra.getItem() != Items.ELYTRA) return false;
        return (elytra.getMaxDamage() - elytra.getDamage()) <= repairThreshold.get();
    }

    private void debugLog(String message) {
        if (debugMode.get()) {
            info("[DEBUG] " + message);
        }
    }
}