package com.stash.hunt.modules.searcharea;

import com.stash.hunt.Addon;
import com.stash.hunt.modules.searcharea.modes.Rectangle;
import com.stash.hunt.modules.searcharea.modes.Spiral;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.containers.WVerticalList;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import com.stash.hunt.utils.FlightManager;
import com.stash.hunt.utils.IFlightModule;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.util.math.BlockPos;

import static com.stash.hunt.Utils.setPressed;

public class SearchArea extends Module implements IFlightModule {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private boolean paused = false;

    @Override
    public void pauseFlight() {
        this.paused = true;
    }

    @Override
    public void resumeFlight() {
        this.paused = false;
    }


    public final Setting<SearchAreaModes> chunkLoadMode = sgGeneral.add(new EnumSetting.Builder<SearchAreaModes>()
        .name("Mode")
        .description("The mode chunks are loaded.")
        .defaultValue(SearchAreaModes.Rectangle)
        .onModuleActivated(chunkMode -> onModeChanged(chunkMode.get()))
        .onChanged(this::onModeChanged)
        .build()
    );

    public final Setting<BlockPos> startPos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("Start Position")
        .description("The coordinates to start the rectangle at. Y Pos is ignored")
        .defaultValue(new BlockPos(0,0,0))
        .visible(() -> chunkLoadMode.get() == SearchAreaModes.Rectangle)
        .build()
    );

    public final Setting<BlockPos> targetPos = sgGeneral.add(new BlockPosSetting.Builder()
        .name("End Position")
        .description("The coordinates to end the rectangle at. Y Pos is ignored")
        .defaultValue(new BlockPos(0,0,0))
        .visible(() -> chunkLoadMode.get() == SearchAreaModes.Rectangle)
        .build()
    );

    public final Setting<Integer> rowGap = sgGeneral.add(new IntSetting.Builder()
        .name("Path Gap")
        .description("The amount of chunks to space between each chunk path.")
        .defaultValue(12)
        .min(1)
        .sliderRange(0, 32)
        .build()
    );

    public final Setting<String> saveLocation = sgGeneral.add(new StringSetting.Builder()
        .name("Save Name")
        .description("The name to use for the folder that saves data, if you leave it blank, no data will be saved.")
        .defaultValue("")
        .build()
    );

    public final Setting<Boolean> disconnectOnCompletion = sgGeneral.add(new BoolSetting.Builder()
        .name("Disconnect on Completion")
        .description("Whether to disconnect after the path is complete. This will turn autoreconnect off when disconnecting.")
        .defaultValue(false)
        .visible(() -> chunkLoadMode.get() == SearchAreaModes.Rectangle)
        .build()
    );


    public SearchArea() {
        super(Addon.CATEGORY, "search-area", "Either loads chunks in a rectangle to a certain point from you, or spirals endlessly from you. Useful with Stash Finder or other map saving mods.");
    }

    private SearchAreaMode currentMode = new Rectangle();

    @Override
    public WWidget getWidget(GuiTheme theme)
    {
        WVerticalList list = theme.verticalList();
        WButton clear = list.add(theme.button("Clear Currently Selected")).widget();

        clear.action = () -> currentMode.clear();

        WButton clearAll = list.add(theme.button("Clear All")).widget();

        clearAll.action = () -> currentMode.clearAll();

        return list;
    }

    @Override
    public void onActivate() {
        FlightManager.register(this);
        currentMode.onActivate();
    }

    @Override
    public void onDeactivate()
    {
        FlightManager.unregister(this);
        currentMode.onDeactivate();
    }

    @EventHandler
    private void onTick(TickEvent.Post event)
    {
        if (paused) {
            setPressed(mc.options.forwardKey, false);
            return;
        }
        currentMode.onTick();
    }

    private void onModeChanged(SearchAreaModes mode) {
        switch (mode) {
            case Rectangle -> currentMode = new Rectangle();
            case Spiral -> currentMode = new Spiral();
        }
    }

    public enum WebhookSettings
    {
        Off,
        LogChat,
        LogStashes,
        LogBoth
    }

}
