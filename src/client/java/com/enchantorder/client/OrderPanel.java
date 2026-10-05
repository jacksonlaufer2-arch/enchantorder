package com.enchantorder.client;

import com.enchantorder.client.AnvilPlanner.Part;
import com.enchantorder.client.AnvilPlanner.Step;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The panel on the right of the anvil: the cheapest order to do the anvil steps in. The next step
 * to do is highlighted, and steps you've already done get a tick.
 */
final class OrderPanel extends PanelWidget {
	private static final int STEP = 24;
	private static final int CURRENT_BACKGROUND = 0xFF3D3A1F;
	private static final int CURRENT_BAR = 0xFFFFD84A;
	private static final int ITEM = 0xFF55FFFF;
	private static final int BOOK = 0xFFE3A6FF;
	private static final int DONE = 0xFF707070;
	private static final int DONE_TICK = 0xFF55AA55;
	private static final int AFFORDABLE = 0xFF80FF20;
	private static final int TOO_HIGH = 0xFFFF6060;
	private static final int ALL_DONE = 0xFF1E7A1E;

	private int shownCurrentStep = -2;

	OrderPanel(int x, int y, int width, int height) {
		super(x, y, width, height, Component.translatable("enchantorder.order.title"));
	}

	@Override
	int contentHeight() {
		return planner.steps().size() * STEP + 2;
	}

	@Override
	int scrollStep() {
		return STEP;
	}

	@Override
	void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		graphics.text(font, fit(getMessage().getString(), getWidth() - 14), getX() + 7, getY() + 8, LABEL, false);

		List<Step> steps = planner.steps();
		int width = rowWidth();
		if (!planner.hasSelection()) {
			drawWrapped(graphics, Component.translatable("enchantorder.order.empty"), listLeft() + 3, listTop() + 4, width - 6, TEXT_DIM);
		} else if (planner.isTooExpensive()) {
			int y = listTop() + 4;
			y += drawWrapped(graphics, Component.translatable("container.repair.expensive"), listLeft() + 3, y, width - 6, TOO_HIGH) + 4;
			drawWrapped(graphics, Component.translatable("enchantorder.order.too_expensive"), listLeft() + 3, y, width - 6, TEXT_DIM);
		} else {
			keepCurrentStepInView();
			Step hovered = null;
			graphics.enableScissor(listLeft(), listTop(), listRight(), listBottom());
			for (int i = 0; i < steps.size(); i++) {
				int stepY = listTop() + 1 + i * STEP - scroll;
				if (stepY + STEP <= listTop() || stepY >= listBottom()) {
					continue;
				}
				boolean isHovered = inList(mouseX, mouseY) && isInside(mouseX, mouseY, listLeft(), stepY, width, STEP);
				if (isHovered) {
					hovered = steps.get(i);
				}
				drawStep(graphics, steps.get(i), listLeft(), stepY, width, i == planner.currentStep(), isHovered);
			}
			graphics.disableScissor();
			if (hovered != null) {
				tooltip(graphics, stepTooltip(hovered), mouseX, mouseY);
			}
		}

		// Footer: the total, or "All done!" once every step has been done.
		if (!steps.isEmpty()) {
			int footerY = getBottom() - FOOTER + 7;
			boolean allDone = planner.currentStep() < 0;
			Component footer = allDone
					? Component.translatable("enchantorder.order.done")
					: Component.translatable("enchantorder.order.total", planner.totalLevels());
			graphics.text(font, fit(footer.getString(), getWidth() - 14), getX() + 7, footerY, allDone ? ALL_DONE : LABEL, false);
			if (isInside(mouseX, mouseY, getX(), footerY - 4, getWidth(), FOOTER - 2)) {
				tooltip(graphics, List.of(
						Component.translatable("enchantorder.order.total", planner.totalLevels()),
						Component.translatable("enchantorder.tooltip.work_penalty_after", planner.finalWorkPenalty())), mouseX, mouseY);
			}
		}
	}

	private void drawStep(GuiGraphicsExtractor graphics, Step step, int x, int y, int width, boolean current, boolean hovered) {
		if (current) {
			graphics.fill(x, y, x + width, y + STEP - 1, CURRENT_BACKGROUND);
			graphics.fill(x, y, x + 2, y + STEP - 1, CURRENT_BAR);
		} else if (hovered) {
			graphics.fill(x, y, x + width, y + STEP - 1, ROW_HOVER);
		}
		boolean done = step.isDone();

		String number = done ? "✔" : step.number + ".";
		graphics.text(font, number, x + 4, y + 3, done ? DONE_TICK : current ? 0xFFFFFF55 : TEXT_DIM, true);

		String cost = Component.translatable("enchantorder.order.cost", step.cost).getString();
		int costWidth = font.width(cost);
		graphics.text(font, cost, x + width - 3 - costWidth, y + 3, done ? DONE : costColor(step.cost), true);

		int textX = x + 17;
		graphics.text(font, fit(step.first.label().getString(), x + width - 3 - costWidth - 4 - textX), textX, y + 3,
				done ? DONE : partColor(step.first), true);
		graphics.text(font, "+", x + 9, y + 13, done ? DONE : TEXT_DIM, true);
		graphics.text(font, fit(step.second.label().getString(), x + width - 3 - textX), textX, y + 13,
				done ? DONE : partColor(step.second), true);
	}

	private List<Component> stepTooltip(Step step) {
		List<Component> lines = new ArrayList<>();
		lines.add(Component.translatable("enchantorder.tooltip.step", step.number).withStyle(ChatFormatting.YELLOW));
		addPart(lines, "enchantorder.tooltip.first_slot", step.first);
		addPart(lines, "enchantorder.tooltip.second_slot", step.second);
		lines.add(Component.translatable("enchantorder.tooltip.step_cost", step.cost)
				.withStyle(costColor(step.cost) == AFFORDABLE ? ChatFormatting.GREEN : ChatFormatting.RED));
		if (step.isDone()) {
			lines.add(Component.translatable("enchantorder.tooltip.step_done").withStyle(ChatFormatting.DARK_GREEN));
		}
		return lines;
	}

	private static void addPart(List<Component> lines, String key, Part part) {
		lines.add(Component.translatable(key, part.label()));
		// For books made in an earlier step, list what is on them.
		if (!part.isItem() && part.contents().size() > 1) {
			for (Component enchantment : part.contents()) {
				lines.add(Component.literal("  ").append(enchantment).withStyle(ChatFormatting.GRAY));
			}
		}
	}

	private void keepCurrentStepInView() {
		int current = planner.currentStep();
		if (current == shownCurrentStep) {
			return;
		}
		shownCurrentStep = current;
		if (current < 0) {
			return;
		}
		int top = current * STEP;
		int visible = listBottom() - listTop();
		if (top < scroll) {
			scroll = top;
		} else if (top + STEP + 2 > scroll + visible) {
			scroll = top + STEP + 2 - visible;
		}
		scroll = Math.clamp(scroll, 0, maxScroll());
	}

	private int costColor(int cost) {
		var player = Minecraft.getInstance().player;
		boolean affordable = planner.isCreative() || (player != null && player.experienceLevel >= cost);
		return affordable ? AFFORDABLE : TOO_HIGH;
	}

	private static int partColor(Part part) {
		return part.isItem() ? ITEM : BOOK;
	}

	@Override
	void click(double mouseX, double mouseY, int button) {
		// Nothing to click here; the panel only catches clicks so the anvil doesn't get them.
	}
}
