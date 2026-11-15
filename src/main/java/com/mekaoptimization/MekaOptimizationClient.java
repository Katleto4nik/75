package com.mekaoptimization;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MekaOptimization - optimize second futures with Minecraft.
 * The mod is always on (no toggle). It does no rendering and makes no OpenGL calls,
 * so it works the same with the OpenGL and Vulkan renderers.
 */
public class MekaOptimizationClient implements ClientModInitializer {
    public static final String MOD_ID = "mekaoptimization";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        AnchorMacro.init();
        LOGGER.info("MekaOptimization loaded");
    }
}
