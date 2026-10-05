package com.enchantorder.plan;

import java.util.ArrayList;
import java.util.List;

/**
 * Works out the cheapest order to put a set of enchanted books onto one item with an anvil.
 *
 * <p>This class knows nothing about Minecraft itself (it only works with numbers), so it can be
 * tested without starting the game. The anvil rules it copies from vanilla are:
 * <ul>
 *   <li>Each anvil use costs: the left item's work penalty + the right item's work penalty
 *       + the enchantments on the right item (level x the enchantment's book cost).</li>
 *   <li>The result's work penalty is {@code max(left, right) * 2 + 1}, so every use makes the
 *       next one more expensive (0, 1, 3, 7, 15, 31...).</li>
 *   <li>A step that costs 40 levels or more is "Too Expensive!" in survival.</li>
 * </ul>
 *
 * <p>It tries every possible way of pairing up the books (books can be combined with each other
 * before going on the item) and keeps the one with the lowest total level cost. Ties are broken by
 * the lowest work penalty on the finished item, then by the fewest raw XP points.
 */
public final class AnvilOptimizer {
	/** A single anvil step costing this many levels or more shows "Too Expensive!" in survival. */
	public static final int TOO_EXPENSIVE = 40;
	/** The search grows quickly with each extra book, so cap it to keep the game smooth. */
	public static final int MAX_BOOKS = 12;

	private AnvilOptimizer() {
	}

	/**
	 * One enchanted book (with a single enchantment) that should end up on the item.
	 *
	 * @param costOnBook  levels this book adds to a step when it goes in the right slot on top of another book
	 * @param costOnItem  levels this book adds to a step when its enchantment lands on the item. This is
	 *                    usually the same as {@code costOnBook}, but differs when the item already has the
	 *                    enchantment at a lower level.
	 * @param workPenalty the book's own prior work penalty: 0 for a fresh book, more if it has been
	 *                    through an anvil before
	 */
	public record Book(int costOnBook, int costOnItem, int workPenalty) {
		/** A fresh book, straight from an enchanting table, a librarian or a chest. */
		public Book(int costOnBook, int costOnItem) {
			this(costOnBook, costOnItem, 0);
		}
	}

	/**
	 * One thing in the plan: the item, a single starting book, or the result of an anvil step.
	 *
	 * @param book        index into the book list for a starting book, otherwise -1
	 * @param left        what goes in the anvil's first slot to make this (null if not made in the anvil)
	 * @param right       what goes in the anvil's second slot to make this (null if not made in the anvil)
	 * @param stepCost    levels the anvil step that made this costs (0 if not made in the anvil)
	 * @param workPenalty the "prior work penalty" (RepairCost) of this item or book
	 * @param totalLevels levels spent on every step that went into this, including this one
	 * @param totalXp     XP points those steps cost if you start each step with exactly enough levels
	 * @param books       bit mask of the starting books that are in this
	 * @param isItem      true if this is the item being enchanted (or a later version of it)
	 */
	public record Node(int book, Node left, Node right, int stepCost, int workPenalty, int totalLevels,
			long totalXp, int books, boolean isItem) {
		public boolean isStep() {
			return left != null;
		}
	}

	/**
	 * The finished plan.
	 *
	 * @param result the fully enchanted item; null when nothing was asked for or it can't be done
	 * @param steps  the anvil steps in the order to do them. All the book-on-book steps come first,
	 *               then the item stays in the first slot while the books go on one at a time.
	 */
	public record Plan(Node result, List<Node> steps) {
		public boolean isPossible() {
			return result != null;
		}

		public int totalLevels() {
			return result == null ? 0 : result.totalLevels();
		}

		public int highestStep() {
			int highest = 0;
			for (Node step : steps) {
				highest = Math.max(highest, step.stepCost());
			}
			return highest;
		}
	}

	/**
	 * Finds the cheapest order.
	 *
	 * @param itemWorkPenalty the item's current prior work penalty (its RepairCost, 0 for a new item)
	 * @param books           the books to put on it, at most {@link #MAX_BOOKS}
	 * @param maxStepCost     the most levels one step may cost: {@code TOO_EXPENSIVE - 1} in survival,
	 *                        {@link Integer#MAX_VALUE} in creative
	 */
	public static Plan solve(int itemWorkPenalty, List<Book> books, int maxStepCost) {
		int count = books.size();
		if (count > MAX_BOOKS) {
			throw new IllegalArgumentException("Too many books: " + count);
		}
		Node item = new Node(-1, null, null, 0, Math.max(0, itemWorkPenalty), 0, 0, 0, true);
		if (count == 0) {
			return new Plan(item, List.of());
		}

		int all = (1 << count) - 1;

		// How many levels each group of books adds to a step, when put onto a book or onto the item.
		int[] costOnBook = new int[all + 1];
		int[] costOnItem = new int[all + 1];
		for (int mask = 1; mask <= all; mask++) {
			int lowest = Integer.numberOfTrailingZeros(mask);
			costOnBook[mask] = costOnBook[mask & (mask - 1)] + books.get(lowest).costOnBook();
			costOnItem[mask] = costOnItem[mask & (mask - 1)] + books.get(lowest).costOnItem();
		}

		// bookOnly[mask]: cheapest ways to merge exactly these books into one book.
		// withItem[mask]: cheapest ways to get exactly these books onto the item.
		// Each keeps one entry per useful work penalty, because a cheaper result with a higher
		// penalty can still end up costing more later.
		@SuppressWarnings("unchecked")
		List<Node>[] bookOnly = new List[all + 1];
		@SuppressWarnings("unchecked")
		List<Node>[] withItem = new List[all + 1];
		withItem[0] = List.of(item);

		for (int mask = 1; mask <= all; mask++) {
			if (Integer.bitCount(mask) == 1) {
				int index = Integer.numberOfTrailingZeros(mask);
				bookOnly[mask] = List.of(new Node(index, null, null, 0, Math.max(0, books.get(index).workPenalty()), 0, 0, mask, false));
			} else {
				List<Node> options = new ArrayList<>();
				int lowestBit = mask & -mask;
				// Split the books into two groups. Requiring the lowest book in the first group
				// stops us from looking at every split twice; both slot orders are tried below.
				for (int first = (mask - 1) & mask; first > 0; first = (first - 1) & mask) {
					if ((first & lowestBit) == 0) {
						continue;
					}
					int second = mask ^ first;
					for (Node a : bookOnly[first]) {
						for (Node b : bookOnly[second]) {
							offer(options, merge(a, b, costOnBook[second], maxStepCost, false), maxStepCost, false);
							offer(options, merge(b, a, costOnBook[first], maxStepCost, false), maxStepCost, false);
						}
					}
				}
				bookOnly[mask] = options;
			}

			List<Node> options = new ArrayList<>();
			// The last step adds the books in "added" (already merged into one book) to an item
			// that already has the rest.
			for (int added = mask; added > 0; added = (added - 1) & mask) {
				int already = mask ^ added;
				for (Node target : withItem[already]) {
					for (Node book : bookOnly[added]) {
						offer(options, merge(target, book, costOnItem[added], maxStepCost, true), maxStepCost, mask == all);
					}
				}
			}
			withItem[mask] = options;
		}

		Node best = null;
		for (Node option : withItem[all]) {
			if (best == null || isBetterFinal(option, best)) {
				best = option;
			}
		}
		if (best == null) {
			return new Plan(null, List.of());
		}
		return new Plan(best, orderSteps(best));
	}

	/** Puts {@code right} into the anvil on top of {@code left}. Returns null if the step is too expensive. */
	private static Node merge(Node left, Node right, int enchantCost, int maxStepCost, boolean isItem) {
		long cost = (long) left.workPenalty() + right.workPenalty() + enchantCost;
		if (cost > maxStepCost) {
			return null;
		}
		int stepCost = (int) cost;
		int penalty = (int) Math.min((long) Math.max(left.workPenalty(), right.workPenalty()) * 2L + 1L, Integer.MAX_VALUE);
		return new Node(-1, left, right, stepCost, penalty,
				left.totalLevels() + right.totalLevels() + stepCost,
				left.totalXp() + right.totalXp() + xpForLevels(stepCost),
				left.books() | right.books(), isItem);
	}

	/** Adds a candidate unless another one is at least as good in every way. */
	private static void offer(List<Node> options, Node candidate, int maxStepCost, boolean keepUnmergeable) {
		if (candidate == null) {
			return;
		}
		// Anything whose work penalty alone is over the limit can never go in an anvil again.
		if (!keepUnmergeable && candidate.workPenalty() > maxStepCost) {
			return;
		}
		for (Node existing : options) {
			if (atLeastAsGood(existing, candidate)) {
				return;
			}
		}
		options.removeIf(existing -> atLeastAsGood(candidate, existing));
		options.add(candidate);
	}

	private static boolean atLeastAsGood(Node a, Node b) {
		return a.workPenalty() <= b.workPenalty() && a.totalLevels() <= b.totalLevels() && a.totalXp() <= b.totalXp();
	}

	private static boolean isBetterFinal(Node a, Node b) {
		if (a.totalLevels() != b.totalLevels()) {
			return a.totalLevels() < b.totalLevels();
		}
		if (a.workPenalty() != b.workPenalty()) {
			return a.workPenalty() < b.workPenalty();
		}
		return a.totalXp() < b.totalXp();
	}

	/**
	 * Lists the steps so that all book-on-book merging happens first (in the order the books are
	 * needed), and then the item goes in the first slot and stays there for the remaining steps.
	 */
	private static List<Node> orderSteps(Node result) {
		List<Node> itemSteps = new ArrayList<>();
		for (Node node = result; node.isStep(); node = node.left()) {
			itemSteps.addFirst(node);
		}
		List<Node> steps = new ArrayList<>();
		for (Node itemStep : itemSteps) {
			addBookSteps(itemStep.right(), steps);
		}
		steps.addAll(itemSteps);
		return steps;
	}

	private static void addBookSteps(Node node, List<Node> steps) {
		if (!node.isStep()) {
			return;
		}
		addBookSteps(node.left(), steps);
		addBookSteps(node.right(), steps);
		steps.add(node);
	}

	/** XP points needed to go from level 0 to {@code level}, using the Java Edition formula. */
	public static long xpForLevels(int level) {
		long l = level;
		if (l <= 16) {
			return l * l + 6 * l;
		}
		if (l <= 31) {
			return (5 * l * l - 81 * l + 720) / 2;
		}
		return (9 * l * l - 325 * l + 4440) / 2;
	}
}
