package com.enchantorder;

import com.enchantorder.client.AnvilOverlay;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;

public class EnchantOrderClient implements ClientModInitializer {
	// This runs once when the game loads the mod.
	@Override
	public void onInitializeClient() {
		// Every time a screen opens (or the window is resized), check if it's an anvil and add our panels.
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (screen instanceof AnvilScreen anvil) {
				AnvilOverlay.attach(anvil);
			}
		});
	}
}
