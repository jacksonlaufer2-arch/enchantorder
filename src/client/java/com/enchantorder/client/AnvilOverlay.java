package com.enchantorder.client;

import java.util.List;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.world.inventory.AnvilMenu;

/** Puts the two panels next to an open anvil and passes mouse clicks and scrolling to them. */
public final class AnvilOverlay {
	// Size of the anvil window itself, in GUI pixels.
	private static final int ANVIL_WIDTH = 176;
	private static final int ANVIL_HEIGHT = 166;
	private static final int GAP = 4;
	private static final int MIN_PANEL_WIDTH = 100;
	private static final int MAX_PANEL_WIDTH = 180;
	private static final int MAX_PANEL_HEIGHT = 222;

	private final AnvilMenu menu;
	private final List<PanelWidget> panels;
	private boolean pressedOnPanel;

	private AnvilOverlay(AnvilScreen screen) {
		menu = screen.getMenu();

		int anvilLeft = (screen.width - ANVIL_WIDTH) / 2;
		int anvilTop = (screen.height - ANVIL_HEIGHT) / 2;
		int width = Math.clamp(anvilLeft - GAP * 2, MIN_PANEL_WIDTH, MAX_PANEL_WIDTH);
		int height = Math.clamp(screen.height - GAP * 2, ANVIL_HEIGHT, MAX_PANEL_HEIGHT);
		int top = Math.max(GAP, anvilTop + (ANVIL_HEIGHT - height) / 2);

		PickerPanel picker = new PickerPanel(anvilLeft - GAP - width, top, width, height, menu);
		OrderPanel order = new OrderPanel(anvilLeft + ANVIL_WIDTH + GAP, top, width, height);
		panels = List.of(picker, order);
		Screens.getWidgets(screen).add(picker);
		Screens.getWidgets(screen).add(order);

		AnvilPlanner.INSTANCE.sync(menu);
	}

	/** Called every time an anvil screen opens or is resized. */
	public static void attach(AnvilScreen screen) {
		if (Screens.getWidgets(screen).stream().anyMatch(widget -> widget instanceof PanelWidget)) {
			return; // Already attached since the screen was last set up.
		}
		AnvilOverlay overlay = new AnvilOverlay(screen);
		ScreenEvents.afterTick(screen).register(s -> AnvilPlanner.INSTANCE.sync(overlay.menu));
		ScreenMouseEvents.allowMouseClick(screen).register((s, event) -> overlay.allowClick(event));
		ScreenMouseEvents.allowMouseRelease(screen).register((s, event) -> overlay.allowRelease());
		ScreenMouseEvents.allowMouseScroll(screen).register((s, mouseX, mouseY, scrollX, scrollY) -> overlay.allowScroll(mouseX, mouseY, scrollY));
	}

	private PanelWidget panelAt(double mouseX, double mouseY) {
		for (PanelWidget panel : panels) {
			if (panel.contains(mouseX, mouseY)) {
				return panel;
			}
		}
		return null;
	}

	/** Returns false (meaning "the anvil shouldn't see this click") when the click was on a panel. */
	private boolean allowClick(MouseButtonEvent event) {
		PanelWidget panel = panelAt(event.x(), event.y());
		if (panel == null) {
			return true;
		}
		panel.click(event.x(), event.y(), event.button());
		pressedOnPanel = true;
		return false;
	}

	/**
	 * Hides the mouse release that goes with a click on a panel. Otherwise the anvil would treat it
	 * as a click outside its window and throw whatever item you are holding on the ground.
	 */
	private boolean allowRelease() {
		if (pressedOnPanel) {
			pressedOnPanel = false;
			return false;
		}
		return true;
	}

	private boolean allowScroll(double mouseX, double mouseY, double amount) {
		PanelWidget panel = panelAt(mouseX, mouseY);
		if (panel == null) {
			return true;
		}
		panel.scroll(amount);
		return false;
	}
}
