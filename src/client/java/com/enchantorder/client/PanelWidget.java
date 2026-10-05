package com.enchantorder.client;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;

/**
 * A side panel next to the anvil, drawn in the same grey style as the anvil itself, with a dark
 * scrolling list in the middle.
 *
 * <p>Clicks and scrolling don't arrive through the normal widget methods: {@link AnvilOverlay} catches
 * them first so the anvil never sees them (otherwise clicking a panel while holding an item would
 * throw the item on the ground).
 */
abstract class PanelWidget extends AbstractWidget {
	static final int HEADER = 22;
	static final int FOOTER = 20;
	static final int PADDING = 5;
	static final int SCROLLBAR = 4;

	static final int LABEL = 0xFF404040;
	static final int LIST_BACKGROUND = 0xFF262626;
	static final int ROW_HOVER = 0xFF3A3A3A;
	static final int TEXT = 0xFFFFFFFF;
	static final int TEXT_DIM = 0xFFB0B0B0;
	static final int TEXT_OFF = 0xFF666666;

	private static final Identifier BUTTON = Identifier.withDefaultNamespace("widget/button");
	private static final Identifier BUTTON_HIGHLIGHTED = Identifier.withDefaultNamespace("widget/button_highlighted");
	private static final Identifier BUTTON_DISABLED = Identifier.withDefaultNamespace("widget/button_disabled");

	protected final AnvilPlanner planner = AnvilPlanner.INSTANCE;
	protected final Font font = Minecraft.getInstance().font;
	protected int scroll;

	PanelWidget(int x, int y, int width, int height, Component title) {
		super(x, y, width, height, title);
	}

	/** Handles a click anywhere on the panel. */
	abstract void click(double mouseX, double mouseY, int button);

	/** Total height of everything in the list, to know how far it can scroll. */
	abstract int contentHeight();

	/** How far one notch of the scroll wheel moves the list. */
	abstract int scrollStep();

	abstract void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

	boolean contains(double mouseX, double mouseY) {
		return planner.isActive() && mouseX >= getX() && mouseX < getRight() && mouseY >= getY() && mouseY < getBottom();
	}

	void scroll(double amount) {
		scroll = Math.clamp(scroll - Math.round(amount * scrollStep()), 0, maxScroll());
	}

	int listLeft() {
		return getX() + PADDING;
	}

	int listTop() {
		return getY() + HEADER;
	}

	int listRight() {
		return getRight() - PADDING;
	}

	int listBottom() {
		return getBottom() - FOOTER;
	}

	int maxScroll() {
		return Math.max(0, contentHeight() - (listBottom() - listTop()));
	}

	/** Width of the list rows, leaving room for the scroll bar when there is one. */
	int rowWidth() {
		return listRight() - listLeft() - (maxScroll() > 0 ? SCROLLBAR + 1 : 0);
	}

	boolean inList(double mouseX, double mouseY) {
		return mouseX >= listLeft() && mouseX < listRight() && mouseY >= listTop() && mouseY < listBottom();
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (!planner.isActive()) {
			return;
		}
		scroll = Math.clamp(scroll, 0, maxScroll());
		drawFrame(graphics);
		drawInset(graphics, listLeft() - 1, listTop() - 1, listRight() + 1, listBottom() + 1);
		extractPanel(graphics, mouseX, mouseY);
		drawScrollbar(graphics);
	}

	/** The raised grey box with a black outline, like every container screen. */
	private void drawFrame(GuiGraphicsExtractor graphics) {
		int left = getX();
		int top = getY();
		int right = getRight();
		int bottom = getBottom();
		graphics.fill(left + 2, top, right - 2, top + 1, 0xFF000000);
		graphics.fill(left + 2, bottom - 1, right - 2, bottom, 0xFF000000);
		graphics.fill(left, top + 2, left + 1, bottom - 2, 0xFF000000);
		graphics.fill(right - 1, top + 2, right, bottom - 2, 0xFF000000);
		graphics.fill(left + 1, top + 1, left + 2, top + 2, 0xFF000000);
		graphics.fill(right - 2, top + 1, right - 1, top + 2, 0xFF000000);
		graphics.fill(left + 1, bottom - 2, left + 2, bottom - 1, 0xFF000000);
		graphics.fill(right - 2, bottom - 2, right - 1, bottom - 1, 0xFF000000);
		graphics.fill(left + 1, top + 2, right - 1, bottom - 2, 0xFFC6C6C6);
		graphics.fill(left + 2, top + 1, right - 2, bottom - 1, 0xFFC6C6C6);
		graphics.fill(left + 2, top + 1, right - 3, top + 3, 0xFFFFFFFF);
		graphics.fill(left + 1, top + 2, left + 3, bottom - 3, 0xFFFFFFFF);
		graphics.fill(left + 3, bottom - 3, right - 2, bottom - 1, 0xFF555555);
		graphics.fill(right - 3, top + 3, right - 1, bottom - 2, 0xFF555555);
	}

	/** A sunken box, like an item slot. */
	private static void drawInset(GuiGraphicsExtractor graphics, int left, int top, int right, int bottom) {
		graphics.fill(left, top, right, bottom, 0xFF373737);
		graphics.fill(left + 1, top + 1, right, bottom, 0xFFFFFFFF);
		graphics.fill(left + 1, top + 1, right - 1, bottom - 1, LIST_BACKGROUND);
	}

	private void drawScrollbar(GuiGraphicsExtractor graphics) {
		int max = maxScroll();
		if (max <= 0) {
			return;
		}
		int trackTop = listTop();
		int trackHeight = listBottom() - listTop();
		int left = listRight() - SCROLLBAR;
		int thumbHeight = Math.max(10, trackHeight * trackHeight / contentHeight());
		int thumbTop = trackTop + (trackHeight - thumbHeight) * scroll / max;
		graphics.fill(left, trackTop, listRight(), listBottom(), 0xFF000000);
		graphics.fill(left, thumbTop, listRight(), thumbTop + thumbHeight, 0xFF808080);
		graphics.fill(left, thumbTop, listRight() - 1, thumbTop + thumbHeight - 1, 0xFFC0C0C0);
	}

	/** A normal Minecraft button. Returns true if the mouse is over it. */
	boolean drawButton(GuiGraphicsExtractor graphics, int x, int y, int width, int height, Component label, boolean active, int mouseX, int mouseY) {
		boolean hovered = isInside(mouseX, mouseY, x, y, width, height);
		Identifier sprite = !active ? BUTTON_DISABLED : hovered ? BUTTON_HIGHLIGHTED : BUTTON;
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, sprite, x, y, width, height);
		String text = fit(label.getString(), width - 4);
		graphics.text(font, text, x + (width - font.width(text) + 1) / 2, y + (height - 8) / 2, active ? TEXT : 0xFFA0A0A0, true);
		if (hovered && active) {
			graphics.requestCursor(CursorTypes.POINTING_HAND);
		}
		return hovered;
	}

	/** Shortens text that doesn't fit, ending it with "...". */
	String fit(String text, int width) {
		if (font.width(text) <= width) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, width - font.width("..."))) + "...";
	}

	/** Shows a tooltip, wrapping long lines. */
	void tooltip(GuiGraphicsExtractor graphics, List<Component> lines, int mouseX, int mouseY) {
		List<FormattedCharSequence> wrapped = new ArrayList<>();
		for (Component line : lines) {
			wrapped.addAll(font.split(line, 200));
		}
		graphics.setTooltipForNextFrame(font, wrapped, mouseX, mouseY);
	}

	/** Draws wrapped text in the list area and returns how tall it was. */
	int drawWrapped(GuiGraphicsExtractor graphics, Component text, int x, int y, int width, int color) {
		int height = 0;
		for (FormattedCharSequence line : font.split(text, width)) {
			graphics.text(font, line, x, y + height, color, true);
			height += font.lineHeight + 1;
		}
		return height;
	}

	static boolean isInside(double mouseX, double mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	static void playClickSound() {
		AbstractWidget.playButtonClickSound(Minecraft.getInstance().getSoundManager());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		return false;
	}

	@Override
	public ComponentPath nextFocusPath(FocusNavigationEvent event) {
		return null;
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, getMessage());
	}
}
