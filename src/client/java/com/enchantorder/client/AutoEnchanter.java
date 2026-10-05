package com.enchantorder.client;

import com.enchantorder.plan.AnvilOptimizer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;

/**
 * Does the anvil steps for you, one shift-click at a time, the same way you would by hand: the two
 * things for a step go into the anvil, then the result is shift-clicked back into your inventory.
 * The server sees normal clicks, so this works on any server.
 */
public final class AutoEnchanter {
	public static final AutoEnchanter INSTANCE = new AutoEnchanter();

	// Wait a few ticks after each click, so the server keeps up and you can watch it happen.
	private static final int TICKS_BETWEEN_CLICKS = 3;
	// A step takes at most 5 clicks (2 to clear the anvil, 2 to fill it, 1 to take the result).
	// Needing many more means the clicks aren't working, so give up instead of clicking forever.
	private static final int MAX_CLICKS_PER_STEP = 12;
	// How many times to look for the anvil's result before giving up (about 4 seconds).
	private static final int MAX_RESULT_CHECKS = 40;

	private final AnvilPlanner planner = AnvilPlanner.INSTANCE;
	private boolean running;
	private AnvilMenu menu;
	private List<AnvilPlanner.Step> steps = List.of();
	private int startLevel;
	private int wait;
	private int stepInProgress;
	private int clicks;
	private int resultChecks;
	private Component message = Component.empty();
	private boolean messageIsProblem;
	private List<AnvilPlanner.Step> messageSteps = List.of();

	private AutoEnchanter() {
	}

	public boolean isRunning() {
		return running;
	}

	/** What happened last time, for the plan that is showing now (empty if nothing to say). */
	public Component message() {
		return messageSteps == planner.steps() ? message : Component.empty();
	}

	public boolean messageIsProblem() {
		return messageIsProblem;
	}

	/**
	 * A reason the steps can't be done automatically right now.
	 *
	 * @param brief  a few words for the button
	 * @param detail the whole story, for the tooltip
	 */
	public record Problem(Component brief, Component detail) {
	}

	/** Reasons the steps can't be done automatically right now. Empty when Apply can be clicked. */
	public List<Problem> problems(AnvilMenu menu) {
		List<Problem> problems = new ArrayList<>();
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || planner.currentStep() < 0) {
			return problems;
		}
		List<Component> missing = planner.missingThings(menu);
		if (!missing.isEmpty()) {
			MutableComponent list = Component.empty();
			for (Component thing : missing) {
				list.append(list.getSiblings().isEmpty() ? Component.empty() : Component.literal(", ")).append(thing);
			}
			problems.add(new Problem(Component.translatable("enchantorder.auto.missing.brief"),
					Component.translatable("enchantorder.auto.missing", list)));
		}
		int needed = planner.remainingLevels();
		if (!planner.isCreative() && player.experienceLevel < needed) {
			problems.add(new Problem(Component.translatable("enchantorder.auto.levels.brief", needed),
					Component.translatable("enchantorder.auto.levels", needed, player.experienceLevel)));
		}
		if (!menu.getCarried().isEmpty()) {
			problems.add(new Problem(Component.translatable("enchantorder.auto.holding.brief"),
					Component.translatable("enchantorder.auto.holding")));
		}
		return problems;
	}

	public void start(AnvilMenu menu) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (running || player == null || !problems(menu).isEmpty()) {
			return;
		}
		running = true;
		this.menu = menu;
		steps = planner.steps();
		startLevel = player.experienceLevel;
		wait = 0;
		stepInProgress = -1;
		say(Component.translatable("enchantorder.auto.working"), false);
		planner.setLocked(true);
	}

	public void stop(Component why, boolean problem) {
		if (!running) {
			return;
		}
		running = false;
		menu = null;
		planner.setLocked(false);
		say(why, problem);
	}

	private void say(Component text, boolean problem) {
		message = text;
		messageIsProblem = problem;
		messageSteps = steps;
	}

	/** Called every tick while the anvil is open, after the planner has caught up with your inventory. */
	public void tick(AnvilMenu menu) {
		if (!running) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		LocalPlayer player = minecraft.player;
		if (player == null || minecraft.gameMode == null || menu != this.menu || player.containerMenu != menu) {
			stop(Component.translatable("enchantorder.auto.closed"), true);
			return;
		}
		if (planner.steps() != steps) {
			stop(Component.translatable("enchantorder.auto.plan_changed"), true);
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		int current = planner.currentStep();
		if (current < 0) {
			int used = startLevel - player.experienceLevel;
			stop(Component.translatable("enchantorder.auto.done", planner.isCreative() ? 0 : Math.max(0, used)), false);
			return;
		}
		if (!menu.getCarried().isEmpty()) {
			// We never leave anything on the cursor, so someone else put it there.
			stop(Component.translatable("enchantorder.auto.holding_stop"), true);
			return;
		}

		if (current != stepInProgress) {
			stepInProgress = current;
			clicks = 0;
			resultChecks = 0;
		}

		AnvilPlanner.Step step = steps.get(current);
		ItemStack first = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
		ItemStack second = menu.getSlot(AnvilMenu.ADDITIONAL_SLOT).getItem();
		boolean firstReady = planner.matches(first, step.firstNeeds());
		boolean secondReady = planner.matches(second, step.secondNeeds());

		// Take out anything that doesn't belong in the anvil for this step.
		if (!first.isEmpty() && !firstReady) {
			click(AnvilMenu.INPUT_SLOT);
			return;
		}
		if (!second.isEmpty() && !secondReady) {
			click(AnvilMenu.ADDITIONAL_SLOT);
			return;
		}
		// Shift-clicking from the inventory fills the first empty anvil slot, so fill the first one first.
		if (!firstReady) {
			moveIn(step.firstNeeds(), step.first);
			return;
		}
		if (!secondReady) {
			moveIn(step.secondNeeds(), step.second);
			return;
		}

		// Both are in: check the anvil's result and price, then take it.
		ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
		int cost = menu.getCost();
		if (result.isEmpty() || cost <= 0) {
			if (++resultChecks > MAX_RESULT_CHECKS) {
				stop(Component.translatable("enchantorder.auto.no_result"), true);
			}
			wait = 1;
			return;
		}
		if (!planner.matches(result, step.makes())) {
			stop(Component.translatable("enchantorder.auto.wrong_result"), true);
			return;
		}
		if (!planner.isCreative()) {
			if (cost >= AnvilOptimizer.TOO_EXPENSIVE) {
				stop(Component.translatable("enchantorder.auto.too_expensive"), true);
				return;
			}
			// The anvil might ask for a little more than planned (for example if the item has been in an
			// anvil more often than the one you planned with). Only carry on if the rest is still affordable.
			int stillNeeded = cost + planner.remainingLevels() - step.cost;
			if (player.experienceLevel < stillNeeded) {
				stop(Component.translatable("enchantorder.auto.not_enough", stillNeeded, player.experienceLevel), true);
				return;
			}
		}
		click(AnvilMenu.RESULT_SLOT);
	}

	/** Shift-clicks the thing a step needs from your inventory into the anvil. */
	private void moveIn(AnvilPlanner.Need need, AnvilPlanner.Part part) {
		int from = planner.findInInventory(menu, need);
		if (from < 0) {
			stop(Component.translatable("enchantorder.auto.lost", part.label()), true);
			return;
		}
		click(from);
	}

	/** Shift-clicks a slot, like holding shift and clicking it. */
	private void click(int slot) {
		if (++clicks > MAX_CLICKS_PER_STEP) {
			stop(Component.translatable("enchantorder.auto.stuck"), true);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
		wait = TICKS_BETWEEN_CLICKS;
	}
}
