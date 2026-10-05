package com.enchantorder.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.enchantorder.plan.AnvilOptimizer.Book;
import com.enchantorder.plan.AnvilOptimizer.Node;
import com.enchantorder.plan.AnvilOptimizer.Plan;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class AnvilOptimizerTest {
	private static final int SURVIVAL = AnvilOptimizer.TOO_EXPENSIVE - 1;

	private static List<Book> books(int... costs) {
		List<Book> books = new ArrayList<>();
		for (int cost : costs) {
			books.add(new Book(cost, cost));
		}
		return books;
	}

	@Test
	void nothingToDo() {
		Plan plan = AnvilOptimizer.solve(0, List.of(), SURVIVAL);
		assertTrue(plan.isPossible());
		assertTrue(plan.steps().isEmpty());
	}

	@Test
	void oneBook() {
		// Sharpness V on a new sword: 5 levels.
		Plan plan = AnvilOptimizer.solve(0, books(5), SURVIVAL);
		assertEquals(1, plan.steps().size());
		assertEquals(5, plan.totalLevels());
		assertEquals(1, plan.result().workPenalty());
	}

	@Test
	void itemWorkPenaltyIsCharged() {
		// An item that has been in an anvil twice already (penalty 3) pays 3 extra.
		Plan plan = AnvilOptimizer.solve(3, books(5), SURVIVAL);
		assertEquals(8, plan.totalLevels());
		assertEquals(7, plan.result().workPenalty());
	}

	@Test
	void realExamples() {
		// Diamond sword: Sharpness V, Unbreaking III, Mending, Looting III, Fire Aspect II, Sweeping Edge III, Knockback II.
		assertEquals(49, AnvilOptimizer.solve(0, books(5, 3, 2, 6, 4, 6, 2), SURVIVAL).totalLevels());
		// Helmet: Protection IV, Respiration III, Aqua Affinity, Unbreaking III, Mending, Thorns III.
		assertEquals(45, AnvilOptimizer.solve(0, books(4, 6, 2, 3, 2, 12), SURVIVAL).totalLevels());
		// Pickaxe: Efficiency V, Fortune III, Unbreaking III, Mending.
		assertEquals(23, AnvilOptimizer.solve(0, books(5, 6, 3, 2), SURVIVAL).totalLevels());
	}

	@Test
	void tooExpensiveInSurvivalButFineInCreative() {
		// An item with a huge work penalty can't take anything more in survival.
		assertFalse(AnvilOptimizer.solve(63, books(1), SURVIVAL).isPossible());
		assertTrue(AnvilOptimizer.solve(63, books(1), Integer.MAX_VALUE).isPossible());
	}

	@Test
	void stepsAreInADoableOrder() {
		Plan plan = AnvilOptimizer.solve(0, books(5, 3, 2, 6, 4, 6, 2), SURVIVAL);
		Map<Node, Boolean> made = new IdentityHashMap<>();
		for (Node step : plan.steps()) {
			for (Node input : List.of(step.left(), step.right())) {
				assertTrue(!input.isStep() || made.containsKey(input), "a step uses something that isn't made yet");
			}
			assertFalse(step.right().isItem(), "the item must always go in the first slot");
			made.put(step, true);
		}
		assertTrue(plan.steps().getLast() == plan.result());
	}

	/** Compares against trying every possible order the slow way, for small random cases. */
	@Test
	void matchesBruteForce() {
		Random random = new Random(1234);
		int[] multipliers = {1, 1, 2, 2, 4};
		for (int round = 0; round < 400; round++) {
			int count = 1 + random.nextInt(5);
			int itemPenalty = random.nextInt(4) == 0 ? new int[] {1, 3, 7}[random.nextInt(3)] : 0;
			int maxStep = random.nextInt(8) == 0 ? Integer.MAX_VALUE : SURVIVAL;
			List<Book> books = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				int onBook = multipliers[random.nextInt(multipliers.length)] * (1 + random.nextInt(5));
				books.add(new Book(onBook, random.nextInt(5) == 0 ? onBook + 1 : onBook));
			}

			BruteForce brute = new BruteForce(books, maxStep);
			List<Piece> start = new ArrayList<>();
			start.add(new Piece(true, 0, itemPenalty));
			for (int i = 0; i < count; i++) {
				start.add(new Piece(false, 1 << i, 0));
			}
			brute.search(start, 0, 0);

			Plan plan = AnvilOptimizer.solve(itemPenalty, books, maxStep);
			if (brute.bestLevels == Long.MAX_VALUE) {
				assertFalse(plan.isPossible(), "round " + round);
				continue;
			}
			assertTrue(plan.isPossible(), "round " + round);
			assertEquals(brute.bestLevels, plan.totalLevels(), "levels, round " + round);
			assertEquals(brute.bestPenalty, plan.result().workPenalty(), "work penalty, round " + round);
			assertEquals(brute.bestXp, plan.result().totalXp(), "xp, round " + round);
		}
	}

	private record Piece(boolean item, int books, int penalty) {
	}

	private static final class BruteForce {
		private final List<Book> books;
		private final int maxStep;
		long bestLevels = Long.MAX_VALUE;
		int bestPenalty = Integer.MAX_VALUE;
		long bestXp = Long.MAX_VALUE;

		BruteForce(List<Book> books, int maxStep) {
			this.books = books;
			this.maxStep = maxStep;
		}

		void search(List<Piece> pieces, long levels, long xp) {
			if (pieces.size() == 1) {
				Piece last = pieces.getFirst();
				if (levels < bestLevels || (levels == bestLevels && (last.penalty < bestPenalty || (last.penalty == bestPenalty && xp < bestXp)))) {
					bestLevels = levels;
					bestPenalty = last.penalty;
					bestXp = xp;
				}
				return;
			}
			for (int a = 0; a < pieces.size(); a++) {
				for (int b = 0; b < pieces.size(); b++) {
					Piece left = pieces.get(a);
					Piece right = pieces.get(b);
					if (a == b || right.item) {
						continue;
					}
					long cost = (long) left.penalty + right.penalty;
					for (int i = 0; i < books.size(); i++) {
						if ((right.books & (1 << i)) != 0) {
							cost += left.item ? books.get(i).costOnItem() : books.get(i).costOnBook();
						}
					}
					if (cost > maxStep) {
						continue;
					}
					List<Piece> next = new ArrayList<>();
					for (int k = 0; k < pieces.size(); k++) {
						if (k != a && k != b) {
							next.add(pieces.get(k));
						}
					}
					next.add(new Piece(left.item, left.books | right.books, Math.max(left.penalty, right.penalty) * 2 + 1));
					search(next, levels + cost, xp + AnvilOptimizer.xpForLevels((int) cost));
				}
			}
		}
	}
}
