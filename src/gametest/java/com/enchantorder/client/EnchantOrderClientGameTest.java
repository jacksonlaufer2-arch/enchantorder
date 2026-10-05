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
import net.minecraft.network.chat.Component;
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
		try {
			test(context);
		} catch (Throwable failure) {
			context.takeScreenshot("failure");
			throw failure;
		}
	}

	private void test(ClientGameTestContext context) {
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
			openAnvil(context, singleplayer, anvilPos);
			context.takeScreenshot("0-anvil-open");
			check(context, client -> !AnvilPlanner.INSTANCE.isActive(), "the panels should stay hidden until an item goes in");

			// Move the sword from the hotbar into the anvil (the test can't hold shift while clicking).
			clickInAnvil(context, 8 + 8, 142 + 8);
			context.waitFor(client -> !anvil(client).getMenu().getCarried().isEmpty());
			clickInAnvil(context, 27 + 8, 47 + 8);
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

			// The level arrows: lower Sharpness to IV, then put it back to V.
			clickLevelArrow(context, Enchantments.SHARPNESS, true);
			check(context, client -> levelOf(Enchantments.SHARPNESS) == 4, "the < arrow should lower the level");
			clickLevelArrow(context, Enchantments.SHARPNESS, false);
			check(context, client -> levelOf(Enchantments.SHARPNESS) == 5, "the > arrow should raise the level");

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
			clickInAnvil(context, 27 + 8, 47 + 8);
			context.waitFor(client -> !anvil(client).getMenu().getCarried().isEmpty());
			clickInAnvil(context, 26 + 8, 84 + 8);
			context.waitFor(client -> !anvil(client).getMenu().getSlot(0).hasItem() && anvil(client).getMenu().getCarried().isEmpty());
			context.waitTicks(3);
			check(context, client -> AnvilPlanner.INSTANCE.isActive() && AnvilPlanner.INSTANCE.currentStep() == 0,
					"the plan should stay after taking the sword out");

			// Hand the player whatever step 1 makes: step 1 should get ticked off.
			Map<Holder<Enchantment>, Integer> firstResult = context.computeOnClient(client -> AnvilPlanner.INSTANCE.steps().getFirst().result());
			boolean firstMakesItem = context.computeOnClient(client -> AnvilPlanner.INSTANCE.steps().getFirst().makesItem());
			int firstPenalty = context.computeOnClient(client -> AnvilPlanner.INSTANCE.steps().getFirst().makes().workPenalty());
			singleplayer.getServer().runOnServer(server -> {
				Registry<Enchantment> enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
				ItemEnchantments.Mutable stored = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
				firstResult.forEach((holder, level) -> stored.set(enchantments.getOrThrow(holder.unwrapKey().orElseThrow()), level));
				ItemStack made = new ItemStack(firstMakesItem ? Items.DIAMOND_SWORD : Items.ENCHANTED_BOOK);
				made.set(firstMakesItem ? DataComponents.ENCHANTMENTS : DataComponents.STORED_ENCHANTMENTS, stored.toImmutable());
				made.set(DataComponents.REPAIR_COST, firstPenalty);
				singleplayer.getConnection().getServerPlayer().getInventory().setItem(20, made);
			});
			context.waitFor(client -> AnvilPlanner.INSTANCE.currentStep() == 1);
			context.waitTicks(2);
			context.takeScreenshot("4-step-1-done");

			// Same thing in a 1080p window, the most common size.
			context.getInput().resizeWindow(1920, 1080);
			context.waitTicks(5);
			context.takeScreenshot("5-1080p");

			// "Clear" unticks everything, and "My books" ticks what you have books for (just Mending here;
			// the book from step 1 has two enchantments so it doesn't count).
			PickerPanel bigPicker = context.computeOnClient(client -> panel(client, PickerPanel.class));
			clickAt(context, bigPicker.clearX() + 5, bigPicker.buttonY() + 5);
			check(context, client -> !AnvilPlanner.INSTANCE.hasSelection(), "Clear should untick everything");
			clickAt(context, bigPicker.myBooksX() + 5, bigPicker.buttonY() + 5);
			check(context, client -> levelOf(Enchantments.MENDING) == 1 && AnvilPlanner.INSTANCE.steps().size() == 1,
					"My books should tick just Mending");
			context.takeScreenshot("6-my-books");

			// Automatic mode. Swap the inventory for a sword called "Excalibur", a spare plain sword in the
			// first inventory slot, and a book for each of the seven enchantments.
			singleplayer.getServer().runOnServer(server -> {
				ServerPlayer player = singleplayer.getConnection().getServerPlayer();
				Registry<Enchantment> enchantments = server.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
				player.getInventory().clearContent();
				ItemStack excalibur = new ItemStack(Items.DIAMOND_SWORD);
				excalibur.set(DataComponents.CUSTOM_NAME, Component.literal("Excalibur"));
				player.getInventory().setItem(0, excalibur);
				player.getInventory().setItem(9, new ItemStack(Items.DIAMOND_SWORD));
				int slot = 10;
				for (ResourceKey<Enchantment> key : SWORD_ENCHANTMENTS) {
					Holder<Enchantment> enchantment = enchantments.getOrThrow(key);
					player.getInventory().setItem(slot++, EnchantmentHelper.createBook(new EnchantmentInstance(enchantment, enchantment.value().getMaxLevel())));
				}
			});
			context.waitFor(client -> anvil(client).getMenu().getSlot(3 + 27).getItem().has(DataComponents.CUSTOM_NAME));
			// Put Excalibur in the anvil: the plan switches to it.
			clickInAnvil(context, 8 + 8, 142 + 8);
			context.waitFor(client -> !anvil(client).getMenu().getCarried().isEmpty());
			clickInAnvil(context, 27 + 8, 47 + 8);
			context.waitFor(client -> anvil(client).getMenu().getSlot(0).hasItem());
			context.waitTicks(3);
			check(context, client -> AnvilPlanner.INSTANCE.item().has(DataComponents.CUSTOM_NAME), "the plan should be for Excalibur now");
			clickAt(context, bigPicker.clearX() + 5, bigPicker.buttonY() + 5);
			clickAt(context, bigPicker.myBooksX() + 5, bigPicker.buttonY() + 5);
			check(context, client -> AnvilPlanner.INSTANCE.steps().size() == 7 && AnvilPlanner.INSTANCE.totalLevels() == 49,
					"My books should tick all seven books");

			// Tick "Auto". With only 30 levels, Apply must not be allowed (the plan costs 49).
			OrderPanel bigOrder = context.computeOnClient(client -> panel(client, OrderPanel.class));
			clickAt(context, bigOrder.autoBoxX() + 4, bigOrder.autoBoxY() + 4);
			check(context, client -> EnchantOrderSettings.autoApply(), "clicking the box should tick Auto");
			check(context, client -> client.player.experienceLevel == 30, "the player should have 30 levels");
			check(context, client -> !AutoEnchanter.INSTANCE.problems(anvil(client).getMenu()).isEmpty(), "Apply should need more levels");
			clickAt(context, bigOrder.buttonX() + 10, bigOrder.buttonY() + 8);
			context.waitTicks(5);
			check(context, client -> !AutoEnchanter.INSTANCE.isRunning(), "Apply shouldn't start without enough levels");
			context.takeScreenshot("7-auto-needs-levels");

			// With 60 levels it can go. Click Apply and let it do every step.
			singleplayer.getServer().runOnServer(server -> singleplayer.getConnection().getServerPlayer().giveExperienceLevels(30));
			context.waitFor(client -> client.player.experienceLevel == 60);
			check(context, client -> AutoEnchanter.INSTANCE.problems(anvil(client).getMenu()).isEmpty(), "Apply should be ready");
			context.takeScreenshot("8-auto-ready");
			for (int attempt = 0; attempt < 4 && !allStepsDone(context); attempt++) {
				if (!context.computeOnClient(client -> client.gui.screen() instanceof AnvilScreen)) {
					// The anvil wore out and broke (12% chance per use): put down a new one and carry on.
					openAnvil(context, singleplayer, anvilPos);
				}
				OrderPanel order2 = context.computeOnClient(client -> panel(client, OrderPanel.class));
				clickAt(context, order2.buttonX() + 10, order2.buttonY() + 8);
				check(context, client -> AutoEnchanter.INSTANCE.isRunning(), "Apply should start");
				if (attempt == 0) {
					context.waitTicks(20);
					context.takeScreenshot("9-auto-working");
				}
				context.waitFor(client -> !AutoEnchanter.INSTANCE.isRunning(), 1200);
			}
			check(context, client -> allStepsDoneOnClient(), "every step should be done: " + context.computeOnClient(client -> AutoEnchanter.INSTANCE.message().getString()));
			check(context, client -> client.player.experienceLevel == 60 - 49,
					"the steps should cost exactly 49 levels, the player has " + context.computeOnClient(client -> client.player.experienceLevel) + " left");
			// The enchantments went on Excalibur, and the spare sword was left alone.
			check(context, client -> enchantmentsOnSword(client, true) == 7 && enchantmentsOnSword(client, false) == 0,
					"Excalibur should have all 7 enchantments and the spare none, but they have "
							+ context.computeOnClient(client -> enchantmentsOnSword(client, true) + " and " + enchantmentsOnSword(client, false)));
			context.waitTicks(2);
			context.takeScreenshot("10-auto-done");

			context.setScreen(() -> null);
		}
	}

	/** Puts an anvil down next to the player and opens it by pressing "use" on it. */
	private static void openAnvil(ClientGameTestContext context, TestSingleplayerContext singleplayer, BlockPos anvilPos) {
		singleplayer.getServer().runCommand("setblock %d %d %d minecraft:anvil".formatted(anvilPos.getX(), anvilPos.getY(), anvilPos.getZ()));
		context.waitFor(client -> client.level.getBlockState(anvilPos).is(Blocks.ANVIL));
		context.getInput().lookAt(anvilPos);
		context.waitTick();
		context.getInput().pressKey(options -> options.keyUse);
		context.waitForScreen(AnvilScreen.class);
		context.waitTicks(5);
	}

	/** How many enchantments the named (or the plain) diamond sword in the inventory has, or -1 if it's not there. */
	private static int enchantmentsOnSword(Minecraft client, boolean named) {
		var inventory = client.player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.getItem() == Items.DIAMOND_SWORD && stack.has(DataComponents.CUSTOM_NAME) == named) {
				return stack.getEnchantments().size();
			}
		}
		return -1;
	}

	private static boolean allStepsDone(ClientGameTestContext context) {
		return context.computeOnClient(client -> allStepsDoneOnClient());
	}

	private static boolean allStepsDoneOnClient() {
		return !AnvilPlanner.INSTANCE.steps().isEmpty() && AnvilPlanner.INSTANCE.currentStep() < 0;
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

	private static void clickLevelArrow(ClientGameTestContext context, ResourceKey<Enchantment> key, boolean left) {
		int[] position = context.computeOnClient(client -> {
			PickerPanel picker = panel(client, PickerPanel.class);
			List<AnvilPlanner.Choice> choices = AnvilPlanner.INSTANCE.choices();
			for (int i = 0; i < choices.size(); i++) {
				if (choices.get(i).enchantment.is(key)) {
					int x = left ? picker.leftArrowX(choices.get(i), picker.listLeft(), picker.rowWidth())
							: PickerPanel.rightArrowX(picker.listLeft(), picker.rowWidth());
					return new int[] {x + 2, picker.listTop() + 1 + i * 12 - picker.scroll + 6};
				}
			}
			throw new AssertionError(key + " is not in the list");
		});
		clickAt(context, position[0], position[1]);
	}

	/** The level an enchantment is ticked at, or 0 if it isn't ticked. */
	private static int levelOf(ResourceKey<Enchantment> key) {
		for (AnvilPlanner.Choice choice : AnvilPlanner.INSTANCE.choices()) {
			if (choice.enchantment.is(key)) {
				return choice.status() == AnvilPlanner.Status.SELECTED ? AnvilPlanner.INSTANCE.selectedLevel(choice) : 0;
			}
		}
		return 0;
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
