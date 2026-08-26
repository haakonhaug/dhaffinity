package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.common.wrappers.world.ClientLevelWrapper_fabric;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.github.haakon.dhaffinity.core.DhKeyedLevelState;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.ref.WeakReference;

/**
 * {@code ClientLevelWrapper_fabric.getWrapper(level, bypass)} is DH's "give me the wrapper for
 * this Minecraft level" — called by every DH worker and the render thread, constantly. Each call
 * takes DH's synchronized level map twice. With 66 fast workers that monitor is the second lock
 * convoy the render thread queues behind (66% of remaining hitch time after the first fix).
 *
 * <p>While DH's keyed-level mode is off (singleplayer, and servers without DH level keys) the
 * answer for a level is stable until DH unloads the wrapper, so a single-entry lock-free cache
 * is exact: filled from the real result, cleared in {@code onUnload}, and bypassed
 * entirely once keyed levels are enabled (DH's own logic then handles overrides).
 */
@Mixin(value = ClientLevelWrapper_fabric.class, remap = false)
public abstract class ClientLevelWrapperMixin {

	@Unique
	// Both weak: DH keeps live wrappers strongly itself, so hits are unaffected while a world is
	// loaded, and the cache can never keep a closed world reachable from the main menu.
	private record Entry(WeakReference<Object> level, WeakReference<IClientLevelWrapper> wrapper) {}

	@Unique
	private static volatile Entry dhaffinity$cache;

	@Inject(method = "getWrapper(Lnet/minecraft/client/multiplayer/ClientLevel;Z)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;",
			at = @At("HEAD"), cancellable = true, remap = true, require = 0)
	private static void dhaffinity$fastPath(ClientLevel level, boolean bypassLevelKeyManager, CallbackInfoReturnable<IClientLevelWrapper> cir) {
		if (level == null || DhKeyedLevelState.keyedLevelsEnabled) {
			return;
		}
		Entry entry = dhaffinity$cache;
		if (entry != null && entry.level().get() == level) {
			IClientLevelWrapper wrapper = entry.wrapper().get();
			if (wrapper != null) {
				cir.setReturnValue(wrapper);
			}
		}
	}

	@Inject(method = "getWrapper(Lnet/minecraft/client/multiplayer/ClientLevel;Z)Lcom/seibel/distanthorizons/core/wrapperInterfaces/world/IClientLevelWrapper;",
			at = @At("RETURN"), remap = true, require = 0)
	private static void dhaffinity$remember(ClientLevel level, boolean bypassLevelKeyManager, CallbackInfoReturnable<IClientLevelWrapper> cir) {
		IClientLevelWrapper wrapper = cir.getReturnValue();
		if (level == null || wrapper == null || DhKeyedLevelState.keyedLevelsEnabled || wrapper instanceof IServerKeyedClientLevel) {
			return;
		}
		dhaffinity$cache = new Entry(new WeakReference<>(level), new WeakReference<>(wrapper));
	}

	/**
	 * {@code onUnload} is the single place DH removes a wrapper from its map (world close, level
	 * unload, {@code tryUnloadFromWorld} all end here). Cleared at both ends so a lookup that raced
	 * the removal cannot leave the unloaded wrapper behind.
	 */
	@Inject(method = "onUnload", at = {@At("HEAD"), @At("RETURN")}, remap = false, require = 0)
	private void dhaffinity$invalidate(CallbackInfo ci) {
		dhaffinity$cache = null;
	}
}
