package com.larp.debug;

import com.larp.debug.modules.Finder;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AddonTemplate extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("larpdebug");
    public static final HudGroup HUD_GROUP = new HudGroup("larpdebug");

    @Override
    public void onInitialize() {
        LOG.info("Initializing LarpDebug Addon");
        Modules.get().add(new Finder());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.larp.debug";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("larpdebug", "addon");
    }
}