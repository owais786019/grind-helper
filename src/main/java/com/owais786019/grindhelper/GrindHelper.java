package com.owais786019.grindhelper;

import com.owais786019.grindhelper.modules.AutoEnchanter;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class GrindHelper extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Grind Helper");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Grind Helper");

        Modules.get().add(new AutoEnchanter());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.owais786019.grindhelper";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("owais786019", "grind-helper");
    }
}
