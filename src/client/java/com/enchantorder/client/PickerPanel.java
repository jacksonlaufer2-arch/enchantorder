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
	private static final int NAME_X = 13;
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

	int buttonWidth() {
		return (getWidth() - PADDING * 2 - 4) / 2;
	}

	int buttonY() {
		return getBottom() - FOOTER + 3;
	}

	int myBooksX() {
		return listLeft();
	}

	int clearX() {
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
		int boxX = x + 2;
		int boxY = y + 2;
		graphics.outline(boxX, boxY, 8, 8, clickable ? 0xFFA0A0A0 : 0xFF505050);
		if (status == Status.SELECTED) {
			graphics.fill(boxX + 2, boxY + 2, boxX + 6, boxY + 6, CHECK);
		} else if (status == Status.ON_ITEM) {
			graphics.fill(boxX + 2, boxY + 2, boxX + 6, boxY + 6, ON_ITEM);
		}

		// Level, on the right: "< III >" when it can be changed, otherwise just the numeral.
		String numeral = choice.maxLevel > 1 ? numeral(shownLevel(choice)) : "";
		if (hasArrows(choice)) {
			int level = planner.selectedLevel(choice);
			int arrowColor = 0xFFFFFF55;
			boolean overLeft = hovered && isOverLeftArrow(choice, mouseX, x, width);
			boolean overRight = hovered && isOverRightArrow(mouseX, x, width);
			graphics.text(font, "<", leftArrowX(choice, x, width), y + 2, level > choice.minLevel() ? (overLeft ? TEXT : arrowColor) : TEXT_OFF, true);
			graphics.text(font, ">", rightArrowX(x, width), y + 2, level < choice.maxLevel ? (overRight ? TEXT : arrowColor) : TEXT_OFF, true);
			graphics.text(font, numeral, numeralRight(x, width) - font.width(numeral), y + 2, TEXT, true);
		} else {
			graphics.text(font, numeral, x + width - 2 - font.width(numeral), y + 2, status == Status.SELECTED ? TEXT : textColor(choice), true);
		}

		// Name.
		String name = choice.enchantment.value().description().getString();
		graphics.text(font, fit(name, nameWidth(choice, x, width)), x + NAME_X, y + 2, textColor(choice), true);
	}

	private int shownLevel(Choice choice) {
		return switch (choice.status()) {
			case SELECTED -> planner.selectedLevel(choice);
			case ON_ITEM -> choice.levelOnItem;
			default -> choice.maxLevel;
		};
	}

	/** How much room the name has before it runs into the level on the right. */
	private int nameWidth(Choice choice, int rowX, int rowWidth) {
		int levelLeft;
		if (hasArrows(choice)) {
			levelLeft = leftArrowX(choice, rowX, rowWidth);
		} else {
			levelLeft = rowX + rowWidth - 2 - (choice.maxLevel > 1 ? font.width(numeral(shownLevel(choice))) : 0);
		}
		return levelLeft - 3 - (rowX + NAME_X);
	}

	private static boolean hasArrows(Choice choice) {
		return choice.status() == Status.SELECTED && choice.hasLevelChoice();
	}

	// The ">" sits at the right edge of the row, the numeral just left of it, and the "<" just left of that.
	static int rightArrowX(int rowX, int rowWidth) {
		return rowX + rowWidth - 7;
	}

	private static int numeralRight(int rowX, int rowWidth) {
		return rightArrowX(rowX, rowWidth) - 2;
	}

	int leftArrowX(Choice choice, int rowX, int rowWidth) {
		return numeralRight(rowX, rowWidth) - font.width(numeral(planner.selectedLevel(choice))) - 7;
	}

	private boolean isOverLeftArrow(Choice choice, double mouseX, int rowX, int rowWidth) {
		int arrowX = leftArrowX(choice, rowX, rowWidth);
		return mouseX >= arrowX - 3 && mouseX < arrowX + 7;
	}

	private static boolean isOverRightArrow(double mouseX, int rowX, int rowWidth) {
		return mouseX >= rightArrowX(rowX, rowWidth) - 2 && mouseX < rowX + rowWidth;
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
				if (hasArrows(choice) && (isOverLeftArrow(choice, mouseX, listLeft(), rowWidth) || isOverRightArrow(mouseX, listLeft(), rowWidth))) {
					tooltip(graphics, List.of(Component.translatable("enchantorder.tooltip.levels")), mouseX, mouseY);
				} else if (isNameCut(choice, rowWidth)) {
					tooltip(graphics, List.of(name), mouseX, mouseY);
				}
			}
		}
	}

	private boolean isNameCut(Choice choice, int rowWidth) {
		return font.width(choice.enchantment.value().description().getString()) > nameWidth(choice, listLeft(), rowWidth);
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
		if (hasArrows(choice)) {
			if (isOverLeftArrow(choice, mouseX, listLeft(), rowWidth())) {
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
