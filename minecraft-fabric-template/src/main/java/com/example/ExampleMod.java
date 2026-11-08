package com.example;

import com.example.item.RemoldWandItem;
import net.fabricmc.api.ModInitializer;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ExampleMod implements ModInitializer {
    public static final String MOD_ID = "example_mod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static final Identifier REMOLD_WAND_ID = Identifier.of(MOD_ID, "remold_wand");

    // 注册演示 Remold 热重载的物品：右键触发信息。
    // 1.21.2+ 起 Item.Settings 必须显式给出 registryKey（否则 Item 构造时 "Item id not set"）。
    public static final Item REMOLD_WAND = Registry.register(
        Registries.ITEM,
        REMOLD_WAND_ID,
        new RemoldWandItem(new Item.Settings()
            .maxCount(1)
            .registryKey(RegistryKey.of(RegistryKeys.ITEM, REMOLD_WAND_ID)))
    );

    @Override
    public void onInitialize() {
        LOGGER.info("[Remold] Example Fabric Mod initialized successfully with Remold hot reload support!");
    }
}
