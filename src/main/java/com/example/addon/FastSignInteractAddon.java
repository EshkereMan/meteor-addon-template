package com.example.addon;

import com.example.addon.commands.CommandExample;
import com.example.addon.hud.HudExample;
import com.example.addon.modules.FastSignInteract;      // ← добавь этот импорт
import com.example.addon.modules.ModuleExample;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.systems.hud.Hud;
import meteordevelopment.meteorclient.systems.hud.HudGroup;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class FastSignInteractAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    // Твоя кастомная категория для модулей аддона
    public static final Category CATEGORY = new Category("Example");

    // Группа для HUD-элементов (если используешь)
    public static final HudGroup HUD_GROUP = new HudGroup("Example");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Example Addon (com.example.addon)");

        // Регистрация модулей
        Modules.get().add(new ModuleExample());
        Modules.get().add(new FastSignInteract());   // ← вот здесь добавляем наш модуль

        // Регистрация команд (если есть)
        Commands.add(new CommandExample());

        // Регистрация HUD-элементов (если есть)
        // Hud.get().register(HudExample.INFO);
    }

    @Override
    public void onRegisterCategories() {
        // Регистрируем свою категорию, чтобы она появилась в меню Meteor
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        // Очень важно! Meteor использует это для поиска событий/модулей в твоём пакете
        return "com.example.addon";
    }

    @Override
    public GithubRepo getRepo() {
        // Если планируешь публиковать на GitHub — укажи свой репозиторий
        // Пока можно оставить шаблонный или закомментировать/вернуть null
        return new GithubRepo("MeteorDevelopment", "meteor-addon-template");
        // Или свой: return new GithubRepo("твой_юзернейм", "имя_твоего_репо");
    }
}
