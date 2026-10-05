package com.enchantorder.client;

import com.enchantorder.plan.AnvilOptimizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;

/**
 * Remembers which item you are planning for and which enchantments you ticked, and turns that into
 * a list of anvil steps.
 *
 * <p>There is only one planner, so the plan stays put when you take the item out of the anvil to
 * combine books, or close the anvil and come back later.
 */
public final class AnvilPlanner {
	public static final AnvilPlanner INSTANCE = new AnvilPlanner();

	public enum Status {
		/** Can be ticked. */
		AVAILABLE,
		/** Ticked. */
		SELECTED,
		/** The item already has it at the highest level. */
		ON_ITEM,
		/** Can't be combined with something on the item or something already ticked. */
		CONFLICT
	}

	/** One enchantment that fits the item. */
	public static final class Choice {
		public final Holder<Enchantment> enchantment;
		public final int maxLevel;
		public final int levelOnItem;
		public final boolean curse;
		private Status status = Status.AVAILABLE;
		private Component conflictsWith = Component.empty();

		private Choice(Holder<Enchantment> enchantment, int levelOnItem) {
			this.enchantment = enchantment;
			this.maxLevel = enchantment.value().getMaxLevel();
			this.levelOnItem = levelOnItem;
			this.curse = enchantment.is(EnchantmentTags.CURSE);
		}

		public Status status() {
			return status;
		}

		public Component conflictsWith() {
			return conflictsWith;
		}

		/** The lowest level worth putting on: one more than the item already has. */
		public int minLevel() {
			return levelOnItem + 1;
		}

		public boolean hasLevelChoice() {
			return minLevel() < maxLevel;
		}

		/** Levels this enchantment's book adds to an anvil step, per enchantment level. */
		int bookCostPerLevel() {
			return Math.max(1, enchantment.value().getAnvilCost() / 2);
		}
	}

	/** One of the two things you put in the anvil for a step, as it is shown. */
	public record Part(Component label, List<Component> contents, boolean isItem) {
	}

	/**
	 * Exactly what an item or book in the plan looks like, so it can be found in your inventory.
	 *
	 * @param enchantments  every enchantment on it, with levels
	 * @param isItem        true for the item being enchanted, false for an enchanted book
	 * @param startingThing true if you need to have it before you start (the item, or one of the single
	 *                      books), false if an earlier step makes it
	 */
	public record Need(Map<Holder<Enchantment>, Integer> enchantments, boolean isItem, boolean startingThing) {
	}

	/** One anvil step. */
	public static final class Step {
		public final int number;
		public final Part first;
		public final Part second;
		public final int cost;
		private final Need firstNeeds;
		private final Need secondNeeds;
		private final Need makes;
		private int usedBy = -1;
		private boolean done;

		private Step(int number, Part first, Part second, int cost, Need firstNeeds, Need secondNeeds, Need makes) {
			this.number = number;
			this.first = first;
			this.second = second;
			this.cost = cost;
			this.firstNeeds = firstNeeds;
			this.secondNeeds = secondNeeds;
			this.makes = makes;
		}

		/** True once the result of this step (or something made from it) is in your inventory. */
		public boolean isDone() {
			return done;
		}

		/** What goes in the anvil's first slot. */
		public Need firstNeeds() {
			return firstNeeds;
		}

		/** What goes in the anvil's second slot. */
		public Need secondNeeds() {
			return secondNeeds;
		}

		/** What comes out. */
		public Need makes() {
			return makes;
		}

		/** The enchantments on whatever this step makes. */
		Map<Holder<Enchantment>, Integer> result() {
			return makes.enchantments();
		}

		boolean makesItem() {
			return makes.isItem();
		}
	}

	// Enchantments belong to the world you are in, so the plan is thrown away when you join another one.
	private ClientPacketListener session;
	private ItemStack item = ItemStack.EMPTY;
	private List<Choice> choices = List.of();
	private final Map<Holder<Enchantment>, Integer> selected = new LinkedHashMap<>();
	private AnvilOptimizer.Plan plan;
	private List<Step> steps = List.of();
	private int currentStep = -1;
	private boolean creative;
	// Which anvil we last looked at, and whether it had something to plan for in its first slot.
	private AnvilMenu lastMenu;
	private boolean hadItem;
	// The work penalty of the book you have for each ticked enchantment. It is remembered after the book
	// is used up, so doing a step doesn't change the plan.
	private final Map<Holder<Enchantment>, Integer> bookPenalties = new HashMap<>();
	// True while the anvil steps are being done automatically: the plan must not change under it.
	private boolean locked;

	private AnvilPlanner() {
	}

	/** Whether the panels should be shown. */
	public boolean isActive() {
		return !item.isEmpty();
	}

	public ItemStack item() {
		return item;
	}

	public List<Choice> choices() {
		return choices;
	}

	public boolean hasSelection() {
		return !selected.isEmpty();
	}

	public int selectedLevel(Choice choice) {
		return selected.getOrDefault(choice.enchantment, choice.maxLevel);
	}

	/** True when something is ticked but no order keeps every step under 40 levels. */
	public boolean isTooExpensive() {
		return plan != null && !plan.isPossible();
	}

	public List<Step> steps() {
		return steps;
	}

	/** Index of the next step to do, or -1 when there are no steps or all of them are done. */
	public int currentStep() {
		return currentStep;
	}

	public int totalLevels() {
		return plan == null ? 0 : plan.totalLevels();
	}

	public int finalWorkPenalty() {
		return plan == null || !plan.isPossible() ? 0 : plan.result().workPenalty();
	}

	public int itemWorkPenalty() {
		return item.getOrDefault(DataComponents.REPAIR_COST, 0);
	}

	public boolean isCreative() {
		return creative;
	}

	/** Stops the plan from changing (used while the steps are done automatically). */
	public void setLocked(boolean locked) {
		this.locked = locked;
	}

	public boolean isLocked() {
		return locked;
	}

	/** Levels the steps that aren't done yet will cost. */
	public int remainingLevels() {
		int levels = 0;
		for (Step step : steps) {
			if (!step.done) {
				levels += step.cost;
			}
		}
		return levels;
	}

	/**
	 * The things the remaining steps need that you don't have: the item itself, or single-enchantment
	 * books (at the ticked level).
	 */
	public List<Component> missingThings(AnvilMenu menu) {
		List<ItemStack> owned = ownedStacks(menu);
		List<Component> missing = new ArrayList<>();
		for (Step step : steps) {
			if (step.done) {
				continue;
			}
			for (Need need : List.of(step.firstNeeds, step.secondNeeds)) {
				if (need.startingThing() && owned.stream().noneMatch(stack -> matches(stack, need))) {
					missing.add(need.isItem() ? item.getHoverName() : describeBooks(need));
				}
			}
		}
		return missing;
	}

	/** True if the stack is exactly the item or book described. */
	public boolean matches(ItemStack stack, Need need) {
		if (stack.isEmpty()) {
			return false;
		}
		if (need.isItem()) {
			return stack.getItem() == item.getItem() && !stack.has(DataComponents.STORED_ENCHANTMENTS)
					&& sameEnchantments(stack.getEnchantments(), need.enchantments());
		}
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		return stored != null && sameEnchantments(stored, need.enchantments());
	}

	/**
	 * Finds the inventory slot (not one of the anvil's own slots) holding something that matches, picking
	 * the one with the lowest work penalty. Returns -1 if there isn't one.
	 */
	public int findInInventory(AnvilMenu menu, Need need) {
		int best = -1;
		int bestPenalty = Integer.MAX_VALUE;
		for (Slot slot : menu.slots) {
			if (slot.index <= menu.getResultSlot() || !matches(slot.getItem(), need)) {
				continue;
			}
			int penalty = slot.getItem().getOrDefault(DataComponents.REPAIR_COST, 0);
			if (penalty < bestPenalty) {
				best = slot.index;
				bestPenalty = penalty;
			}
		}
		return best;
	}

	private Component describeBooks(Need need) {
		MutableComponent text = Component.empty();
		for (Map.Entry<Holder<Enchantment>, Integer> entry : need.enchantments().entrySet()) {
			if (!text.getSiblings().isEmpty()) {
				text.append(", ");
			}
			text.append(enchantmentName(entry.getKey(), entry.getValue()));
		}
		return text;
	}

	/** Called every tick while the anvil is open, to follow what is in the anvil and your inventory. */
	public void sync(AnvilMenu menu) {
		Minecraft minecraft = Minecraft.getInstance();
		Player player = minecraft.player;
		ClientPacketListener connection = minecraft.getConnection();
		if (player == null || connection == null) {
			return;
		}
		if (session != connection) {
			session = connection;
			clear();
		}
		boolean newAnvil = menu != lastMenu;
		lastMenu = menu;
		if (locked) {
			// Steps are being done automatically: just keep track of which ones are finished.
			updateProgress(menu);
			return;
		}
		if (creative != player.hasInfiniteMaterials()) {
			creative = player.hasInfiniteMaterials();
			recalculate();
		}

		ItemStack input = menu.getSlot(AnvilMenu.INPUT_SLOT).getItem();
		boolean hasItem = canPlanFor(input);
		if (hasItem) {
			if (item.isEmpty() || input.getItem() != item.getItem()) {
				// A different kind of item: start a fresh plan for it.
				startPlan(input, false);
			} else if (!isPartOfPlan(input)) {
				// The same kind of item, but not one this plan made (for example one that already has
				// some of the enchantments). Plan for it instead, keeping whatever still makes sense.
				startPlan(input, true);
			}
		} else if (selected.isEmpty() && !item.isEmpty() && (hadItem || newAnvil)) {
			// The item was just taken out (or the anvil was just opened without it) and nothing is
			// ticked, so there is nothing worth keeping on screen.
			clear();
		}
		hadItem = hasItem;

		if (bookPenaltiesChanged(ownedStacks(menu))) {
			recalculate();
		}
		updateProgress(menu);
	}

	public void toggle(Choice choice) {
		if (locked) {
			return;
		}
		if (choice.status == Status.SELECTED) {
			selected.remove(choice.enchantment);
		} else if (choice.status == Status.AVAILABLE && selected.size() < AnvilOptimizer.MAX_BOOKS) {
			selected.put(choice.enchantment, choice.maxLevel);
		} else {
			return;
		}
		recalculate();
	}

	public boolean canSelectMore() {
		return selected.size() < AnvilOptimizer.MAX_BOOKS;
	}

	public void changeLevel(Choice choice, int change) {
		Integer level = selected.get(choice.enchantment);
		if (level == null || locked) {
			return;
		}
		int newLevel = Math.clamp(level + change, choice.minLevel(), choice.maxLevel);
		if (newLevel != level) {
			selected.put(choice.enchantment, newLevel);
			recalculate();
		}
	}

	public void clearSelection() {
		if (locked) {
			return;
		}
		selected.clear();
		recalculate();
	}

	/**
	 * Ticks every enchantment you have a single-enchantment book for in your inventory (or in the anvil),
	 * at that book's level.
	 */
	public void selectBooksYouHave(AnvilMenu menu) {
		if (locked) {
			return;
		}
		boolean changed = false;
		for (ItemStack stack : ownedStacks(menu)) {
			ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
			if (stored == null || stored.size() != 1) {
				continue;
			}
			for (var entry : stored.entrySet()) {
				Choice choice = choiceFor(entry.getKey());
				if (choice == null) {
					continue;
				}
				int level = Math.min(entry.getIntValue(), choice.maxLevel);
				if (level < choice.minLevel()) {
					continue;
				}
				refreshStatuses();
				if (choice.status == Status.SELECTED && level > selected.get(choice.enchantment)) {
					selected.put(choice.enchantment, level);
					changed = true;
				} else if (choice.status == Status.AVAILABLE && canSelectMore()) {
					selected.put(choice.enchantment, level);
					changed = true;
				}
			}
		}
		if (changed) {
			recalculate();
		}
	}

	private void clear() {
		item = ItemStack.EMPTY;
		choices = List.of();
		selected.clear();
		bookPenalties.clear();
		plan = null;
		steps = List.of();
		currentStep = -1;
	}

	private void startPlan(ItemStack newItem, boolean keepSelection) {
		Map<Holder<Enchantment>, Integer> previous = keepSelection ? new LinkedHashMap<>(selected) : Map.of();
		item = newItem.copyWithCount(1);
		choices = buildChoices(item);
		selected.clear();
		bookPenalties.clear();
		for (Choice choice : choices) {
			Integer level = previous.get(choice.enchantment);
			if (level == null) {
				continue;
			}
			refreshStatuses();
			if (choice.status == Status.AVAILABLE) {
				selected.put(choice.enchantment, Math.clamp(level, choice.minLevel(), choice.maxLevel));
			}
		}
		recalculate();
	}

	private static List<Choice> buildChoices(ItemStack item) {
		Registry<Enchantment> registry = enchantmentRegistry();
		if (registry == null) {
			return List.of();
		}
		// Use the same order as enchantment tooltips, with anything not listed there sorted by name after.
		Map<Holder<Enchantment>, Integer> tooltipOrder = new HashMap<>();
		registry.get(EnchantmentTags.TOOLTIP_ORDER).ifPresent(tag -> {
			for (Holder<Enchantment> holder : tag) {
				tooltipOrder.putIfAbsent(holder, tooltipOrder.size());
			}
		});

		ItemEnchantments onItem = item.getEnchantments();
		List<Choice> list = new ArrayList<>();
		registry.listElements()
				.filter(holder -> holder.value().canEnchant(item))
				.forEach(holder -> list.add(new Choice(holder, onItem.getLevel(holder))));
		list.sort(Comparator.<Choice, Boolean>comparing(choice -> choice.curse)
				.thenComparing(choice -> tooltipOrder.getOrDefault(choice.enchantment, Integer.MAX_VALUE))
				.thenComparing(choice -> choice.enchantment.value().description().getString()));
		return List.copyOf(list);
	}

	/** True for tools, weapons and armor: anything an anvil can put at least one enchantment on. */
	private static boolean canPlanFor(ItemStack stack) {
		if (stack.isEmpty() || stack.has(DataComponents.STORED_ENCHANTMENTS) || !EnchantmentHelper.canStoreEnchantments(stack)) {
			return false;
		}
		Registry<Enchantment> registry = enchantmentRegistry();
		return registry != null && registry.listElements().anyMatch(holder -> holder.value().canEnchant(stack));
	}

	private static Registry<Enchantment> enchantmentRegistry() {
		ClientPacketListener connection = Minecraft.getInstance().getConnection();
		return connection == null ? null : connection.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
	}

	private Choice choiceFor(Holder<Enchantment> enchantment) {
		for (Choice choice : choices) {
			if (choice.enchantment.equals(enchantment)) {
				return choice;
			}
		}
		return null;
	}

	/** Works out which enchantments can still be ticked. */
	private void refreshStatuses() {
		ItemEnchantments onItem = item.getEnchantments();
		for (Choice choice : choices) {
			choice.conflictsWith = Component.empty();
			if (choice.levelOnItem >= choice.maxLevel) {
				choice.status = Status.ON_ITEM;
				continue;
			}
			Holder<Enchantment> clash = findClash(choice.enchantment, onItem.keySet());
			if (clash != null) {
				choice.status = Status.CONFLICT;
				choice.conflictsWith = clash.value().description();
			} else if (selected.containsKey(choice.enchantment)) {
				choice.status = Status.SELECTED;
			} else if ((clash = findClash(choice.enchantment, selected.keySet())) != null) {
				choice.status = Status.CONFLICT;
				choice.conflictsWith = clash.value().description();
			} else {
				choice.status = Status.AVAILABLE;
			}
		}
	}

	private static Holder<Enchantment> findClash(Holder<Enchantment> enchantment, Iterable<Holder<Enchantment>> others) {
		for (Holder<Enchantment> other : others) {
			if (!other.equals(enchantment) && !Enchantment.areCompatible(enchantment, other)) {
				return other;
			}
		}
		return null;
	}

	/**
	 * Notes the work penalty of the book you have for each ticked enchantment. Returns true if any of them
	 * changed. A book that isn't there (yet, or any more) keeps the penalty it had last time.
	 */
	private boolean bookPenaltiesChanged(List<ItemStack> owned) {
		boolean changed = false;
		for (Map.Entry<Holder<Enchantment>, Integer> entry : selected.entrySet()) {
			Need book = new Need(Map.of(entry.getKey(), entry.getValue()), false, true);
			int best = Integer.MAX_VALUE;
			for (ItemStack stack : owned) {
				if (matches(stack, book)) {
					best = Math.min(best, stack.getOrDefault(DataComponents.REPAIR_COST, 0));
				}
			}
			int known = bookPenalties.getOrDefault(entry.getKey(), 0);
			if (best != Integer.MAX_VALUE && best != known) {
				bookPenalties.put(entry.getKey(), best);
				changed = true;
			}
		}
		return changed;
	}

	/** Runs the order calculator again after the ticked enchantments (or levels) changed. */
	private void recalculate() {
		bookPenalties.keySet().retainAll(selected.keySet());
		if (lastMenu != null) {
			bookPenaltiesChanged(ownedStacks(lastMenu));
		}
		refreshStatuses();
		plan = null;
		steps = List.of();
		currentStep = -1;
		if (selected.isEmpty() || item.isEmpty()) {
			return;
		}

		List<Choice> picked = new ArrayList<>();
		List<AnvilOptimizer.Book> books = new ArrayList<>();
		for (Choice choice : choices) {
			Integer level = selected.get(choice.enchantment);
			if (level == null) {
				continue;
			}
			// The anvil charges for the level the enchantment ends up at. Putting a book on top of
			// another book never merges levels here (every enchantment is on its own book), but the
			// item might already have the enchantment at the same level, which bumps it up by one.
			int onItem = choice.levelOnItem == level ? Math.min(level + 1, choice.maxLevel) : Math.max(level, choice.levelOnItem);
			picked.add(choice);
			books.add(new AnvilOptimizer.Book(level * choice.bookCostPerLevel(), onItem * choice.bookCostPerLevel(),
					bookPenalties.getOrDefault(choice.enchantment, 0)));
		}

		int maxStep = creative ? Integer.MAX_VALUE : AnvilOptimizer.TOO_EXPENSIVE - 1;
		plan = AnvilOptimizer.solve(itemWorkPenalty(), books, maxStep);
		if (plan.isPossible()) {
			steps = describeSteps(plan, picked);
		}
	}

	private List<Step> describeSteps(AnvilOptimizer.Plan plan, List<Choice> picked) {
		Map<AnvilOptimizer.Node, Integer> stepIndex = new IdentityHashMap<>();
		for (int i = 0; i < plan.steps().size(); i++) {
			stepIndex.put(plan.steps().get(i), i);
		}

		List<Step> result = new ArrayList<>();
		for (int i = 0; i < plan.steps().size(); i++) {
			AnvilOptimizer.Node node = plan.steps().get(i);
			result.add(new Step(i + 1, describe(node.left(), stepIndex, picked), describe(node.right(), stepIndex, picked),
					node.stepCost(), need(node.left(), picked), need(node.right(), picked), need(node, picked)));
		}
		for (int i = 0; i < plan.steps().size(); i++) {
			AnvilOptimizer.Node node = plan.steps().get(i);
			for (AnvilOptimizer.Node input : List.of(node.left(), node.right())) {
				Integer from = stepIndex.get(input);
				if (from != null) {
					result.get(from).usedBy = i;
				}
			}
		}
		return List.copyOf(result);
	}

	/** The enchantments on an item or book in the plan. */
	private Need need(AnvilOptimizer.Node node, List<Choice> picked) {
		Map<Holder<Enchantment>, Integer> enchantments = new HashMap<>();
		if (node.isItem()) {
			for (var entry : item.getEnchantments().entrySet()) {
				enchantments.put(entry.getKey(), entry.getIntValue());
			}
		}
		for (int book = 0; book < picked.size(); book++) {
			if ((node.books() & (1 << book)) != 0) {
				enchantments.put(picked.get(book).enchantment, selected.get(picked.get(book).enchantment));
			}
		}
		return new Need(Map.copyOf(enchantments), node.isItem(), !node.isStep());
	}

	private Part describe(AnvilOptimizer.Node node, Map<AnvilOptimizer.Node, Integer> stepIndex, List<Choice> picked) {
		if (node.isItem()) {
			return new Part(item.getHoverName(), List.of(), true);
		}
		List<Component> contents = new ArrayList<>();
		for (int book = 0; book < picked.size(); book++) {
			if ((node.books() & (1 << book)) != 0) {
				Choice choice = picked.get(book);
				contents.add(enchantmentName(choice.enchantment, selected.get(choice.enchantment)));
			}
		}
		if (!node.isStep()) {
			return new Part(contents.getFirst(), contents, false);
		}
		Component label = Component.translatable("enchantorder.order.book_from_step", stepIndex.get(node) + 1);
		return new Part(label, contents, false);
	}

	/** Ticks off steps whose results are already in your inventory, and finds the next step to do. */
	private void updateProgress(AnvilMenu menu) {
		currentStep = -1;
		if (steps.isEmpty()) {
			return;
		}
		List<ItemStack> owned = ownedStacks(menu);
		for (int i = steps.size() - 1; i >= 0; i--) {
			Step step = steps.get(i);
			// A step also counts as done once the thing it made has been used up in a later step.
			step.done = (step.usedBy >= 0 && steps.get(step.usedBy).done) || owned.stream().anyMatch(stack -> matches(stack, step.makes));
		}
		for (int i = 0; i < steps.size(); i++) {
			if (!steps.get(i).done) {
				currentStep = i;
				return;
			}
		}
	}

	/** True if the stack is the planned item before or after one of the steps. */
	private boolean isPartOfPlan(ItemStack stack) {
		Map<Holder<Enchantment>, Integer> original = new HashMap<>();
		for (var entry : item.getEnchantments().entrySet()) {
			original.put(entry.getKey(), entry.getIntValue());
		}
		if (sameEnchantments(stack.getEnchantments(), original)) {
			return true;
		}
		for (Step step : steps) {
			if (step.makes.isItem() && sameEnchantments(stack.getEnchantments(), step.makes.enchantments())) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameEnchantments(ItemEnchantments actual, Map<Holder<Enchantment>, Integer> expected) {
		if (actual.size() != expected.size()) {
			return false;
		}
		for (Map.Entry<Holder<Enchantment>, Integer> entry : expected.entrySet()) {
			if (actual.getLevel(entry.getKey()) != entry.getValue()) {
				return false;
			}
		}
		return true;
	}

	/** Everything in your inventory, in the anvil's two input slots, and on your cursor. */
	private static List<ItemStack> ownedStacks(AnvilMenu menu) {
		List<ItemStack> stacks = new ArrayList<>();
		for (Slot slot : menu.slots) {
			if (slot.index != menu.getResultSlot() && !slot.getItem().isEmpty()) {
				stacks.add(slot.getItem());
			}
		}
		if (!menu.getCarried().isEmpty()) {
			stacks.add(menu.getCarried());
		}
		return stacks;
	}

	/** "Sharpness V", or just "Mending" for enchantments that only have one level. */
	public static Component enchantmentName(Holder<Enchantment> enchantment, int level) {
		MutableComponent name = enchantment.value().description().copy();
		if (level != 1 || enchantment.value().getMaxLevel() != 1) {
			name.append(" ").append(Component.translatable("enchantment.level." + level));
		}
		return name;
	}
}
