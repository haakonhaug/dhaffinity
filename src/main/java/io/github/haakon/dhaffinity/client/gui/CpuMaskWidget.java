package io.github.haakon.dhaffinity.client.gui;

import io.github.haakon.dhaffinity.affinity.MaskFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarratedElementType;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.function.LongConsumer;

/**
 * A grid of one toggle cell per logical CPU. Click toggles a cell; click-and-drag paints the
 * value of the first cell over every cell the pointer crosses. Cells outside
 * {@link #setAvailableMask(long) the available mask} (CPUs the process may not use, e.g. outside
 * its cpuset) are drawn disabled and cannot be toggled.
 */
public final class CpuMaskWidget extends AbstractWidget {

	public static final int CELL = 14;
	public static final int GAP = 2;
	public static final int MAX_PER_LINE = 16;

	private static final int COLOR_ON = 0xFF3C9A5F;
	private static final int COLOR_ON_HOVER = 0xFF5BC27F;
	private static final int COLOR_OFF = 0xFF2B2B2B;
	private static final int COLOR_OFF_HOVER = 0xFF4A4A4A;
	private static final int COLOR_ON_DISABLED = 0xFF2E4A38;
	private static final int COLOR_OFF_DISABLED = 0xFF1E1E1E;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_TEXT_DIM = 0xFF8A8A8A;
	private static final int COLOR_OUTLINE = 0xFFE0E0E0;

	private final int cpuCount;
	private final int perLine;
	private final LongConsumer onChange;
	private long mask;
	private long availableMask = -1L;
	private boolean editable = true;
	/** True between a press on an available cell and the matching release. */
	private boolean painting;
	private boolean paintValue;
	private int lastPainted = -1;

	/**
	 * @param cpuCount number of cells (CPU indices {@code 0..cpuCount-1}), clamped to 1..64
	 */
	public CpuMaskWidget(int x, int y, int cpuCount, long mask, Component name, LongConsumer onChange) {
		super(x, y, widthFor(perLineFor(cpuCount)), heightFor(cpuCount, perLineFor(cpuCount)), name);
		this.cpuCount = clampCount(cpuCount);
		this.perLine = perLineFor(this.cpuCount);
		this.mask = mask;
		this.onChange = onChange;
	}

	private static int clampCount(int cpuCount) {
		return Math.max(1, Math.min(64, cpuCount));
	}

	public static int perLineFor(int cpuCount) {
		return Math.min(MAX_PER_LINE, clampCount(cpuCount));
	}

	public static int widthFor(int perLine) {
		return perLine * (CELL + GAP) - GAP;
	}

	public static int heightFor(int cpuCount, int perLine) {
		int lines = (clampCount(cpuCount) + perLine - 1) / perLine;
		return lines * (CELL + GAP) - GAP;
	}

	public long mask() {
		return mask;
	}

	public void setMask(long mask) {
		this.mask = mask;
	}

	public boolean isEditable() {
		return editable;
	}

	public void setEditable(boolean editable) {
		this.editable = editable;
	}

	/** CPUs that may be selected at all; bits outside it are drawn disabled and ignored on click. */
	public void setAvailableMask(long availableMask) {
		this.availableMask = availableMask;
	}

	private boolean isAvailable(int cpu) {
		return (availableMask & (1L << cpu)) != 0;
	}

	@Override
	protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		int hovered = editable && isHovered() ? cellAt(mouseX, mouseY) : -1;
		for (int cpu = 0; cpu < cpuCount; cpu++) {
			int cx = cellX(cpu);
			int cy = cellY(cpu);
			boolean on = (mask & (1L << cpu)) != 0;
			boolean enabled = editable && isAvailable(cpu);
			int color;
			if (!enabled) {
				color = on ? COLOR_ON_DISABLED : COLOR_OFF_DISABLED;
			} else if (cpu == hovered) {
				color = on ? COLOR_ON_HOVER : COLOR_OFF_HOVER;
			} else {
				color = on ? COLOR_ON : COLOR_OFF;
			}
			graphics.fill(cx, cy, cx + CELL, cy + CELL, color);
			if (cpu == hovered || (isFocused() && cpu == 0)) {
				graphics.renderOutline(cx, cy, CELL, CELL, COLOR_OUTLINE);
			}
			graphics.drawCenteredString(Minecraft.getInstance().font, Integer.toString(cpu), cx + CELL / 2, cy + (CELL - 8) / 2 + 1,
					enabled ? COLOR_TEXT : COLOR_TEXT_DIM);
		}
		if (hovered >= 0) {
			String state = !isAvailable(hovered) ? " — not available to this process"
					: (mask & (1L << hovered)) != 0 ? " — selected" : " — not selected";
			graphics.setTooltipForNextFrame(Component.literal("CPU " + hovered + state), mouseX, mouseY);
		}
	}

	private int cellX(int cpu) {
		return getX() + (cpu % perLine) * (CELL + GAP);
	}

	private int cellY(int cpu) {
		return getY() + (cpu / perLine) * (CELL + GAP);
	}

	/** Index of the cell under the pointer, or -1. */
	int cellAt(double mouseX, double mouseY) {
		int relX = (int) Math.floor(mouseX) - getX();
		int relY = (int) Math.floor(mouseY) - getY();
		if (relX < 0 || relY < 0) {
			return -1;
		}
		int col = relX / (CELL + GAP);
		int row = relY / (CELL + GAP);
		if (col >= perLine || relX % (CELL + GAP) >= CELL || relY % (CELL + GAP) >= CELL) {
			return -1;
		}
		int cpu = row * perLine + col;
		return cpu < cpuCount ? cpu : -1;
	}

	@Override
	public void onClick(MouseButtonEvent event, boolean doubleClick) {
		painting = false;
		int cpu = cellAt(event.x(), event.y());
		if (!editable || cpu < 0 || !isAvailable(cpu)) {
			return;
		}
		painting = true;
		paintValue = (mask & (1L << cpu)) == 0;
		lastPainted = cpu;
		apply(cpu, paintValue);
	}

	@Override
	protected void onDrag(MouseButtonEvent event, double dragX, double dragY) {
		if (!painting || !editable) {
			return;
		}
		int cpu = cellAt(event.x(), event.y());
		if (cpu < 0 || cpu == lastPainted) {
			return;
		}
		lastPainted = cpu;
		apply(cpu, paintValue);
	}

	@Override
	public void onRelease(MouseButtonEvent event) {
		painting = false;
		lastPainted = -1;
	}

	private void apply(int cpu, boolean on) {
		if (!isAvailable(cpu)) {
			return;
		}
		long bit = 1L << cpu;
		long next = on ? (mask | bit) : (mask & ~bit);
		if (next != mask) {
			mask = next;
			onChange.accept(mask);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		output.add(NarratedElementType.TITLE, Component.literal(getMessage().getString() + ": CPUs " + MaskFormat.toCpuList(mask)));
		if (editable) {
			output.add(NarratedElementType.USAGE, Component.literal("Click a cell to toggle that CPU; drag to paint."));
		}
	}
}
