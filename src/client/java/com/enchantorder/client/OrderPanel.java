package com.enchantorder.client;

import com.enchantorder.client.AnvilPlanner.Part;
import com.enchantorder.client.AnvilPlanner.Step;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.inventory.AnvilMenu;

/**
 * The panel on the right of the anvil: the cheapest order to do the anvil steps in. The next step
 * to do is highlighted, and steps you've already done get a tick. With "Auto" ticked, an Apply button
 * at the bottom does the steps for you.
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
	private static final int GOOD_NEWS = 0xFF55FF55;
	private static final int BOX = 9;

	private final AnvilMenu menu;
	private final AutoEnchanter auto = AutoEnchanter.INSTANCE;
	private int shownCurrentStep = -2;

	OrderPanel(int x, int y, int width, int height, AnvilMenu menu) {
		super(x, y, width, height, Component.translatable("enchantorder.order.title"));
		this.menu = menu;
	}

	@Override
	int contentHeight() {
		return planner.steps().size() * STEP + 2 + messageHeight();
	}

	@Override
	int scrollStep() {
		return STEP;
	}

	// The "Auto" tick box, in the top right corner.
	int autoBoxX() {
		return getRight() - 7 - font.width(Component.translatable("enchantorder.auto.checkbox")) - BOX - 3;
	}

	int autoBoxY() {
		return getY() + 7;
	}

	private int autoWidth() {
		return getRight() - 5 - autoBoxX();
	}

	// The Apply / Stop button along the bottom, shown when "Auto" is ticked and there is something to do.
	boolean showsButton() {
		return EnchantOrderSettings.autoApply() && planner.currentStep() >= 0;
	}

	int buttonX() {
		return listLeft();
	}

	int buttonY() {
		return getBottom() - FOOTER + 3;
	}

	int buttonWidth() {
		return listRight() - listLeft();
	}

	@Override
	void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// Header: the title, and the "Auto" tick box.
		int boxX = autoBoxX();
		graphics.text(font, fit(getMessage().getString(), boxX - 4 - (getX() + 7)), getX() + 7, getY() + 8, LABEL, false);
		boolean autoHovered = isInside(mouseX, mouseY, boxX - 1, autoBoxY() - 1, autoWidth() + 2, BOX + 2);
		drawTickBox(graphics, boxX, autoBoxY(), EnchantOrderSettings.autoApply(), autoHovered);
		graphics.text(font, Component.translatable("enchantorder.auto.checkbox"), boxX + BOX + 3, autoBoxY() + 1, LABEL, false);
		if (autoHovered) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}

		List<Step> steps = planner.steps();
		int width = rowWidth();
		Step hovered = null;
		if (!planner.hasSelection()) {
			drawWrapped(graphics, Component.translatable("enchantorder.order.empty"), listLeft() + 3, listTop() + 4, width - 6, TEXT_DIM);
		} else if (planner.isTooExpensive()) {
			int y = listTop() + 4;
			y += drawWrapped(graphics, Component.translatable("container.repair.expensive"), listLeft() + 3, y, width - 6, TOO_HIGH) + 4;
			drawWrapped(graphics, Component.translatable("enchantorder.order.too_expensive"), listLeft() + 3, y, width - 6, TEXT_DIM);
		} else {
			keepCurrentStepInView();
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
			// What the automatic mode last said (finished, or why it stopped), under the steps.
			Component message = auto.message();
			if (!message.getString().isEmpty()) {
				int color = auto.isRunning() ? TEXT_DIM : auto.messageIsProblem() ? TOO_HIGH : GOOD_NEWS;
				drawWrapped(graphics, message, listLeft() + 3, listTop() + 4 + steps.size() * STEP - scroll, width - 6, color);
			}
			graphics.disableScissor();
		}

		// Footer: the Apply / Stop button, or the total.
		if (showsButton()) {
			drawAutoButton(graphics, mouseX, mouseY);
		} else if (!steps.isEmpty()) {
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

		// Tooltips (only one shows at a time).
		if (autoHovered) {
			tooltip(graphics, List.of(Component.translatable("enchantorder.auto.checkbox.tooltip")), mouseX, mouseY);
		} else if (hovered != null) {
			tooltip(graphics, stepTooltip(hovered), mouseX, mouseY);
		}
	}

	/** A small sunken box with a green tick inside when it's on. */
	private static void drawTickBox(GuiGraphicsExtractor graphics, int x, int y, boolean ticked, boolean hovered) {
		graphics.fill(x, y, x + BOX, y + BOX, 0xFF373737);
		graphics.fill(x + 1, y + 1, x + BOX, y + BOX, 0xFFFFFFFF);
		graphics.fill(x + 1, y + 1, x + BOX - 1, y + BOX - 1, hovered ? 0xFF9C9C9C : 0xFF8B8B8B);
		if (ticked) {
			graphics.fill(x + 2, y + 2, x + BOX - 2, y + BOX - 2, 0xFF2EB82E);
		}
	}

	private void drawAutoButton(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		Component label;
		List<Component> tooltip;
		boolean active;
		if (auto.isRunning()) {
			label = Component.translatable("enchantorder.auto.stop", planner.currentStep() + 1, planner.steps().size());
			tooltip = List.of(Component.translatable("enchantorder.auto.stop.tooltip"));
			active = true;
		} else {
			List<AutoEnchanter.Problem> problems = auto.problems(menu);
			if (problems.isEmpty()) {
				label = Component.translatable("enchantorder.auto.apply", planner.remainingLevels());
				tooltip = List.of(Component.translatable("enchantorder.auto.apply.tooltip", planner.remainingLevels()));
				active = true;
			} else {
				label = problems.getFirst().brief();
				tooltip = problems.stream().map(AutoEnchanter.Problem::detail).toList();
				active = false;
			}
		}
		boolean hovered = drawButton(graphics, buttonX(), buttonY(), buttonWidth(), 16, label, active, mouseX, mouseY);
		if (hovered) {
			tooltip(graphics, tooltip, mouseX, mouseY);
		}
	}

	private int messageHeight() {
		Component message = auto.message();
		if (message.getString().isEmpty() || planner.steps().isEmpty()) {
			return 0;
		}
		List<FormattedCharSequence> lines = font.split(message, Math.max(10, rowWidth() - 6));
		return lines.size() * (font.lineHeight + 1) + 6;
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

		// Leave room for the widest step number, so the names line up.
		int textX = x + 7 + font.width(planner.steps().size() + ".");
		String first = step.first.label().getString();
		// "3 lvl" when there's room, otherwise just "3" so the name has more space.
		String cost = Component.translatable("enchantorder.order.cost", step.cost).getString();
		if (font.width(first) > x + width - 2 - font.width(cost) - 4 - textX) {
			cost = String.valueOf(step.cost);
		}
		int costWidth = font.width(cost);
		graphics.text(font, cost, x + width - 2 - costWidth, y + 3, done ? DONE : costColor(step.cost), true);

		graphics.text(font, fit(first, x + width - 2 - costWidth - 4 - textX), textX, y + 3, done ? DONE : partColor(step.first), true);
		graphics.text(font, "+", x + 8, y + 13, done ? DONE : TEXT_DIM, true);
		graphics.text(font, fit(step.second.label().getString(), x + width - 2 - textX), textX, y + 13,
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
		if (button != 0) {
			return;
		}
		if (isInside(mouseX, mouseY, autoBoxX() - 1, autoBoxY() - 1, autoWidth() + 2, BOX + 2)) {
			playClickSound();
			EnchantOrderSettings.setAutoApply(!EnchantOrderSettings.autoApply());
			return;
		}
		if (showsButton() && isInside(mouseX, mouseY, buttonX(), buttonY(), buttonWidth(), 16)) {
			if (auto.isRunning()) {
				playClickSound();
				auto.stop(Component.translatable("enchantorder.auto.stopped"), false);
			} else if (auto.problems(menu).isEmpty()) {
				playClickSound();
				auto.start(menu);
			}
		}
	}
}
