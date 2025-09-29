package com.stash.hunt.utils;

import meteordevelopment.meteorclient.systems.modules.Module;

import java.util.ArrayList;
import java.util.List;

public class FlightManager {
    private static final List<IFlightModule> activeFlightModules = new ArrayList<>();

    public static void register(IFlightModule module) {
        if (!activeFlightModules.contains(module)) {
            activeFlightModules.add(module);
        }
    }

    public static void unregister(IFlightModule module) {
        activeFlightModules.remove(module);
    }

    public static void pause() {
        for (IFlightModule module : new ArrayList<>(activeFlightModules)) {
            if (module instanceof Module && ((Module) module).isActive()) {
                module.pauseFlight();
            }
        }
    }

    public static void resume() {
        for (IFlightModule module : new ArrayList<>(activeFlightModules)) {
            module.resumeFlight();
        }
    }

    public static boolean isFlightActive() {
        for (IFlightModule module : activeFlightModules) {
            if (module instanceof Module && ((Module) module).isActive()) {
                return true;
            }
        }
        return false;
    }
}