package io.github.haakon.dhaffinity.client.gui;

import io.github.haakon.dhaffinity.affinity.CpuTopology;
import io.github.haakon.dhaffinity.affinity.MaskFormat;
import io.github.haakon.dhaffinity.affinity.NoopAffinityBackend;
import io.github.haakon.dhaffinity.config.AffinityConfig;
import io.github.haakon.dhaffinity.core.DhAffinity;
import io.github.haakon.dhaffinity.core.ThreadRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.layouts.HeaderAndFooterLayout;
import net.minecraft.client.gui.layouts.LinearLayout;
import net.minecraft.client.gui.layouts.SpacerElement;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Options menu: assign logical CPUs to the thread groups of {@link AffinityConfig}. Groups may
 * overlap freely. The screen edits an in-memory draft; "Save &amp; Apply" writes the config file
 * and applies it live through {@link DhAffinity#save(AffinityConfig.Json)}.
 *
 * <p>Reached via ModMenu (Configure) or {@code /dhaffinity gui}.
 */
public final class AffinityConfigScreen extends Screen {

	private static final int HEADER_HEIGHT = 33;
	private static final int FOOTER_HEIGHT = 46;
	private static final int MAX_ROW_WIDTH = 460;
	private static final int MIN_ROW_WIDTH = 120;
	/** Width of the label column when a row is wide enough to put the grid beside the text. */
	private static final int LABEL_COLUMN = 156;
	private static final int COLUMN_GAP = 6;
	private static final int LINE = 10;
	private static final int SMALL_BUTTON_HEIGHT = 16;
	/** Long preset labels (sparse CPU lists) scroll inside the button instead of widening it. */
	private static final int MAX_BUTTON_WIDTH = 120;
	private static final int BUTTON_GAP = 2;
	private static final int ROW_GAP = 4;
	private static final int COLOR_TEXT = 0xFFFFFFFF;
	private static final int COLOR_DIM = 0xFFA0A0A0;
	private static final int COLOR_ERROR = 0xFFFF5555;
	private static final int COLOR_SEPARATOR = 0x30FFFFFF;

	private static final String KEY_GAME = "game";
	private static final String KEY_MAIN = "main";
	private static final String KEY_DH = "dh";
	private static final String POOL_PREFIX = "pool:";

	private static final Map<String, String> POOL_DESCRIPTIONS = Map.of(
			"World Gen", "Generates terrain for distant LODs (the heaviest pool).",
			"LOD Builder", "Builds LOD meshes from generated and loaded chunks.",
			"Render Loader", "Loads LOD data for rendering.",
			"IO", "Reads and writes the LOD database.",
			"Update Propagator", "Propagates LOD updates between detail levels.",
			"Network Compression", "Compresses LOD data for multiplayer.",
			"GPU Upload", "This mod's own thread that copies finished LODs to the GPU (off-thread upload).");

	/** A one-click mask such as an L3 cache group. */
	private record Preset(String label, long mask, String tooltip) {}

	/** Editable state of one thread group; survives {@link #init()} and window resizes. */
	private static final class GroupState {
		long mask;
		boolean follow;

		GroupState(long mask, boolean follow) {
			this.mask = mask;
			this.follow = follow;
		}
	}

	private final Screen parent;
	private final DhAffinity core;
	private final long allCpus;
	/** Number of grid cells: highest usable CPU index + 1 (the usable set may be sparse). */
	private final int cpuCells;
	private final List<Preset> presets;
	private final List<String> poolNames;
	/** Every non-mask field of the config file; masks live in {@link #states}. */
	private final AffinityConfig.Json base;
	private final Map<String, GroupState> states = new LinkedHashMap<>();
	private final String subtitle;
	private boolean advanced;

	private HeaderAndFooterLayout layout;
	private StringWidget subtitleWidget;
	private GroupList list;
	private SpacerElement noteSlot;
	private Button saveButton;
	private GroupRow gameRow;
	private GroupRow dhRow;
	private GroupRow mainRow;
	private final List<GroupRow> poolRows = new ArrayList<>();
	private Row advancedRow;
	private Row optionsRow;
	private String note = "";

	public AffinityConfigScreen(Screen parent) {
		super(Component.literal("DH Affinity"));
		this.parent = parent;
		this.core = DhAffinity.get();
		this.allCpus = core.backend().allCpusMask();
		this.cpuCells = allCpus == 0 ? 1 : 64 - Long.numberOfLeadingZeros(allCpus);
		this.presets = buildPresets(core.topology(), allCpus);
		this.subtitle = core.backend().cpuCount() + " logical CPUs · " + core.topology().describe()
				+ " · overlapping groups are allowed"
				+ (core.backend() instanceof NoopAffinityBackend ? " · NO AFFINITY SUPPORT HERE (" + core.backend().name() + ")" : "");

		AffinityConfig cfg = core.config();
		this.base = cfg.source.copy();
		// resolve() trims pool keys, so normalise them here too or a padded key would be dropped on save.
		Map<String, String> pools = new LinkedHashMap<>();
		if (base.dhPoolMasks != null) {
			for (Map.Entry<String, String> e : base.dhPoolMasks.entrySet()) {
				if (e.getKey() != null && !isBlank(e.getKey())) {
					pools.put(e.getKey().trim(), e.getValue());
				}
			}
		}
		base.dhPoolMasks = pools;
		states.put(KEY_GAME, new GroupState(parseMask(base.gameMask, cfg.gameMask), false));
		states.put(KEY_DH, new GroupState(parseMask(base.dhMask, cfg.dhMask), false));
		boolean mainFollows = isBlank(base.mainThreadMask);
		states.put(KEY_MAIN, new GroupState(mainFollows ? 0 : parseMask(base.mainThreadMask, cfg.mainThreadMask), mainFollows));

		TreeSet<String> extras = new TreeSet<>();
		for (String pool : base.dhPoolMasks.keySet()) {
			if (!AffinityConfig.KNOWN_DH_POOLS.contains(pool)) {
				extras.add(pool);
			}
		}
		for (ThreadRegistry.DhThread t : core.registry().snapshot()) {
			if (t.pool() != null && !isBlank(t.pool()) && !AffinityConfig.KNOWN_DH_POOLS.contains(t.pool().trim())) {
				extras.add(t.pool().trim());
			}
		}
		List<String> names = new ArrayList<>(AffinityConfig.KNOWN_DH_POOLS);
		names.addAll(extras);
		this.poolNames = List.copyOf(names);
		for (String pool : poolNames) {
			String text = base.dhPoolMasks.get(pool);
			boolean follows = isBlank(text);
			states.put(POOL_PREFIX + pool, new GroupState(follows ? 0 : parseMask(text, cfg.maskForDhPool(pool)), follows));
		}
	}

	private static List<Preset> buildPresets(CpuTopology topology, long allCpus) {
		List<Long> masks = topology.presets(allCpus);
		List<CpuTopology.CacheGroup> groups = topology.l3Groups();
		boolean fromL3 = groups.size() >= 2
				&& masks.stream().allMatch(m -> groups.stream().anyMatch(g -> (g.mask() & allCpus) == m));
		List<Preset> out = new ArrayList<>();
		for (int i = 0; i < masks.size(); i++) {
			long mask = masks.get(i);
			String cpus = MaskFormat.toCpuList(mask);
			if (fromL3) {
				long size = groups.stream().filter(g -> (g.mask() & allCpus) == mask).mapToLong(CpuTopology.CacheGroup::sizeBytes).max().orElse(0);
				String sizeText = size > 0 ? " (" + (size / (1024 * 1024)) + " MB)" : "";
				out.add(new Preset("L3 #" + (i + 1) + ": " + cpus, mask,
						"CPUs sharing L3 cache #" + (i + 1) + sizeText + ": " + cpus));
			} else {
				String half = i == 0 ? "Lower half" : "Upper half";
				out.add(new Preset(half + ": " + cpus, mask, half + " of the usable CPUs: " + cpus + " (cache layout unknown)"));
			}
		}
		return out;
	}

	private long parseMask(String text, long fallback) {
		if (isBlank(text)) {
			return fallback;
		}
		if ("none".equalsIgnoreCase(text.trim())) {
			return 0; // what toCpuList(0) writes; shown as empty so the user has to pick CPUs
		}
		try {
			return MaskFormat.parse(text) & allCpus;
		} catch (IllegalArgumentException e) {
			return fallback;
		}
	}

	private static boolean isBlank(String s) {
		return s == null || s.trim().isEmpty();
	}

	/** Single-line text that fits {@code maxWidth}, with an ellipsis when cut. */
	private String clip(String text, int maxWidth) {
		if (maxWidth <= 0 || font.width(text) <= maxWidth) {
			return text;
		}
		return font.plainSubstrByWidth(text, Math.max(0, maxWidth - font.width("…"))) + "…";
	}

	private GroupState state(String key) {
		return states.get(key);
	}

	// ---------------------------------------------------------------- lifecycle

	@Override
	protected void init() {
		layout = new HeaderAndFooterLayout(this, HEADER_HEIGHT, FOOTER_HEIGHT);

		LinearLayout header = layout.addToHeader(LinearLayout.vertical().spacing(3));
		header.defaultCellSetting().alignHorizontallyCenter();
		header.addChild(new StringWidget(getTitle(), font));
		subtitleWidget = header.addChild(new StringWidget(Component.literal(subtitle).withStyle(ChatFormatting.GRAY), font).setMaxWidth(width - 20));

		buildRows();
		list = layout.addToContents(new GroupList(minecraft, width, layout.getContentHeight(), layout.getHeaderHeight()));

		LinearLayout footer = layout.addToFooter(LinearLayout.vertical().spacing(4));
		footer.defaultCellSetting().alignHorizontallyCenter();
		noteSlot = footer.addChild(new SpacerElement(1, LINE - 1));
		LinearLayout buttons = footer.addChild(LinearLayout.horizontal().spacing(8));
		buttons.addChild(Button.builder(Component.literal("Reset to defaults"), b -> resetToDefaults())
				.width(110)
				.tooltip(Tooltip.create(Component.literal("Recommended split for this machine (largest L3 group -> game, the rest -> DH). Nothing is saved until you press Save & Apply.")))
				.build());
		buttons.addChild(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose()).width(80).build());
		saveButton = buttons.addChild(Button.builder(Component.literal("Save & Apply"), b -> saveAndApply())
				.width(100)
				.tooltip(Tooltip.create(Component.literal("Write config/dhaffinity.json and re-pin the threads now.")))
				.build());

		layout.visitWidgets(this::addRenderableWidget);
		repositionElements();
		validate();
	}

	@Override
	protected void repositionElements() {
		subtitleWidget.setMaxWidth(width - 20);
		layout.arrangeElements();
		list.updateSize(width, layout);
		list.rebuild();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		if (!note.isEmpty() && noteSlot != null) {
			graphics.drawCenteredString(font, clip(note, width - 8), width / 2, noteSlot.getY(), COLOR_ERROR);
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	private void buildRows() {
		gameRow = new GroupRow(KEY_GAME, "Minecraft & everything else", "game",
				"Minecraft, the JVM, drivers and every thread that is not a DH worker.", null);
		dhRow = new GroupRow(KEY_DH, "Distant Horizons", "Distant Horizons",
				"Default for all Distant Horizons worker threads. Single pools can differ (Advanced).", null);
		mainRow = new GroupRow(KEY_MAIN, "Main / render thread", "main thread",
				"Only the main (render) thread. Keep it on the fastest cores.", gameRow);
		poolRows.clear();
		for (String pool : poolNames) {
			String description = POOL_DESCRIPTIONS.getOrDefault(pool, "Additional DH pool (seen in the config file or currently running).");
			poolRows.add(new GroupRow(POOL_PREFIX + pool, pool, pool, description, dhRow));
		}
		advancedRow = new ButtonRow(advancedLabel(), b -> {
			advanced = !advanced;
			b.setMessage(advancedLabel());
			list.rebuild();
		});
		optionsRow = new OptionsRow();
	}

	private Component advancedLabel() {
		return Component.literal(advanced ? "Advanced ▾" : "Advanced ▸");
	}

	private List<Row> visibleRows() {
		List<Row> rows = new ArrayList<>();
		rows.add(gameRow);
		rows.add(dhRow);
		rows.add(advancedRow);
		if (advanced) {
			rows.add(mainRow);
			rows.addAll(poolRows);
			rows.add(optionsRow);
		}
		return rows;
	}

	// ---------------------------------------------------------------- actions

	private void onRowChanged(GroupRow row) {
		GroupState st = state(row.key);
		if (!st.follow) {
			st.mask = row.grid.mask();
		}
		validate();
	}

	private void validate() {
		List<String> empty = new ArrayList<>();
		if (state(KEY_GAME).mask == 0) {
			empty.add("Minecraft & everything else");
		}
		if (state(KEY_DH).mask == 0) {
			empty.add("Distant Horizons");
		}
		GroupState main = state(KEY_MAIN);
		if (!main.follow && main.mask == 0) {
			empty.add("Main / render thread");
		}
		for (String pool : poolNames) {
			GroupState st = state(POOL_PREFIX + pool);
			if (!st.follow && st.mask == 0) {
				empty.add(pool);
			}
		}
		if (empty.isEmpty()) {
			note = "";
		} else {
			note = "No CPU selected for: " + String.join(", ", empty) + ". A thread with no CPU cannot run.";
		}
		if (saveButton != null) {
			saveButton.active = empty.isEmpty();
		}
	}

	/** Compose the file contents from the draft. Only overrides are written as pool entries. */
	private AffinityConfig.Json buildJson() {
		AffinityConfig.Json json = base.copy();
		json.gameMask = MaskFormat.toCpuList(state(KEY_GAME).mask);
		json.dhMask = MaskFormat.toCpuList(state(KEY_DH).mask);
		GroupState main = state(KEY_MAIN);
		json.mainThreadMask = main.follow ? "" : MaskFormat.toCpuList(main.mask);
		json.dhPoolMasks = new LinkedHashMap<>();
		for (String pool : poolNames) {
			GroupState st = state(POOL_PREFIX + pool);
			if (!st.follow) {
				json.dhPoolMasks.put(pool, MaskFormat.toCpuList(st.mask));
			}
		}
		return json;
	}

	private void saveAndApply() {
		validate();
		if (!note.isEmpty()) {
			return;
		}
		String message = core.save(buildJson());
		String[] lines = message.split("\n");
		minecraft.getToastManager().addToast(SystemToast.multiline(minecraft, SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
				Component.literal("DH Affinity"), Component.literal(lines[0])));
		if (minecraft.level != null) {
			for (String line : lines) {
				minecraft.gui.getChat().addMessage(Component.literal("[DH Affinity] " + line));
			}
		}
		onClose();
	}

	/**
	 * Replace the menu-visible values with the machine defaults; sweep intervals and other
	 * file-only settings are kept.
	 */
	private void resetToDefaults() {
		AffinityConfig.Json defaults = AffinityConfig.defaultsFor(allCpus, core.topology());
		state(KEY_GAME).mask = parseMask(defaults.gameMask, allCpus);
		state(KEY_DH).mask = parseMask(defaults.dhMask, allCpus);
		GroupState main = state(KEY_MAIN);
		main.follow = true;
		main.mask = 0;
		for (String pool : poolNames) {
			GroupState st = state(POOL_PREFIX + pool);
			st.follow = true;
			st.mask = 0;
		}
		base.enabled = defaults.enabled;
		base.manageNonDhThreads = defaults.manageNonDhThreads;
		base.offThreadGpuUpload = defaults.offThreadGpuUpload;
		base.uploadPacing = defaults.uploadPacing;
		rebuildWidgets();
	}

	// ---------------------------------------------------------------- list

	/** The scrolling list of group rows; row width follows the window. */
	private final class GroupList extends ContainerObjectSelectionList<Row> {

		GroupList(Minecraft minecraft, int width, int height, int y) {
			super(minecraft, width, height, y, 24);
		}

		@Override
		public int getRowWidth() {
			return Math.max(MIN_ROW_WIDTH, Math.min(MAX_ROW_WIDTH, getWidth() - 40));
		}

		/** Re-add the visible rows with heights for the current row width, keeping the scroll position. */
		void rebuild() {
			double scroll = scrollAmount();
			clearEntries();
			int rowWidth = getRowWidth();
			for (Row row : visibleRows()) {
				addEntry(row, row.heightFor(rowWidth));
			}
			setScrollAmount(scroll);
		}
	}

	/** Base row: children are positioned whenever the list moves the row. */
	private abstract class Row extends ContainerObjectSelectionList.Entry<Row> {

		abstract int heightFor(int rowWidth);

		abstract void layoutChildren();

		abstract List<AbstractWidget> widgets();

		@Override
		public List<? extends GuiEventListener> children() {
			return widgets();
		}

		@Override
		public List<? extends NarratableEntry> narratables() {
			return widgets();
		}

		@Override
		public void setX(int x) {
			super.setX(x);
			layoutChildren();
		}

		@Override
		public void setY(int y) {
			super.setY(y);
			layoutChildren();
		}

		@Override
		public void setWidth(int width) {
			super.setWidth(width);
			layoutChildren();
		}

		@Override
		public void setHeight(int height) {
			super.setHeight(height);
			layoutChildren();
		}

		protected void renderWidgets(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
			// Rows are scissored to the list; keep hover effects and tooltips from showing on the clipped part.
			boolean inside = list != null && mouseY >= list.getY() && mouseY < list.getBottom();
			int mx = inside ? mouseX : -1;
			int my = inside ? mouseY : -1;
			for (AbstractWidget w : widgets()) {
				w.render(graphics, mx, my, partialTick);
			}
		}

		protected void renderSeparator(GuiGraphics graphics) {
			int y = getY() + getHeight() - 2;
			graphics.fill(getX(), y, getX() + getWidth(), y + 1, COLOR_SEPARATOR);
		}
	}

	/** One thread group: label + description, CPU grid, preset buttons, optional "same as" box. */
	private final class GroupRow extends Row {

		final String key;
		final String label;
		/** Short form used in "Same as <shortName>". */
		final String shortName;
		final String description;
		final GroupRow parentRow;
		final CpuMaskWidget grid;
		final Checkbox followBox;
		final List<Button> buttons = new ArrayList<>();
		private final List<AbstractWidget> widgets = new ArrayList<>();
		private List<FormattedCharSequence> descriptionLines = List.of();
		/** Width the cached {@link #descriptionLines} were wrapped for (the list relayouts on every scroll). */
		private int wrappedForWidth = -1;

		GroupRow(String key, String label, String shortName, String description, GroupRow parentRow) {
			this.key = key;
			this.label = label;
			this.shortName = shortName;
			this.description = description;
			this.parentRow = parentRow;
			GroupState st = state(key);
			long shown = st.follow && parentRow != null ? parentRow.effectiveMask() : st.mask;
			grid = new CpuMaskWidget(0, 0, cpuCells, shown, Component.literal(label), m -> onRowChanged(this));
			grid.setAvailableMask(allCpus);
			widgets.add(grid);
			if (parentRow != null) {
				followBox = Checkbox.builder(Component.literal("Same as " + parentRow.shortName), font)
						.selected(st.follow)
						.maxWidth(LABEL_COLUMN)
						.onValueChange((box, value) -> setFollow(value))
						.build();
				widgets.add(followBox);
			} else {
				followBox = null;
			}
			addButton("All", "Select every usable CPU", allCpus);
			addButton("None", "Clear the selection", 0);
			for (Preset preset : presets) {
				addButton(preset.label(), preset.tooltip(), preset.mask());
			}
			applyEditable(!st.follow);
		}

		private void addButton(String text, String tooltip, long mask) {
			Button button = Button.builder(Component.literal(text), b -> setMask(mask))
					.size(Math.min(font.width(text) + 10, MAX_BUTTON_WIDTH), SMALL_BUTTON_HEIGHT)
					.tooltip(Tooltip.create(Component.literal(tooltip)))
					.build();
			buttons.add(button);
			widgets.add(button);
		}

		long effectiveMask() {
			GroupState st = state(key);
			return st.follow && parentRow != null ? parentRow.effectiveMask() : st.mask;
		}

		private void setMask(long mask) {
			if (state(key).follow) {
				return;
			}
			grid.setMask(mask);
			onRowChanged(this);
		}

		private void setFollow(boolean follow) {
			GroupState st = state(key);
			st.follow = follow;
			if (!follow) {
				if (st.mask == 0 && parentRow != null) {
					st.mask = parentRow.effectiveMask();
				}
				grid.setMask(st.mask);
			}
			applyEditable(!follow);
			validate();
		}

		private void applyEditable(boolean editable) {
			grid.setEditable(editable);
			grid.active = editable;
			for (Button b : buttons) {
				b.active = editable;
			}
		}

		@Override
		List<AbstractWidget> widgets() {
			return widgets;
		}

		private boolean isWide(int contentWidth) {
			return contentWidth >= LABEL_COLUMN + COLUMN_GAP + grid.getWidth();
		}

		private int textHeight(int labelWidth) {
			int h = 1 + LINE + font.split(Component.literal(description), labelWidth - 2).size() * LINE + LINE;
			if (followBox != null) {
				h += 2 + followBox.getHeight();
			}
			return h;
		}

		private int buttonRows(int availableWidth) {
			int rows = 1;
			int x = 0;
			for (Button b : buttons) {
				if (x > 0 && x + b.getWidth() > availableWidth) {
					rows++;
					x = 0;
				}
				x += b.getWidth() + BUTTON_GAP;
			}
			return rows;
		}

		@Override
		int heightFor(int rowWidth) {
			int w = rowWidth - 2 * CONTENT_PADDING;
			boolean wide = isWide(w);
			int labelWidth = wide ? LABEL_COLUMN : w;
			int availableWidth = wide ? w - LABEL_COLUMN - COLUMN_GAP : w;
			int textH = textHeight(labelWidth);
			int gridBlock = 1 + grid.getHeight() + 4 + buttonRows(availableWidth) * (SMALL_BUTTON_HEIGHT + BUTTON_GAP) - BUTTON_GAP;
			int inner = wide ? Math.max(textH, gridBlock) : textH + 2 + gridBlock;
			return inner + 2 * CONTENT_PADDING + ROW_GAP;
		}

		@Override
		void layoutChildren() {
			int x = getContentX();
			int y = getContentY();
			int w = getContentWidth();
			boolean wide = isWide(w);
			int labelWidth = wide ? LABEL_COLUMN : w;
			if (labelWidth != wrappedForWidth) {
				// addEntry() calls setX() before setWidth(), so the first pass sees a negative width: skip it.
				descriptionLines = labelWidth > 2 ? font.split(Component.literal(description), labelWidth - 2) : List.of();
				wrappedForWidth = labelWidth;
			}
			int textBottom = y + 1 + LINE + descriptionLines.size() * LINE + LINE;
			if (followBox != null) {
				followBox.setPosition(x, textBottom + 2);
				textBottom += 2 + followBox.getHeight();
			}
			int gx = wide ? x + LABEL_COLUMN + COLUMN_GAP : x;
			int gy = wide ? y + 1 : textBottom + 3;
			grid.setPosition(gx, gy);
			int availableWidth = wide ? w - LABEL_COLUMN - COLUMN_GAP : w;
			int bx = gx;
			int by = gy + grid.getHeight() + 4;
			for (Button b : buttons) {
				if (bx > gx && bx + b.getWidth() > gx + availableWidth) {
					bx = gx;
					by += SMALL_BUTTON_HEIGHT + BUTTON_GAP;
				}
				b.setPosition(bx, by);
				bx += b.getWidth() + BUTTON_GAP;
			}
		}

		@Override
		public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			GroupState st = state(key);
			if (st.follow && parentRow != null) {
				grid.setMask(parentRow.effectiveMask());
			}
			int x = getContentX();
			int y = getContentY() + 1;
			int w = getContentWidth();
			int labelWidth = (isWide(w) ? LABEL_COLUMN : w) - 2;
			graphics.drawString(font, clip(label, labelWidth), x, y, COLOR_TEXT);
			y += LINE;
			for (FormattedCharSequence line : descriptionLines) {
				graphics.drawString(font, line, x, y, COLOR_DIM);
				y += LINE;
			}
			long mask = effectiveMask();
			String summary = mask == 0 ? "Selected: none" : "Selected: " + MaskFormat.toCpuList(mask) + " (" + Long.bitCount(mask) + ")";
			graphics.drawString(font, clip(summary, labelWidth), x, y, mask == 0 ? COLOR_ERROR : COLOR_DIM);
			renderWidgets(graphics, mouseX, mouseY, partialTick);
			renderSeparator(graphics);
		}
	}

	/** A single centred button (the Advanced toggle). */
	private final class ButtonRow extends Row {

		private final Button button;
		private final List<AbstractWidget> widgets;

		ButtonRow(Component label, Button.OnPress onPress) {
			button = Button.builder(label, onPress).width(150).build();
			widgets = List.of(button);
		}

		@Override
		List<AbstractWidget> widgets() {
			return widgets;
		}

		@Override
		int heightFor(int rowWidth) {
			return button.getHeight() + 2 * CONTENT_PADDING + ROW_GAP;
		}

		@Override
		void layoutChildren() {
			button.setWidth(Math.min(150, Math.max(60, getContentWidth())));
			button.setPosition(getContentXMiddle() - button.getWidth() / 2, getContentY());
		}

		@Override
		public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			renderWidgets(graphics, mouseX, mouseY, partialTick);
		}
	}

	/** The two global switches at the end of the advanced section. */
	private final class OptionsRow extends Row {

		private final Checkbox enabledBox;
		private final Checkbox manageBox;
		private final Checkbox gpuUploadBox;
		private final CycleButton<String> pacingButton;
		private final List<AbstractWidget> widgets;
		private static final List<String> PACING_VALUES = List.of("auto", "off", "1", "2", "4", "8", "16");

		OptionsRow() {
			enabledBox = Checkbox.builder(Component.literal("Enabled"), font)
					.selected(base.enabled)
					.onValueChange((box, value) -> base.enabled = value)
					.tooltip(Tooltip.create(Component.literal("Master switch. When off, threads keep whatever cores they have.")))
					.build();
			manageBox = Checkbox.builder(Component.literal("Manage non-DH threads"), font)
					.selected(base.manageNonDhThreads)
					.onValueChange((box, value) -> base.manageNonDhThreads = value)
					.tooltip(Tooltip.create(Component.literal("Also pin every other thread of the game to the game CPUs (replaces a Process Lasso rule). "
							+ "When off, only DH workers and the main thread are pinned.")))
					.build();
			gpuUploadBox = Checkbox.builder(Component.literal("Off-thread GPU upload (experimental)"), font)
					.selected(base.offThreadGpuUpload)
					.onValueChange((box, value) -> base.offThreadGpuUpload = value)
					.tooltip(Tooltip.create(Component.literal("Upload finished LODs to the GPU from a dedicated thread instead of the render thread, "
							+ "removing the hitch when new terrain appears. Turn off if LODs flicker or look corrupted.")))
					.build();
			String current = base.uploadPacing == null ? "auto" : base.uploadPacing.trim().toLowerCase();
			if (!PACING_VALUES.contains(current)) {
				current = "auto";
			}
			pacingButton = CycleButton.<String>builder(v -> Component.literal(switch (v) {
						case "auto" -> "Auto (adaptive)";
						case "off" -> "Off";
						default -> v + " sections / frame";
					}), current)
					.withValues(PACING_VALUES)
					.create(0, 0, 220, 20, Component.literal("Hand-over pacing"), (button, value) -> base.uploadPacing = value);
			pacingButton.setTooltip(Tooltip.create(Component.literal("How many finished LOD sections may be handed to the renderer per frame. "
					+ "Auto grows the limit while frames are smooth and halves it when frames hitch.")));
			widgets = List.of(enabledBox, manageBox, gpuUploadBox, pacingButton);
		}

		@Override
		List<AbstractWidget> widgets() {
			return widgets;
		}

		private boolean sideBySide(int contentWidth) {
			return enabledBox.getWidth() + 12 + manageBox.getWidth() <= contentWidth;
		}

		@Override
		int heightFor(int rowWidth) {
			int w = rowWidth - 2 * CONTENT_PADDING;
			int inner = sideBySide(w) ? enabledBox.getHeight() : enabledBox.getHeight() + 2 + manageBox.getHeight();
			inner += 2 + gpuUploadBox.getHeight();
			inner += 2 + pacingButton.getHeight();
			return inner + 2 * CONTENT_PADDING + ROW_GAP;
		}

		@Override
		void layoutChildren() {
			int x = getContentX();
			int y = getContentY();
			enabledBox.setPosition(x, y);
			int nextY;
			if (sideBySide(getContentWidth())) {
				manageBox.setPosition(x + enabledBox.getWidth() + 12, y);
				nextY = y + enabledBox.getHeight() + 2;
			} else {
				manageBox.setPosition(x, y + enabledBox.getHeight() + 2);
				nextY = y + enabledBox.getHeight() + 2 + manageBox.getHeight() + 2;
			}
			gpuUploadBox.setPosition(x, nextY);
			pacingButton.setWidth(Math.min(220, Math.max(120, getContentWidth())));
			pacingButton.setPosition(x, nextY + gpuUploadBox.getHeight() + 2);
		}

		@Override
		public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			renderWidgets(graphics, mouseX, mouseY, partialTick);
		}
	}
}
