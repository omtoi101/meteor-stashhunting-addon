package com.stash.hunt.modules;

import baritone.api.BaritoneAPI;
import baritone.api.pathing.goals.GoalXZ;
import com.stash.hunt.Addon;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.stash.hunt.Utils;
import com.stash.hunt.utils.FlightManager;
import com.stash.hunt.utils.IFlightModule;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.text.Text;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.item.Items;
import xaeroplus.XaeroPlus;
import xaeroplus.event.ChunkDataEvent;
import xaeroplus.module.ModuleManager;
import xaeroplus.module.impl.OldChunks;
import xaeroplus.module.impl.PaletteNewChunks;

import java.time.Duration;
import java.util.ArrayDeque;

import static com.stash.hunt.Utils.positionInDirection;
import static com.stash.hunt.Utils.sendWebhook;

import com.google.common.reflect.TypeToken;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.WindowScreen;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WTable;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.gui.widgets.pressable.WMinus;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BarrelBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.DispenserBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.block.entity.HopperBlockEntity;
import net.minecraft.block.entity.ShulkerBoxBlockEntity;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;
import xaero.map.mods.SupportMods;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;

public class TrailFollower extends Module implements IFlightModule {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgStashFinder = settings.createGroup("Stash Finder");
    private boolean paused = false;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    public List<Chunk> chunks = new ArrayList<>();

    @Override
    public void pauseFlight() {
        this.paused = true;
        if (BaritoneAPI.getProvider() != null && BaritoneAPI.getProvider().getPrimaryBaritone().getPathingBehavior().isPathing()) {
            BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("cancel");
        }
    }

    @Override
    public void resumeFlight() {
        this.paused = false;
    }

    // TODO: Set this automatically either by looking at the rate of chunk loads or by using yaw instead of block pos so size doesnt negatively effect result
    public final Setting<Integer> maxTrailLength = sgGeneral.add(new IntSetting.Builder()
        .name("Max Trail Length")
        .description("The number of trail points to keep for the average. Adjust to change how quickly the average will change. More does not necessarily equal better because if the list is too long it will contain chunks behind you.")
        .defaultValue(20)
        .sliderRange(1, 100)
        .build()
    );

    public final Setting<Integer> chunksBeforeStarting = sgGeneral.add(new IntSetting.Builder()
        .name("Chunks Before Starting")
        .description("Useful for afking looking for a trail. The amount of chunks before it gets detected as a trail.")
        .defaultValue(10)
        .sliderRange(1, 50)
        .build()
    );

    public final Setting<Integer> chunkConsiderationWindow = sgGeneral.add(new IntSetting.Builder()
        .name("Chunk Timeframe")
        .description("The amount of time in seconds that the chunks must be found in before starting.")
        .defaultValue(5)
        .sliderRange(1, 20)
        .build()
    );

    public final Setting<TrailEndBehavior> trailEndBehavior = sgGeneral.add(new EnumSetting.Builder<TrailEndBehavior>()
        .name("Trail End Behavior")
        .description("What to do when the trail ends.")
        .defaultValue(TrailEndBehavior.DISABLE)
        .build()
    );

    public final Setting<Double> trailEndYaw = sgGeneral.add(new DoubleSetting.Builder()
        .name("Trail End Yaw")
        .description("The direction to go after the trail is abandoned.")
        .defaultValue(0.0)
        .sliderRange(0.0, 359.9)
        .visible(() -> trailEndBehavior.get() == TrailEndBehavior.FLY_TOWARDS_YAW)
        .build()
    );

    // changed to an enum dropdown for fly selection
    public enum OverworldFlightMode {
        VANILLA,
        PITCH40,
        OTHER
    }

    public enum NetherPathMode {
        AVERAGE,
        OTHER
    }

    public final Setting<OverworldFlightMode> overworldFlightMode = sgGeneral.add(new EnumSetting.Builder<OverworldFlightMode>()
        .name("Overworld Flight Mode")
        .description("Choose how TrailFollower flies in Overworld. If other is selected then nothing will be automatically enabled, instead just your yaw will be changed to point towards the trail.")
        .defaultValue(OverworldFlightMode.PITCH40)
        .build()
    );

    public final Setting<NetherPathMode> netherPathMode = sgGeneral.add(new EnumSetting.Builder<NetherPathMode>()
        .name("Nether Path Mode")
        .description("Choose how TrailFollower does baritone pathing in Nether. If other is selected then nothing will be automatically enabled, instead just your yaw will be changed to point towards the trail.")
        .defaultValue(NetherPathMode.AVERAGE)
        .build()
    );

    public final Setting<Boolean> pitch40Firework = sgGeneral.add(new BoolSetting.Builder()
        .name("Auto Firework")
        .description("Uses a firework automatically if your velocity is too low.")
        .defaultValue(true)
        .visible(() -> overworldFlightMode.get() == OverworldFlightMode.PITCH40)
        .build()
    );

    public final Setting<Double> rotateScaling = sgGeneral.add(new DoubleSetting.Builder()
        .name("Rotate Scaling")
        .description("Scaling of how fast the yaw changes. 1 = instant, 0 = doesn't change")
        .defaultValue(0.1)
        .sliderRange(0.0, 1.0)
        .build()
    );

    public final Setting<Boolean> oppositeDimension = sgGeneral.add(new BoolSetting.Builder()
        .name("Opposite Dimension")
        .description("Follows trails from the opposite dimension (Requires that you've already loaded the other dimension with XP).")
        .defaultValue(false)
        .build()
    );

    // Stash Finder Settings
    private final Setting<List<BlockEntityType<?>>> storageBlocks = sgStashFinder.add(new StorageBlockListSetting.Builder()
        .name("storage-blocks")
        .description("Select the storage blocks to search for.")
        .defaultValue(StorageBlockListSetting.STORAGE_BLOCKS)
        .build()
    );

    private final Setting<Integer> minimumStorageCount = sgStashFinder.add(new IntSetting.Builder()
        .name("minimum-storage-count")
        .description("The minimum amount of storage blocks in a chunk to record the chunk.")
        .defaultValue(4)
        .min(1)
        .sliderMin(1)
        .build()
    );

    private final Setting<Boolean> shulkerInstantHit = sgStashFinder.add(new BoolSetting.Builder()
        .name("shulker-instant-hit")
        .description("If a single shulker counts as a stash.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreTrialChambers = sgStashFinder.add(new BoolSetting.Builder()
        .name("ignore-trial-chambers")
        .description("Attempts to ignore trial chambers, but may cause false negatives if someone made their base to look like a trial chamber.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumDistance = sgStashFinder.add(new IntSetting.Builder()
        .name("minimum-distance")
        .description("The minimum distance you must be from spawn to record a certain chunk.")
        .defaultValue(0)
        .min(0)
        .sliderMax(10000)
        .build()
    );

    private final Setting<Boolean> saveToWaypoints = sgStashFinder.add(new BoolSetting.Builder()
        .name("save-to-waypoints")
        .description("Creates xaeros minimap waypoints for stash finds.")
        .defaultValue(false)
        .onChanged(this::waypointSettingChanged)
        .build()
    );

    private final Setting<Boolean> sendNotifications = sgStashFinder.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Sends Minecraft notifications when new stashes are found.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Mode> notificationMode = sgStashFinder.add(new EnumSetting.Builder<Mode>()
        .name("notification-mode")
        .description("The mode to use for notifications.")
        .defaultValue(Mode.Both)
        .visible(sendNotifications::get)
        .build()
    );

    private final Setting<Boolean> sendStashWebhook = sgStashFinder.add(new BoolSetting.Builder()
        .name("Send Stash Webhook")
        .description("Sends a webhook when a stash is found.")
        .defaultValue(false)
        .build()
    );

    public final Setting<String> stashWebhookLink = sgStashFinder.add(new StringSetting.Builder()
        .name("Stash Webhook Link")
        .description("A discord webhook link for stash notifications.")
        .defaultValue("")
        .visible(sendStashWebhook::get)
        .build()
    );

    public final Setting<Boolean> advancedStashLogging = sgStashFinder.add(new BoolSetting.Builder()
        .name("advanced-stash-logging")
        .description("Will log more information, including the amount of each container found.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Boolean> pingForStash = sgStashFinder.add(new BoolSetting.Builder()
        .name("Ping For Stash")
        .description("Pings you for stash finder messages")
        .defaultValue(false)
        .visible(sendStashWebhook::get)
        .build()
    );

    public final Setting<String> stashDiscordId = sgStashFinder.add(new StringSetting.Builder()
        .name("Stash Discord ID")
        .description("Your discord ID for stash pings")
        .defaultValue("")
        .visible(() -> sendStashWebhook.get() && pingForStash.get())
        .build()
    );

    public final Setting<Boolean> autoElytra = sgGeneral.add(new BoolSetting.Builder()
        .name("[Baritone] Auto Start Baritone Elytra")
        .description("Starts baritone elytra for you.")
        .defaultValue(false)
        .build()
    );

    private final SettingGroup sgAdvanced = settings.createGroup("Advanced", false);

    public final Setting<Double> pathDistance = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Path Distance")
        .description("The distance to add trail positions in the direction the player is facing. (Ignored when following overworld from nether)")
        .defaultValue(500)
        .sliderRange(100, 2000)
        .onChanged(value -> pathDistanceActual = value)
        .build()
    );

    public final Setting<FollowMode> flightMethod = sgAdvanced.add(new EnumSetting.Builder<FollowMode>()
        .name("Flight Method")
        .description("Decided how the goals will be used. Leave this on AUTO unless you want to use yaw lock in the nether for example.")
        .defaultValue(FollowMode.AUTO)
        .build()
    );

    public final Setting<Double> startDirectionWeighting = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Start Direction Weight")
        .description("The weighting of the direction the player is facing when starting the trail. 0 for no weighting (not recommended) 1 for max weighting (will take a bit for direction to change)")
        .defaultValue(0.5)
        .min(0)
        .sliderMax(1)
        .build()
    );

    public final Setting<DirectionWeighting> directionWeighting = sgAdvanced.add(new EnumSetting.Builder<DirectionWeighting>()
        .name("Direction Weighting")
        .description("How the chunks found should be weighted. Useful for path splits. Left will weight chunks to the left of the player higher, right will weigh chunks to the right higher, and none will be in the middle/random. ")
        .defaultValue(DirectionWeighting.NONE)
        .build()
    );

    public final Setting<Integer> directionWeightingMultiplier = sgAdvanced.add(new IntSetting.Builder()
        .name("Direction Weighting Multiplier")
        .description("The multiplier for how much weight should be given to chunks in the direction specified. Values are capped to be in the range [2, maxTrailLength].")
        .defaultValue(2)
        .min(2)
        .sliderMax(10)
        .visible(() -> directionWeighting.get() != DirectionWeighting.NONE)
        .build()
    );

    public final Setting<Boolean> only112 = sgAdvanced.add(new BoolSetting.Builder()
        .name("Follow Only 1.12")
        .description("Will only follow 1.12 chunks and will ignore other ones.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> chunkFoundTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("Chunk Found Timeout (S)")
        .description("The amount of seconds without a chunk found to trigger circling.")
        .defaultValue(5)
        .min(1)
        .sliderMax(10)
        .build()
    );

    public final Setting<Double> circlingDegPerTick = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Circling Degrees Per Tick")
        .description("The amount of degrees to change per tick while circling.")
        .defaultValue(2.0)
        .min(1.0)
        .sliderMax(20.0)
        .build()
    );

    private final Setting<Integer> trailTimeout = sgAdvanced.add(new IntSetting.Builder()
        .name("Trail Timeout (S)")
        .description("The amount of seconds without a chunk found to stop following the trail.")
        .defaultValue(30)
        .min(10)
        .sliderMax(60)
        .build()
    );
    // added trail deviation slider now that baritone is locked to trail pathing
    public final Setting<Double> maxTrailDeviation = sgAdvanced.add(new DoubleSetting.Builder()
        .name("Max Trail Deviation")
        .description("Maximum allowed angle (in degrees) from the original trail direction. Helps avoid switching to intersecting trails.")
        .defaultValue(180.0)
        .min(1.0)
        .sliderMax(270.0)
        .build()
    );

    public final Setting<Integer> chunkCacheLength = sgAdvanced.add(new IntSetting.Builder()
        .name("Chunk Cache Length")
        .description("The amount of chunks to keep in the cache. (Won't be applied until deactivating)")
        .defaultValue(100_000)
        .sliderRange(0, 10_000_000)
        .build()
    );

    public final Setting<String> webhookLink = sgGeneral.add(new StringSetting.Builder()
        .name("Webhook Link")
        .description("Will send all updates to the webhook link. Leave blank to disable.")
        .defaultValue("")
        .build()
    );

    public final Setting<Integer> baritoneUpdateTicks = sgAdvanced.add(new IntSetting.Builder()
        .name("[Baritone] Baritone Path Update Ticks")
        .description("The amount of ticks between updates to the baritone goal. Low values may cause high instability.")
        .defaultValue(5 * 20) // 5 seconds
        .sliderRange(20, 30 * 20)
        .build()
    );

    public final Setting<Boolean> debug = sgAdvanced.add(new BoolSetting.Builder()
        .name("Debug")
        .description("Debug mode.")
        .defaultValue(false)
        .build()
    );

    // TODO: Auto disconnect at certain chunk load speed

    private boolean oldAutoFireworkValue;

    private FollowMode followMode;

    private boolean followingTrail = false;

    private ArrayDeque<Vec3d> trail = new ArrayDeque<>();
    private ArrayDeque<Vec3d> possibleTrail = new ArrayDeque<>();

    private long lastFoundTrailTime;
    private long lastFoundPossibleTrailTime;

    private double pathDistanceActual = pathDistance.get();

    private Cache<Long, Byte> seenChunksCache = Caffeine.newBuilder()
        .maximumSize(chunkCacheLength.get())
        .expireAfterWrite(Duration.ofMinutes(5))
        .build();

    // Credit to WarriorLost: https://github.com/WarriorLost/meteor-client/tree/master

    public TrailFollower()
    {
        super(Addon.CATEGORY, "TrailFollower", "Automatically follows trails in all dimensions.");
    }

    void resetTrail()
    {
        baritoneSetGoalTicks = 0;
        followingTrail = false;
        trail = new ArrayDeque<>();
        possibleTrail = new ArrayDeque<>();
    }

    @Override
    public void onActivate()
    {
        FlightManager.register(this);
        resetTrail();
        XaeroPlus.EVENT_BUS.register(this);
        load();
        if (mc.player != null && mc.world != null)
        {
            RegistryKey<World> currentDimension = mc.world.getRegistryKey();
            if (oppositeDimension.get())
            {
                if (currentDimension.equals(World.END))
                {
                    info("There is no opposite dimension to the end. Disabling TrailFollower");
                    this.toggle();
                    return;
                }
                else if (currentDimension.equals(World.NETHER))
                {
                    info("Following overworld trails from the nether is not supported yet, sorry. Disabling TrailFollower");
                    this.toggle();
                    return;
                }
            }
            if (flightMethod.get() != FollowMode.AUTO)
            {
                followMode = flightMethod.get();
            }
            else
            {
                if (!currentDimension.equals(World.NETHER))
                {
                    followMode = FollowMode.YAWLOCK;
                    info("You are in the overworld or end, basic yaw mode will be used.");
                }
                else
                {
                    try {
                        Class.forName("baritone.api.BaritoneAPI");
                        followMode = FollowMode.BARITONE;
                        info("You are in the nether, baritone mode will be used.");
                    } catch (ClassNotFoundException e) {
                        info("Baritone is required to trail follow in the nether. Disabling TrailFollower");
                        this.toggle();
                        return;
                    }
                }
            }

            if (followMode == FollowMode.YAWLOCK && !mc.world.getRegistryKey().equals(World.NETHER)) {
                if (overworldFlightMode.get() == OverworldFlightMode.PITCH40) {
                    Class<? extends Module> pitch40Util = Pitch40Util.class;
                    Module pitch40UtilModule = Modules.get().get(pitch40Util);
                    if (!pitch40UtilModule.isActive()) {
                        pitch40UtilModule.toggle();
                        if (pitch40Firework.get()) {
                            Setting<Boolean> setting = ((Setting<Boolean>) pitch40UtilModule.settings.get("Auto Firework"));
                            info("Auto Firework enabled, if you want to change the velocity threshold or the firework cooldown check the settings under Pitch40Util.");
                            oldAutoFireworkValue = setting.get();
                            setting.set(true);
                        }
                    }
                } else if (overworldFlightMode.get() == OverworldFlightMode.VANILLA) {
                    AFKVanillaFly afkVanillaFly = Modules.get().get(AFKVanillaFly.class);
                    if (!afkVanillaFly.isActive()) {
                        afkVanillaFly.toggle();
                    }
                }
            }
            // set original pos to pathDistance blocks in the direction the player is facing
            Vec3d offset = (new Vec3d(Math.sin(-mc.player.getYaw() * Math.PI / 180), 0, Math.cos(-mc.player.getYaw() * Math.PI / 180)).normalize()).multiply(pathDistance.get());
            Vec3d targetPos = mc.player.getPos().add(offset);
            for (int i = 0; i < (maxTrailLength.get() * startDirectionWeighting.get()); i++)
            {
                trail.add(targetPos);
            }
            targetYaw = getActualYaw(mc.player.getYaw());
        }
        else
        {
            this.toggle();
        }
    }

    @Override
    public void onDeactivate()
    {
        FlightManager.unregister(this);
        // do this at the end to free memory
        seenChunksCache = Caffeine.newBuilder()
            .maximumSize(chunkCacheLength.get())
            .expireAfterWrite(Duration.ofMinutes(5))
            .build();
        XaeroPlus.EVENT_BUS.unregister(this);
        trail.clear();
        // If follow mode was never set due to baritone not being present, etc.
        if (followMode == null) return;
        switch (followMode)
        {
            case BARITONE:
            {
                BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("cancel");
                break;
            }
            case YAWLOCK: {
                if (mc.world == null || mc.world.getRegistryKey().equals(World.NETHER)) return;
                if (overworldFlightMode.get() == OverworldFlightMode.VANILLA) {
                    AFKVanillaFly afkVanillaFly = Modules.get().get(AFKVanillaFly.class);
                    if (afkVanillaFly != null) {
                        afkVanillaFly.resetYLock();
                        if (afkVanillaFly.isActive()) afkVanillaFly.toggle();
                    }
                } else if (overworldFlightMode.get() == OverworldFlightMode.PITCH40) {
                    Class<? extends Module> pitch40Util = Pitch40Util.class;
                    Module pitch40UtilModule = Modules.get().get(pitch40Util);
                    if (pitch40UtilModule.isActive()) {
                        pitch40UtilModule.toggle();
                    }
                    ((Setting<Boolean>) pitch40UtilModule.settings.get("Auto Firework")).set(oldAutoFireworkValue);
                }
                break;
            }
        }
    }

    private double targetYaw;

    private int baritoneSetGoalTicks = 0;

    private void circle()
    {
        if (followMode == FollowMode.BARITONE) return;
        mc.player.setYaw(getActualYaw((float) (mc.player.getYaw() + circlingDegPerTick.get())));
        if (mc.player.age % 100 == 0)
        {
            log("Circling to look for new chunks, abandoning trail in " + (trailTimeout.get() * 1000 - (System.currentTimeMillis() - lastFoundTrailTime)) / 1000 + " seconds.");
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        if (mc.player == null || mc.world == null || paused) return;
        if (followingTrail && System.currentTimeMillis() - lastFoundTrailTime > trailTimeout.get() * 1000)
        {
            resetTrail();
            log("Trail timed out, stopping.");
            switch (trailEndBehavior.get())
            {
                case DISABLE:
                {
                    this.toggle();
                    break;
                }
                case FLY_TOWARDS_YAW:
                {
                    targetYaw = trailEndYaw.get();
                    break;
                }
                case DISCONNECT:
                {
                    mc.player.networkHandler.onDisconnect(new DisconnectS2CPacket(Text.literal("[TrailFollower] Trail timed out.")));
                    break;
                }
            }
        }
        if (followingTrail && System.currentTimeMillis() - lastFoundTrailTime > chunkFoundTimeout.get() * 1000)
        {
            circle();
            return;
        }
        switch (followMode)
        {
            case BARITONE:
            {
                if (baritoneSetGoalTicks > 0)
                {
                    baritoneSetGoalTicks--;
                }
                else if (baritoneSetGoalTicks == 0)
                {
                    // if following overworld from nether we need to wait to set the goal until we are close to the current goal
                    // make sure targetPos is on an actual chunk
//                    if (mc.world.getRegistryKey().equals(World.NETHER) && oppositeDimension.get())
//                    {
//                        if (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() != null
//                            && !BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination().isWithinDistance(mc.player.getPos(), 200))
//                        {
//                            return;
//                        }
//                        else
//                        {
//                            boolean chunkFound = false;
//                            for (int i = 1000; i >= 0; i--)
//                            {
//                                Vec3d nextPosition = positionInDirection(mc.player.getPos().multiply(8), targetYaw, 16 * i);
//                                ChunkPos nextChunkPosition = new ChunkPos(new BlockPos((int)nextPosition.x, 0, (int)nextPosition.z));
//                                if (isValidChunk(nextChunkPosition, World.OVERWORLD))
//                                {
//                                    pathDistanceActual = (double) (16 * i) / 8;
//                                    chunkFound = true;
//                                    break;
//                                }
//                            }
//                            if (!chunkFound) return;
//                        }
//                    }
                    //instead of flying to a calculated offset from the player using pathDistanceActual, will directly set the last trail chunk detected
                    baritoneSetGoalTicks = baritoneUpdateTicks.get();
                    if (mc.world.getRegistryKey().equals(World.NETHER)) {

                        if (!trail.isEmpty()) {
                            Vec3d baritoneTarget;
                            if (netherPathMode.get() == NetherPathMode.AVERAGE) {
                                Vec3d averagePos = calculateAveragePosition(trail);
                                Vec3d directionVec = averagePos.subtract(mc.player.getPos()).normalize();
                                Vec3d predictedPos = mc.player.getPos().add(directionVec.multiply(10));
                                targetYaw = Rotations.getYaw(predictedPos);
                                baritoneTarget = positionInDirection(mc.player.getPos(), targetYaw, pathDistanceActual);
                            } else {
                                Vec3d lastPos = trail.getLast();
                                baritoneTarget = lastPos;
                            }

                            BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess()
                                .setGoalAndPath(new GoalXZ((int) baritoneTarget.x, (int) baritoneTarget.z));
                        }
                    } else {
                        // use average path for overworld
                        Vec3d targetPos = positionInDirection(mc.player.getPos(), targetYaw, pathDistanceActual);
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(new GoalXZ((int) targetPos.x, (int) targetPos.z));

                        targetYaw = Rotations.getYaw(targetPos); // smooth rotation target
                    }
                    if (autoElytra.get() && (BaritoneAPI.getProvider().getPrimaryBaritone().getElytraProcess().currentDestination() == null))
                    {
                        // TODO: Fix this
                        log("The auto elytra mode is broken right now. If it's not working just turn it off and manually use #elytra to start.");
                        BaritoneAPI.getSettings().elytraTermsAccepted.value = true;
                        BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute("elytra");
                    }
                }
                break;
            }
            case YAWLOCK: {
                mc.player.setYaw(Utils.smoothRotation(getActualYaw(mc.player.getYaw()), targetYaw, rotateScaling.get()));
                break;
            }
        }

    }

    Vec3d posDebug;

    @EventHandler
    private void onRender(Render3DEvent event)
    {
        if (!debug.get()) return;
        Vec3d targetPos = positionInDirection(mc.player.getPos(), targetYaw, 10);
        // target line
        event.renderer.line(mc.player.getX(), mc.player.getY(), mc.player.getZ(), targetPos.x, targetPos.y, targetPos.z, new Color(255, 0, 0));
        // chunk
        if (posDebug != null) event.renderer.line(mc.player.getX(), mc.player.getY(), mc.player.getZ(), posDebug.x, targetPos.y, posDebug.z, new Color(0, 0, 255));
    }

    @net.lenni0451.lambdaevents.EventHandler(priority = -1)
    public void onChunkData(ChunkDataEvent event)
    {
        if (event.seenChunk()) return;

        // Stash Finder Logic
        double chunkXAbs = Math.abs(event.chunk().getPos().x * 16);
        double chunkZAbs = Math.abs(event.chunk().getPos().z * 16);
        if (Math.sqrt(chunkXAbs * chunkXAbs + chunkZAbs * chunkZAbs) >= minimumDistance.get()) {
            scanChunkForStash(event);
        }

        // Trail Follower Logic
        RegistryKey<World> currentDimension = mc.world.getRegistryKey();
        WorldChunk chunk = event.chunk();
        ChunkPos chunkPos = chunk.getPos();
        long chunkLong = chunkPos.toLong();

        // if found in the cache then ignore the chunk
        if (seenChunksCache.getIfPresent(chunkLong) != null) return;

        ChunkPos chunkDelta = new ChunkPos(chunkPos.x - mc.player.getChunkPos().x, chunkPos.z - mc.player.getChunkPos().z);

        if (oppositeDimension.get())
        {
            if (currentDimension.equals(World.OVERWORLD))
            {
                chunkPos = new ChunkPos(mc.player.getChunkPos().x / 8 + chunkDelta.x, mc.player.getChunkPos().z / 8 + chunkDelta.z);
                currentDimension = World.NETHER;
            }
            else if (currentDimension.equals(World.NETHER))
            {
                chunkPos = new ChunkPos(mc.player.getChunkPos().x * 8 + chunkDelta.x, mc.player.getChunkPos().z * 8 + chunkDelta.z);
//                log("ChunkPos: " + chunkPos.x + ", " + chunkPos.z);
                currentDimension = World.OVERWORLD;
            }
        }
        // Check that the chunk is actually mapped, and that it is an old chunk
        if (!isValidChunk(chunkPos, currentDimension)) return;

        seenChunksCache.put(chunkLong, Byte.MAX_VALUE);

        // nether will get out of chunk render distance range of overworld. needs fix.
        // possible fix:
        // make sure baritone markers are on the trail, only look for new chunks when player is near the waypoint


        // use chunk.getPos() here instead of the dimension specific chunkPos because we have to path to blocks in our dimension
        Vec3d pos = chunk.getPos().getCenterAtY(0).toCenterPos();
        posDebug = pos;

        if (!followingTrail)
        {
            if (System.currentTimeMillis() - lastFoundPossibleTrailTime > chunkConsiderationWindow.get() * 1000)
            {
                possibleTrail.clear();
            }
            possibleTrail.add(pos);
            lastFoundPossibleTrailTime = System.currentTimeMillis();
            if (possibleTrail.size() > chunksBeforeStarting.get())
            {
                log("Trail found, starting to follow.");
                followingTrail = true;
                lastFoundTrailTime = System.currentTimeMillis();
                trail.addAll(possibleTrail);
                possibleTrail.clear();
            }
            return;
        }


        // add chunks to the list

        double chunkAngle = Rotations.getYaw(pos);
        double angleDiff = Utils.angleDifference(targetYaw, chunkAngle);
        // was not able to add this before, but now can successfully filter out most other trails using the most recent chunk for pathing
        if (followingTrail && Math.abs(angleDiff) > maxTrailDeviation.get())
        {
            return;
        }
        lastFoundTrailTime = System.currentTimeMillis();
        while(trail.size() >= maxTrailLength.get())
        {
            trail.pollFirst();
        }

        if (angleDiff > 0 && angleDiff < 90 && directionWeighting.get() == DirectionWeighting.LEFT)
        {
            // add extra chunks to increase the weighting
            // TODO: Maybe redo this to use a map of chunk pos to weights
            for (int i = 0; i < directionWeightingMultiplier.get() - 1; i++)
            {
                trail.pollFirst();
                trail.add(pos);
            }
            trail.add(pos);
        }
        else if (angleDiff < 0 && angleDiff > -90 && directionWeighting.get() == DirectionWeighting.RIGHT)
        {
            for (int i = 0; i < directionWeightingMultiplier.get() - 1; i++)
            {
                trail.pollFirst();
                trail.add(pos);
            }
            trail.add(pos);
        }
        else
        {
            trail.add(pos);
        }


        // instead of a calculated average coordinate, will use latest chunk added to trail
        // *fix for overworld smoothing
        if (!trail.isEmpty()) {
            if (followMode == FollowMode.YAWLOCK) {
                Vec3d averagePos = calculateAveragePosition(trail);
                Vec3d positionVec = averagePos.subtract(mc.player.getPos()).normalize();
                Vec3d targetPos = mc.player.getPos().add(positionVec.multiply(10));
                targetYaw = Rotations.getYaw(targetPos);
            } else {
                Vec3d lastTrailPoint = trail.getLast();
                targetYaw = Rotations.getYaw(lastTrailPoint);
            }
        }
    }

    private boolean isValidChunk(ChunkPos chunkPos, RegistryKey<World> currentDimension)
    {
        PaletteNewChunks paletteNewChunks = ModuleManager.getModule(PaletteNewChunks.class);
        boolean is119NewChunk = paletteNewChunks
            .isNewChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        boolean is112OldChunk = ModuleManager.getModule(OldChunks.class)
            .isOldChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        boolean isHighlighted = is119NewChunk || paletteNewChunks
            .isInverseNewChunk(
                chunkPos.x,
                chunkPos.z,
                currentDimension
            );

        return isHighlighted && ((!is119NewChunk && !only112.get()) || is112OldChunk);
    }

    // not using this method now but will keep it in case
    private Vec3d calculateAveragePosition(ArrayDeque<Vec3d> positions)
    {
        double sumX = 0, sumZ = 0;
        for (Vec3d pos : positions) {
            sumX += pos.x;
            sumZ += pos.z;
        }
        return new Vec3d(sumX / positions.size(), 0, sumZ / positions.size());
    }

    private float getActualYaw(float yaw)
    {
        return (yaw % 360 + 360) % 360;
    }

    private void log(String message)
    {
        info(message);
        if (!webhookLink.get().isEmpty())
        {
            sendWebhook(webhookLink.get(), "TrailFollower", message, null, mc.player.getGameProfile().getName());
        }
    }

    // Stash Finder Helper Methods
    private void scanChunkForStash(ChunkDataEvent event) {
        Chunk chunk = new Chunk(event.chunk().getPos());
        RegistryKey<World> currentDimension = mc.world.getRegistryKey();

        for (BlockEntity blockEntity : event.chunk().getBlockEntities().values()) {
            if (!storageBlocks.get().contains(blockEntity.getType())) continue;

            Block blockUnder = mc.world.getBlockState(blockEntity.getPos().down()).getBlock();
            if (ignoreTrialChambers.get() && (blockUnder.equals(Blocks.WAXED_OXIDIZED_CUT_COPPER) ||
                blockUnder.equals(Blocks.TUFF_BRICKS) || blockUnder.equals(Blocks.WAXED_COPPER_BLOCK) ||
                blockUnder.equals(Blocks.WAXED_OXIDIZED_COPPER)))
            {
                continue;
            }

            if (blockEntity instanceof ChestBlockEntity) chunk.chests++;
            else if (blockEntity instanceof BarrelBlockEntity) chunk.barrels++;
            else if (blockEntity instanceof ShulkerBoxBlockEntity) chunk.shulkers++;
            else if (blockEntity instanceof EnderChestBlockEntity) chunk.enderChests++;
            else if (blockEntity instanceof AbstractFurnaceBlockEntity) chunk.furnaces++;
            else if (blockEntity instanceof DispenserBlockEntity) chunk.dispensersDroppers++;
            else if (blockEntity instanceof HopperBlockEntity) chunk.hoppers++;
        }

        if ((chunk.getTotal() >= minimumStorageCount.get()) || (shulkerInstantHit.get() && chunk.shulkers > 0)) {
            Chunk prevChunk = null;
            int i = chunks.indexOf(chunk);

            if (i < 0) chunks.add(chunk);
            else prevChunk = chunks.set(i, chunk);

            saveJson();

            if (!chunk.equals(prevChunk) || !chunk.countsEqual(prevChunk)) {
                if (sendNotifications.get()) {
                    switch (notificationMode.get()) {
                        case Chat, Toast, Both -> info("Found stash at (highlight)%s(default), (highlight)%s(default).", chunk.x, chunk.z);
                    }
                }

                if (sendStashWebhook.get() && !stashWebhookLink.get().isEmpty()) {
                    // Simplified webhook logic for now
                    String message = "Found stash at " + chunk.x + ", " + chunk.z + ".";
                    new Thread(() -> sendWebhook(stashWebhookLink.get(), "Stash Found", message, pingForStash.get() ? stashDiscordId.get() : null, mc.player.getGameProfile().getName())).start();
                }

                if (saveToWaypoints.get()) {
                    WaypointSet waypointSet = getWaypointSet();
                    if (waypointSet == null) return;
                    addToWaypoints(waypointSet, chunk);
                    SupportMods.xaeroMinimap.requestWaypointsRefresh();
                }
            }
        }
    }

    private void load() {
        File file = getJsonFile();
        if (file.exists()) {
            try {
                FileReader reader = new FileReader(file);
                chunks = GSON.fromJson(reader, new TypeToken<List<Chunk>>() {}.getType());
                if (chunks == null) chunks = new ArrayList<>();
                reader.close();
                for (Chunk chunk : chunks) chunk.calculatePos();
            } catch (Exception ignored) {
                if (chunks == null) chunks = new ArrayList<>();
            }
        }
    }

    private void saveJson() {
        try {
            File file = getJsonFile();
            file.getParentFile().mkdirs();
            Writer writer = new FileWriter(file);
            GSON.toJson(chunks, writer);
            writer.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private File getJsonFile() {
        return new File(new File(MeteorClient.FOLDER, "trail-follower-stashes"), Utils.getFileWorldName() + ".json");
    }

    private WaypointSet getWaypointSet()
    {
        MinimapSession minimapSession = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (minimapSession == null) return null;
        MinimapWorld currentWorld = minimapSession.getWorldManager().getCurrentWorld();
        if (currentWorld == null) return null;
        return currentWorld.getCurrentWaypointSet();
    }

    private final Setting<Integer> stashColorThreshold1 = sgStashFinder.add(new IntSetting.Builder()
        .name("Stash Color Threshold 1")
        .description("The total number of storage blocks for the first color tier.")
        .defaultValue(15)
        .build());

    private final Setting<Integer> stashColorThreshold2 = sgStashFinder.add(new IntSetting.Builder()
        .name("Stash Color Threshold 2")
        .description("The total number of storage blocks for the second color tier.")
        .defaultValue(50)
        .build());

    private final Setting<Integer> stashColorThreshold3 = sgStashFinder.add(new IntSetting.Builder()
        .name("Stash Color Threshold 3")
        .description("The total number of storage blocks for the third color tier.")
        .defaultValue(100)
        .build());

    private void addToWaypoints(WaypointSet waypointSet, Chunk chunk)
    {
        int x = chunk.x;
        int z = chunk.z;
        if (getWaypointByCoordinate(x, z) != null) return;
        String waypointName = getWaypointName(chunk);
        int color = 0;
        if (chunk.getTotal() < stashColorThreshold1.get()) color = 10; // green
        else if (chunk.getTotal() < stashColorThreshold2.get()) color = 14; // yellow
        else if (chunk.getTotal() < stashColorThreshold3.get()) color = 12; // orange
        else color = 4; // red
        Waypoint waypoint = new Waypoint(x, 70, z, waypointName, "S", color, 0, false);
        waypointSet.add(waypoint);
    }

    private String getWaypointName(Chunk chunk) {
        String waypointName = "";
        if (chunk.chests > 0) waypointName += "C:" + chunk.chests;
        if (chunk.barrels > 0) waypointName += "B:" + chunk.barrels;
        if (chunk.shulkers > 0) waypointName += "S:" + chunk.shulkers;
        if (chunk.enderChests > 0) waypointName += "E:" + chunk.enderChests;
        if (chunk.hoppers > 0) waypointName += "H:" + chunk.hoppers;
        if (chunk.dispensersDroppers > 0) waypointName += "D:" + chunk.dispensersDroppers;
        if (chunk.furnaces > 0) waypointName += "F:" + chunk.furnaces;
        return waypointName;
    }

    private Waypoint getWaypointByCoordinate(int x, int z)
    {
        WaypointSet waypointSet = getWaypointSet();
        if (waypointSet == null) return null;
        for (Waypoint waypoint : waypointSet.getWaypoints())
        {
            if (waypoint.getX() == x && waypoint.getZ() == z)
            {
                return waypoint;
            }
        }
        return null;
    }

    private void removeAllStashWaypoints(List<Chunk> chunks)
    {
        WaypointSet waypointSet = getWaypointSet();
        if (waypointSet == null) return;
        for (Chunk chunk : chunks)
        {
            Waypoint waypoint = getWaypointByCoordinate(chunk.x, chunk.z);
            if (waypoint != null)
            {
                waypointSet.remove(waypoint);
            }
        }
        SupportMods.xaeroMinimap.requestWaypointsRefresh();
    }

    private void waypointSettingChanged(boolean enabled)
    {
        if (!enabled) {
            removeAllStashWaypoints(chunks);
        } else {
            WaypointSet waypointSet = getWaypointSet();
            if (waypointSet == null) return;
            for (Chunk chunk : chunks) {
                addToWaypoints(waypointSet, chunk);
            }
            SupportMods.xaeroMinimap.requestWaypointsRefresh();
        }
    }

    public enum FollowMode
    {
        AUTO,
        BARITONE,
        YAWLOCK
    }

    public enum DirectionWeighting
    {
        LEFT,
        NONE,
        RIGHT
    }

//    public enum ChunkTypes
//    {
//        ONLY_OLD, // only 1.12 chunks that are not 1.19 chunks
//        OLD_AND_LOADED_IN_119, // 1.12 chunks that are loaded in 1.19
//        ONLY_NEW, // only 1.19 chunks that are not 1.12 chunks
//        ALL // all chunks
//    }

    public enum TrailEndBehavior
    {
        DISABLE,
        FLY_TOWARDS_YAW,
        DISCONNECT
    }

    public enum Mode {
        Chat,
        Toast,
        Both
    }

    public static class Chunk {
        private static final StringBuilder sb = new StringBuilder();

        public ChunkPos chunkPos;
        public transient int x, z;
        public int chests, barrels, shulkers, enderChests, furnaces, dispensersDroppers, hoppers;

        public Chunk(ChunkPos chunkPos) {
            this.chunkPos = chunkPos;

            calculatePos();
        }

        public void calculatePos() {
            x = chunkPos.x * 16 + 8;
            z = chunkPos.z * 16 + 8;
        }

        public int getTotal() {
            return chests + barrels + shulkers + enderChests + furnaces + dispensersDroppers + hoppers;
        }

        public boolean countsEqual(Chunk c) {
            if (c == null) return false;
            return chests != c.chests || barrels != c.barrels || shulkers != c.shulkers || enderChests != c.enderChests || furnaces != c.furnaces || dispensersDroppers != c.dispensersDroppers || hoppers != c.hoppers;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Chunk chunk = (Chunk) o;
            return Objects.equals(chunkPos, chunk.chunkPos);
        }

        @Override
        public int hashCode() {
            return Objects.hash(chunkPos);
        }
    }
}