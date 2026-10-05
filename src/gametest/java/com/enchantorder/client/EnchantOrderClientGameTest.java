package com.enchantorder.client;

import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;

/**
 * Starts the real game, opens an anvil and uses the panels with the mouse, taking screenshots on the way.
 * Run it with {@code ./gradlew runClientGameTest}; the screenshots end up in {@code build/run/clientGameTest/screenshots}.
 */
@SuppressWarnings("UnstableApiUsage")
public class EnchantOrderClientGameTest implements FabricClientGameTest {
	private static final List<ResourceKey<Enchantment>> SWORD_ENCHANTMENTS = List.of(
			Enchantments.SHARPNESS, Enchantments.UNBREAKING, Enchantments.MENDING, Enchantments.LOOTING,
			Enchantments.FIRE_ASPECT, Enchantments.SWEEPING_EDGE, Enchantments.KNOCKBACK);

	@Override
	public void runTest(ClientGameTestContext context) {
		try (TestSingleplayerContext singleplayer = context.worldBuilder()
				.adjustSettings(creator -> creator.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL))
				.create()) {
			singleplayer.getConnection().waitForChunksRender();

			// A diamond sword in the hotbar, a Mending book in the inventory, and 30 levels.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = singleplayer.getConnection().getServerPlayer();
				Registry<Enchantment> enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
				player.getInventory().clearContent();
				player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD));
				player.getInventory().setItem(9, EnchantmentHelper.createBook(new EnchantmentInstance(enchantments.getOrThrow(Enchantments.MENDING), 1)));
				player.giveExperienceLevels(30);
			});

			// Put down an anvil and open it like a player would.
			BlockPos anvilPos = context.computeOnClient(client -> BlockPos.containing(client.player.position()).east().east());
			singleplayer.getServer().runCommand("setblock %d %d %d minecraft:anvil".formatted(anvilPos.getX(), anvilPos.getY(), anvilPos.getZ()));
			context.waitFor(client -> client.level.getBlockState(anvilPos).is(Blocks.ANVIL));
			context.getInput().lookAt(anvilPos);
			context.waitTick();
			context.getInput().pressKey(options -> options.keyUse);
			context.waitForScreen(AnvilScreen.class);
			context.waitTicks(5);
			check(context, client -> !AnvilPlanner.INSTANCE.isActive(), "the panels should stay hidden until an item goes in");

			// Shift-click the sword from the hotbar into the anvil.
			context.getInput().holdShift();
			clickInAnvil(context, 8 + 8, 142 + 8);
			context.getInput().releaseShift();
			context.waitFor(client -> anvil(client).getMenu().getSlot(0).hasItem());
			context.waitTicks(3);
			check(context, client -> AnvilPlanner.INSTANCE.isActive(), "the panels should show up for a sword");
			context.takeScreenshot("1-picker");

			// Tick seven enchantments by clicking their rows.
			for (ResourceKey<Enchantment> key : SWORD_ENCHANTMENTS) {
				clickRow(context, key);
			}
			// Smite can't go with Sharpness, so clicking it should do nothing.
			clickRow(context, Enchantments.SMITE);
			context.waitTicks(2);
			check(context, client -> AnvilPlanner.INSTANCE.steps().size() == 7, "expected 7 steps");
			check(context, client -> AnvilPlanner.INSTANCE.totalLevels() == 49,
					"expected 49 levels, got " + context.computeOnClient(client -> AnvilPlanner.INSTANCE.totalLevels()));
			context.takeScreenshot("2-order");

			// Hover the third step to show its tooltip.
			OrderPanel order = context.computeOnClient(client -> panel(client, OrderPanel.class));
			moveTo(context, order.listLeft() + 30, order.listTop() + 1 + 2 * 24 + 12);
			context.waitTicks(2);
			context.takeScreenshot("3-step-tooltip");

			// Pick up the Mending book, click a panel while holding it, and make sure it isn't thrown away.
			clickInAnvil(context, 8 + 8, 84 + 8);
			context.waitFor(client -> !anvil(client).getMenu().getCarried().isEmpty());
			PickerPanel picker = context.computeOnClient(client -> panel(client, PickerPanel.class));
			clickAt(context, picker.getX() + picker.getWidth() / 2, picker.getY() + 10);
			context.waitTicks(5);
			check(context, client -> !anvil(client).getMenu().getCarried().isEmpty(), "clicking a panel threw the held item away");
			clickInAnvil(context, 8 + 8, 84 + 8);
			context.waitFor(client -> anvil(client).getMenu().getCarried().isEmpty());

			// Take the sword back out: the plan should stay on screen.
			context.getInput().holdShift();
			clickInAnvil(context, 27 + 8, 47 + 8);
			context.getInput().releaseShift();
			context.waitFor(client -> !anvil(client).getMenu().getSlot(0).hasItem());
			context.waitTicks(3);
			check(context, client -> AnvilPlanner.INSTANCE.isActive() && AnvilPlanner.INSTANCE.currentStep() == 0,
					"the plan should stay after taking the sword out");

			// Hand the player whatever step 1 makes: step 1 should get ticked off.
			Map<Holder<Enchantment>, Integer> firstResult = context.computeOnClient(client -> AnvilPlanner.INSTANCE.steps().getFirst().result());
			boolean firstMakesItem = context.computeOnClient(client -> AnvilPlanner.INSTANCE.steps().getFirst().makesItem());
			singleplayer.getServer().runOnServer(server -> {
				Registry<Enchantment> enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
				ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
				firstResult.forEach((holder, level) -> stored.set(enchantments.getOrThrow(holder.unwrapKey().orElseThrow()), level));
				ItemStack made = new ItemStack(firstMakesItem ? Items.DIAMOND_SWORD : Items.ENCHANTED_BOOK);
				made.set(firstMakesItem ? DataComponents.ENCHANTMENTS : DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
				singleplayer.getConnection().getServerPlayer().getInventory().setItem(20, made);
			});
			context.waitFor(client -> AnvilPlanner.INSTANCE.currentStep() == 1);
			context.waitTicks(2);
			context.takeScreenshot("4-step-1-done");

			// Same thing in a 1080p window, the most common size.
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(5);
			context.takeScreenshot("5-1080p");

			context.setScreen(() -> null);
		}
	}

	private static AnvilScreen anvil(Minecraft client) {
		Screen screen = client.gui.screen();
		if (screen instanceof AnvilScreen anvil) {
			return anvil;
		}
		throw new AssertionError("The anvil screen is not open, the screen is " + screen);
	}

	private static <T extends AbstractWidget> T panel(Minecraft client, Class<T> type) {
		for (AbstractWidget widget : Screens.getWidgets(anvil(client))) {
			if (type.isInstance(widget)) {
				return type.cast(widget);
			}
		}
		throw new AssertionError("No " + type.getSimpleName() + " on the anvil screen");
	}

	private static void clickRow(ClientGameTestContext context, ResourceKey<Enchantment> key) {
		int[] position = context.computeOnClient(client -> {
			PickerPanel picker = panel(client, PickerPanel.class);
			List<AnvilPlanner.Choice> choices = AnvilPlanner.INSTANCE.choices();
			for (int i = 0; i < choices.size(); i++) {
				if (choices.get(i).enchantment.is(key)) {
					return new int[] {picker.listLeft() + 40, picker.listTop() + 1 + i * 12 - picker.scroll + 6};
				}
			}
			throw new AssertionError(key + " is not in the list");
		});
		clickAt(context, position[0], position[1]);
	}

	/** Clicks at a position inside the anvil window, measured from its top-left corner. */
	private static void clickInAnvil(ClientGameTestContext context, int x, int y) {
		int[] corner = context.computeOnClient(client -> new int[] {(anvil(client).width - 176) / 2, (anvil(client).height - 166) / 2});
		clickAt(context, corner[0] + x, corner[1] + y);
	}

	private static void clickAt(ClientGameTestContext context, int guiX, int guiY) {
		moveTo(context, guiX, guiY);
		context.getInput().pressMouse(0);
		context.waitTick();
	}

	/** Moves the mouse to a position given in GUI pixels. */
	private static void moveTo(ClientGameTestContext context, int guiX, int guiY) {
		double scale = context.computeOnClient(client -> client.getWindow().getScreenWidth() / (double) anvil(client).width);
		context.getInput().setCursorPos((guiX + 0.5) * scale, (guiY + 0.5) * scale);
		context.waitTick();
	}

	private static void check(ClientGameTestContext context, java.util.function.Predicate<Minecraft> condition, String message) {
		if (!context.computeOnClient(condition::test)) {
			throw new AssertionError(message);
		}
	}
}
