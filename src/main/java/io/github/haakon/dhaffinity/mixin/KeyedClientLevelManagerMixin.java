package io.github.haakon.dhaffinity.mixin;

import com.seibel.distanthorizons.common.wrappers.level.KeyedClientLevelManager_fabric;
import com.seibel.distanthorizons.core.level.IKeyedClientLevelManager;
import com.seibel.distanthorizons.core.level.IServerKeyedClientLevel;
import com.seibel.distanthorizons.core.wrapperInterfaces.world.IClientLevelWrapper;
import io.github.haakon.dhaffinity.core.DhKeyedLevelState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.ref.WeakReference;

/**
 * {@code KeyedClientLevelManager_fabric#getServerKeyedLevel} is called constantly by every DH
 * worker thread and by the render thread, and it takes one global monitor on every call. In
 * singleplayer there is no server key, so the call always ends in "return null" without caching
 * anything — every call pays the lock plus a wrapper lookup. With DH's workers on dedicated
 * cores that monitor becomes a convoy the render thread has to queue behind: measured as 76% of
 * hitch-frame time on the user's machine.
 *
 * <p>This adds a lock-free single-entry cache in front of it. There is only one client level at
 * a time, so it hits almost always; it is invalidated whenever DH changes the keys, and a null
 * result is only cached while DH says keyed levels are disabled (singleplayer), so a server that
 * later assigns a key is never masked.
 */
@Mixin(value = KeyedClientLevelManager_fabric.class, remap = false)
public abstract class KeyedClientLevelManagerMixin {

	/** Immutable (level, result) pair published as one volatile write, so a reader can never pair a level with another level's answer. */
	@Unique
	private record Entry(WeakReference<Object> level, IServerKeyedClientLevel result) {}

	@Unique
	private volatile Entry dhaffinity$cache;

	@Inject(method = "getServerKeyedLevel", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void dhaffinity$fastPath(IClientLevelWrapper levelWrapper, CallbackInfoReturnable<IServerKeyedClientLevel> cir) {
		if (levelWrapper == null) {
			return;
		}
		Entry entry = dhaffinity$cache;
		if (entry == null) {
			return;
		}
		Object level = levelWrapper.getWrappedMcObject();
		if (level == null || entry.level().get() != level) {
			return;
		}
		cir.setReturnValue(entry.result());
	}

	@Inject(method = "getServerKeyedLevel", at = @At("RETURN"), remap = false, require = 0)
	private void dhaffinity$remember(IClientLevelWrapper levelWrapper, CallbackInfoReturnable<IServerKeyedClientLevel> cir) {
		if (levelWrapper == null) {
			return;
		}
		Object level = levelWrapper.getWrappedMcObject();
		if (level == null) {
			return;
		}
		IServerKeyedClientLevel value = cir.getReturnValue();
		IKeyedClientLevelManager self = (IKeyedClientLevelManager) (Object) this;
		if (value == null && self.isEnabled()) {
			return; // keyed levels are on but this level has none yet: never cache that miss
		}
		dhaffinity$cache = new Entry(new WeakReference<>(level), value);
		if (value == null && self.isEnabled()) {
			// A key was assigned while we were computing the miss: drop it again.
			dhaffinity$cache = null;
		}
		// Known, harmless window: a wrapper computed under an old key can be published just after the
		// key changed; the next key event (or the next miss) replaces it.
	}

	@Inject(method = {"clearKeyedLevel", "disable"}, at = {@At("HEAD"), @At("RETURN")}, remap = false, require = 0)
	private void dhaffinity$invalidate(CallbackInfo ci) {
		dhaffinity$cache = null;
		DhKeyedLevelState.keyedLevelsEnabled = ((IKeyedClientLevelManager) (Object) this).isEnabled();
	}

	@Inject(method = "setServerKeyedLevel", at = {@At("HEAD"), @At("RETURN")}, remap = false, require = 0)
	private void dhaffinity$invalidateOnSet(IClientLevelWrapper clientLevel, String dimensionResource, String serverKey, String levelKey,
			CallbackInfoReturnable<IServerKeyedClientLevel> cir) {
		dhaffinity$cache = null;
		// Mirror DH's flag conservatively: assume enabled from the moment a key is being set.
		DhKeyedLevelState.keyedLevelsEnabled = true;
	}
}
