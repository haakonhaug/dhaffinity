package io.github.haakon.dhaffinity.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.haakon.dhaffinity.client.diag.FrameStats;
import io.github.haakon.dhaffinity.client.diag.HitchProfiler;
import io.github.haakon.dhaffinity.client.gpu.GpuUploadWorker;
import io.github.haakon.dhaffinity.client.gui.AffinityConfigScreen;
import io.github.haakon.dhaffinity.core.AffinitySweeper;
import io.github.haakon.dhaffinity.core.DhAffinity;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Registers {@code /dhaffinity [status|reload|sweep|gui]}, the frame diagnostics and the GPU upload status. */
public final class DhAffinityClient implements ClientModInitializer {

	/** {@code -Ddhaffinity.statusLogSeconds=N} logs the status lines every N seconds (for automated testing). */
	private static final int STATUS_LOG_SECONDS = Integer.getInteger("dhaffinity.statusLogSeconds", 0);
	/** {@code -Ddhaffinity.autoProfileSeconds=N} profiles the render thread for N s once a world renders (for automated testing). */
	private static final int AUTO_PROFILE_SECONDS = Integer.getInteger("dhaffinity.autoProfileSeconds", 0);

	@Override
	public void onInitializeClient() {
		DhAffinity core = DhAffinity.get();
		long mainTid = core.backend().currentThreadId();
		if (mainTid != 0 && core.mainThreadTid() != 0 && mainTid != core.mainThreadTid()) {
			DhAffinity.LOG.warn("DH Affinity: the client initialiser runs on thread {} but preLaunch ran on thread {}; the 'main thread' row may target the wrong thread.",
					mainTid, core.mainThreadTid());
		}
		FrameStats.INSTANCE.register();
		core.addStatusProvider(GpuUploadWorker.INSTANCE::statusLines);
		core.addStatusProvider(FrameStats.INSTANCE::statusLines);
		if (AUTO_PROFILE_SECONDS > 0) {
			FrameStats.FrameListener[] once = new FrameStats.FrameListener[1];
			once[0] = (start, end, hitch) -> {
				FrameStats.INSTANCE.removeListener(once[0]);
				HitchProfiler.INSTANCE.start(AUTO_PROFILE_SECONDS, lines -> {});
			};
			FrameStats.INSTANCE.addListener(once[0]);
		}
		if (STATUS_LOG_SECONDS > 0) {
			int[] ticks = {0};
			ClientTickEvents.END_CLIENT_TICK.register(client -> {
				if (++ticks[0] >= STATUS_LOG_SECONDS * 20) {
					ticks[0] = 0;
					for (String line : DhAffinity.get().statusLines()) {
						DhAffinity.LOG.info("[status] {}", line);
					}
				}
			});
		}
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
				ClientCommandManager.literal("dhaffinity")
						.executes(ctx -> status(ctx.getSource()))
						.then(ClientCommandManager.literal("status").executes(ctx -> status(ctx.getSource())))
						.then(ClientCommandManager.literal("reload").executes(ctx -> reload(ctx.getSource())))
						.then(ClientCommandManager.literal("sweep").executes(ctx -> sweep(ctx.getSource())))
						.then(ClientCommandManager.literal("gui").executes(ctx -> gui()))
						.then(ClientCommandManager.literal("profile")
								.executes(ctx -> profile(ctx.getSource(), 30))
								.then(ClientCommandManager.literal("stop").executes(ctx -> profileStop(ctx.getSource())))
								.then(ClientCommandManager.argument("seconds", IntegerArgumentType.integer(5, 300))
										.executes(ctx -> profile(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "seconds")))))));
	}

	private static int status(FabricClientCommandSource source) {
		String version = FabricLoader.getInstance().getModContainer(DhAffinity.MOD_ID)
				.map(c -> c.getMetadata().getVersion().getFriendlyString()).orElse("?");
		source.sendFeedback(Component.literal("DH Affinity " + version + " — open the menu with /dhaffinity gui"));
		for (String line : DhAffinity.get().statusLines()) {
			source.sendFeedback(Component.literal(line));
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int reload(FabricClientCommandSource source) {
		for (String line : DhAffinity.get().reload().split("\n")) {
			source.sendFeedback(Component.literal(line));
		}
		return Command.SINGLE_SUCCESS;
	}

	/**
	 * The command runs while the chat screen is still open and the chat screen closes itself right
	 * after, so the menu is opened on the next client tick instead of immediately.
	 */
	private static int gui() {
		Minecraft minecraft = Minecraft.getInstance();
		minecraft.schedule(() -> minecraft.setScreen(new AffinityConfigScreen(null)));
		return Command.SINGLE_SUCCESS;
	}

	/** Samples the render thread while you fly, then reports what it was doing during hitch frames. */
	private static int profile(FabricClientCommandSource source, int seconds) {
		Minecraft minecraft = Minecraft.getInstance();
		boolean started = HitchProfiler.INSTANCE.start(seconds, lines -> minecraft.execute(() -> {
			for (String line : lines) {
				minecraft.gui.getChat().addMessage(Component.literal("[DH Affinity] " + line));
			}
		}));
		source.sendFeedback(Component.literal(started
				? "Profiling the render thread for " + seconds + " s — fly into new terrain now. Results arrive in chat (and the log)."
				: "A profile is already running (/dhaffinity profile stop)."));
		return Command.SINGLE_SUCCESS;
	}

	private static int profileStop(FabricClientCommandSource source) {
		if (!HitchProfiler.INSTANCE.isRunning()) {
			source.sendFeedback(Component.literal("No profile is running."));
		} else {
			HitchProfiler.INSTANCE.stop();
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int sweep(FabricClientCommandSource source) {
		AffinitySweeper sweeper = DhAffinity.get().sweeper();
		if (sweeper == null) {
			source.sendFeedback(Component.literal("Sweeper is not running."));
		} else {
			sweeper.poke();
			source.sendFeedback(Component.literal("Sweep requested."));
		}
		return Command.SINGLE_SUCCESS;
	}
}
