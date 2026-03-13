// src/main/java/com/example/addon/AddonTemplate.java  (или переименуй в DeliveryAddon.java)

package com.example.addon;

import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.example.addon.modules.DeliverySpoofer;

public class DeliveryAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();

    // Твоя категория (можно использовать свою или встроенную, например Categories.Render)
    public static final Category CATEGORY = new Category("Delivery");

    @Override
    public void onInitialize() {
        LOG.info("Загружается аддон Delivery Spoofer");

        // Регистрируем только нужный модуль
        Modules.get().add(new DeliverySpoofer());
    }

    @Override
    public void onRegisterCategories() {
        // Регистрируем категорию, если используешь свою
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        // Очень важно! Должен совпадать с пакетом, где лежат все классы модулей
        return "com.example.addon";
    }
}
