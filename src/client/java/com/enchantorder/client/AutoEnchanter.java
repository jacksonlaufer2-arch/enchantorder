package com.enchantorder.client;

import com.enchantorder.plan.AnvilOptimizer;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.StringUtil;
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

	// Wait a few ticks after each click (longer on a laggy server), so the server keeps up and you can
	// watch it happen.
	private static final int TICKS_BETWEEN_CLICKS = 3;
	// A step takes at most 5 clicks (2 to clear the anvil, 2 to fill it, 1 to take the result).
	// Needing many more means the clicks aren't working, so give up instead of clicking forever.
	private static final int CLICKS_PER_STEP = 5;
	private static final int SPARE_CLICKS = 8;
	// How many times to look at the anvil's result and price before giving up (about 4 seconds).
	private static final int MAX_RESULT_CHECKS = 40;

	private final AnvilPlanner planner = AnvilPlanner.INSTANCE;
	private boolean running;
	private AnvilMenu menu;
	private List<AnvilPlanner.Step> steps = List.of();
	private int startLevel;
	private int wait;
	private int furthestStep;
	private int clicks;
	private int resultChecks;
	private int lastCost;
	private boolean lastResultEmpty;
	private boolean resetNameBox;
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
		// The exact copy of the item you planned with has to be there. If it has been used or renamed since,
		// putting it in the anvil makes the plan follow it (you may have spares, so it can't just guess).
		AnvilPlanner.Need itemNext = planner.itemNeededNext();
		ItemStack inAnvil = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
		if (itemNext != null && !planner.owns(menu, itemNext)) {
			if (planner.looksLike(inAnvil, itemNext)) {
				problems.add(new Problem(Component.translatable("enchantorder.auto.penalty.brief"), Component.translatable("enchantorder.auto.penalty")));
			} else {
				problems.add(new Problem(Component.translatable("enchantorder.auto.put_in.brief"),
						Component.translatable("enchantorder.auto.put_in", planner.item().getHoverName())));
			}
		}
		if (!anvilKeepsName(planner.item())) {
			problems.add(new Problem(Component.translatable("enchantorder.auto.name.brief"), Component.translatable("enchantorder.auto.name")));
		}
		List<AnvilPlanner.Need> missing = planner.missingBooks(menu);
		if (!missing.isEmpty()) {
			MutableComponent list = Component.empty();
			for (AnvilPlanner.Need thing : missing) {
				list.append(list.getSiblings().isEmpty() ? Component.empty() : Component.literal(", ")).append(planner.describe(thing));
			}
			problems.add(new Problem(Component.translatable("enchantorder.auto.missing.brief"), Component.translatable("enchantorder.auto.missing", list)));
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

	/**
	 * False if the item has a name the anvil's name box can't hold (too long, or with characters it
	 * leaves out). The anvil would then rename it on every step, for 1 more level each time.
	 */
	private static boolean anvilKeepsName(ItemStack item) {
		if (!item.has(DataComponents.CUSTOM_NAME)) {
			return true;
		}
		String name = item.getHoverName().getString();
		return name.length() <= AnvilMenu.MAX_NAME_LENGTH && StringUtil.filterText(name).equals(name) && !StringUtil.isBlank(name);
	}

	public void start(AnvilMenu menu) {
		LocalPlayer player = Minecraft.getInstance().player;
		if (running || player == null || planner.currentStep() < 0 || !problems(menu).isEmpty()) {
			return;
		}
		running = true;
		this.menu = menu;
		steps = planner.steps();
		startLevel = player.experienceLevel;
		wait = 0;
		furthestStep = planner.currentStep();
		clicks = 0;
		resultChecks = 0;
		resetNameBox = true;
		say(Component.translatable("enchantorder.auto.working"), false);
		planner.setLocked(true);
	}

	/**
	 * Called when the anvil screen closes while the steps are being done. If it closed because the anvil
	 * broke on the very last step, that still counts as done.
	 */
	public void anvilClosed(AnvilMenu menu) {
		if (!running) {
			return;
		}
		if (menu == this.menu) {
			planner.sync(menu);
		}
		if (planner.steps() == steps && planner.currentStep() < 0) {
			finish();
		} else {
			stop(Component.translatable("enchantorder.auto.closed"), true);
		}
	}

	private void finish() {
		LocalPlayer player = Minecraft.getInstance().player;
		int used = player == null || planner.isCreative() ? 0 : Math.max(0, startLevel - player.experienceLevel);
		stop(Component.translatable("enchantorder.auto.done", used), false);
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
		int current = planner.currentStep();
		int reached = current < 0 ? steps.size() : current;
		if (reached < furthestStep) {
			// A step that was done isn't any more: the server must have said no to a click.
			stop(Component.translatable("enchantorder.auto.undone"), true);
			return;
		}
		furthestStep = reached;
		if (wait > 0) {
			wait--;
			return;
		}
		if (current < 0) {
			finish();
			return;
		}
		if (!menu.getCarried().isEmpty()) {
			// We never leave anything on the cursor, so someone else put it there.
			stop(Component.translatable("enchantorder.auto.holding_stop"), true);
			return;
		}

		if (resetNameBox) {
			// Take the item out once (it goes back in when its step comes). Putting it back resets the
			// anvil's name box, so a name typed there before doesn't rename it for an extra level.
			resetNameBox = false;
			if (!menu.getSlot(AnvilMenu.INPUT_SLOT).getItem().isEmpty()) {
				click(AnvilMenu.INPUT_SLOT);
				return;
			}
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

		// Both are in: check the anvil's result and price, then take it. The price comes from the server
		// and can lag a moment behind the slots, so only go by it once it has stayed the same for two
		// checks in a row.
		ItemStack result = menu.getSlot(AnvilMenu.RESULT_SLOT).getItem();
		int cost = menu.getCost();
		boolean settled = resultChecks > 0 && cost == lastCost && result.isEmpty() == lastResultEmpty;
		lastCost = cost;
		lastResultEmpty = result.isEmpty();
		resultChecks++;
		boolean survival = !planner.isCreative();
		if (settled && survival && cost >= AnvilOptimizer.TOO_EXPENSIVE) {
			// In survival the anvil shows no result at all for 40 levels or more.
			stop(Component.translatable("enchantorder.auto.too_expensive", step.number, cost), true);
			return;
		}
		if (!settled || result.isEmpty() || cost != step.cost) {
			if (resultChecks < MAX_RESULT_CHECKS + latencyTicks()) {
				wait = 1;
			} else if (result.isEmpty() || cost <= 0) {
				stop(Component.translatable("enchantorder.auto.no_result"), true);
			} else {
				// The anvil wants a different price than planned, so the plan no longer fits what you have.
				stop(Component.translatable("enchantorder.auto.cost_changed", step.number, cost, step.cost), true);
			}
			return;
		}
		if (!planner.matches(result, step.makes())) {
			stop(Component.translatable("enchantorder.auto.wrong_result"), true);
			return;
		}
		if (survival && player.experienceLevel < planner.remainingLevels()) {
			stop(Component.translatable("enchantorder.auto.not_enough", planner.remainingLevels(), player.experienceLevel), true);
			return;
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
		if (++clicks > steps.size() * CLICKS_PER_STEP + SPARE_CLICKS) {
			stop(Component.translatable("enchantorder.auto.stuck"), true);
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.gameMode.handleContainerInput(menu.containerId, slot, 0, ContainerInput.QUICK_MOVE, minecraft.player);
		resultChecks = 0;
		// Give the server time to answer before the next click, so that if it said no (or the anvil broke)
		// we find out before doing anything else.
		wait = Math.max(TICKS_BETWEEN_CLICKS, latencyTicks() + 2);
	}

	/** Your ping, in ticks. */
	private static int latencyTicks() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientPacketListener connection = minecraft.getConnection();
		if (connection == null || minecraft.player == null) {
			return 0;
		}
		PlayerInfo info = connection.getPlayerInfo(minecraft.player.getUUID());
		return info == null ? 0 : Math.clamp(info.getLatency() / 50, 0, 40);
	}
}
