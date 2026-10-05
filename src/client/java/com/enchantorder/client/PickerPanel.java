package com.enchantorder.client;

import com.enchantorder.client.AnvilPlanner.Choice;
import com.enchantorder.client.AnvilPlanner.Status;
import com.enchantorder.plan.AnvilOptimizer;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AnvilMenu;

/**
 * The panel on the left of the anvil: the item you're planning for, and a list of every enchantment
 * that fits it. Click one to tick it, and use the little arrows to pick a lower level.
 */
final class PickerPanel extends PanelWidget {
	private static final int ROW = 12;
	private static final int LEVEL_COLUMN = 32;
	private static final int ROW_SELECTED = 0xFF233D27;
	private static final int CHECK = 0xFF55FF55;
	private static final int CURSE = 0xFFFF6E6E;
	private static final int ON_ITEM = 0xFF6FA56F;

	private final AnvilMenu menu;

	PickerPanel(int x, int y, int width, int height, AnvilMenu menu) {
		super(x, y, width, height, Component.translatable("enchantorder.picker.title"));
		this.menu = menu;
	}

	@Override
	int contentHeight() {
		return planner.choices().size() * ROW + 2;
	}

	@Override
	int scrollStep() {
		return ROW * 2;
	}

	private int buttonWidth() {
		return (getWidth() - PADDING * 2 - 4) / 2;
	}

	private int buttonY() {
		return getBottom() - FOOTER + 3;
	}

	private int myBooksX() {
		return listLeft();
	}

	private int clearX() {
		return listRight() - buttonWidth();
	}

	@Override
	void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		// Header: the item and its name.
		graphics.item(planner.item(), getX() + 5, getY() + 4);
		graphics.text(font, fit(planner.item().getHoverName().getString(), getWidth() - 30), getX() + 24, getY() + 8, LABEL, false);
		boolean headerHovered = isInside(mouseX, mouseY, getX(), getY(), getWidth(), HEADER);

		// The list of enchantments.
		List<Choice> choices = planner.choices();
		int rowWidth = rowWidth();
		Choice hoveredChoice = null;
		graphics.enableScissor(listLeft(), listTop(), listRight(), listBottom());
		for (int i = 0; i < choices.size(); i++) {
			int rowY = listTop() + 1 + i * ROW - scroll;
			if (rowY + ROW <= listTop() || rowY >= listBottom()) {
				continue;
			}
			boolean hovered = inList(mouseX, mouseY) && isInside(mouseX, mouseY, listLeft(), rowY, rowWidth, ROW);
			if (hovered) {
				hoveredChoice = choices.get(i);
			}
			drawRow(graphics, choices.get(i), listLeft(), rowY, rowWidth, hovered, mouseX);
		}
		graphics.disableScissor();

		if (choices.isEmpty()) {
			drawWrapped(graphics, Component.translatable("enchantorder.picker.none"), listLeft() + 3, listTop() + 4, rowWidth - 6, TEXT_DIM);
		}

		// Footer buttons.
		boolean myBooksHovered = drawButton(graphics, myBooksX(), buttonY(), buttonWidth(), 16,
				Component.translatable("enchantorder.button.my_books"), true, mouseX, mouseY);
		boolean clearHovered = drawButton(graphics, clearX(), buttonY(), buttonWidth(), 16,
				Component.translatable("enchantorder.button.clear"), planner.hasSelection(), mouseX, mouseY);

		// Tooltips.
		if (myBooksHovered) {
			tooltip(graphics, List.of(Component.translatable("enchantorder.button.my_books.tooltip")), mouseX, mouseY);
		} else if (clearHovered && planner.hasSelection()) {
			tooltip(graphics, List.of(Component.translatable("enchantorder.button.clear.tooltip")), mouseX, mouseY);
		} else if (headerHovered) {
			tooltip(graphics, List.of(planner.item().getHoverName(),
					Component.translatable("enchantorder.tooltip.work_penalty", planner.itemWorkPenalty())), mouseX, mouseY);
		} else if (hoveredChoice != null) {
			rowTooltip(graphics, hoveredChoice, rowWidth, mouseX, mouseY);
		}
	}

	private void drawRow(GuiGraphicsExtractor graphics, Choice choice, int x, int y, int width, boolean hovered, int mouseX) {
		Status status = choice.status();
		boolean clickable = isClickable(choice);
		if (status == Status.SELECTED) {
			graphics.fill(x, y, x + width, y + ROW, ROW_SELECTED);
		}
		if (hovered && clickable) {
			graphics.fill(x, y, x + width, y + ROW, ROW_HOVER);
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}

		// Tick box.
		int boxX = x + 3;
		int boxY = y + 2;
		graphics.outline(boxX, boxY, 8, 8, clickable ? 0xFFA0A0A0 : 0xFF505050);
		if (status == Status.SELECTED) {
			graphics.fill(boxX + 2, boxY + 2, boxX + 6, boxY + 6, CHECK);
		} else if (status == Status.ON_ITEM) {
			graphics.fill(boxX + 2, boxY + 2, boxX + 6, boxY + 6, ON_ITEM);
		}

		// Level, on the right.
		int right = x + width - 2;
		int levelSpace;
		if (status == Status.SELECTED && choice.hasLevelChoice()) {
			int level = planner.selectedLevel(choice);
			String numeral = numeral(level);
			boolean overLeft = hovered && isOverLeftArrow(mouseX, x, width);
			boolean overRight = hovered && isOverRightArrow(mouseX, x, width);
			int arrowColor = 0xFFFFFF55;
			graphics.text(font, "<", right - LEVEL_COLUMN + 1, y + 2, level > choice.minLevel() ? (overLeft ? TEXT : arrowColor) : TEXT_OFF, true);
			graphics.text(font, ">", right - 5, y + 2, level < choice.maxLevel ? (overRight ? TEXT : arrowColor) : TEXT_OFF, true);
			graphics.text(font, numeral, right - LEVEL_COLUMN / 2 - 2 - font.width(numeral) / 2, y + 2, TEXT, true);
			levelSpace = LEVEL_COLUMN + 2;
		} else {
			int shownLevel = switch (status) {
				case SELECTED -> planner.selectedLevel(choice);
				case ON_ITEM -> choice.levelOnItem;
				default -> choice.maxLevel;
			};
			String numeral = choice.maxLevel > 1 ? numeral(shownLevel) : "";
			graphics.text(font, numeral, right - font.width(numeral), y + 2, status == Status.SELECTED ? TEXT : textColor(choice), true);
			levelSpace = font.width(numeral) + 4;
		}

		// Name.
		String name = choice.enchantment.value().description().getString();
		graphics.text(font, fit(name, width - 15 - levelSpace), x + 15, y + 2, textColor(choice), true);
	}

	private void rowTooltip(GuiGraphicsExtractor graphics, Choice choice, int rowWidth, int mouseX, int mouseY) {
		Component name = choice.enchantment.value().description();
		switch (choice.status()) {
			case CONFLICT -> tooltip(graphics, List.of(name, Component.translatable("enchantorder.tooltip.conflict", choice.conflictsWith())), mouseX, mouseY);
			case ON_ITEM -> tooltip(graphics, List.of(name, Component.translatable("enchantorder.tooltip.on_item")), mouseX, mouseY);
			case AVAILABLE -> {
				if (!planner.canSelectMore()) {
					tooltip(graphics, List.of(name, Component.translatable("enchantorder.tooltip.too_many", AnvilOptimizer.MAX_BOOKS)), mouseX, mouseY);
				} else if (isNameCut(choice, rowWidth)) {
					tooltip(graphics, List.of(name), mouseX, mouseY);
				}
			}
			case SELECTED -> {
				if (choice.hasLevelChoice() && (isOverLeftArrow(mouseX, listLeft(), rowWidth) || isOverRightArrow(mouseX, listLeft(), rowWidth))) {
					tooltip(graphics, List.of(Component.translatable("enchantorder.tooltip.levels")), mouseX, mouseY);
				} else if (isNameCut(choice, rowWidth)) {
					tooltip(graphics, List.of(name), mouseX, mouseY);
				}
			}
		}
	}

	private boolean isNameCut(Choice choice, int rowWidth) {
		return font.width(choice.enchantment.value().description().getString()) > rowWidth - 15 - LEVEL_COLUMN - 2;
	}

	@Override
	void click(double mouseX, double mouseY, int button) {
		if (isInside(mouseX, mouseY, myBooksX(), buttonY(), buttonWidth(), 16)) {
			playClickSound();
			planner.selectBooksYouHave(menu);
			return;
		}
		if (isInside(mouseX, mouseY, clearX(), buttonY(), buttonWidth(), 16) && planner.hasSelection()) {
			playClickSound();
			planner.clearSelection();
			return;
		}
		if (!inList(mouseX, mouseY) || button != 0) {
			return;
		}
		int row = (int) Math.floor((mouseY - listTop() - 1 + scroll) / ROW);
		List<Choice> choices = planner.choices();
		if (row < 0 || row >= choices.size() || mouseX >= listLeft() + rowWidth()) {
			return;
		}
		Choice choice = choices.get(row);
		if (choice.status() == Status.SELECTED && choice.hasLevelChoice()) {
			if (isOverLeftArrow(mouseX, listLeft(), rowWidth())) {
				playClickSound();
				planner.changeLevel(choice, -1);
				return;
			}
			if (isOverRightArrow(mouseX, listLeft(), rowWidth())) {
				playClickSound();
				planner.changeLevel(choice, 1);
				return;
			}
		}
		if (isClickable(choice)) {
			playClickSound();
			planner.toggle(choice);
		}
	}

	private boolean isClickable(Choice choice) {
		return choice.status() == Status.SELECTED || (choice.status() == Status.AVAILABLE && planner.canSelectMore());
	}

	private static boolean isOverLeftArrow(double mouseX, int rowX, int rowWidth) {
		int right = rowX + rowWidth - 2;
		return mouseX >= right - LEVEL_COLUMN - 1 && mouseX < right - LEVEL_COLUMN + 8;
	}

	private static boolean isOverRightArrow(double mouseX, int rowX, int rowWidth) {
		int right = rowX + rowWidth - 2;
		return mouseX >= right - 8 && mouseX < right + 2;
	}

	private static int textColor(Choice choice) {
		return switch (choice.status()) {
			case SELECTED -> TEXT;
			case AVAILABLE -> choice.curse ? CURSE : TEXT_DIM;
			case ON_ITEM -> ON_ITEM;
			case CONFLICT -> TEXT_OFF;
		};
	}

	private static String numeral(int level) {
		return Component.translatable("enchantment.level." + level).getString();
	}
}
